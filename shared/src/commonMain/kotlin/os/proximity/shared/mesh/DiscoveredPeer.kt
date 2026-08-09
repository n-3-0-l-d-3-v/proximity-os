package os.proximity.shared.mesh

/** A nearby device observed by the transport layer, before any connection is made. */
data class DiscoveredPeer(
    val transportAddress: String,
    val displayName: String?,
    val rssi: Int?,
    val lastSeenEpochMillis: Long
)
