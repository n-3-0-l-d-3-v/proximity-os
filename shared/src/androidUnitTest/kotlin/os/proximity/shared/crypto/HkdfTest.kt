package os.proximity.shared.crypto

import os.proximity.shared.util.hexToBytesOrNull
import os.proximity.shared.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RFC 5869 published test vectors. These matter more than they look: an
 * HKDF that is subtly wrong still produces plausible-looking random bytes,
 * so without vectors a broken key schedule would pass every round-trip test
 * while silently weakening every session.
 */
class HkdfTest {

    private val primitives = AndroidCryptoPrimitives()

    private fun hex(s: String) = requireNotNull(s.hexToBytesOrNull())

    @Test
    fun rfc5869TestCase1() {
        val ikm = hex("0b".repeat(22))
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")

        val prk = Hkdf.extract(primitives, salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            prk.toHex()
        )

        val okm = Hkdf.expand(primitives, prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a" +
                "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
            okm.toHex()
        )
    }

    @Test
    fun rfc5869TestCase3EmptySaltAndInfo() {
        val ikm = hex("0b".repeat(22))

        val prk = Hkdf.extract(primitives, ByteArray(0), ikm)
        assertEquals(
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            prk.toHex()
        )

        val okm = Hkdf.expand(primitives, prk, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31" +
                "b8a11f5c5ee1879ec3454e5f3c738d2d" +
                "9d201395faa4b61a96c8",
            okm.toHex()
        )
    }

    @Test
    fun deriveIsDeterministicAndLengthRespecting() {
        val ikm = primitives.randomBytes(32)
        val salt = primitives.randomBytes(16)
        val info = "proximity-os/test".encodeToByteArray()

        val a = Hkdf.derive(primitives, ikm, salt, info, 64)
        val b = Hkdf.derive(primitives, ikm, salt, info, 64)

        assertEquals(64, a.size)
        assertEquals(a.toHex(), b.toHex())
    }

    @Test
    fun differentInfoProducesDifferentKeys() {
        val ikm = primitives.randomBytes(32)
        val salt = primitives.randomBytes(16)

        val send = Hkdf.derive(primitives, ikm, salt, "send".encodeToByteArray(), 32)
        val receive = Hkdf.derive(primitives, ikm, salt, "receive".encodeToByteArray(), 32)

        // Directional keys must not collide, or a peer could replay our own
        // frames back at us and have them authenticate.
        kotlin.test.assertNotEquals(send.toHex(), receive.toHex())
    }
}
