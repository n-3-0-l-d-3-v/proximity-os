package os.proximity.shared.crypto

/**
 * HKDF (RFC 5869) over HMAC-SHA256.
 *
 * Implemented in shared code rather than delegated to each platform so it
 * can be checked against the RFC's published test vectors — see
 * `HkdfTest`. A raw ECDH output must never be used as a key directly; it
 * goes through [derive] first.
 */
object Hkdf {

    private const val HASH_LEN = 32

    /** RFC 5869 §2.2 — extract a uniformly random pseudo-random key. */
    fun extract(primitives: CryptoPrimitives, salt: ByteArray, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return primitives.hmacSha256(actualSalt, ikm)
    }

    /** RFC 5869 §2.3 — expand a pseudo-random key to [length] bytes. */
    fun expand(primitives: CryptoPrimitives, prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        require(length <= 255 * HASH_LEN) { "length exceeds HKDF maximum of ${255 * HASH_LEN}" }

        val output = ByteArray(length)
        var previousBlock = ByteArray(0)
        var written = 0
        var counter = 1

        while (written < length) {
            val input = ByteArray(previousBlock.size + info.size + 1)
            previousBlock.copyInto(input, 0)
            info.copyInto(input, previousBlock.size)
            input[input.size - 1] = counter.toByte()

            previousBlock = primitives.hmacSha256(prk, input)
            val toCopy = minOf(previousBlock.size, length - written)
            previousBlock.copyInto(output, written, 0, toCopy)
            written += toCopy
            counter++
        }

        return output
    }

    /** Convenience: extract then expand in one step. */
    fun derive(
        primitives: CryptoPrimitives,
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int
    ): ByteArray = expand(primitives, extract(primitives, salt, ikm), info, length)
}
