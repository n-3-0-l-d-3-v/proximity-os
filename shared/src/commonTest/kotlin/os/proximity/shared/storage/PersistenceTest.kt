package os.proximity.shared.storage

import kotlinx.coroutines.test.runTest
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.AuditLogEntry
import os.proximity.shared.guardrail.FileAuditLog
import os.proximity.shared.guardrail.GuardrailRequest
import os.proximity.shared.guardrail.PeerContext
import os.proximity.shared.guardrail.RequestDirection
import os.proximity.shared.guardrail.TrustState
import os.proximity.shared.identity.FileTrustStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceTest {

    private fun entry(index: Int, action: ActionType = ActionType.DISCOVER_PEER) = AuditLogEntry(
        timestampEpochMillis = 1_000L + index,
        request = GuardrailRequest(
            direction = RequestDirection.OUTBOUND,
            actionType = action,
            peer = PeerContext("peer-$index", TrustState.UNVERIFIED)
        ),
        decision = AuditLogEntry.DecisionOutcome.ALLOW,
        reason = "reason $index"
    )

    // ------------------------------------------------------------ audit log

    @Test
    fun auditEntriesSurviveAReload() = runTest {
        val files = InMemoryFileStore()

        val first = FileAuditLog(files)
        first.append(entry(1))
        first.append(entry(2))

        val reloaded = FileAuditLog(files)
        reloaded.load()

        val entries = reloaded.recent()
        assertEquals(2, entries.size)
        // Newest first is the API contract, even across a reload.
        assertEquals("reason 2", entries.first().reason)
        assertEquals("reason 1", entries.last().reason)
    }

    @Test
    fun reloadPreservesFullEntryContent() = runTest {
        val files = InMemoryFileStore()
        FileAuditLog(files).append(entry(7, ActionType.EXECUTE_CODE))

        val reloaded = FileAuditLog(files).also { it.load() }
        val restored = reloaded.recent().single()

        assertEquals(ActionType.EXECUTE_CODE, restored.request.actionType)
        assertEquals("peer-7", restored.request.peer?.deviceId)
        assertEquals(AuditLogEntry.DecisionOutcome.ALLOW, restored.decision)
        assertEquals(1_007L, restored.timestampEpochMillis)
    }

    @Test
    fun aCorruptLineIsSkippedRatherThanLosingTheWholeLog() = runTest {
        val files = InMemoryFileStore()
        val log = FileAuditLog(files)
        log.append(entry(1))
        log.append(entry(2))

        // Simulate a torn write from a crash.
        files.appendLine(FileAuditLog.DEFAULT_FILE_NAME, "{\"timestampEpochMil")

        val reloaded = FileAuditLog(files).also { it.load() }

        assertEquals(2, reloaded.recent().size)
        assertEquals(1, reloaded.corruptLinesSkipped)
    }

    @Test
    fun logIsBoundedAcrossReloads() = runTest {
        val files = InMemoryFileStore()
        val log = FileAuditLog(files, maxEntries = 5)
        repeat(20) { log.append(entry(it)) }

        assertEquals(5, log.recent(100).size)

        val reloaded = FileAuditLog(files, maxEntries = 5).also { it.load() }
        assertEquals(5, reloaded.recent(100).size)
        // The most recent entries are the ones kept.
        assertEquals("reason 19", reloaded.recent().first().reason)
    }

    @Test
    fun compactionShrinksTheFileButKeepsRetainedEntries() = runTest {
        val files = InMemoryFileStore()
        val log = FileAuditLog(files, maxEntries = 3)
        repeat(30) { log.append(entry(it)) }

        val sizeBefore = files.sizeOf(FileAuditLog.DEFAULT_FILE_NAME)
        log.compact()
        val sizeAfter = files.sizeOf(FileAuditLog.DEFAULT_FILE_NAME)

        assertTrue(sizeAfter < sizeBefore, "compaction should shrink the file")

        val reloaded = FileAuditLog(files, maxEntries = 3).also { it.load() }
        assertEquals(3, reloaded.recent(100).size)
        assertEquals("reason 29", reloaded.recent().first().reason)
    }

    @Test
    fun emptyStoreLoadsCleanly() = runTest {
        val log = FileAuditLog(InMemoryFileStore())
        log.load()
        assertTrue(log.recent().isEmpty())
    }

    // ---------------------------------------------------------- trust store

    @Test
    fun verificationSurvivesAReload() = runTest {
        val files = InMemoryFileStore()

        val store = FileTrustStore(files)
        store.markVerified("device-a")
        store.markVerified("device-b")

        val reloaded = FileTrustStore(files).also { it.load() }

        assertEquals(TrustState.VERIFIED, reloaded.trustStateOf("device-a"))
        assertEquals(TrustState.VERIFIED, reloaded.trustStateOf("device-b"))
        assertEquals(TrustState.UNVERIFIED, reloaded.trustStateOf("device-c"))
    }

    @Test
    fun revocationSurvivesAReload() = runTest {
        val files = InMemoryFileStore()
        val store = FileTrustStore(files)
        store.markVerified("device-a")
        store.revokeVerification("device-a")

        val reloaded = FileTrustStore(files).also { it.load() }
        assertEquals(TrustState.UNVERIFIED, reloaded.trustStateOf("device-a"))
    }

    @Test
    fun corruptTrustFileFailsClosed() = runTest {
        val files = InMemoryFileStore()
        files.writeText(FileTrustStore.DEFAULT_FILE_NAME, "not json at all")

        val store = FileTrustStore(files).also { it.load() }

        // Trusting nobody is the safe failure. Trusting everybody would be
        // catastrophic, and silently trusting a partial list nearly as bad.
        assertEquals(TrustState.UNVERIFIED, store.trustStateOf("device-a"))
        assertTrue(store.verifiedDeviceIds.value.isEmpty())
    }

    @Test
    fun trustStoreStartsEmptyOnFirstRun() = runTest {
        val store = FileTrustStore(InMemoryFileStore()).also { it.load() }
        assertTrue(store.verifiedDeviceIds.value.isEmpty())
    }
}
