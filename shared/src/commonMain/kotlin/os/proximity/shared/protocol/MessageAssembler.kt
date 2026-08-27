package os.proximity.shared.protocol

/**
 * Reassembles [Frame]s back into whole messages.
 *
 * Every input here is attacker-controlled (see docs/THREAT_MODEL.md #1
 * "Malicious nearby peer"), so this class is written defensively:
 *
 * - a message whose declared size exceeds [Frame.MAX_MESSAGE_BYTES] is
 *   refused before any memory is committed to it;
 * - the number of simultaneously in-flight messages is capped, so a peer
 *   cannot exhaust memory by opening thousands of partial messages;
 * - partial messages are evicted once stale, so a peer that starts messages
 *   and never finishes them cannot pin memory indefinitely;
 * - duplicate chunks are ignored rather than trusted to overwrite.
 *
 * One instance is expected per peer. Not thread-safe; callers must confine
 * it to a single coroutine or guard it themselves.
 */
class MessageAssembler(
    private val maxInFlightMessages: Int = 16,
    private val staleAfterMillis: Long = 60_000
) {

    private class Partial(
        val type: FrameType,
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        var receivedCount: Int,
        var byteCount: Int,
        var lastUpdatedMillis: Long
    )

    private val partials = mutableMapOf<Int, Partial>()

    /**
     * Offer a decoded frame. Returns the completed message when [frame] was
     * the last chunk outstanding, otherwise null.
     */
    fun offer(frame: Frame, nowMillis: Long): AssembledMessage? {
        evictStale(nowMillis)

        // Single-chunk fast path: no bookkeeping needed.
        if (frame.totalChunks == 1) {
            return AssembledMessage(frame.messageId, frame.type, frame.payload)
        }

        val existing = partials[frame.messageId]
        val partial = if (existing != null) {
            // A peer that changes its mind about a message's shape mid-stream
            // is either buggy or hostile; drop the whole message.
            if (existing.totalChunks != frame.totalChunks || existing.type != frame.type) {
                partials.remove(frame.messageId)
                return null
            }
            existing
        } else {
            if (partials.size >= maxInFlightMessages) return null
            Partial(
                type = frame.type,
                totalChunks = frame.totalChunks,
                chunks = arrayOfNulls(frame.totalChunks),
                receivedCount = 0,
                byteCount = 0,
                lastUpdatedMillis = nowMillis
            ).also { partials[frame.messageId] = it }
        }

        // Ignore duplicates rather than letting a peer overwrite a chunk it
        // already sent.
        if (partial.chunks[frame.sequence] != null) return null

        if (partial.byteCount + frame.payload.size > Frame.MAX_MESSAGE_BYTES) {
            partials.remove(frame.messageId)
            return null
        }

        partial.chunks[frame.sequence] = frame.payload
        partial.receivedCount++
        partial.byteCount += frame.payload.size
        partial.lastUpdatedMillis = nowMillis

        if (partial.receivedCount != partial.totalChunks) return null

        partials.remove(frame.messageId)
        val assembled = ByteArray(partial.byteCount)
        var offset = 0
        for (chunk in partial.chunks) {
            val bytes = chunk ?: return null
            bytes.copyInto(assembled, offset)
            offset += bytes.size
        }
        return AssembledMessage(frame.messageId, partial.type, assembled)
    }

    /** Number of messages currently partially received. Exposed for tests. */
    fun inFlightCount(): Int = partials.size

    fun clear() = partials.clear()

    private fun evictStale(nowMillis: Long) {
        if (partials.isEmpty()) return
        val iterator = partials.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis - entry.value.lastUpdatedMillis > staleAfterMillis) {
                iterator.remove()
            }
        }
    }
}

data class AssembledMessage(
    val messageId: Int,
    val type: FrameType,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AssembledMessage) return false
        return messageId == other.messageId &&
            type == other.type &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageId
        result = 31 * result + type.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
