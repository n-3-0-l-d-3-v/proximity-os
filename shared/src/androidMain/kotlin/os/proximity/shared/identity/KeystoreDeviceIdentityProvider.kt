package os.proximity.shared.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DeviceIdentityProvider] backed by the Android Keystore.
 *
 * The signing key is generated inside the Keystore and is not extractable:
 * on devices with a TEE or StrongBox the private key never exists in this
 * process's memory at all. That directly limits the blast radius of
 * docs/THREAT_MODEL.md #8 (single device compromise) — malware that reads
 * app storage still cannot walk away with the device's identity.
 *
 * P-256 ECDSA rather than Ed25519: Keystore support for Ed25519 is recent
 * and uneven, while EC P-256 has been Keystore-backed since API 23. See
 * docs/adr/0001-cryptography.md.
 */
class KeystoreDeviceIdentityProvider : DeviceIdentityProvider {

    private val mutex = Mutex()

    @Volatile
    private var cached: DeviceIdentity? = null

    override suspend fun getOrCreateIdentity(): DeviceIdentity {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: loadOrCreate().also { cached = it }
        }
    }

    private fun loadOrCreate(): DeviceIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }

        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val privateKey: PrivateKey = entry.privateKey
        val encodedPublicKey: ByteArray = entry.certificate.publicKey.encoded
        val publicKeyHash = MessageDigest.getInstance("SHA-256").digest(encodedPublicKey)

        return KeystoreIdentity(
            deviceId = DeviceIdentifiers.deviceIdFrom(publicKeyHash),
            publicKeyBytes = encodedPublicKey,
            fingerprint = DeviceIdentifiers.fingerprintFrom(publicKeyHash),
            privateKey = privateKey
        )
    }

    private class KeystoreIdentity(
        override val deviceId: String,
        override val publicKeyBytes: ByteArray,
        override val fingerprint: String,
        private val privateKey: PrivateKey
    ) : DeviceIdentity {
        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(privateKey)
                update(data)
                sign()
            }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "proximity_os_device_identity_v1"
        private const val CURVE = "secp256r1"
        internal const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

/** JCA-backed [SignatureVerifier] for peer-supplied keys and signatures. */
class JcaSignatureVerifier : SignatureVerifier {

    override fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance(KeystoreDeviceIdentityProvider.SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    } catch (e: Exception) {
        // Malformed key or signature from a peer is a failed verification,
        // never an exception that could disrupt the transport.
        false
    }
}
