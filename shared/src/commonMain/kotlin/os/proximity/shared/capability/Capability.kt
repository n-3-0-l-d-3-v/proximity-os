package os.proximity.shared.capability

import kotlinx.serialization.Serializable

/**
 * Something a device is currently offering to do for nearby peers.
 *
 * A capability is a **claim, not a grant**. "I accept files" says what this
 * device is willing to be asked; it confers nothing on the asker. Whether
 * an actual request is honoured is decided by the Guardrail Engine on the
 * receiving side, every time. Conflating the two would let a peer widen its
 * own permissions simply by advertising more.
 *
 * Capabilities are deliberately short-lived. A standing, permanent
 * advertisement is a beacon: it says "this device is here and does X" to
 * anyone listening, indefinitely. Expiry means a device that walks away
 * stops being advertised rather than lingering in everyone's list, and it
 * bounds how stale a peer's picture of the mesh can be.
 */
@Serializable
data class Capability(
    val name: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long
) {
    fun isValidAt(nowEpochMillis: Long): Boolean = nowEpochMillis < expiresAtEpochMillis
}

/** What one device told us it currently offers. */
@Serializable
data class CapabilityAdvertisement(
    val capabilities: List<Capability>,
    val issuedAtEpochMillis: Long
)

/**
 * The capabilities this build understands, with the words shown to a user.
 *
 * Like the policy catalog, this exists so the description and the behaviour
 * live together. An advertisement naming something not in this catalog is
 * ignored rather than displayed: a peer must not be able to put arbitrary
 * text in front of the user.
 */
data class CapabilityDefinition(
    val name: String,
    val title: String,
    val explanation: String,
    val enabledByDefault: Boolean
)

object CapabilityCatalog {

    const val CHAT = "chat"
    const val SHARED_LISTS = "shared_lists"
    const val RELAY = "relay"
    const val FILE_DROP = "file_drop"

    val definitions: List<CapabilityDefinition> = listOf(
        CapabilityDefinition(
            name = CHAT,
            title = "Messaging",
            explanation = "Nearby people can start a conversation with you.",
            enabledByDefault = true
        ),
        CapabilityDefinition(
            name = SHARED_LISTS,
            title = "Shared lists",
            explanation = "People you connect to can see and edit lists you share with them.",
            enabledByDefault = true
        ),
        CapabilityDefinition(
            name = RELAY,
            title = "Carrying messages",
            explanation = "Your phone offers to pass encrypted messages between devices that " +
                "cannot reach each other directly. You cannot read what you carry.",
            enabledByDefault = false
        ),
        CapabilityDefinition(
            name = FILE_DROP,
            title = "Receiving files",
            explanation = "Tells nearby devices you are willing to be asked. Every file still " +
                "needs your approval, and your rules still apply.",
            enabledByDefault = false
        )
    )

    val defaultEnabled: Set<String> =
        definitions.filter { it.enabledByDefault }.map { it.name }.toSet()

    fun isKnown(name: String): Boolean = definitions.any { it.name == name }

    fun definitionOf(name: String): CapabilityDefinition? =
        definitions.firstOrNull { it.name == name }
}
