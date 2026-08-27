package os.proximity.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncodingTest {

    @Test
    fun hexRoundTrips() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x7F, 0xFF.toByte(), 0x80.toByte())
        assertEquals("000f7fff80", bytes.toHex())
        assertTrue(bytes.contentEquals(bytes.toHex().hexToBytesOrNull()))
    }

    @Test
    fun hexParsingRejectsMalformedInput() {
        assertNull("abc".hexToBytesOrNull())      // odd length
        assertNull("zz".hexToBytesOrNull())       // non-hex characters
        assertNull("0g".hexToBytesOrNull())
    }

    @Test
    fun hexParsingAcceptsUppercase() {
        assertTrue(byteArrayOf(0xAB.toByte()).contentEquals("AB".hexToBytesOrNull()))
    }

    @Test
    fun fingerprintIsGroupedAndStable() {
        val hash = ByteArray(32) { it.toByte() }
        assertEquals("0001-0203-0405", hash.toFingerprint())
    }

    @Test
    fun constantTimeEqualsMatchesContentEquals() {
        val a = byteArrayOf(1, 2, 3)
        assertTrue(a.constantTimeEquals(byteArrayOf(1, 2, 3)))
        assertFalse(a.constantTimeEquals(byteArrayOf(1, 2, 4)))
        assertFalse(a.constantTimeEquals(byteArrayOf(1, 2)))
    }
}
