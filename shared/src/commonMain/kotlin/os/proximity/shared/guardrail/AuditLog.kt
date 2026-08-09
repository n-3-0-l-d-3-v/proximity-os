package os.proximity.shared.guardrail

import kotlinx.serialization.Serializable

/**
 * A single, immutable audit log entry. The audit log is append-only and
 * local — it is never transmitted off-device by default (doing so would
 * itself be a [GuardrailRequest]).
 */
@Serializable
data class AuditLogEntry(
    val timestampEpochMillis: Long,
    val request: GuardrailRequest,
    val decision: DecisionOutcome,
    val reason: String
) {
    @Serializable
    enum class DecisionOutcome { ALLOW, DENY, ASK_USER }
}

/** Append-only local store for [AuditLogEntry] records. */
interface AuditLog {

    suspend fun append(entry: AuditLogEntry)

    suspend fun recent(limit: Int = 100): List<AuditLogEntry>
}
