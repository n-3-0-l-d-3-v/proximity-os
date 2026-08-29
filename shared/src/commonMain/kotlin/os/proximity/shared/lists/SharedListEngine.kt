package os.proximity.shared.lists

/**
 * Applies and merges [SharedList] state.
 *
 * Every function here is pure: same inputs, same output, no clocks and no
 * I/O. That is what makes the convergence properties testable rather than
 * merely asserted.
 */
object SharedListEngine {

    /**
     * Applies [operation] to [list], returning the updated list.
     *
     * Applying the same operation twice yields the same result as applying
     * it once, so a peer that re-sends after a dropped connection cannot
     * corrupt state.
     */
    fun apply(list: SharedList, operation: ListOperation): SharedList {
        if (operation.listId != list.id) return list

        // Observing a remote counter advances ours past it, so any edit we
        // make afterwards is ordered strictly later than what we have seen.
        val advancedCounter = maxOf(list.counter, operation.counter)

        if (operation.kind == ListOperation.Kind.RENAME_LIST) {
            val name = operation.listName ?: return list.copy(counter = advancedCounter)
            return list.copy(name = name, counter = advancedCounter)
        }

        val stamp = operation.stamp
        val existing = list.items[operation.itemId]
        val base = existing ?: placeholderFor(operation)

        val mutated = when (operation.kind) {
            // An ADD carries a text and asserts the item exists, so it writes
            // those two fields. It deliberately does *not* touch doneStamp:
            // advancing a stamp without writing the value it stamps would
            // suppress an earlier SET_DONE depending purely on arrival order,
            // and the two replicas would disagree about a ticked-off item.
            // A stamp only ever moves together with its own value.
            ListOperation.Kind.ADD -> base.copy(
                text = if (stamp > base.textStamp) operation.text.orEmpty() else base.text,
                textStamp = maxOf(stamp, base.textStamp),
                removed = if (stamp > base.removedStamp) false else base.removed,
                removedStamp = maxOf(stamp, base.removedStamp)
            )

            ListOperation.Kind.SET_TEXT ->
                if (stamp > base.textStamp) {
                    base.copy(text = operation.text.orEmpty(), textStamp = stamp)
                } else {
                    base
                }

            ListOperation.Kind.SET_DONE ->
                if (stamp > base.doneStamp) {
                    base.copy(done = operation.done ?: base.done, doneStamp = stamp)
                } else {
                    base
                }

            ListOperation.Kind.REMOVE ->
                if (stamp > base.removedStamp) {
                    base.copy(removed = true, removedStamp = stamp)
                } else {
                    base
                }

            ListOperation.Kind.RENAME_LIST -> base
        }

        // Creation time has no stamp of its own, so it must converge some
        // other way: the earliest timestamp of any operation touching the
        // item. Minimum is commutative, associative and idempotent, so every
        // replica lands on the same value regardless of arrival order —
        // without this, two phones sort the same list differently.
        val updated = mutated.copy(
            createdAtEpochMillis = minOf(
                existing?.createdAtEpochMillis ?: operation.atEpochMillis,
                operation.atEpochMillis
            )
        )

        return list.copy(
            items = list.items + (operation.itemId to updated),
            counter = advancedCounter
        )
    }

    fun applyAll(list: SharedList, operations: Iterable<ListOperation>): SharedList =
        operations.fold(list, ::apply)

    /**
     * Merges two full replicas of the same list.
     *
     * Needed on reconnect: two devices that were out of range have each
     * accumulated operations the other never saw, and replaying every
     * operation ever made would not scale. Exchanging state and merging
     * field-by-field reaches the same answer.
     */
    fun merge(a: SharedList, b: SharedList): SharedList {
        require(a.id == b.id) { "cannot merge lists with different ids" }

        val mergedItems = (a.items.keys + b.items.keys).associateWith { id ->
            val left = a.items[id]
            val right = b.items[id]
            when {
                left == null -> right!!
                right == null -> left
                else -> mergeItems(left, right)
            }
        }

        // The list name is a last-writer-wins register without a stamp of its
        // own; the replica that has observed more wins, with a deterministic
        // fallback so both sides agree when they are level.
        val name = when {
            a.counter > b.counter -> a.name
            b.counter > a.counter -> b.name
            else -> minOf(a.name, b.name)
        }

        return SharedList(
            id = a.id,
            name = name,
            items = mergedItems,
            counter = maxOf(a.counter, b.counter)
        )
    }

    private fun mergeItems(left: ListItem, right: ListItem): ListItem = ListItem(
        id = left.id,
        text = if (left.textStamp >= right.textStamp) left.text else right.text,
        textStamp = maxOf(left.textStamp, right.textStamp),
        done = if (left.doneStamp >= right.doneStamp) left.done else right.done,
        doneStamp = maxOf(left.doneStamp, right.doneStamp),
        removed = if (left.removedStamp >= right.removedStamp) left.removed else right.removed,
        removedStamp = maxOf(left.removedStamp, right.removedStamp),
        createdAtEpochMillis = minOf(left.createdAtEpochMillis, right.createdAtEpochMillis)
    )

    /**
     * An edit can arrive before the ADD that created the item — the mesh
     * does not guarantee ordering. Rather than dropping it (which would lose
     * the edit permanently), we materialise a placeholder that a later ADD
     * fills in.
     */
    private fun placeholderFor(operation: ListOperation): ListItem {
        val zero = Stamp(0, "")
        return ListItem(
            id = operation.itemId,
            text = "",
            done = false,
            removed = false,
            createdAtEpochMillis = operation.atEpochMillis,
            textStamp = zero,
            doneStamp = zero,
            removedStamp = zero
        )
    }
}
