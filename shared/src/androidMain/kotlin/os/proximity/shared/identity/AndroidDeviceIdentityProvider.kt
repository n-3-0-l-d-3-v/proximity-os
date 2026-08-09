package os.proximity.shared.identity

import android.content.Context
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.SignatureKeyTemplates
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tink-backed [DeviceIdentityProvider]. The private signing key never
 * leaves this class: it is generated on first run, wrapped by a key that
 * lives in the Android Keystore (hardware-backed where available), and
 * persisted to SharedPreferences in that wrapped form only.
 */
class AndroidDeviceIdentityProvider(private val context: Context) : DeviceIdentityProvider {

    private val mutex = Mutex()

    @Volatile
    private var cached: DeviceIdentity? = null

    override suspend fun getOrCreateIdentity(): DeviceIdentity {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return it }
            createIdentity().also { cached = it }
        }
    }

    private fun createIdentity(): DeviceIdentity {
        TinkConfig.register()

        val keysetHandle: KeysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(SignatureKeyTemplates.ED25519)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle

        val signer = keysetHandle.getPrimitive(PublicKeySign::class.java)
        val publicKeysetHandle = keysetHandle.publicKeysetHandle
        val publicKeyBytes = publicKeysetHandle.serializeCleartext()

        return object : DeviceIdentity {
            override val deviceId: String = deriveDeviceId(publicKeyBytes)
            override val publicKeyBytes: ByteArray = publicKeyBytes

            override fun sign(data: ByteArray): ByteArray = signer.sign(data)

            override fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
                return try {
                    val handle = CleartextKeysetHandle.read(JsonKeysetReader.withBytes(publicKey))
                    val verifier = handle.getPrimitive(PublicKeyVerify::class.java)
                    verifier.verify(signature, data)
                    true
                } catch (e: GeneralSecurityException) {
                    false
                }
            }
        }
    }

    companion object {
        private const val KEYSET_NAME = "proximity_os_device_identity_keyset"
        private const val PREF_FILE_NAME = "proximity_os_identity_prefs"
        private const val MASTER_KEY_URI = "android-keystore://proximity_os_identity_master_key"

        private fun deriveDeviceId(publicKeyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        private fun KeysetHandle.serializeCleartext(): ByteArray {
            val out = ByteArrayOutputStream()
            CleartextKeysetHandle.write(this, JsonKeysetWriter.withOutputStream(out))
            return out.toByteArray()
        }
    }
}
