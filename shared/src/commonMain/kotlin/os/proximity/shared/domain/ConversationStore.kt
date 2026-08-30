package os.proximity.shared.domain

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import os.proximity.shared.storage.FileStore

/** Persists conversation history across restarts. */
interface ConversationStore {
    suspend fun load(): Map<String, Conversation>
    suspend fun save(conversations: Map<String, Conversation>)
}

/**
 * File-backed [ConversationStore].
 *
 * History is trimmed to the most recent [maxMessagesPerConversation] per
 * peer. An unbounded chat log on a device with no way to delete it is both
 * a storage problem and a privacy one — a phone that is later lost or
 * inspected should not carry every message ever exchanged.
 *
 * Messages are stored in plaintext (see docs/adr/0002-storage.md); this
 * relies on Android's app-private storage and full-disk encryption, and is
 * a known gap rather than a deliberate position.
 */
class FileConversationStore(
    private val files: FileStore,
    private val maxMessagesPerConversation: Int = 500,
    private val fileName: String = DEFAULT_FILE_NAME
) : ConversationStore {

    override suspend fun load(): Map<String, Conversation> {
        val raw = files.readText(fileName) ?: return emptyMap()
        return try {
            json.decodeFromString(ListSerializer(Conversation.serializer()), raw)
                // Nothing is connected at startup, whatever the file says.
                .map { it.copy(isOnline = false) }
                .associateBy { it.peerDeviceId }
        } catch (e: Exception) {
            // Losing chat history is unfortunate but not a security failure;
            // start empty rather than refusing to launch.
            emptyMap()
        }
    }

    override suspend fun save(conversations: Map<String, Conversation>) {
        val trimmed = conversations.values.map { conversation ->
            conversation.copy(
                messages = conversation.messages.takeLast(maxMessagesPerConversation)
            )
        }
        files.writeText(
            fileName,
            json.encodeToString(ListSerializer(Conversation.serializer()), trimmed)
        )
    }

    companion object {
        const val DEFAULT_FILE_NAME = "conversations.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
