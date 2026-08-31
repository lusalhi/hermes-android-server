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
}
