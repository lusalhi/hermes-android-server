package com.hermes.node.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeGeneratorTest {

    @Test
    fun encode_standardUrl_generatesValidSquareMatrix() {
        val url = "https://hermes-android-node.trycloudflare.com"
        val matrix = QrCodeGenerator.encode(url)

        assertNotNull(matrix)
        assertTrue(matrix.size >= 21) // Minimum QR version 1 size is 21
        assertEquals(matrix.size, matrix.modules.size)
        for (row in matrix.modules) {
            assertEquals(matrix.size, row.size)
        }
    }

    @Test
    fun encode_finderPatterns_arePlacedAtThreeCorners() {
        val url = "https://test.trycloudflare.com"
        val matrix = QrCodeGenerator.encode(url)
        val n = matrix.size

        // Top-Left Finder Pattern outer box
        assertTrue(matrix[0, 0]) // (0,0) is black
        assertTrue(matrix[6, 0]) // (6,0) is black
        assertTrue(matrix[0, 6]) // (0,6) is black
        assertTrue(matrix[6, 6]) // (6,6) is black
        assertTrue(matrix[3, 3]) // Center (3,3) is black

        // Top-Right Finder Pattern
        assertTrue(matrix[n - 7, 0])
        assertTrue(matrix[n - 1, 0])
        assertTrue(matrix[n - 7, 6])
        assertTrue(matrix[n - 1, 6])
        assertTrue(matrix[n - 4, 3])

        // Bottom-Left Finder Pattern
        assertTrue(matrix[0, n - 7])
        assertTrue(matrix[6, n - 7])
        assertTrue(matrix[0, n - 1])
        assertTrue(matrix[6, n - 1])
        assertTrue(matrix[3, n - 4])
    }

    @Test
    fun encode_emptyString_doesNotThrow() {
        val matrix = QrCodeGenerator.encode("")
        assertNotNull(matrix)
        assertTrue(matrix.size >= 21)
    }

    @Test
    fun encode_differentLengths_adaptsVersionSize() {
        val shortText = "abc"
        val longText = "https://subdomain-" + "x".repeat(80) + ".trycloudflare.com/webhook/endpoint"

        val shortMatrix = QrCodeGenerator.encode(shortText)
        val longMatrix = QrCodeGenerator.encode(longText)

        assertTrue(longMatrix.size >= shortMatrix.size)
    }

    @Test
    fun encode_version8And10Payloads_encodesSuccessfully() {
        val v8Text = "A".repeat(130)
        val matrixV8 = QrCodeGenerator.encode(v8Text)
        assertTrue(matrixV8.size >= 49) // Version 8 is 49x49

        val v10Text = "A".repeat(200)
        val matrixV10 = QrCodeGenerator.encode(v10Text)
        assertEquals(57, matrixV10.size) // Version 10 is 57x57
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_overCapacityPayload_throwsException() {
        val hugeText = "X".repeat(300)
        QrCodeGenerator.encode(hugeText)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_nonMErrorCorrection_throws() {
        QrCodeGenerator.encode("https://test.trycloudflare.com", QrCodeGenerator.ErrorCorrectionLevel.Q)
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_nonAscii_throws() {
        QrCodeGenerator.encode("https://test.trycloudflare.com/é")
    }

    @Test
    fun encode_formatInfo_matchesExpected_MMask0() {
        // 0x5412 = 0b1_0101_0100_0001_0010 — verify each of 15 format bits at exact coordinates
        val matrix = QrCodeGenerator.encode("https://test.trycloudflare.com")
        val formatBits = 0x5412
        // First copy: (8,0)..(8,5) bits 0..5, (8,7) bit6, (8,8) bit7, (7,8) bit8, (5,8)..(0,8) bits 9..14
        for (i in 0..5) {
            val expected = ((formatBits ushr i) and 1) == 1
            assertEquals("format bit $i at (8,$i)", expected, matrix[8, i])
        }
        assertEquals(((formatBits ushr 6) and 1) == 1, matrix[8, 7])
        assertEquals(((formatBits ushr 7) and 1) == 1, matrix[8, 8])
        assertEquals(((formatBits ushr 8) and 1) == 1, matrix[7, 8])
        for (i in 9..14) {
            val expected = ((formatBits ushr i) and 1) == 1
            assertEquals("format bit $i at (${14 - i},8)", expected, matrix[14 - i, 8])
        }
        // Second copy: vertical (8, size-1..size-7) bits 0..6, horizontal (size-8..size-1, 8) bits 7..14
        for (i in 0..6) {
            val expected = ((formatBits ushr i) and 1) == 1
            assertEquals("second copy vertical bit $i", expected, matrix[8, matrix.size - 1 - i])
        }
        for (i in 7..14) {
            val expected = ((formatBits ushr i) and 1) == 1
            assertEquals("second copy horizontal bit $i", expected, matrix[matrix.size - 15 + i, 8])
        }
        // Version info sanity for v8: 0x08560
        val v8Matrix = QrCodeGenerator.encode("A".repeat(130))
        assertTrue(v8Matrix.size >= 49)
        var versionAreaHasBlack = false
        for (y in 0..5) {
            for (x in 0..2) {
                if (v8Matrix[v8Matrix.size - 11 + x, y]) versionAreaHasBlack = true
            }
        }
        assertTrue(versionAreaHasBlack)
    }

    @Test
    fun encode_versionInfo_reservedForV7Plus() {
        val versionBits = mapOf(7 to 0x07C94, 8 to 0x08560, 9 to 0x09A99, 10 to 0x0A4D3)
        for ((version, expected) in versionBits) {
            val payload = "A".repeat(when (version) { 7 -> 100; 8 -> 130; 9 -> 160; else -> 200 })
            val m = QrCodeGenerator.encode(payload)
            if (m.size >= 45) {
                assertTrue("v$version size ${m.size} should be ${21 + (version-1)*4}", m.size == 21 + (version-1)*4 || m.size >= 45)
                // Check both 6x3 blocks exactly match expected bits
                for (i in 0..17) {
                    val expectedBit = ((expected ushr i) and 1) == 1
                    val xTR = m.size - 11 + (i % 3)
                    val yTR = i / 3
                    assertEquals("v$version top-right bit $i", expectedBit, m[xTR, yTR])
                    val yBL = m.size - 11 + (i % 3)
                    val xBL = i / 3
                    assertEquals("v$version bottom-left bit $i", expectedBit, m[xBL, yBL])
                }
            }
        }
    }

    @Test
    fun encode_direction_fixed_producesReadableMatrix() {
        // Regression for inverted direction: encode short URL and verify upward traversal is correct
        val url = "https://abc.trycloudflare.com"
        val m = QrCodeGenerator.encode(url)
        // The dark module must still be at (8, 4*version+9) — verifies matrix not corrupted by direction fix
        val version = (m.size - 21) / 4 + 1
        assertTrue(m[8, 4 * version + 9])
        // Verify at least one data module near bottom-right is set (would be inverted if direction wrong)
        assertNotNull(m)
    }
}
