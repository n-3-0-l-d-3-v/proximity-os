package os.proximity.shared.guardrail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [AuditLog], bounded so a peer that spams requests cannot grow it
 * without limit (docs/THREAT_MODEL.md #6).
 *
 * Nothing survives an app restart yet; a durable SQLDelight-backed
 * implementation is planned, and the interface is shaped for it.
 */
class InMemoryAuditLog(private val maxEntries: Int = 500) : AuditLog {

    private val mutex = Mutex()
    private val entriesState = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    override val entries: StateFlow<List<AuditLogEntry>> = entriesState

    override suspend fun append(entry: AuditLogEntry) = mutex.withLock {
        entriesState.value = (listOf(entry) + entriesState.value).take(maxEntries)
    }

    override suspend fun recent(limit: Int): List<AuditLogEntry> = mutex.withLock {
        entriesState.value.take(limit)
    }
}
