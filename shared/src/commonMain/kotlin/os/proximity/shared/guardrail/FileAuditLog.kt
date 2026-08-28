package os.proximity.shared.guardrail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import os.proximity.shared.storage.FileStore

/**
 * Durable [AuditLog], stored as JSON Lines — one entry per line, appended.
 *
 * The format is chosen so the log is genuinely *readable*, which the
 * project promises: a user (or someone helping them) can open the file and
 * see plain text, rather than needing a database tool to check what their
 * phone did.
 *
 * Append-only is a security property, not just an implementation detail: a
 * log that is rewritten on every change is a log that can quietly lose
 * entries. Rewrites happen only during compaction, and only to drop the
 * oldest entries once the bound is exceeded.
 *
 * A corrupt line — a partial write from a crash, say — is skipped rather
 * than failing the whole load. Losing one entry is much better than losing
 * the entire history.
 */
class FileAuditLog(
    private val files: FileStore,
    private val maxEntries: Int = 1000,
    private val fileName: String = DEFAULT_FILE_NAME
) : AuditLog {

    private val mutex = Mutex()
    private val entriesState = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    override val entries: StateFlow<List<AuditLogEntry>> = entriesState.asStateFlow()

    private var appendsSinceCompaction = 0

    /** Number of lines that could not be parsed on the last [load]. */
    var corruptLinesSkipped: Int = 0
        private set

    /** Reads the log from disk. Call once at startup, before [append]. */
    suspend fun load() = mutex.withLock {
        val raw = files.readText(fileName) ?: return@withLock
        var skipped = 0

        val loaded = raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    json.decodeFromString(AuditLogEntry.serializer(), line)
                } catch (e: Exception) {
                    skipped++
                    null
                }
            }
            .toList()

        corruptLinesSkipped = skipped
        // Stored oldest-first; the API contract is newest-first.
        entriesState.value = loaded.takeLast(maxEntries).asReversed()
    }

    override suspend fun append(entry: AuditLogEntry) = mutex.withLock {
        entriesState.value = (listOf(entry) + entriesState.value).take(maxEntries)
        files.appendLine(fileName, json.encodeToString(AuditLogEntry.serializer(), entry))

        appendsSinceCompaction++
        if (appendsSinceCompaction >= COMPACT_EVERY) {
            compactLocked()
        }
    }

    override suspend fun recent(limit: Int): List<AuditLogEntry> = mutex.withLock {
        entriesState.value.take(limit)
    }

    /** Rewrites the file with only the retained entries. */
    suspend fun compact() = mutex.withLock { compactLocked() }

    private suspend fun compactLocked() {
        val oldestFirst = entriesState.value.asReversed()
        files.writeText(
            fileName,
            oldestFirst.joinToString(separator = "\n", postfix = "\n") {
                json.encodeToString(AuditLogEntry.serializer(), it)
            }
        )
        appendsSinceCompaction = 0
    }

    companion object {
        const val DEFAULT_FILE_NAME = "audit-log.jsonl"

        /** Compaction rewrites the whole file, so it should be infrequent. */
        private const val COMPACT_EVERY = 200

        private val json = Json { ignoreUnknownKeys = true }
    }
}
