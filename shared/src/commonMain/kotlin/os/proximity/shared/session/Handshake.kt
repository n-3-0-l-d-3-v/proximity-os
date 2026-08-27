package os.proximity.shared.session

import os.proximity.shared.crypto.CryptoPrimitives
import os.proximity.shared.crypto.EcdhKeyPair
import os.proximity.shared.crypto.Hkdf
import os.proximity.shared.crypto.SecureSession
import os.proximity.shared.identity.DeviceIdentifiers
import os.proximity.shared.identity.DeviceIdentity
import os.proximity.shared.identity.SignatureVerifier
import os.proximity.shared.protocol.Envelope
import os.proximity.shared.util.hexToBytesOrNull
import os.proximity.shared.util.toHex

enum class SessionRole { INITIATOR, RESPONDER }

sealed class HandshakeOutcome {
    data class Established(val session: SecureSession) : HandshakeOutcome()
    data class Failed(val reason: String) : HandshakeOutcome()
}

/**
 * Drives one handshake to completion. See docs/adr/0001-cryptography.md.
 *
 * ```
 * initiator → responder   Hello    { idPub, ephPub, nonce, sig }
 * responder → initiator   HelloAck { idPub, ephPub, nonce, sig }
 * ```
 *
 * Each side signs a transcript binding its ephemeral key to its long-term
 * identity key. Without that binding the exchange is unauthenticated
 * Diffie-Hellman, and anyone in range could substitute their own ephemeral
 * key and sit in the middle.
 *
 * One instance per connection attempt; not reusable.
 */
