package os.proximity.shared.lists

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import os.proximity.shared.mesh.ListSyncDelegate
import os.proximity.shared.storage.FileStore

/**
 * Owns this device's replicas of every shared list.
 *
 * Local edits return the [ListOperation] they produced so the caller can
 * broadcast it; the repository does not know about the mesh, and the mesh
 * does not know about storage. Keeping that seam means list semantics can
 * be tested without a radio, and the transport can be tested without a
 * disk.
 *
 * Every mutation is persisted before it is announced. A list the user can
 * see but that vanishes on restart would be worse than one that never
 * appeared.
 */
class SharedListRepository(
    private val files: FileStore,
    private val deviceId: suspend () -> String,
    private val now: () -> Long,
    private val fileName: String = DEFAULT_FILE_NAME
) : ListSyncDelegate {

    private val mutex = Mutex()
    private val listsState = MutableStateFlow<Map<String, SharedList>>(emptyMap())
    val lists: StateFlow<Map<String, SharedList>> = listsState.asStateFlow()

    /** Reads persisted lists. Call once at startup. */
    suspend fun load() = mutex.withLock {
        val raw = files.readText(fileName) ?: return@withLock
        val loaded = try {
            json.decodeFromString(ListSerializer(SharedList.serializer()), raw)
        } catch (e: Exception) {
            // A corrupt list file loses shopping items, not security. Start
            // empty rather than refusing to launch.
            emptyList()
        }
        listsState.value = loaded.associateBy { it.id }
    }

    suspend fun createList(name: String): SharedList = mutex.withLock {
        val list = SharedList(id = newId(), name = name.trim().ifBlank { "Untitled list" })
        listsState.value = listsState.value + (list.id to list)
        persist()
        list
    }

    suspend fun deleteListLocally(listId: String) = mutex.withLock {
        // Local-only: removing a list from this device does not tell peers to
        // drop theirs. Deleting other people's copy of a shared thing should
        // be an explicit action, not a side effect of tidying your own phone.
        listsState.value = listsState.value - listId
        persist()
    }

    suspend fun addItem(listId: String, text: String): ListOperation? =
        edit(listId) { list, stampCounter, device ->
            ListOperation(
                listId = listId,
                opId = newId(),
                itemId = newId(),
                kind = ListOperation.Kind.ADD,
                counter = stampCounter,
                deviceId = device,
                atEpochMillis = now(),
                text = text.trim()
            )
        }

    suspend fun setItemText(listId: String, itemId: String, text: String): ListOperation? =
        edit(listId) { _, stampCounter, device ->
            ListOperation(
                listId = listId,
                opId = newId(),
                itemId = itemId,
                kind = ListOperation.Kind.SET_TEXT,
                counter = stampCounter,
                deviceId = device,
                atEpochMillis = now(),
                text = text.trim()
            )
        }

    suspend fun setItemDone(listId: String, itemId: String, done: Boolean): ListOperation? =
        edit(listId) { _, stampCounter, device ->
            ListOperation(
                listId = listId,
                opId = newId(),
                itemId = itemId,
                kind = ListOperation.Kind.SET_DONE,
                counter = stampCounter,
                deviceId = device,
                atEpochMillis = now(),
                done = done
            )
        }

    suspend fun removeItem(listId: String, itemId: String): ListOperation? =
        edit(listId) { _, stampCounter, device ->
            ListOperation(
                listId = listId,
                opId = newId(),
                itemId = itemId,
                kind = ListOperation.Kind.REMOVE,
                counter = stampCounter,
                deviceId = device,
                atEpochMillis = now()
            )
        }

    suspend fun renameList(listId: String, name: String): ListOperation? =
        edit(listId) { _, stampCounter, device ->
            ListOperation(
                listId = listId,
                opId = newId(),
                itemId = "",
                kind = ListOperation.Kind.RENAME_LIST,
                counter = stampCounter,
                deviceId = device,
                atEpochMillis = now(),
                listName = name.trim()
            )
        }

    /** Applies an operation received from a peer. */
    suspend fun applyRemote(operation: ListOperation) = mutex.withLock {
        val existing = listsState.value[operation.listId]
        val target = existing
            // A peer can introduce a list we have never seen — that is how
            // sharing starts. The name arrives with a later snapshot or
            // rename; until then it is unnamed rather than fabricated.
            ?: SharedList(id = operation.listId, name = "Shared list")

        listsState.value = listsState.value +
            (operation.listId to SharedListEngine.apply(target, operation))
        persist()
    }

    /** Merges a full replica received from a peer. */
    suspend fun mergeRemote(remote: SharedList) = mutex.withLock {
        val local = listsState.value[remote.id]
        val merged = if (local == null) remote else SharedListEngine.merge(local, remote)
        listsState.value = listsState.value + (remote.id to merged)
        persist()
    }

    // ------------------------------------------------- ListSyncDelegate

    override suspend fun onRemoteOperation(operation: ListOperation) = applyRemote(operation)

    override suspend fun onRemoteSnapshot(list: SharedList) = mergeRemote(list)

    override fun snapshotsForSync(): List<SharedList> = listsState.value.values.toList()

    private suspend fun edit(
        listId: String,
        build: (SharedList, Long, String) -> ListOperation
    ): ListOperation? = mutex.withLock {
        val list = listsState.value[listId] ?: return@withLock null
        val device = deviceId()
        // Strictly greater than anything this replica has observed, so our
        // edit is ordered after everything we already know about.
        val operation = build(list, list.counter + 1, device)

        listsState.value = listsState.value +
            (listId to SharedListEngine.apply(list, operation))
        persist()
        operation
    }

    private suspend fun persist() {
        files.writeText(
            fileName,
            json.encodeToString(ListSerializer(SharedList.serializer()), listsState.value.values.toList())
        )
    }

    private fun newId(): String = "${now().toString(36)}-${(idCounter++).toString(36)}"

    private var idCounter: Long = 0

    companion object {
        const val DEFAULT_FILE_NAME = "shared-lists.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
