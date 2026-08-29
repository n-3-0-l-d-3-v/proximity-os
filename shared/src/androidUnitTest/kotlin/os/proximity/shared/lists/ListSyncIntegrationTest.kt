package os.proximity.shared.lists

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.guardrail.ActionType
import os.proximity.shared.guardrail.DefaultGuardrailEngine
import os.proximity.shared.guardrail.GuardrailDecision
import os.proximity.shared.guardrail.InMemoryAuditLog
import os.proximity.shared.guardrail.PolicyRule
import os.proximity.shared.identity.DeviceIdentifiers
import os.proximity.shared.identity.DeviceIdentity
import os.proximity.shared.identity.DeviceIdentityProvider
import os.proximity.shared.identity.InMemoryTrustStore
import os.proximity.shared.identity.JcaSignatureVerifier
import os.proximity.shared.mesh.DiscoveredPeer
import os.proximity.shared.mesh.IncomingMessage
import os.proximity.shared.mesh.MeshManager
import os.proximity.shared.mesh.MeshTransport
import os.proximity.shared.storage.InMemoryFileStore
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Shared lists travelling over the real mesh stack — policy, handshake,
 * encryption, chunking and all — with only the radio replaced.
 *
 * The convergence properties are covered exhaustively by
 * [SharedListEngineTest]; what this adds is proof that the operations
 * actually survive the wire and arrive somewhere useful.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListSyncIntegrationTest {

    private val primitives = AndroidCryptoPrimitives()
    private val verifier = JcaSignatureVerifier()

    private class LoopbackTransport(private val selfAddress: String) : MeshTransport {
        var peer: LoopbackTransport? = null
        private val inbox = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 8192)
        private val peersState = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

        override val discoveredPeers: Flow<List<DiscoveredPeer>> = peersState
        override val incomingMessages: Flow<IncomingMessage> = inbox

        /** Simulates walking out of range: writes are silently lost. */
        var reachable: Boolean = true

        override fun startDiscovery() {
            peersState.value = listOf(DiscoveredPeer(peer!!.selfAddress, null, -50, 1_000))
        }

        override fun stopDiscovery() {
            peersState.value = emptyList()
        }

        override suspend fun connect(peerAddress: String): Boolean = reachable
        override fun disconnect(peerAddress: String) = Unit
        override fun maxPayloadSize(peerAddress: String): Int = 80

        override suspend fun send(peerAddress: String, payload: ByteArray): Boolean {
            val target = peer ?: return false
            if (!reachable || !target.reachable) return false
            target.inbox.emit(IncomingMessage(selfAddress, payload))
            return true
        }
    }

    private fun identityProvider(): DeviceIdentityProvider {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val identity = object : DeviceIdentity {
            override val publicKeyBytes: ByteArray = keyPair.public.encoded
            private val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            override val deviceId: String = DeviceIdentifiers.deviceIdFrom(hash)
            override val fingerprint: String = DeviceIdentifiers.fingerprintFrom(hash)
            override fun sign(data: ByteArray): ByteArray =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(data)
                    sign()
                }
        }
        return object : DeviceIdentityProvider {
            override suspend fun getOrCreateIdentity(): DeviceIdentity = identity
        }
    }

    private class Node(
        val manager: MeshManager,
        val transport: LoopbackTransport,
        val repository: SharedListRepository,
        val engine: DefaultGuardrailEngine
    )

    private var clock = 10_000L

    private fun node(name: String, address: String, scope: CoroutineScope): Node {
        val transport = LoopbackTransport(address)
        val engine = DefaultGuardrailEngine(InMemoryAuditLog())
        val identity = identityProvider()
        val repository = SharedListRepository(
            files = InMemoryFileStore(),
            deviceId = { identity.getOrCreateIdentity().deviceId },
            now = { clock++ }
        )
        val manager = MeshManager(
            transport = transport,
            primitives = primitives,
            identityProvider = identity,
            verifier = verifier,
            guardrail = engine,
            trustStore = InMemoryTrustStore(),
            scope = scope,
            displayName = { name },
            listSync = repository
        )
        return Node(manager, transport, repository, engine)
    }

    private fun allowConnect() = PolicyRule(
        id = "test-allow-connect",
        description = "Allow connections without prompting",
        priority = 100,
        matches = { it.actionType == ActionType.CONNECT_PEER },
        decide = { GuardrailDecision.Allow("Allowed by test policy.") }
    )

    private suspend fun pair(scope: CoroutineScope): Pair<Node, Node> {
        val alice = node("Alice", "AA", scope)
        val bob = node("Bob", "BB", scope)
        alice.transport.peer = bob.transport
        bob.transport.peer = alice.transport
        alice.engine.addRule(allowConnect())
        bob.engine.addRule(allowConnect())
        alice.manager.start()
        bob.manager.start()
        return alice to bob
    }

    @Test
    fun anExistingListReachesAPeerOnConnect() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)

        val list = alice.repository.createList("Camping trip")
        alice.repository.addItem(list.id, "Tent")
        alice.repository.addItem(list.id, "Stove")

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
        advanceUntilIdle()

        val onBob = assertNotNull(bob.repository.lists.value[list.id], "Bob never received the list")
        assertEquals("Camping trip", onBob.name)
        assertEquals(setOf("Tent", "Stove"), onBob.visibleItems.map { it.text }.toSet())
    }

    @Test
    fun anEditMadeWhileConnectedReachesThePeer() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        val list = alice.repository.createList("Shopping")

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
        advanceUntilIdle()

        val operation = assertNotNull(alice.repository.addItem(list.id, "Milk"))
        alice.manager.broadcastListOperation(operation)
        advanceUntilIdle()

        val onBob = assertNotNull(bob.repository.lists.value[list.id])
        assertEquals(listOf("Milk"), onBob.visibleItems.map { it.text })
    }

    @Test
    fun tickingAnItemOffPropagates() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        val list = alice.repository.createList("Shopping")
        alice.repository.addItem(list.id, "Milk")

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
        advanceUntilIdle()

        val itemId = alice.repository.lists.value.getValue(list.id).visibleItems.single().id
        val done = assertNotNull(alice.repository.setItemDone(list.id, itemId, true))
        alice.manager.broadcastListOperation(done)
        advanceUntilIdle()

        assertTrue(bob.repository.lists.value.getValue(list.id).visibleItems.single().done)
        assertEquals(0, bob.repository.lists.value.getValue(list.id).remainingCount)
    }

    @Test
    fun editsMadeWhileApartConvergeOnReconnect() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)
        val list = alice.repository.createList("Shopping")
        alice.repository.addItem(list.id, "Milk")

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
        advanceUntilIdle()
        assertNotNull(bob.repository.lists.value[list.id])

        // Out of range: each side edits, neither hearing the other.
        alice.transport.reachable = false
        bob.transport.reachable = false

        val aliceEdit = assertNotNull(alice.repository.addItem(list.id, "Coffee"))
        alice.manager.broadcastListOperation(aliceEdit)

        val bobEdit = assertNotNull(bob.repository.addItem(list.id, "Sugar"))
        bob.manager.broadcastListOperation(bobEdit)
        advanceUntilIdle()

        // Neither edit crossed while they were apart.
        assertEquals(2, alice.repository.lists.value.getValue(list.id).visibleItems.size)
        assertEquals(2, bob.repository.lists.value.getValue(list.id).visibleItems.size)

        // Back in range: reconnecting exchanges snapshots and merges.
        alice.transport.reachable = true
        bob.transport.reachable = true
        alice.manager.disconnect("BB")
        alice.manager.connectTo("BB")
        advanceUntilIdle()

        val onAlice = alice.repository.lists.value.getValue(list.id).visibleItems.map { it.text }
        val onBob = bob.repository.lists.value.getValue(list.id).visibleItems.map { it.text }

        assertEquals(setOf("Milk", "Coffee", "Sugar"), onAlice.toSet())
        assertEquals(onAlice.toSet(), onBob.toSet())
    }

    @Test
    fun aBlockedSyncPolicyStopsListTrafficReachingThePeer() = runTest(UnconfinedTestDispatcher()) {
        val (alice, bob) = pair(backgroundScope)

        // Bob refuses list sync entirely.
        bob.engine.addRule(
            PolicyRule(
                id = "no-lists",
                description = "Refuse all list sync",
                priority = 200,
                matches = { it.actionType == ActionType.SYNC_LIST },
                decide = { GuardrailDecision.Deny("Not accepting shared lists.") }
            )
        )

        val list = alice.repository.createList("Shopping")
        alice.repository.addItem(list.id, "Milk")

        alice.manager.startDiscovery()
        alice.manager.connectTo("BB")
        advanceUntilIdle()

        // The session is fine; only the list traffic was refused.
        assertTrue(bob.manager.peers.value.any { it.isSecured })
        assertTrue(
            bob.repository.lists.value.isEmpty(),
            "policy should have stopped the list from being accepted"
        )
    }
}