class Handshake(
    private val primitives: CryptoPrimitives,
    private val identity: DeviceIdentity,
    private val verifier: SignatureVerifier,
    private val displayName: String,
    val role: SessionRole
) {

    private val ephemeral: EcdhKeyPair = primitives.generateEcdhKeyPair()
    private val localNonce: ByteArray = primitives.randomBytes(NONCE_SIZE)

    /** The message this side sends first. */
    fun createGreeting(): Envelope {
        val signature = identity.sign(
            bindingTranscript(identity.publicKeyBytes, ephemeral.publicKey, localNonce)
        )
        return when (role) {
            SessionRole.INITIATOR -> Envelope.Hello(
                identityPublicKey = identity.publicKeyBytes.toHex(),
                ephemeralPublicKey = ephemeral.publicKey.toHex(),
                nonce = localNonce.toHex(),
                signature = signature.toHex(),
                displayName = displayName
            )

            SessionRole.RESPONDER -> Envelope.HelloAck(
                identityPublicKey = identity.publicKeyBytes.toHex(),
                ephemeralPublicKey = ephemeral.publicKey.toHex(),
                nonce = localNonce.toHex(),
                signature = signature.toHex(),
                displayName = displayName
            )
        }
    }

    /**
     * Consume the peer's greeting and derive the session. Every failure is
     * reported as [HandshakeOutcome.Failed] with a reason suitable for the
     * audit log — never as an exception, since all of this input is
     * attacker-controlled.
     */
    fun accept(peerGreeting: Envelope): HandshakeOutcome {
        val peer = when (peerGreeting) {
            is Envelope.Hello -> PeerGreeting(
                peerGreeting.identityPublicKey,
                peerGreeting.ephemeralPublicKey,
                peerGreeting.nonce,
                peerGreeting.signature,
                peerGreeting.displayName,
                peerGreeting.protocolVersion
            )

            is Envelope.HelloAck -> PeerGreeting(
                peerGreeting.identityPublicKey,
                peerGreeting.ephemeralPublicKey,
                peerGreeting.nonce,
                peerGreeting.signature,
                peerGreeting.displayName,
                peerGreeting.protocolVersion
            )

            else -> return HandshakeOutcome.Failed("Expected a handshake greeting.")
        }

        if (peer.protocolVersion != Envelope.PROTOCOL_VERSION) {
            return HandshakeOutcome.Failed(
                "This device speaks protocol v${Envelope.PROTOCOL_VERSION}; " +
                    "the other device speaks v${peer.protocolVersion}."
            )
        }

        val peerIdentityKey = peer.identityPublicKey.hexToBytesOrNull()
            ?: return HandshakeOutcome.Failed("Peer sent a malformed identity key.")
        val peerEphemeralKey = peer.ephemeralPublicKey.hexToBytesOrNull()
            ?: return HandshakeOutcome.Failed("Peer sent a malformed ephemeral key.")
        val peerNonce = peer.nonce.hexToBytesOrNull()
            ?: return HandshakeOutcome.Failed("Peer sent a malformed nonce.")
        val peerSignature = peer.signature.hexToBytesOrNull()
            ?: return HandshakeOutcome.Failed("Peer sent a malformed signature.")

        if (peerNonce.size != NONCE_SIZE) {
            return HandshakeOutcome.Failed("Peer sent a nonce of the wrong size.")
        }

        // A peer echoing our own identity key back is either a reflection
        // attack or a loopback; either way it is not a second device.
        if (peerIdentityKey.contentEquals(identity.publicKeyBytes)) {
            return HandshakeOutcome.Failed("Peer presented this device's own identity.")
        }

        val signatureValid = verifier.verify(
            data = bindingTranscript(peerIdentityKey, peerEphemeralKey, peerNonce),
            signature = peerSignature,
            publicKey = peerIdentityKey
        )
        if (!signatureValid) {
            return HandshakeOutcome.Failed(
                "Peer could not prove it owns the identity it claimed."
            )
        }

        val sharedSecret = primitives.ecdhSharedSecret(ephemeral, peerEphemeralKey)
            ?: return HandshakeOutcome.Failed("Key agreement with the peer failed.")

        // Ordered by role, not by who is local, so both sides compute the
        // identical transcript and therefore the identical keys.
        val salt = primitives.sha256(
            when (role) {
                SessionRole.INITIATOR -> sessionTranscript(
                    initiatorIdentity = identity.publicKeyBytes,
                    initiatorEphemeral = ephemeral.publicKey,
                    initiatorNonce = localNonce,
                    responderIdentity = peerIdentityKey,
                    responderEphemeral = peerEphemeralKey,
                    responderNonce = peerNonce
                )

                SessionRole.RESPONDER -> sessionTranscript(
                    initiatorIdentity = peerIdentityKey,
                    initiatorEphemeral = peerEphemeralKey,
                    initiatorNonce = peerNonce,
                    responderIdentity = identity.publicKeyBytes,
                    responderEphemeral = ephemeral.publicKey,
                    responderNonce = localNonce
                )
            }
        )

        val keyMaterial = Hkdf.derive(
            primitives = primitives,
            ikm = sharedSecret,
            salt = salt,
            info = SESSION_INFO.encodeToByteArray(),
            length = KEY_MATERIAL_SIZE
        )

        val initiatorToResponderKey = keyMaterial.copyOfRange(0, 32)
        val responderToInitiatorKey = keyMaterial.copyOfRange(32, 64)
        val initiatorToResponderPrefix = keyMaterial.copyOfRange(64, 68)
        val responderToInitiatorPrefix = keyMaterial.copyOfRange(68, 72)

        val peerKeyHash = primitives.sha256(peerIdentityKey)

        val session = SecureSession(
            primitives = primitives,
            sendKey = if (role == SessionRole.INITIATOR) initiatorToResponderKey else responderToInitiatorKey,
            receiveKey = if (role == SessionRole.INITIATOR) responderToInitiatorKey else initiatorToResponderKey,
            sendNoncePrefix = if (role == SessionRole.INITIATOR) initiatorToResponderPrefix else responderToInitiatorPrefix,
            receiveNoncePrefix = if (role == SessionRole.INITIATOR) responderToInitiatorPrefix else initiatorToResponderPrefix,
            sessionId = primitives.sha256(Transcript().add(salt).add(SESSION_ID_LABEL).build()),
            peerIdentityKey = peerIdentityKey,
            peerDeviceId = DeviceIdentifiers.deviceIdFrom(peerKeyHash),
            peerFingerprint = DeviceIdentifiers.fingerprintFrom(peerKeyHash),
            peerDisplayName = peer.displayName.take(MAX_DISPLAY_NAME_LENGTH)
        )

        return HandshakeOutcome.Established(session)
    }

    private fun bindingTranscript(
        identityKey: ByteArray,
        ephemeralKey: ByteArray,
        nonce: ByteArray
    ): ByteArray = Transcript()
        .add(BIND_LABEL)
        .add(identityKey)
        .add(ephemeralKey)
        .add(nonce)
        .build()

    private fun sessionTranscript(
        initiatorIdentity: ByteArray,
        initiatorEphemeral: ByteArray,
        initiatorNonce: ByteArray,
        responderIdentity: ByteArray,
        responderEphemeral: ByteArray,
        responderNonce: ByteArray
    ): ByteArray = Transcript()
        .add(TRANSCRIPT_LABEL)
        .add(initiatorIdentity)
        .add(initiatorEphemeral)
        .add(initiatorNonce)
        .add(responderIdentity)
        .add(responderEphemeral)
        .add(responderNonce)
        .build()

    private data class PeerGreeting(
        val identityPublicKey: String,
        val ephemeralPublicKey: String,
        val nonce: String,
        val signature: String,
        val displayName: String,
        val protocolVersion: Int
    )

    companion object {
        const val NONCE_SIZE = 16
        const val MAX_DISPLAY_NAME_LENGTH = 40

        /** 2 × 32-byte keys + 2 × 4-byte nonce prefixes. */
        private const val KEY_MATERIAL_SIZE = 72

        private const val BIND_LABEL = "proximity-os/v1/bind"
        private const val TRANSCRIPT_LABEL = "proximity-os/v1/transcript"
        private const val SESSION_INFO = "proximity-os/v1/session"
        private const val SESSION_ID_LABEL = "proximity-os/v1/session-id"
    }
}
