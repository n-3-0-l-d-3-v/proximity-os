package os.proximity.shared.protocol

import os.proximity.shared.util.readIntAt
import os.proximity.shared.util.readShortAt
import os.proximity.shared.util.writeIntAt
import os.proximity.shared.util.writeShortAt

/**
 * The on-wire framing format.
 *
 * Bluetooth LE gives us roughly 20–512 bytes per write depending on the
 * negotiated MTU, so anything larger than a trivial message must be split
 * across multiple frames and reassembled by the receiver. Frames carry
 * enough metadata to reassemble out of order and to detect a truncated or
 * hostile stream without allocating unbounded memory.
 *
 * Layout (big-endian, 12-byte header):
 *
 * ```
 * offset  size  field
 * 0       1     version
 * 1       1     type
 * 2       4     messageId
 * 6       2     sequence      (0-based index of this chunk)
 * 8       2     totalChunks
 * 10      2     payloadLength
 * 12      n     payload
 * ```
 */
data class Frame(
    val version: Int,
    val type: FrameType,
    val messageId: Int,
    val sequence: Int,
    val totalChunks: Int,
    val payload: ByteArray
) {
    fun encode(): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = version.toByte()
        out[1] = type.wireValue.toByte()
        out.writeIntAt(2, messageId)
        out.writeShortAt(6, sequence)
        out.writeShortAt(8, totalChunks)
        out.writeShortAt(10, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    // ByteArray in a data class needs structural equality spelled out.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return version == other.version &&
            type == other.type &&
            messageId == other.messageId &&
            sequence == other.sequence &&
            totalChunks == other.totalChunks &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type.hashCode()
        result = 31 * result + messageId
        result = 31 * result + sequence
        result = 31 * result + totalChunks
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 12
        const val CURRENT_VERSION = 1

        /** Hard ceiling on a reassembled message, to bound memory per peer. */
        const val MAX_MESSAGE_BYTES = 512 * 1024

        /**
         * Decode a frame, or return null if [bytes] is not a well-formed
         * frame. Never throws: this parses attacker-controlled input, so a
         * malformed frame must be an ordinary "ignore it" result rather
         * than an exception that could take down the transport.
         */
        fun decode(bytes: ByteArray): Frame? {
            if (bytes.size < HEADER_SIZE) return null

            val version = bytes[0].toInt() and 0xFF
            if (version != CURRENT_VERSION) return null

            val type = FrameType.fromWire(bytes[1].toInt() and 0xFF) ?: return null
            val messageId = bytes.readIntAt(2)
            val sequence = bytes.readShortAt(6)
            val totalChunks = bytes.readShortAt(8)
            val payloadLength = bytes.readShortAt(10)

            if (totalChunks <= 0) return null
            if (sequence >= totalChunks) return null
            if (payloadLength != bytes.size - HEADER_SIZE) return null

            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            return Frame(version, type, messageId, sequence, totalChunks, payload)
        }
    }
}

enum class FrameType(val wireValue: Int) {
    /** Handshake traffic; not yet encrypted. */
    HANDSHAKE(1),

    /** Encrypted application payload. */
    SEALED(2);

    companion object {
        fun fromWire(value: Int): FrameType? = entries.firstOrNull { it.wireValue == value }
    }
}

