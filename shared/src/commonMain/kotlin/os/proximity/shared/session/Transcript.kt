package os.proximity.shared.session

import os.proximity.shared.util.writeIntAt

/**
 * Length-prefixed concatenation for anything that gets signed or hashed.
 *
 * Plain concatenation is ambiguous: `("ab", "c")` and `("a", "bc")` produce
 * identical bytes, which lets an attacker shift a boundary and have a
 * signature over one message validate a different one. Every field is
 * therefore prefixed with its length.
 */
class Transcript {

    private val parts = mutableListOf<ByteArray>()

    fun add(bytes: ByteArray): Transcript {
        parts.add(bytes)
        return this
    }

    fun add(text: String): Transcript = add(text.encodeToByteArray())

    fun build(): ByteArray {
        val totalSize = parts.sumOf { it.size + 4 }
        val out = ByteArray(totalSize)
        var offset = 0
        for (part in parts) {
            out.writeIntAt(offset, part.size)
            offset += 4
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}
