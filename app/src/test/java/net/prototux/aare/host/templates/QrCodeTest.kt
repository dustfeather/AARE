package net.prototux.aare.host.templates

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun capacitiesMatchTheStandardTables() {
        // byte-mode capacities at level M (ISO/IEC 18004 table 7)
        assertEquals(16, QrCode.dataCodewords(1)) // 14 bytes
        assertEquals(216, QrCode.dataCodewords(10)) // 213 bytes
        assertEquals(2334, QrCode.dataCodewords(40)) // 2331 bytes
    }

    @Test
    fun alignmentPatternPositions() {
        assertArrayEquals(intArrayOf(), QrCode.alignmentPositions(1))
        assertArrayEquals(intArrayOf(6, 18), QrCode.alignmentPositions(2))
        assertArrayEquals(intArrayOf(6, 22, 38), QrCode.alignmentPositions(7))
        assertArrayEquals(intArrayOf(6, 26, 48, 70), QrCode.alignmentPositions(15))
        assertArrayEquals(intArrayOf(6, 34, 60, 86, 112, 138), QrCode.alignmentPositions(32))
    }

    @Test
    fun picksSmallestVersion() {
        assertEquals(21, QrCode.encode("12345678901234")!!.size) // 14 bytes: version 1
        assertEquals(25, QrCode.encode("123456789012345")!!.size) // 15 bytes: version 2
        val url = QrCode.encode("https://example.com/activate?code=ABCD-EFGH")
        assertNotNull(url)
        assertNull(QrCode.encode("x".repeat(2332)))
    }

    @Test
    fun finderAndTimingPatterns() {
        val qr = QrCode.encode("https://example.com")!!
        val n = qr.size
        for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
            for (i in 0 until 7) {
                assertTrue(qr[ox + i, oy]) // outer ring
                assertTrue(qr[ox, oy + i])
            }
            assertTrue(qr[ox + 3, oy + 3]) // centre
        }
        for (i in 8 until n - 8) assertEquals(i % 2 == 0, qr[i, 6])
        assertTrue(qr[8, n - 8]) // dark module
    }
}
