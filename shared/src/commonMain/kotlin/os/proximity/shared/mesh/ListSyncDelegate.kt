package os.proximity.shared.mesh

import os.proximity.shared.lists.ListOperation
import os.proximity.shared.lists.SharedList

/**
 * How [MeshManager] hands list traffic to whatever owns list state.
 *
 * This seam exists so the mesh does not depend on storage and the list
 * repository does not depend on the radio. Either can be tested without
 * the other.
 */
interface ListSyncDelegate {

    /** A single change arrived from a peer. */
    suspend fun onRemoteOperation(operation: ListOperation)

    /** A full replica arrived from a peer and should be merged. */
    suspend fun onRemoteSnapshot(list: SharedList)

    /** State to offer a peer when a session is established. */
    fun snapshotsForSync(): List<SharedList>
}
