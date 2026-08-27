package os.proximity.shared.util

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        chars[i * 2] = HEX_DIGITS[value ushr 4]
        chars[i * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return chars.concatToString()
}

/** Returns null on malformed input rather than throwing — this parses peer data. */
fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = HEX_DIGITS.indexOf(this[i * 2].lowercaseChar())
        val lo = HEX_DIGITS.indexOf(this[i * 2 + 1].lowercaseChar())
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * A short, human-comparable fingerprint of a public key, for out-of-band
 * verification ("I know this person" — read the code aloud and check it
 * matches). Grouping makes transcription errors obvious.
 */
fun ByteArray.toFingerprint(groups: Int = 3, groupSize: Int = 4): String =
    toHex().uppercase().take(groups * groupSize).chunked(groupSize).joinToString("-")

internal fun ByteArray.writeIntAt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.writeShortAt(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.readIntAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.readShortAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.writeLongAt(offset: Int, value: Long) {
    for (i in 0 until 8) {
        this[offset + i] = (value ushr (56 - i * 8)).toByte()
    }
}

internal fun ByteArray.readLongAt(offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) {
        result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    }
    return result
}

/**
 * Constant-time comparison. Used anywhere a mismatch could otherwise leak
 * position information through timing.
 */
fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) {
        diff = diff or (this[i].toInt() xor other[i].toInt())
    }
    return diff == 0
}
