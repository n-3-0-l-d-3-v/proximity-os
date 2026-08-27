package os.proximity.shared.mesh

import kotlinx.coroutines.flow.Flow

/**
 * A single inbound payload received from an already-connected peer.
 */
data class IncomingMessage(
    val fromPeerAddress: String,
    val payload: ByteArray
)

/**
 * Platform-specific radio transport (Bluetooth LE today; Wi-Fi Direct/Aware
 * later). Implementations live outside `shared`, per docs/ARCHITECTURE.md —
 * this is the interface the rest of the app programs against, so every
 * inbound and outbound action can be mediated by the Guardrail Engine
 * uniformly regardless of which radio carried it.
 *
 * Nothing in this interface bypasses the Guardrail Engine implicitly:
 * callers are responsible for evaluating a [os.proximity.shared.guardrail.GuardrailRequest]
 * before calling [connect] or acting on a value from [incomingMessages].
 */
interface MeshTransport {

    /** Nearby peers currently visible, updated as scan results arrive. */
    val discoveredPeers: Flow<List<DiscoveredPeer>>

    /** Raw bytes received from already-connected peers. */
    val incomingMessages: Flow<IncomingMessage>

    fun startDiscovery()

    fun stopDiscovery()

    suspend fun connect(peerAddress: String): Boolean

    fun disconnect(peerAddress: String)

    /**
     * Largest single write this peer's link will accept, after any MTU
     * negotiation. Callers must chunk to this size — on BLE it can be as
     * small as 20 bytes.
     */
    fun maxPayloadSize(peerAddress: String): Int

    /**
     * Sends one already-chunked payload, suspending until the link reports
     * the write completed. Implementations must serialise writes per peer:
     * BLE allows only one outstanding write per connection, and issuing the
     * next before the previous completes silently drops it.
     */
    suspend fun send(peerAddress: String, payload: ByteArray): Boolean
}
