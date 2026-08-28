package os.proximity.shared.identity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import os.proximity.shared.guardrail.TrustState
import os.proximity.shared.storage.FileStore

/**
 * Durable [TrustStore].
 *
 * This is the most consequential thing the app persists. "I checked this
 * person's code in person" is expensive for a user to redo, and losing it
 * silently would either push them to re-verify constantly or — far worse —
 * train them to click through verification prompts without reading.
 *
 * If the file is unreadable, the store starts empty rather than guessing.
 * Failing closed means the user is asked to verify again; failing open
 * would mean trusting devices nobody vouched for.
 */
class FileTrustStore(
    private val files: FileStore,
    private val fileName: String = DEFAULT_FILE_NAME
) : TrustStore {

    private val mutex = Mutex()
    private val verified = MutableStateFlow<Set<String>>(emptySet())
    override val verifiedDeviceIds: StateFlow<Set<String>> = verified.asStateFlow()

    /** Reads persisted verifications. Call once at startup. */
    suspend fun load() = mutex.withLock {
        val raw = files.readText(fileName) ?: return@withLock
        verified.value = try {
            json.decodeFromString(SetSerializer(String.serializer()), raw)
        } catch (e: Exception) {
            // Fail closed: an unreadable trust file must not become
            // "everyone is trusted".
            emptySet()
        }
    }

    override fun trustStateOf(deviceId: String): TrustState =
        if (verified.value.contains(deviceId)) TrustState.VERIFIED else TrustState.UNVERIFIED

    override suspend fun markVerified(deviceId: String) = mutex.withLock {
        verified.value = verified.value + deviceId
        persist()
    }

    override suspend fun revokeVerification(deviceId: String) = mutex.withLock {
        verified.value = verified.value - deviceId
        persist()
    }

    private suspend fun persist() {
        files.writeText(
            fileName,
            json.encodeToString(SetSerializer(String.serializer()), verified.value)
        )
    }

    companion object {
        const val DEFAULT_FILE_NAME = "verified-peers.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
