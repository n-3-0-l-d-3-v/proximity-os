package os.proximity.shared.lists

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The convergence properties are the entire reason this design was chosen,
 * so they are tested as properties — over shuffled and duplicated operation
 * streams — rather than only as hand-picked examples.
 */
class SharedListEngineTest {

    private val empty = SharedList(id = "list-1", name = "Shopping")

    private fun op(
        kind: ListOperation.Kind,
        itemId: String,
        counter: Long,
        deviceId: String,
        text: String? = null,
        done: Boolean? = null,
        listName: String? = null,
        at: Long = 1_000L + counter
    ) = ListOperation(
        listId = "list-1",
        opId = "$deviceId-$counter-$itemId-${kind.name}",
        itemId = itemId,
        kind = kind,
        counter = counter,
        deviceId = deviceId,
        atEpochMillis = at,
        text = text,
        done = done,
        listName = listName
    )

    // ------------------------------------------------------------- basics

    @Test
    fun addingAnItemMakesItVisible() {
        val list = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
        )

        assertEquals(1, list.visibleItems.size)
        assertEquals("Milk", list.visibleItems.single().text)
        assertEquals(1, list.remainingCount)
    }

    @Test
    fun tickingAnItemOffLeavesItVisibleButNotRemaining() {
        val list = SharedListEngine.applyAll(
            empty,
            listOf(
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk"),
                op(ListOperation.Kind.SET_DONE, "i1", 2, "alice", done = true)
            )
        )

        assertEquals(1, list.visibleItems.size)
        assertTrue(list.visibleItems.single().done)
        assertEquals(0, list.remainingCount)
    }

    @Test
    fun removingAnItemHidesItButKeepsTheTombstone() {
        val list = SharedListEngine.applyAll(
            empty,
            listOf(
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk"),
                op(ListOperation.Kind.REMOVE, "i1", 2, "alice")
            )
        )

        assertTrue(list.visibleItems.isEmpty())
        // The tombstone survives, or a peer who missed the delete would
        // resurrect the item on the next sync.
        assertTrue(list.items.getValue("i1").removed)
    }

    @Test
    fun listCanBeRenamed() {
        val list = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.RENAME_LIST, "", 1, "alice", listName = "Camping trip")
        )
        assertEquals("Camping trip", list.name)
    }

    // -------------------------------------------------------- idempotence

    @Test
    fun applyingTheSameOperationTwiceChangesNothing() {
        val add = op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
        val once = SharedListEngine.apply(empty, add)
        val twice = SharedListEngine.apply(once, add)

        assertEquals(once, twice)
    }

    @Test
    fun redeliveredAddDoesNotResetALaterEdit() {
        val add = op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
        val rename = op(ListOperation.Kind.SET_TEXT, "i1", 5, "bob", text = "Oat milk")

        val list = SharedListEngine.applyAll(empty, listOf(add, rename, add))

        // A retry of the original ADD must not undo the later edit.
        assertEquals("Oat milk", list.visibleItems.single().text)
    }

    // ------------------------------------------------------ commutativity

    @Test
    fun orderOfArrivalDoesNotMatter() {
        val operations = listOf(
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk"),
            op(ListOperation.Kind.ADD, "i2", 2, "bob", text = "Bread"),
            op(ListOperation.Kind.SET_DONE, "i1", 3, "bob", done = true),
            op(ListOperation.Kind.SET_TEXT, "i2", 4, "alice", text = "Sourdough"),
            op(ListOperation.Kind.REMOVE, "i1", 5, "alice")
        )

        val inOrder = SharedListEngine.applyAll(empty, operations)

        repeat(50) { seed ->
            val shuffled = operations.shuffled(Random(seed))
            assertEquals(
                inOrder.items,
                SharedListEngine.applyAll(empty, shuffled).items,
                "diverged for seed $seed"
            )
        }
    }

    @Test
    fun duplicatedAndShuffledStreamsStillConverge() {
        val operations = listOf(
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Tent"),
            op(ListOperation.Kind.ADD, "i2", 2, "bob", text = "Stove"),
            op(ListOperation.Kind.SET_DONE, "i2", 3, "alice", done = true),
            op(ListOperation.Kind.SET_TEXT, "i1", 4, "bob", text = "Tent pegs"),
            op(ListOperation.Kind.SET_DONE, "i1", 6, "alice", done = true)
        )
        val expected = SharedListEngine.applyAll(empty, operations).items

        repeat(50) { seed ->
            val random = Random(seed)
            // Simulate retries: some operations get delivered twice.
            val noisy = (operations + operations.filter { random.nextBoolean() })
                .shuffled(random)
            assertEquals(expected, SharedListEngine.applyAll(empty, noisy).items, "seed $seed")
        }
    }

    // ---------------------------------------------------- concurrent edits

    @Test
    fun concurrentEditsToDifferentFieldsBothSurvive() {
        val list = SharedListEngine.applyAll(
            empty,
            listOf(
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk"),
                // One person renames while the other ticks it off, neither
                // having seen the other. Both edits must survive.
                op(ListOperation.Kind.SET_TEXT, "i1", 2, "alice", text = "Oat milk"),
                op(ListOperation.Kind.SET_DONE, "i1", 2, "bob", done = true)
            )
        )

        val item = list.visibleItems.single()
        assertEquals("Oat milk", item.text)
        assertTrue(item.done)
    }

    @Test
    fun equalCountersAreBrokenDeterministicallyByDeviceId() {
        val base = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "x")
        )
        val fromAlice = op(ListOperation.Kind.SET_TEXT, "i1", 7, "alice", text = "Alice text")
        val fromBob = op(ListOperation.Kind.SET_TEXT, "i1", 7, "bob", text = "Bob text")

        val aliceFirst = SharedListEngine.applyAll(base, listOf(fromAlice, fromBob))
        val bobFirst = SharedListEngine.applyAll(base, listOf(fromBob, fromAlice))

        assertEquals(aliceFirst.items, bobFirst.items)
        // "bob" sorts after "alice", so it wins — arbitrary, but identical
        // on every device, which is the property that matters.
        assertEquals("Bob text", aliceFirst.visibleItems.single().text)
    }

    @Test
    fun anEditArrivingBeforeItsAddIsNotLost() {
        // The mesh does not guarantee ordering; dropping the edit would
        // lose it permanently.
        val list = SharedListEngine.applyAll(
            empty,
            listOf(
                op(ListOperation.Kind.SET_DONE, "i1", 3, "bob", done = true),
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
            )
        )

        val item = list.visibleItems.single()
        assertEquals("Milk", item.text)
        assertTrue(item.done, "the earlier-arriving edit should still apply")
    }

    @Test
    fun aStaleAddDoesNotResurrectARemovedItem() {
        val list = SharedListEngine.applyAll(
            empty,
            listOf(
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk"),
                op(ListOperation.Kind.REMOVE, "i1", 9, "alice"),
                // A peer replaying the original ADD after the delete.
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
            )
        )

        assertTrue(list.visibleItems.isEmpty())
    }

    @Test
    fun observingARemoteOperationAdvancesTheLocalCounter() {
        val list = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 42, "bob", text = "Milk")
        )
        // Our next edit must be ordered after everything we have seen, or a
        // device with a lagging counter could never win a conflict.
        assertEquals(42, list.counter)
    }

    // ------------------------------------------------------------ merging

    @Test
    fun divergedReplicasMergeToTheSameState() {
        val base = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
        )

        // Out of range: each side edits independently.
        val aliceSide = SharedListEngine.applyAll(
            base,
            listOf(
                op(ListOperation.Kind.SET_TEXT, "i1", 5, "alice", text = "Oat milk"),
                op(ListOperation.Kind.ADD, "i2", 6, "alice", text = "Coffee")
            )
        )
        val bobSide = SharedListEngine.applyAll(
            base,
            listOf(
                op(ListOperation.Kind.SET_DONE, "i1", 4, "bob", done = true),
                op(ListOperation.Kind.ADD, "i3", 7, "bob", text = "Sugar")
            )
        )

        val onAlice = SharedListEngine.merge(aliceSide, bobSide)
        val onBob = SharedListEngine.merge(bobSide, aliceSide)

        // Merge must be commutative, or the two phones disagree forever.
        assertEquals(onAlice, onBob)
        assertEquals(3, onAlice.visibleItems.size)
        assertEquals("Oat milk", onAlice.items.getValue("i1").text)
        assertTrue(onAlice.items.getValue("i1").done)
    }

    @Test
    fun mergeIsIdempotent() {
        val a = SharedListEngine.applyAll(
            empty,
            listOf(
                op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk"),
                op(ListOperation.Kind.ADD, "i2", 2, "alice", text = "Eggs")
            )
        )
        assertEquals(a, SharedListEngine.merge(a, a))
    }

    @Test
    fun mergePreservesTombstones() {
        val withItem = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
        )
        val afterDelete = SharedListEngine.apply(
            withItem,
            op(ListOperation.Kind.REMOVE, "i1", 5, "alice")
        )

        // A peer that never heard about the delete still holds the item.
        val merged = SharedListEngine.merge(afterDelete, withItem)

        assertTrue(merged.visibleItems.isEmpty(), "the delete must win over a stale replica")
    }

    @Test
    fun mergeKeepsTheEarliestCreationTimeSoOrderingIsStable() {
        val early = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk", at = 100)
        )
        val late = SharedListEngine.apply(
            empty,
            op(ListOperation.Kind.ADD, "i1", 2, "bob", text = "Milk", at = 900)
        )

        assertEquals(
            100,
            SharedListEngine.merge(early, late).items.getValue("i1").createdAtEpochMillis
        )
        assertEquals(
            100,
            SharedListEngine.merge(late, early).items.getValue("i1").createdAtEpochMillis
        )
    }

    @Test
    fun operationsForADifferentListAreIgnored() {
        val foreign = op(ListOperation.Kind.ADD, "i1", 1, "alice", text = "Milk")
            .copy(listId = "some-other-list")

        assertEquals(empty, SharedListEngine.apply(empty, foreign))
    }

    @Test
    fun randomisedStreamsConvergeAcrossReplicas() {
        repeat(30) { seed ->
            val random = Random(seed)
            val devices = listOf("alice", "bob", "carol")
            val operations = (1..40).map { n ->
                val device = devices[random.nextInt(devices.size)]
                val itemId = "i${random.nextInt(6)}"
                when (random.nextInt(4)) {
                    0 -> op(ListOperation.Kind.ADD, itemId, n.toLong(), device, text = "text$n")
                    1 -> op(ListOperation.Kind.SET_TEXT, itemId, n.toLong(), device, text = "text$n")
                    2 -> op(
                        ListOperation.Kind.SET_DONE,
                        itemId,
                        n.toLong(),
                        device,
                        done = random.nextBoolean()
                    )
                    else -> op(ListOperation.Kind.REMOVE, itemId, n.toLong(), device)
                }
            }

            val replicaA = SharedListEngine.applyAll(empty, operations)
            val replicaB = SharedListEngine.applyAll(empty, operations.shuffled(random))
            val replicaC = SharedListEngine.applyAll(empty, operations.reversed())

            assertEquals(replicaA.items, replicaB.items, "A vs B diverged, seed $seed")
            assertEquals(replicaA.items, replicaC.items, "A vs C diverged, seed $seed")
            assertFalse(replicaA.counter == 0L)
        }
    }
}
