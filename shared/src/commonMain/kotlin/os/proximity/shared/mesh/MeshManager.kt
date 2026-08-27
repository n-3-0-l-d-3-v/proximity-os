package os.proximity.shared.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.proximity.shared.crypto.CryptoPrimitives
import os.proximity.shared.crypto.SecureSession
import os.proximity.shared.domain.ChatMessage
import os.proximity.shared.domain.Conversation
import os.proximity.shared.domain.DeliveryState
import os.proximity.shared.domain.LinkState
import os.proximity.shared.domain.MessageDirection
import os.proximity.shared.domain.Peer
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.GuardrailDecision
import os.proximity.shared.guardrail.GuardrailEngine
import os.proximity.shared.guardrail.GuardrailRequest
import os.proximity.shared.guardrail.PeerContext
import os.proximity.shared.guardrail.RequestDirection
import os.proximity.shared.identity.DeviceIdentityProvider
import os.proximity.shared.identity.SignatureVerifier
import os.proximity.shared.identity.TrustStore
import os.proximity.shared.protocol.AssembledMessage
import os.proximity.shared.protocol.Envelope
import os.proximity.shared.protocol.EnvelopeCodec
import os.proximity.shared.protocol.Frame
import os.proximity.shared.protocol.FrameChunker
import os.proximity.shared.protocol.FrameType
import os.proximity.shared.protocol.MessageAssembler
import os.proximity.shared.session.Handshake
import os.proximity.shared.session.HandshakeOutcome
import os.proximity.shared.session.SessionRole
import os.proximity.shared.util.currentTimeMillis

/**
 * Something the mesh needs a human to resolve or be told about.
 */
sealed class MeshEvent {

    /**
     * The Guardrail Engine returned "ask me". The mesh is blocked on this
     * peer until [MeshManager.resolveDecision] is called.
     */
    data class DecisionRequired(
        val decisionId: String,
        val actionType: ActionType,
        val peerLabel: String,
        val peerFingerprint: String?,
        val reason: String
    ) : MeshEvent()

    data class Blocked(val reason: String) : MeshEvent()

    data class Notice(val text: String) : MeshEvent()
}

/**
 * Coordinates transport, handshake, encryption, and policy.
 *
 * This is deliberately the *only* path between the radio and the rest of
 * the app. Nothing here touches the transport without first asking the
 * [GuardrailEngine], and nothing hands a decrypted payload upward without
 * having asked as well — which is what makes "every action passes through
 * the Guardrail Engine" a structural property rather than a convention
 * someone has to remember.
 */
