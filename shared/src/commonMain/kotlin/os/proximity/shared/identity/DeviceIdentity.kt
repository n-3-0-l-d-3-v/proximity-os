package os.proximity.shared.identity

import os.proximity.shared.util.toFingerprint
import os.proximity.shared.util.toHex

/**
 * This device's long-term cryptographic identity.
 *
 * The device ID is derived from the public key — never chosen by the user
 * and never accepted from a peer — so identities stay free to create while
 * remaining impossible to impersonate. Sybil resistance therefore comes
 * from human verification of [fingerprint], not from making identities
 * scarce (docs/THREAT_MODEL.md #3).
 */
interface DeviceIdentity {

    /** Stable identifier derived from [publicKeyBytes]. */
    val deviceId: String

    /** X.509 / SubjectPublicKeyInfo encoding, safe to advertise. */
    val publicKeyBytes: ByteArray

    /** Short human-readable code for out-of-band verification. */
    val fingerprint: String

    /** Sign with the private key. On Android the key lives in the Keystore
     *  and the raw private bytes are never exposed to this process. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * Verifies signatures made by *other* devices. Separate from
 * [DeviceIdentity] because verification is not an operation on our own
 * identity — conflating them invites accidentally trusting a peer-supplied
 * key as if it were ours.
 */
interface SignatureVerifier {
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

/**
 * Loads or lazily creates this device's [DeviceIdentity]. Implementations
 * must persist the keypair so the device ID is stable across restarts —
 * a device whose ID changed every launch would look like a Sybil swarm to
 * its peers.
 */
interface DeviceIdentityProvider {
    suspend fun getOrCreateIdentity(): DeviceIdentity
}

/**
 * Derivation of stable identifiers from key material. Shared so that every
 * platform produces byte-identical IDs for the same key — a mismatch here
 * would silently partition the mesh.
 */
object DeviceIdentifiers {

    const val DEVICE_ID_HEX_LENGTH = 32

    /** @param publicKeyHash SHA-256 over the encoded public key. */
    fun deviceIdFrom(publicKeyHash: ByteArray): String =
        publicKeyHash.toHex().take(DEVICE_ID_HEX_LENGTH)

    /** @param publicKeyHash SHA-256 over the encoded public key. */
    fun fingerprintFrom(publicKeyHash: ByteArray): String =
        publicKeyHash.toFingerprint()
}
