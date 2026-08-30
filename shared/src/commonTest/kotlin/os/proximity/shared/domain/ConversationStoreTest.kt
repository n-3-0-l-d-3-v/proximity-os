package os.proximity.shared.domain

import kotlinx.coroutines.test.runTest
import os.proximity.shared.guardrail.TrustState
import os.proximity.shared.storage.InMemoryFileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationStoreTest {

    private fun message(id: String, body: String, at: Long) = ChatMessage(
        id = id,
        peerDeviceId = "peer-1",
        direction = MessageDirection.SENT,
        body = body,
        sentAtEpochMillis = at,
        deliveryState = DeliveryState.DELIVERED
    )

    private fun conversation(vararg messages: ChatMessage) = Conversation(
        peerDeviceId = "peer-1",
        peerLabel = "Alice",
        peerFingerprint = "ABCD-1234-5678",
        trustState = TrustState.VERIFIED,
        messages = messages.toList(),
        isOnline = true
    )

    @Test
    fun conversationsSurviveAReload() = runTest {
        val files = InMemoryFileStore()
        val store = FileConversationStore(files)

        store.save(mapOf("peer-1" to conversation(message("m1", "hello", 100))))
        val loaded = FileConversationStore(files).load()

        val restored = loaded.getValue("peer-1")
        assertEquals("Alice", restored.peerLabel)
        assertEquals(TrustState.VERIFIED, restored.trustState)
        assertEquals(listOf("hello"), restored.messages.map { it.body })
        assertEquals(DeliveryState.DELIVERED, restored.messages.single().deliveryState)
    }

    @Test
    fun restoredConversationsAreNeverMarkedOnline() = runTest {
        val files = InMemoryFileStore()
        FileConversationStore(files).save(mapOf("peer-1" to conversation(message("m1", "hi", 1))))

        val restored = FileConversationStore(files).load().getValue("peer-1")

        // Reachability is a fact about right now. Showing a peer as connected
        // because they were last time would be a claim the user might act on.
        assertFalse(restored.isOnline)
    }

    @Test
    fun historyIsTrimmedToTheMostRecentMessages() = runTest {
        val files = InMemoryFileStore()
        val store = FileConversationStore(files, maxMessagesPerConversation = 5)

        val many = (1..50).map { message("m$it", "body $it", it.toLong()) }
        store.save(mapOf("peer-1" to conversation(*many.toTypedArray())))

        val restored = FileConversationStore(files).load().getValue("peer-1")

        assertEquals(5, restored.messages.size)
        assertEquals("body 50", restored.messages.last().body)
        assertEquals("body 46", restored.messages.first().body)
    }

    @Test
    fun aCorruptFileLosesHistoryRatherThanFailingToStart() = runTest {
        val files = InMemoryFileStore()
        files.writeText(FileConversationStore.DEFAULT_FILE_NAME, "{ not json")

        assertTrue(FileConversationStore(files).load().isEmpty())
    }

    @Test
    fun firstRunLoadsCleanly() = runTest {
        assertTrue(FileConversationStore(InMemoryFileStore()).load().isEmpty())
    }
}