class MeshManager(
    private val transport: MeshTransport,
    private val primitives: CryptoPrimitives,
    private val identityProvider: DeviceIdentityProvider,
    private val verifier: SignatureVerifier,
    private val guardrail: GuardrailEngine,
    private val trustStore: TrustStore,
    private val scope: CoroutineScope,
    private val displayName: () -> String
) {

    private val mutex = Mutex()
    private val links = mutableMapOf<String, LinkContext>()

    private val peersState = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = peersState.asStateFlow()

    private val conversationsState = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = conversationsState.asStateFlow()

    private val eventsFlow = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<MeshEvent> = eventsFlow.asSharedFlow()

    private val isScanningState = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = isScanningState.asStateFlow()

    private val pendingDecisions = mutableMapOf<String, PendingDecision>()
    private var decisionCounter = 0

    private class LinkContext(
        val address: String,
        val assembler: MessageAssembler = MessageAssembler(),
        var handshake: Handshake? = null,
        var session: SecureSession? = null,
        var state: LinkState = LinkState.DISCOVERED,
        /** Distinguishes application messages we originate. */
        var messageCounter: Int = 0,
        /** Separate sequence: identifies a chunked frame group on the wire. */
        var frameCounter: Int = 0,
        var detail: String? = null
    )

    private class PendingDecision(
        val address: String,
        val onAllow: suspend () -> Unit,
        val onDeny: suspend () -> Unit
    )

    fun start() {
        scope.launch {
            transport.discoveredPeers.collect { discovered -> mergeDiscovered(discovered) }
        }
        scope.launch {
            transport.incomingMessages.collect { message -> onIncoming(message) }
        }
    }

    // ---------------------------------------------------------------- discovery

    suspend fun startDiscovery(): Boolean {
        val decision = guardrail.evaluate(
            GuardrailRequest(RequestDirection.OUTBOUND, ActionType.DISCOVER_PEER)
        )
        if (decision !is GuardrailDecision.Allow) {
            eventsFlow.emit(MeshEvent.Blocked(decision.reason))
            return false
        }
        transport.startDiscovery()
        isScanningState.value = true
        return true
    }

    fun stopDiscovery() {
        transport.stopDiscovery()
        isScanningState.value = false
    }

    private suspend fun mergeDiscovered(discovered: List<DiscoveredPeer>) = mutex.withLock {
        val byAddress = peersState.value.associateBy { it.transportAddress }.toMutableMap()
        discovered.forEach { found ->
            val existing = byAddress[found.transportAddress]
            byAddress[found.transportAddress] = (existing ?: Peer(found.transportAddress)).copy(
                rssi = found.rssi,
                lastSeenEpochMillis = found.lastSeenEpochMillis,
                // Keep the cryptographically-established name once we have one;
                // the advertised name is peer-controlled and unauthenticated.
                displayName = existing?.displayName ?: found.displayName
            )
        }
        peersState.value = byAddress.values.sortedWith(
            compareByDescending<Peer> { it.isSecured }.thenByDescending { it.lastSeenEpochMillis }
        )
    }

    // --------------------------------------------------------------- connecting

    /**
     * Connect and complete a handshake. Returns false if policy blocked it or
     * the transport failed; an "ask me" decision returns false immediately
     * and continues later via [resolveDecision].
     */
    suspend fun connectTo(address: String): Boolean {
        val peer = peersState.value.firstOrNull { it.transportAddress == address }
        val decision = guardrail.evaluate(
            GuardrailRequest(
                direction = RequestDirection.OUTBOUND,
                actionType = ActionType.CONNECT_PEER,
                peer = PeerContext(address, peer?.trustState ?: trustStore.trustStateOf(address))
            )
        )

        return when (decision) {
            is GuardrailDecision.Allow -> performConnect(address)

            is GuardrailDecision.Deny -> {
                eventsFlow.emit(MeshEvent.Blocked(decision.reason))
                false
            }

            is GuardrailDecision.AskUser -> {
                askUser(
                    address = address,
                    actionType = ActionType.CONNECT_PEER,
                    reason = decision.reason,
                    peerLabel = peer?.label ?: address,
                    peerFingerprint = peer?.fingerprint,
                    onAllow = { performConnect(address) },
                    onDeny = { }
                )
                false
            }
        }
    }

    private suspend fun performConnect(address: String): Boolean {
        updatePeer(address) { it.copy(linkState = LinkState.CONNECTING, statusDetail = null) }

        if (!transport.connect(address)) {
            updatePeer(address) {
                it.copy(linkState = LinkState.FAILED, statusDetail = "Could not connect.")
            }
            return false
        }

        val identity = identityProvider.getOrCreateIdentity()
        val handshake = Handshake(
            primitives = primitives,
            identity = identity,
            verifier = verifier,
            displayName = displayName(),
            role = SessionRole.INITIATOR
        )

        val context = mutex.withLock {
            links.getOrPut(address) { LinkContext(address) }.also {
                it.handshake = handshake
                it.state = LinkState.HANDSHAKING
            }
        }

        updatePeer(address) { it.copy(linkState = LinkState.HANDSHAKING) }

        val sent = sendEnvelope(context, handshake.createGreeting(), FrameType.HANDSHAKE, session = null)
        if (!sent) {
            updatePeer(address) {
                it.copy(linkState = LinkState.FAILED, statusDetail = "Handshake could not be sent.")
            }
        }
        return sent
    }

    fun disconnect(address: String) {
        transport.disconnect(address)
        scope.launch {
            mutex.withLock { links.remove(address) }
            updatePeer(address) {
                it.copy(linkState = LinkState.DISCONNECTED, statusDetail = null)
            }
        }
    }

    // ------------------------------------------------------------ user decisions

    private suspend fun askUser(
        address: String,
        actionType: ActionType,
        reason: String,
        peerLabel: String,
        peerFingerprint: String?,
        onAllow: suspend () -> Unit,
        onDeny: suspend () -> Unit
    ) {
        val id = mutex.withLock {
            val next = "decision-${decisionCounter++}"
            pendingDecisions[next] = PendingDecision(address, onAllow, onDeny)
            next
        }
        eventsFlow.emit(
            MeshEvent.DecisionRequired(
                decisionId = id,
                actionType = actionType,
                peerLabel = peerLabel,
                peerFingerprint = peerFingerprint,
                reason = reason
            )
        )
    }

    /** Resolves a [MeshEvent.DecisionRequired]. Unknown ids are ignored. */
    suspend fun resolveDecision(decisionId: String, allow: Boolean) {
        val pending = mutex.withLock { pendingDecisions.remove(decisionId) } ?: return
        if (allow) pending.onAllow() else pending.onDeny()
    }

    // ----------------------------------------------------------------- messaging

    suspend fun sendChat(peerDeviceId: String, body: String): Boolean {
        val context = mutex.withLock {
            links.values.firstOrNull { it.session?.peerDeviceId == peerDeviceId }
        }
        val session = context?.session
        if (context == null || session == null) {
            eventsFlow.emit(MeshEvent.Blocked("Not connected to that device any more."))
            return false
        }

        val decision = guardrail.evaluate(
            GuardrailRequest(
                direction = RequestDirection.OUTBOUND,
                actionType = ActionType.SEND_MESSAGE,
                peer = PeerContext(peerDeviceId, trustStore.trustStateOf(peerDeviceId))
            )
        )
        if (decision !is GuardrailDecision.Allow) {
            eventsFlow.emit(MeshEvent.Blocked(decision.reason))
            return false
        }

        val messageId = "${currentTimeMillis()}-${context.messageCounter++}"
        val message = ChatMessage(
            id = messageId,
            peerDeviceId = peerDeviceId,
            direction = MessageDirection.SENT,
            body = body,
            sentAtEpochMillis = currentTimeMillis(),
            deliveryState = DeliveryState.PENDING
        )
        appendMessage(peerDeviceId, message)

        val sent = sendEnvelope(
            context = context,
            envelope = Envelope.Chat(messageId, message.sentAtEpochMillis, body),
            type = FrameType.SEALED,
            session = session
        )

        updateMessage(peerDeviceId, messageId) {
            it.copy(deliveryState = if (sent) DeliveryState.SENT else DeliveryState.FAILED)
        }
        return sent
    }

    private suspend fun sendEnvelope(
        context: LinkContext,
        envelope: Envelope,
        type: FrameType,
        session: SecureSession?
    ): Boolean {
        val encoded = EnvelopeCodec.encode(envelope)
        val body = if (session != null) {
            session.seal(encoded) ?: run {
                eventsFlow.emit(MeshEvent.Blocked("This secure session is exhausted; reconnect to continue."))
                return false
            }
        } else {
            encoded
        }

        val frames = FrameChunker.chunk(
            messageId = context.frameCounter++,
            type = type,
            payload = body,
            maxFrameSize = transport.maxPayloadSize(context.address)
        )

        // Sequential: the transport serialises writes per peer, and a frame
        // that fails makes the rest of the message pointless.
        for (frame in frames) {
            if (!transport.send(context.address, frame.encode())) return false
        }
        return true
    }

    // ----------------------------------------------------------------- inbound

    private suspend fun onIncoming(message: IncomingMessage) {
        val frame = Frame.decode(message.payload) ?: return
        val context = mutex.withLock {
            links.getOrPut(message.fromPeerAddress) { LinkContext(message.fromPeerAddress) }
        }
        val assembled = context.assembler.offer(frame, currentTimeMillis()) ?: return

        when (assembled.type) {
            FrameType.HANDSHAKE -> onHandshakeMessage(context, assembled)
            FrameType.SEALED -> onSealedMessage(context, assembled)
        }
    }

    private suspend fun onHandshakeMessage(context: LinkContext, assembled: AssembledMessage) {
        val envelope = EnvelopeCodec.decode(assembled.payload) ?: return

        val existing = context.handshake
        if (existing != null) {
            // We initiated; this is the peer's HelloAck.
            completeHandshake(context, existing, envelope)
            return
        }

        // Inbound connection: policy decides whether we answer at all.
        val decision = guardrail.evaluate(
            GuardrailRequest(
                direction = RequestDirection.INBOUND,
                actionType = ActionType.CONNECT_PEER,
                peer = PeerContext(context.address, trustStore.trustStateOf(context.address))
            )
        )

        when (decision) {
            is GuardrailDecision.Allow -> respondToHandshake(context, envelope)

            is GuardrailDecision.Deny -> {
                eventsFlow.emit(MeshEvent.Blocked(decision.reason))
                transport.disconnect(context.address)
            }

            is GuardrailDecision.AskUser -> askUser(
                address = context.address,
                actionType = ActionType.CONNECT_PEER,
                reason = decision.reason,
                peerLabel = peerLabelFor(context.address),
                peerFingerprint = null,
                onAllow = { respondToHandshake(context, envelope) },
                onDeny = { transport.disconnect(context.address) }
            )
        }
    }

    private suspend fun respondToHandshake(context: LinkContext, peerGreeting: Envelope) {
        val identity = identityProvider.getOrCreateIdentity()
        val handshake = Handshake(
            primitives = primitives,
            identity = identity,
            verifier = verifier,
            displayName = displayName(),
            role = SessionRole.RESPONDER
        )
        context.handshake = handshake
        context.state = LinkState.HANDSHAKING
        updatePeer(context.address) { it.copy(linkState = LinkState.HANDSHAKING) }

        // Send our greeting first: if we complete the handshake and then fail
        // to reply, the peer is left waiting forever.
        val sent = sendEnvelope(context, handshake.createGreeting(), FrameType.HANDSHAKE, session = null)
        if (!sent) {
            failLink(context, "Could not reply to the handshake.")
            return
        }
        completeHandshake(context, handshake, peerGreeting)
    }

    private suspend fun completeHandshake(
        context: LinkContext,
        handshake: Handshake,
        peerGreeting: Envelope
    ) {
        when (val outcome = handshake.accept(peerGreeting)) {
            is HandshakeOutcome.Failed -> {
                failLink(context, outcome.reason)
                transport.disconnect(context.address)
            }

            is HandshakeOutcome.Established -> {
                val session = outcome.session
                context.session = session
                context.state = LinkState.SECURED

                updatePeer(context.address) {
                    it.copy(
                        deviceId = session.peerDeviceId,
                        displayName = session.peerDisplayName,
                        fingerprint = session.peerFingerprint,
                        trustState = trustStore.trustStateOf(session.peerDeviceId),
                        linkState = LinkState.SECURED,
                        statusDetail = null
                    )
                }
                ensureConversation(session)
                eventsFlow.emit(
                    MeshEvent.Notice("Secure channel established with ${session.peerDisplayName}.")
                )
            }
        }
    }

    private suspend fun onSealedMessage(context: LinkContext, assembled: AssembledMessage) {
        val session = context.session ?: return
        // A record that fails to open is dropped silently — telling a peer
        // why would leak whether it was a bad tag, a replay, or a stale key.
        val plaintext = session.open(assembled.payload) ?: return
        val envelope = EnvelopeCodec.decode(plaintext) ?: return

        when (envelope) {
            is Envelope.Chat -> onChatReceived(session, envelope)
            is Envelope.Ack -> updateMessage(session.peerDeviceId, envelope.messageId) {
                it.copy(deliveryState = DeliveryState.DELIVERED)
            }
            // Remaining envelope kinds arrive in later phases; ignoring them
            // is the default-deny position, not an oversight.
            else -> Unit
        }
    }

    private suspend fun onChatReceived(session: SecureSession, chat: Envelope.Chat) {
        val decision = guardrail.evaluate(
            GuardrailRequest(
                direction = RequestDirection.INBOUND,
                actionType = ActionType.RECEIVE_MESSAGE,
                peer = PeerContext(
                    session.peerDeviceId,
                    trustStore.trustStateOf(session.peerDeviceId)
                )
            )
        )
        if (decision !is GuardrailDecision.Allow) {
            eventsFlow.emit(MeshEvent.Blocked(decision.reason))
            return
        }

        ensureConversation(session)
        appendMessage(
            session.peerDeviceId,
            ChatMessage(
                id = chat.messageId,
                peerDeviceId = session.peerDeviceId,
                direction = MessageDirection.RECEIVED,
                body = chat.body.take(MAX_MESSAGE_LENGTH),
                sentAtEpochMillis = chat.sentAtEpochMillis,
                deliveryState = DeliveryState.DELIVERED
            )
        )

        val context = mutex.withLock {
            links.values.firstOrNull { it.session?.peerDeviceId == session.peerDeviceId }
        } ?: return
        sendEnvelope(context, Envelope.Ack(chat.messageId), FrameType.SEALED, session)
    }

    // ---------------------------------------------------------------- trust

    suspend fun markVerified(deviceId: String) {
        trustStore.markVerified(deviceId)
        refreshTrust(deviceId)
    }

    suspend fun revokeVerification(deviceId: String) {
        trustStore.revokeVerification(deviceId)
        refreshTrust(deviceId)
    }

    private fun refreshTrust(deviceId: String) {
        val trust = trustStore.trustStateOf(deviceId)
        peersState.value = peersState.value.map {
            if (it.deviceId == deviceId) it.copy(trustState = trust) else it
        }
        conversationsState.value = conversationsState.value.mapValues {
            if (it.key == deviceId) it.value.copy(trustState = trust) else it.value
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun peerLabelFor(address: String): String =
        peersState.value.firstOrNull { it.transportAddress == address }?.label ?: address

    private suspend fun failLink(context: LinkContext, reason: String) {
        context.state = LinkState.FAILED
        context.session = null
        context.handshake = null
        updatePeer(context.address) {
            it.copy(linkState = LinkState.FAILED, statusDetail = reason)
        }
        eventsFlow.emit(MeshEvent.Blocked(reason))
    }

    private fun updatePeer(address: String, transform: (Peer) -> Peer) {
        val current = peersState.value
        val index = current.indexOfFirst { it.transportAddress == address }
        peersState.value = if (index >= 0) {
            current.toMutableList().also { it[index] = transform(it[index]) }
        } else {
            current + transform(Peer(address, lastSeenEpochMillis = currentTimeMillis()))
        }
    }

    private fun ensureConversation(session: SecureSession) {
        val existing = conversationsState.value[session.peerDeviceId]
        conversationsState.value = conversationsState.value + (
            session.peerDeviceId to (
                existing?.copy(
                    peerLabel = session.peerDisplayName,
                    peerFingerprint = session.peerFingerprint,
                    trustState = trustStore.trustStateOf(session.peerDeviceId),
                    isOnline = true
                ) ?: Conversation(
                    peerDeviceId = session.peerDeviceId,
                    peerLabel = session.peerDisplayName,
                    peerFingerprint = session.peerFingerprint,
                    trustState = trustStore.trustStateOf(session.peerDeviceId),
                    isOnline = true
                )
                )
            )
    }

    private fun appendMessage(peerDeviceId: String, message: ChatMessage) {
        val conversation = conversationsState.value[peerDeviceId] ?: return
        if (conversation.messages.any { it.id == message.id }) return
        conversationsState.value = conversationsState.value + (
            peerDeviceId to conversation.copy(messages = conversation.messages + message)
            )
    }

    private fun updateMessage(
        peerDeviceId: String,
        messageId: String,
        transform: (ChatMessage) -> ChatMessage
    ) {
        val conversation = conversationsState.value[peerDeviceId] ?: return
        conversationsState.value = conversationsState.value + (
            peerDeviceId to conversation.copy(
                messages = conversation.messages.map {
                    if (it.id == messageId) transform(it) else it
                }
            )
            )
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 4000
    }
}
