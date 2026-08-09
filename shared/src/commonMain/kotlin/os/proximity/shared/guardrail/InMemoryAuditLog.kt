package os.proximity.shared.guardrail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [AuditLog]. Sufficient for Phase 1 (nothing survives an app
 * restart yet); a durable, on-disk implementation backed by SQLDelight
 * belongs in a later phase per docs/ARCHITECTURE.md.
 */
class InMemoryAuditLog(private val maxEntries: Int = 500) : AuditLog {

    private val mutex = Mutex()
    private val entries = mutableListOf<AuditLogEntry>()

    override suspend fun append(entry: AuditLogEntry) = mutex.withLock {
        entries.add(0, entry)
        while (entries.size > maxEntries) {
            entries.removeAt(entries.lastIndex)
        }
    }

    override suspend fun recent(limit: Int): List<AuditLogEntry> = mutex.withLock {
        entries.take(limit)
    }
}
