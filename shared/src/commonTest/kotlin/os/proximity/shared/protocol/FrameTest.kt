package os.proximity.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameTest {

    @Test
    fun encodeThenDecodeRoundTrips() {
        val frame = Frame(
            version = Frame.CURRENT_VERSION,
            type = FrameType.SEALED,
            messageId = 0x01020304,
            sequence = 3,
            totalChunks = 9,
            payload = byteArrayOf(1, 2, 3, 4, 5)
        )

        val decoded = Frame.decode(frame.encode())

        assertEquals(frame, decoded)
    }

    @Test
    fun decodeHandlesHighBitValuesWithoutSignExtension() {
        // messageId with the top bit set previously round-tripped incorrectly
        // when bytes were widened without masking.
        val frame = Frame(
            version = Frame.CURRENT_VERSION,
            type = FrameType.HANDSHAKE,
            messageId = -1,
            sequence = 0xFFFE,
            totalChunks = 0xFFFF,
            payload = byteArrayOf(0xFF.toByte(), 0x80.toByte())
        )

        val decoded = Frame.decode(frame.encode())

        assertEquals(-1, decoded?.messageId)
        assertEquals(0xFFFE, decoded?.sequence)
        assertEquals(0xFFFF, decoded?.totalChunks)
    }

    @Test
    fun decodeRejectsTruncatedHeader() {
        assertNull(Frame.decode(ByteArray(Frame.HEADER_SIZE - 1)))
    }

    @Test
    fun decodeRejectsUnknownVersion() {
        val bytes = Frame(Frame.CURRENT_VERSION, FrameType.SEALED, 1, 0, 1, ByteArray(0)).encode()
        bytes[0] = 99
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun decodeRejectsUnknownType() {
        val bytes = Frame(Frame.CURRENT_VERSION, FrameType.SEALED, 1, 0, 1, ByteArray(0)).encode()
        bytes[1] = 77
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun decodeRejectsSequenceBeyondTotal() {
        val bytes = Frame(Frame.CURRENT_VERSION, FrameType.SEALED, 1, 0, 4, ByteArray(0)).encode()
        bytes.writeShortAt(6, 4) // sequence == totalChunks is out of range
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun decodeRejectsZeroTotalChunks() {
        val bytes = Frame(Frame.CURRENT_VERSION, FrameType.SEALED, 1, 0, 1, ByteArray(0)).encode()
        bytes.writeShortAt(8, 0)
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun decodeRejectsPayloadLengthMismatch() {
        val bytes = Frame(Frame.CURRENT_VERSION, FrameType.SEALED, 1, 0, 1, byteArrayOf(1, 2, 3)).encode()
        bytes.writeShortAt(10, 99) // claims more payload than is present
        assertNull(Frame.decode(bytes))
    }

    @Test
    fun decodeNeverThrowsOnRandomInput() {
        // A hostile peer can write arbitrary bytes; parsing must degrade to
        // null rather than propagating an exception into the transport.
        val random = kotlin.random.Random(seed = 1234)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(0, 64)) { random.nextInt().toByte() }
            Frame.decode(bytes) // must not throw
        }
        assertTrue(true)
    }
}
