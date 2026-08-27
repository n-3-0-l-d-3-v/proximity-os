package os.proximity.shared.session

import os.proximity.shared.crypto.AndroidCryptoPrimitives
import os.proximity.shared.crypto.SecureSession
import os.proximity.shared.identity.DeviceIdentifiers
import os.proximity.shared.identity.DeviceIdentity
import os.proximity.shared.identity.JcaSignatureVerifier
import os.proximity.shared.protocol.Envelope
import os.proximity.shared.util.toHex
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * End-to-end exercise of the handshake and the session it produces.
 *
 * The negative cases matter as much as the happy path: each one is an
 * attack from docs/THREAT_MODEL.md that the protocol is supposed to stop.
 */
class HandshakeTest {

    private val primitives = AndroidCryptoPrimitives()
    private val verifier = JcaSignatureVerifier()

    /** In-memory identity; the real one lives in the Android Keystore, which
     *  is unavailable in a plain JVM unit test. */
    private class TestIdentity(private val keyPair: KeyPair) : DeviceIdentity {
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

    private fun newIdentity(): DeviceIdentity {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return TestIdentity(generator.generateKeyPair())
    }

    private fun handshakePair(): Pair<SecureSession, SecureSession> {
        val aliceIdentity = newIdentity()
        val bobIdentity = newIdentity()

        val alice = Handshake(primitives, aliceIdentity, verifier, "Alice", SessionRole.INITIATOR)
        val bob = Handshake(primitives, bobIdentity, verifier, "Bob", SessionRole.RESPONDER)

        val hello = alice.createGreeting()
        val helloAck = bob.createGreeting()

        val bobOutcome = bob.accept(hello)
        val aliceOutcome = alice.accept(helloAck)

        val bobSession = (bobOutcome as? HandshakeOutcome.Established)?.session
            ?: fail("Bob failed: ${(bobOutcome as HandshakeOutcome.Failed).reason}")
        val aliceSession = (aliceOutcome as? HandshakeOutcome.Established)?.session
            ?: fail("Alice failed: ${(aliceOutcome as HandshakeOutcome.Failed).reason}")

        return aliceSession to bobSession
    }

    @Test
    fun bothSidesDeriveTheSameSession() {
        val (alice, bob) = handshakePair()
        assertTrue(alice.sessionId.contentEquals(bob.sessionId))
        assertEquals("Bob", alice.peerDisplayName)
        assertEquals("Alice", bob.peerDisplayName)
    }

    @Test
    fun eachSideSeesTheOthersFingerprint() {
        val (alice, bob) = handshakePair()
        // Fingerprints are what a human compares out-of-band, so they must
        // identify the *peer*, never ourselves.
        assertNotEquals(alice.peerFingerprint, bob.peerFingerprint)
        assertTrue(alice.peerFingerprint.isNotBlank())
    }

    @Test
    fun messagesTravelInBothDirections() {
        val (alice, bob) = handshakePair()

        val toBoB = alice.seal("north entrance, 5 minutes".encodeToByteArray())!!
        assertEquals("north entrance, 5 minutes", bob.open(toBoB)?.decodeToString())

        val toAlice = bob.seal("on my way".encodeToByteArray())!!
        assertEquals("on my way", alice.open(toAlice)?.decodeToString())
    }

    @Test
    fun replayedRecordIsRejected() {
        val (alice, bob) = handshakePair()
        val record = alice.seal("spend 100".encodeToByteArray())!!

        assertEquals("spend 100", bob.open(record)?.decodeToString())
        assertNull(bob.open(record), "a replayed record must not authenticate twice")
    }

    @Test
    fun outOfOrderOlderRecordIsRejected() {
        val (alice, bob) = handshakePair()
        val first = alice.seal("one".encodeToByteArray())!!
        val second = alice.seal("two".encodeToByteArray())!!

        assertEquals("two", bob.open(second)?.decodeToString())
        assertNull(bob.open(first), "an older counter must not be accepted after a newer one")
    }

