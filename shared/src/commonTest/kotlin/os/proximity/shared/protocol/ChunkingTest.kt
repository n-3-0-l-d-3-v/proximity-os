package os.proximity.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkingTest {

    private val mtu = 32 // 12-byte header + 20 bytes payload per frame

    @Test
    fun chunkThenAssembleRoundTripsLargePayload() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val frames = FrameChunker.chunk(1, FrameType.SEALED, payload, mtu)
        val assembler = MessageAssembler()

        var result: AssembledMessage? = null
        frames.forEach { frame ->
            val decoded = assertNotNull(Frame.decode(frame.encode()))
            assembler.offer(decoded, nowMillis = 0)?.let { result = it }
        }

        assertNotNull(result)
        assertTrue(payload.contentEquals(result!!.payload))
        assertEquals(FrameType.SEALED, result!!.type)
    }

    @Test
    fun assemblesOutOfOrderFrames() {
        val payload = ByteArray(200) { it.toByte() }
        val frames = FrameChunker.chunk(7, FrameType.SEALED, payload, mtu).shuffled(kotlin.random.Random(9))
        val assembler = MessageAssembler()

        var result: AssembledMessage? = null
        frames.forEach { result = assembler.offer(it, nowMillis = 0) ?: result }

        assertNotNull(result)
        assertTrue(payload.contentEquals(result!!.payload))
    }

    @Test
    fun emptyPayloadStillProducesOneFrame() {
        val frames = FrameChunker.chunk(1, FrameType.SEALED, ByteArray(0), mtu)
        assertEquals(1, frames.size)

        val assembled = MessageAssembler().offer(frames.single(), nowMillis = 0)
        assertNotNull(assembled)
        assertEquals(0, assembled.payload.size)
    }

    @Test
    fun rejectsMtuSmallerThanHeader() {
        assertFailsWith<IllegalArgumentException> {
            FrameChunker.chunk(1, FrameType.SEALED, byteArrayOf(1), Frame.HEADER_SIZE)
        }
    }

    @Test
    fun duplicateChunkIsIgnored() {
        val payload = ByteArray(40) { it.toByte() }
        val frames = FrameChunker.chunk(1, FrameType.SEALED, payload, mtu)
        val assembler = MessageAssembler()

        assertNull(assembler.offer(frames[0], nowMillis = 0))
        assertNull(assembler.offer(frames[0], nowMillis = 0)) // duplicate
        val done = assembler.offer(frames[1], nowMillis = 0)

        assertNotNull(done)
        assertTrue(payload.contentEquals(done.payload))
    }

    @Test
    fun inconsistentTotalChunksDropsMessage() {
        val payload = ByteArray(40) { it.toByte() }
        val frames = FrameChunker.chunk(1, FrameType.SEALED, payload, mtu)
        val assembler = MessageAssembler()

        assembler.offer(frames[0], nowMillis = 0)
        // Same messageId, but the peer now claims a different shape.
        val inconsistent = frames[1].copy(totalChunks = 9)
        assertNull(assembler.offer(inconsistent, nowMillis = 0))
        assertEquals(0, assembler.inFlightCount())
    }

    @Test
    fun inFlightMessagesAreCapped() {
        val assembler = MessageAssembler(maxInFlightMessages = 4)
        // Each message is 2 chunks, so none of them ever completes.
        repeat(10) { id ->
            val frames = FrameChunker.chunk(id, FrameType.SEALED, ByteArray(40), mtu)
            assembler.offer(frames[0], nowMillis = 0)
        }
        assertEquals(4, assembler.inFlightCount())
    }

    @Test
    fun stalePartialsAreEvicted() {
        val assembler = MessageAssembler(staleAfterMillis = 1_000)
        val frames = FrameChunker.chunk(1, FrameType.SEALED, ByteArray(40), mtu)

        assembler.offer(frames[0], nowMillis = 0)
        assertEquals(1, assembler.inFlightCount())

        // A later frame for an unrelated message triggers eviction sweep.
        val other = FrameChunker.chunk(2, FrameType.SEALED, ByteArray(40), mtu)
        assembler.offer(other[0], nowMillis = 5_000)

        assertEquals(1, assembler.inFlightCount()) // only the new one survives
    }
}
