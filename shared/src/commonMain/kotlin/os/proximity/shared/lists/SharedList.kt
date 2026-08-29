package os.proximity.shared.lists

import kotlinx.serialization.Serializable

/**
 * A version stamp for one field of one item.
 *
 * Ordering is by [counter] first, then [deviceId] as a deterministic
 * tie-break. Both sides of a merge must reach the same answer without
 * talking to each other, so "later" has to be decidable from the data
 * alone.
 *
 * [counter] is a **Lamport clock**, not wall-clock time. Two phones that
 * have been apart for a day may disagree wildly about what time it is —
 * one with a badly wrong clock would otherwise win every conflict forever,
 * silently overwriting everyone else's edits. A Lamport counter only ever
 * moves forward and only in response to observed events, so no device can
 * dominate by having a bad clock.
 */
@Serializable
data class Stamp(
    val counter: Long,
    val deviceId: String
) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int {
        val byCounter = counter.compareTo(other.counter)
        return if (byCounter != 0) byCounter else deviceId.compareTo(other.deviceId)
    }
}

/**
 * One entry in a shared list.
 *
 * Each mutable field carries its own [Stamp] so that concurrent edits to
 * *different* fields both survive: if you tick an item off while I rename
 * it, we should end up with the renamed item ticked off — not one edit
 * silently discarded because it lost a whole-item comparison.
 *
 * [removed] is a tombstone rather than a deletion. A deleted item has to
 * stay visible to the merge, or a peer who never heard about the delete
 * would resurrect it on the next sync.
 */
@Serializable
data class ListItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val removed: Boolean,
    val createdAtEpochMillis: Long,
    val textStamp: Stamp,
    val doneStamp: Stamp,
    val removedStamp: Stamp
)

/**
 * A list shared between nearby devices, replicated rather than
 * synchronised through any server.
 *
 * The merge is an operation-based CRDT with last-writer-wins registers per
 * field. That gives the three properties a mesh needs, all of which are
 * enforced by tests:
 *
 * - **Commutative** — order of arrival doesn't matter, and on a mesh you
 *   cannot control arrival order.
 * - **Idempotent** — a re-delivered operation changes nothing, so
 *   retries after a dropped connection are safe.
 * - **Convergent** — two devices that have seen the same operations hold
 *   identical state, whatever path those operations took.
 */
@Serializable
data class SharedList(
    val id: String,
    val name: String,
    val items: Map<String, ListItem> = emptyMap(),
    /** Highest Lamport counter this replica has observed. */
    val counter: Long = 0
) {
    /** Items a user should actually see, in creation order. */
    val visibleItems: List<ListItem>
        get() = items.values
            .filterNot { it.removed }
            .sortedBy { it.createdAtEpochMillis }

    val remainingCount: Int get() = visibleItems.count { !it.done }
}

/** A single change to a [SharedList]. */
@Serializable
data class ListOperation(
    val listId: String,
    /** Unique per operation; makes redelivery a no-op. */
    val opId: String,
    val itemId: String,
    val kind: Kind,
    val counter: Long,
    val deviceId: String,
    val atEpochMillis: Long,
    val text: String? = null,
    val done: Boolean? = null,
    /** Only meaningful for [Kind.RENAME_LIST]. */
    val listName: String? = null
) {
    @Serializable
    enum class Kind { ADD, SET_TEXT, SET_DONE, REMOVE, RENAME_LIST }

    val stamp: Stamp get() = Stamp(counter, deviceId)
}
