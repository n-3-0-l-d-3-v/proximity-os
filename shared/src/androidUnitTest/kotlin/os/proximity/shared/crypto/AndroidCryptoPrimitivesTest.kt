package os.proximity.shared.crypto

import os.proximity.shared.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCryptoPrimitivesTest {

    private val primitives = AndroidCryptoPrimitives()

    @Test
    fun ecdhAgreesOnBothSides() {
        val alice = primitives.generateEcdhKeyPair()
        val bob = primitives.generateEcdhKeyPair()

        val aliceSecret = kotlin.test.assertNotNull(primitives.ecdhSharedSecret(alice, bob.publicKey))
        val bobSecret = kotlin.test.assertNotNull(primitives.ecdhSharedSecret(bob, alice.publicKey))

        assertEquals(aliceSecret.toHex(), bobSecret.toHex())
        assertTrue(aliceSecret.isNotEmpty())
    }

    @Test
    fun ecdhRejectsGarbagePeerKey() {
        val alice = primitives.generateEcdhKeyPair()
        assertNull(primitives.ecdhSharedSecret(alice, byteArrayOf(1, 2, 3, 4)))
        assertNull(primitives.ecdhSharedSecret(alice, ByteArray(0)))
        assertNull(primitives.ecdhSharedSecret(alice, primitives.randomBytes(91)))
    }

    @Test
    fun aeadRoundTrips() {
        val key = primitives.randomBytes(CryptoConstants.AES_KEY_SIZE)
        val nonce = primitives.randomBytes(CryptoConstants.GCM_NONCE_SIZE)
        val plaintext = "meet at the north entrance".encodeToByteArray()
        val aad = "peer-42".encodeToByteArray()

        val sealed = primitives.aeadSeal(key, nonce, plaintext, aad)
        val opened = primitives.aeadOpen(key, nonce, sealed, aad)

        assertEquals(plaintext.decodeToString(), opened?.decodeToString())
        assertEquals(plaintext.size + CryptoConstants.GCM_TAG_SIZE, sealed.size)
    }

    @Test
    fun aeadDetectsTamperedCiphertext() {
        val key = primitives.randomBytes(32)
        val nonce = primitives.randomBytes(12)
        val sealed = primitives.aeadSeal(key, nonce, "hello".encodeToByteArray(), null)

        sealed[0] = (sealed[0].toInt() xor 0x01).toByte()

        assertNull(primitives.aeadOpen(key, nonce, sealed, null))
    }

    @Test
    fun aeadDetectsWrongAad() {
        val key = primitives.randomBytes(32)
        val nonce = primitives.randomBytes(12)
        val sealed = primitives.aeadSeal(key, nonce, "hello".encodeToByteArray(), "a".encodeToByteArray())

        // Binding the frame header as AAD is what stops a peer replaying a
        // valid ciphertext under a different header.
        assertNull(primitives.aeadOpen(key, nonce, sealed, "b".encodeToByteArray()))
    }

    @Test
    fun aeadDetectsWrongKey() {
        val nonce = primitives.randomBytes(12)
        val sealed = primitives.aeadSeal(primitives.randomBytes(32), nonce, "hi".encodeToByteArray(), null)
        assertNull(primitives.aeadOpen(primitives.randomBytes(32), nonce, sealed, null))
    }

    @Test
    fun randomBytesAreNotRepeating() {
        assertNotEquals(primitives.randomBytes(32).toHex(), primitives.randomBytes(32).toHex())
    }
}
