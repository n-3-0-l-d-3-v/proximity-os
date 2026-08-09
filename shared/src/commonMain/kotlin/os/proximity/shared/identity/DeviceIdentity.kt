package os.proximity.shared.identity

/**
 * This device's long-term cryptographic identity. The device ID is derived
 * from the public key — never chosen by the user or asserted by a peer —
 * so that identities stay cheap to create but hard to spoof. See
 * docs/THREAT_MODEL.md (#2 Capability spoofing, #3 Sybil / identity
 * attacks).
 */
interface DeviceIdentity {

    /** Stable identifier derived from the public key. */
    val deviceId: String

    /** Public key material, safe to advertise to peers. */
    val publicKeyBytes: ByteArray

    /** Sign [data] with this device's private key. */
    fun sign(data: ByteArray): ByteArray

    /** Verify a signature over [data] against a peer's [publicKey]. */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}

/**
 * Loads or lazily creates this device's [DeviceIdentity]. Implementations
 * are expected to persist the underlying keypair so the device ID is
 * stable across app restarts.
 */
interface DeviceIdentityProvider {
    suspend fun getOrCreateIdentity(): DeviceIdentity
}
