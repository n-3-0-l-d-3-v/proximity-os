package os.proximity.shared.domain

import kotlinx.serialization.Serializable
import os.proximity.shared.guardrail.TrustState

/** How far along we are with a nearby device. */
enum class LinkState {
    /** Seen in a scan; nothing exchanged. */
    DISCOVERED,

    CONNECTING,

    /** Transport connected, cryptographic handshake in progress. */
    HANDSHAKING,

    /** Authenticated encrypted session established. */
    SECURED,

    DISCONNECTED,

    FAILED
}

/**
 * A nearby device as the app understands it: what the radio saw, plus what
 * the handshake proved, plus what the user has decided about them.
 *
 * [deviceId] and [fingerprint] are null until a handshake completes — before
 * that we know only a transport address, which is not an identity and must
 * never be presented to the user as one.
 */
data class Peer(
    val transportAddress: String,
    val deviceId: String? = null,
    val displayName: String? = null,
    val fingerprint: String? = null,
    val trustState: TrustState = TrustState.UNVERIFIED,
    val linkState: LinkState = LinkState.DISCOVERED,
    val rssi: Int? = null,
    val lastSeenEpochMillis: Long = 0,
    val statusDetail: String? = null
) {
    /** What to show a human. Falls back through the levels of certainty we have. */
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: deviceId?.take(8)
            ?: transportAddress

    val isSecured: Boolean get() = linkState == LinkState.SECURED
}

@Serializable
enum class MessageDirection { SENT, RECEIVED }

@Serializable
enum class DeliveryState { PENDING, SENT, DELIVERED, FAILED }

@Serializable
data class ChatMessage(
    val id: String,
    val peerDeviceId: String,
    val direction: MessageDirection,
    val body: String,
    val sentAtEpochMillis: Long,
    val deliveryState: DeliveryState = DeliveryState.PENDING
)

/** A pairwise conversation, keyed by the peer's cryptographic device ID. */
@Serializable
data class Conversation(
    val peerDeviceId: String,
    val peerLabel: String,
    val peerFingerprint: String?,
    val trustState: TrustState,
    val messages: List<ChatMessage> = emptyList(),
    val isOnline: Boolean = false
) {
    val lastMessage: ChatMessage? get() = messages.lastOrNull()
}