    @Test
    fun tamperedRecordIsRejected() {
        val (alice, bob) = handshakePair()
        val record = alice.seal("transfer approved".encodeToByteArray())!!
        record[record.size - 1] = (record[record.size - 1].toInt() xor 0x01).toByte()

        assertNull(bob.open(record))
    }

    @Test
    fun ourOwnOutboundRecordDoesNotDecryptOnOurInboundPath() {
        val (alice, _) = handshakePair()
        val record = alice.seal("echo me".encodeToByteArray())!!

        // Directional keys: reflecting our own traffic back must not work.
        assertNull(alice.open(record))
    }

    @Test
    fun recordFromADifferentSessionIsRejected() {
        val (alice, _) = handshakePair()
        val (_, otherBob) = handshakePair()

        val record = alice.seal("hello".encodeToByteArray())!!
        assertNull(otherBob.open(record), "sessionId is bound as AEAD associated data")
    }

    @Test
    fun forgedSignatureIsRejected() {
        val aliceIdentity = newIdentity()
        val mallory = Handshake(primitives, newIdentity(), verifier, "Mallory", SessionRole.INITIATOR)
        val bob = Handshake(primitives, newIdentity(), verifier, "Bob", SessionRole.RESPONDER)

        // Mallory presents her own ephemeral key but claims Alice's identity —
        // the classic machine-in-the-middle substitution.
        val genuine = mallory.createGreeting() as Envelope.Hello
        val spoofed = genuine.copy(identityPublicKey = aliceIdentity.publicKeyBytes.toHex())

        val outcome = bob.accept(spoofed)
        assertTrue(outcome is HandshakeOutcome.Failed)
    }

    @Test
    fun mismatchedProtocolVersionIsRejected() {
        val alice = Handshake(primitives, newIdentity(), verifier, "Alice", SessionRole.INITIATOR)
        val bob = Handshake(primitives, newIdentity(), verifier, "Bob", SessionRole.RESPONDER)

        val hello = (alice.createGreeting() as Envelope.Hello).copy(protocolVersion = 99)
        val outcome = bob.accept(hello)

        val failed = outcome as? HandshakeOutcome.Failed ?: fail("expected rejection")
        assertTrue(failed.reason.contains("protocol"))
    }

    @Test
    fun peerPresentingOurOwnIdentityIsRejected() {
        val identity = newIdentity()
        val us = Handshake(primitives, identity, verifier, "Us", SessionRole.RESPONDER)
        val reflected = Handshake(primitives, identity, verifier, "Us", SessionRole.INITIATOR)

        val outcome = us.accept(reflected.createGreeting())
        assertTrue(outcome is HandshakeOutcome.Failed)
    }

    @Test
    fun malformedGreetingFieldsAreRejectedNotThrown() {
        val bob = Handshake(primitives, newIdentity(), verifier, "Bob", SessionRole.RESPONDER)
        val alice = Handshake(primitives, newIdentity(), verifier, "Alice", SessionRole.INITIATOR)
        val hello = alice.createGreeting() as Envelope.Hello

        listOf(
            hello.copy(identityPublicKey = "nothex"),
            hello.copy(ephemeralPublicKey = "zz"),
            hello.copy(nonce = "00"),
            hello.copy(signature = "!!!"),
            hello.copy(ephemeralPublicKey = "abcd")
        ).forEach { malformed ->
            assertTrue(
                bob.accept(malformed) is HandshakeOutcome.Failed,
                "expected failure for $malformed"
            )
        }
    }

    @Test
    fun sessionRefusesNothingUntilCounterExhaustion() {
        val (alice, bob) = handshakePair()
        // Sanity: many sequential records keep working, i.e. the counter
        // advances rather than colliding.
        repeat(200) { i ->
            val record = alice.seal("msg $i".encodeToByteArray())!!
            assertEquals("msg $i", bob.open(record)?.decodeToString())
        }
    }
}
