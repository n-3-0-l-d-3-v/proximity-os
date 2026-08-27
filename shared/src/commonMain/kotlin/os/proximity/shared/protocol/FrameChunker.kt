package os.proximity.shared.protocol

/** Splits an outbound payload into [Frame]s that fit the negotiated MTU. */
object FrameChunker {

    /**
     * @param maxFrameSize total bytes per frame including the header, i.e.
     *   the largest single write the transport will accept.
     * @throws IllegalArgumentException if [maxFrameSize] cannot hold a
     *   header plus at least one payload byte, or the payload is too large
     *   to address with a 16-bit chunk count.
     */
    fun chunk(
        messageId: Int,
        type: FrameType,
        payload: ByteArray,
        maxFrameSize: Int
    ): List<Frame> {
        val maxPayloadPerFrame = maxFrameSize - Frame.HEADER_SIZE
        require(maxPayloadPerFrame > 0) {
            "maxFrameSize $maxFrameSize is too small for a ${Frame.HEADER_SIZE}-byte header"
        }

        // A zero-length payload still needs one frame, or the receiver would
        // never see the message at all.
        if (payload.isEmpty()) {
            return listOf(Frame(Frame.CURRENT_VERSION, type, messageId, 0, 1, ByteArray(0)))
        }

        val totalChunks = (payload.size + maxPayloadPerFrame - 1) / maxPayloadPerFrame
        require(totalChunks <= MAX_CHUNKS) {
            "payload of ${payload.size} bytes needs $totalChunks chunks, exceeding $MAX_CHUNKS"
        }

        return (0 until totalChunks).map { index ->
            val start = index * maxPayloadPerFrame
            val end = minOf(start + maxPayloadPerFrame, payload.size)
            Frame(
                version = Frame.CURRENT_VERSION,
                type = type,
                messageId = messageId,
                sequence = index,
                totalChunks = totalChunks,
                payload = payload.copyOfRange(start, end)
            )
        }
    }

    /** 16-bit sequence/total fields cap how many chunks one message may use. */
    const val MAX_CHUNKS = 0xFFFF
}
