package os.proximity.shared.crypto

import os.proximity.shared.util.readLongAt
import os.proximity.shared.util.writeLongAt

/**
 * An established, authenticated, encrypted channel with one peer.
 *
 * Record layout: `[counter: 8][ciphertext + GCM tag]`. The nonce is a
 * per-session random prefix concatenated with that counter.
 *
 * Three properties this class exists to guarantee:
 *
 * 1. **No nonce reuse.** AES-GCM fails catastrophically if a (key, nonce)
 *    pair is ever repeated — it leaks the authentication key, not just one
 *    message. The counter is monotonic and the session refuses to encrypt
 *    once it would overflow rather than wrapping around.
 * 2. **No replay.** A record whose counter is not strictly greater than the
 *    highest already accepted is rejected, so a peer cannot re-send a
 *    captured record.
 * 3. **No cross-session confusion.** [sessionId] is the AEAD associated
 *    data, so a record from one session will not authenticate in another.
 *
 * Directional keys mean our own outbound records will never decrypt on our
 * inbound path — otherwise an attacker could echo our traffic back at us.
 *
 * Not thread-safe; confine to a single coroutine per peer.
 */
class SecureSession(
    private val primitives: CryptoPrimitives,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    private val sendNoncePrefix: ByteArray,
    private val receiveNoncePrefix: ByteArray,
    /** Transcript hash; identifies this session and binds every record to it. */
    val sessionId: ByteArray,
    val peerIdentityKey: ByteArray,
    val peerDeviceId: String,
    val peerFingerprint: String,
    val peerDisplayName: String
) {

    private var sendCounter: Long = 0
    private var highestAcceptedCounter: Long = -1

    /** Returns null only if the counter space is exhausted. */
    fun seal(plaintext: ByteArray): ByteArray? {
        if (sendCounter == Long.MAX_VALUE) return null

        val counter = sendCounter
        val ciphertext = primitives.aeadSeal(
            key = sendKey,
            nonce = nonceFor(sendNoncePrefix, counter),
            plaintext = plaintext,
            aad = sessionId
        )
        sendCounter++

        val record = ByteArray(COUNTER_SIZE + ciphertext.size)
        record.writeLongAt(0, counter)
        ciphertext.copyInto(record, COUNTER_SIZE)
        return record
    }

    /**
     * Returns null for anything that fails to authenticate, is replayed, or
     * is malformed. Callers must treat null as "drop it silently" — telling
     * a peer *why* their record was rejected leaks information.
     */
    fun open(record: ByteArray): ByteArray? {
        if (record.size < COUNTER_SIZE + CryptoConstants.GCM_TAG_SIZE) return null

        val counter = record.readLongAt(0)
        if (counter <= highestAcceptedCounter) return null // replay or reorder
        if (counter < 0) return null

        val plaintext = primitives.aeadOpen(
            key = receiveKey,
            nonce = nonceFor(receiveNoncePrefix, counter),
            ciphertext = record.copyOfRange(COUNTER_SIZE, record.size),
            aad = sessionId
        ) ?: return null

        // Only advance after authentication succeeds, so a forged record
        // cannot burn counter values and cause us to drop genuine ones.
        highestAcceptedCounter = counter
        return plaintext
    }

    private fun nonceFor(prefix: ByteArray, counter: Long): ByteArray {
        val nonce = ByteArray(CryptoConstants.GCM_NONCE_SIZE)
        prefix.copyInto(nonce, 0, 0, NONCE_PREFIX_SIZE)
        nonce.writeLongAt(NONCE_PREFIX_SIZE, counter)
        return nonce
    }

    companion object {
        const val COUNTER_SIZE = 8
        const val NONCE_PREFIX_SIZE = 4

        init {
            // Documents the invariant the nonce layout depends on.
            require(NONCE_PREFIX_SIZE + COUNTER_SIZE == CryptoConstants.GCM_NONCE_SIZE)
        }
    }
}
