package com.hermes.node.ui.components

import kotlin.math.min

/**
 * 2D Boolean Matrix representing black (true) and white (false) modules of a QR code.
 */
data class QrMatrix(
    val size: Int,
    val modules: Array<BooleanArray>
) {
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QrMatrix
        if (size != other.size) return false
        return modules.contentDeepEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + modules.contentDeepHashCode()
        return result
    }
}

/**
 * Self-contained pure Kotlin QR Code encoder (ISO/IEC 18004).
 * Encodes text strings into standard QR Code module matrices without external libraries.
 */
object QrCodeGenerator {

    enum class ErrorCorrectionLevel(val ordinalBits: Int, val formatBits: Int) {
        L(1, 0b01),
        M(0, 0b00),
        Q(3, 0b11),
        H(2, 0b10)
    }

    // Capacity table: Version 1 to 10 with ECC Level M (byte capacity)
    private val CAPACITIES_M = intArrayOf(0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
    private val TOTAL_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346)
    private val ECC_CODEWORDS_M = intArrayOf(0, 10, 16, 26, 36, 48, 64, 72, 88, 110, 130)
    private val NUM_BLOCKS_M = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    // Alignment pattern positions for versions 1 to 10
    private val ALIGNMENT_PATTERN_POSITIONS = arrayOf(
        intArrayOf(),
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50)
    )

    // GF(256) tables with primitive polynomial 0x11D (285)
    private val EXP_TABLE = IntArray(256)
    private val LOG_TABLE = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP_TABLE[i] = x
            LOG_TABLE[x] = i
            x = x shl 1
            if (x >= 256) {
                x = x xor 0x11D
            }
        }
        EXP_TABLE[255] = EXP_TABLE[0]
    }

    private fun gfMul(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return EXP_TABLE[(LOG_TABLE[x] + LOG_TABLE[y]) % 255]
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = intArrayOf(1, EXP_TABLE[i])
            val newPoly = IntArray(poly.size + 1)
            for (j in poly.indices) {
                for (k in next.indices) {
                    newPoly[j + k] = newPoly[j + k] xor gfMul(poly[j], next[k])
                }
            }
            poly = newPoly
        }
        return poly
    }

    private fun rsCalculateEcc(data: IntArray, eccCount: Int): IntArray {
        val gen = rsGeneratorPoly(eccCount)
        val ecc = IntArray(eccCount)
        for (b in data) {
            val factor = b xor ecc[0]
            for (j in 0 until eccCount - 1) {
                ecc[j] = ecc[j + 1] xor gfMul(gen[j + 1], factor)
            }
            ecc[eccCount - 1] = gfMul(gen[eccCount], factor)
        }
        return ecc
    }

    // Version info BCH(18,6) — precomputed for versions 7..10 (ISO/IEC 18004 Table D.1)
    private val VERSION_INFO_BITS = mapOf(
        7 to 0x07C94,
        8 to 0x08560,
        9 to 0x09A99,
        10 to 0x0A4D3
    )

    private fun getVersionInfoBits(version: Int): Int {
        VERSION_INFO_BITS[version]?.let { return it }
        // Fallback BCH calculation for any version 7..40 (generator 0x1F25)
        var d = version shl 12
        val g = 0x1F25
        var msb = 1 shl 17
        // Find highest set bit to align divisor
        fun highestBit(v: Int): Int {
            var b = 0
            var x = v
            while (x > 0) { b++; x = x shr 1 }
            return b
        }
        while (highestBit(d) >= highestBit(g)) {
            val shift = highestBit(d) - highestBit(g)
            d = d xor (g shl shift)
        }
        return (version shl 12) or d
    }

    /**
     * Generates a QR Code matrix from text using ErrorCorrectionLevel.M.
     * Only M is currently supported — L/Q/H are accepted but rejected to avoid silent mis-encoding.
     */
    fun encode(text: String, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): QrMatrix {
        require(level == ErrorCorrectionLevel.M) { "Only ErrorCorrectionLevel.M is supported (requested $level)" }
        require(text.all { it.code < 128 }) { "Only ASCII is supported — tunnel URLs are ASCII; non-ASCII requires ECI" }
        val rawBytes = text.toByteArray(Charsets.UTF_8)
        require(rawBytes.size <= CAPACITIES_M[10]) {
            "Text exceeds maximum supported QR capacity of ${CAPACITIES_M[10]} bytes"
        }
        var version = 1
        while (version <= 10 && rawBytes.size > CAPACITIES_M[version]) {
            version++
        }
        if (version > 10) {
            version = 10
        }

        val totalDataCodewords = TOTAL_CODEWORDS[version] - ECC_CODEWORDS_M[version]
        val bitBuffer = mutableListOf<Int>()

        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bitBuffer.add((value ushr i) and 1)
            }
        }

        // 1. Mode indicator: Byte mode = 0100
        appendBits(0b0100, 4)

        // 2. Character count indicator: 8 bits for versions 1-9, 16 bits for version 10
        val charCountBits = if (version < 10) 8 else 16
        appendBits(min(rawBytes.size, (1 shl charCountBits) - 1), charCountBits)

        // 3. Payload data
        for (b in rawBytes) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        // 4. Terminator (up to 4 zeroes)
        val dataCapacityBits = totalDataCodewords * 8
        val terminatorBits = min(4, dataCapacityBits - bitBuffer.size)
        appendBits(0, terminatorBits)

        // 5. Byte alignment
        while (bitBuffer.size % 8 != 0) {
            bitBuffer.add(0)
        }

        // 6. Pad bytes 0xEC and 0x11
        val padBytes = intArrayOf(0xEC, 0x11)
        var padIndex = 0
        while (bitBuffer.size < dataCapacityBits) {
            appendBits(padBytes[padIndex % 2], 8)
            padIndex++
        }

        // Convert bits to data codeword array
        val dataCodewords = IntArray(totalDataCodewords)
        for (i in 0 until totalDataCodewords) {
            var byteVal = 0
            for (j in 0 until 8) {
                byteVal = (byteVal shl 1) or bitBuffer[i * 8 + j]
            }
            dataCodewords[i] = byteVal
        }

        // 7. Interleave blocks & calculate ECC (supports irregular block sizes for versions 8-10)
        val numBlocks = NUM_BLOCKS_M[version]
        val shortBlockLen = totalDataCodewords / numBlocks
        val numLongBlocks = totalDataCodewords % numBlocks
        val numShortBlocks = numBlocks - numLongBlocks
        val eccLen = ECC_CODEWORDS_M[version] / numBlocks

        val blocks = Array(numBlocks) { b ->
            val len = if (b < numShortBlocks) shortBlockLen else shortBlockLen + 1
            IntArray(len)
        }
        val eccBlocks = Array(numBlocks) { IntArray(eccLen) }

        var dataOffset = 0
        for (b in 0 until numBlocks) {
            val bLen = blocks[b].size
            for (i in 0 until bLen) {
                blocks[b][i] = dataCodewords[dataOffset++]
            }
            eccBlocks[b] = rsCalculateEcc(blocks[b], eccLen)
        }

        val maxBlockLen = if (numLongBlocks > 0) shortBlockLen + 1 else shortBlockLen
        val finalCodewords = mutableListOf<Int>()
        for (i in 0 until maxBlockLen) {
            for (b in 0 until numBlocks) {
                if (i < blocks[b].size) {
                    finalCodewords.add(blocks[b][i])
                }
            }
        }
        for (i in 0 until eccLen) {
            for (b in 0 until numBlocks) {
                finalCodewords.add(eccBlocks[b][i])
            }
        }

        // 8. Construct QR Matrix
        val matrixSize = 21 + (version - 1) * 4
        val modules = Array(matrixSize) { BooleanArray(matrixSize) }
        val isFunction = Array(matrixSize) { BooleanArray(matrixSize) }

        fun setModule(x: Int, y: Int, isBlack: Boolean, func: Boolean = true) {
            modules[y][x] = isBlack
            if (func) isFunction[y][x] = true
        }

        // Finder patterns (top-left, top-right, bottom-left)
        fun placeFinderPattern(originX: Int, originY: Int) {
            for (dy in -1..7) {
                for (dx in -1..7) {
                    val x = originX + dx
                    val y = originY + dy
                    if (x in 0 until matrixSize && y in 0 until matrixSize) {
                        val isBlack = (dx in 0..6 && (dy == 0 || dy == 6)) ||
                                (dy in 0..6 && (dx == 0 || dx == 6)) ||
                                (dx in 2..4 && dy in 2..4)
                        setModule(x, y, isBlack)
                    }
                }
            }
        }

        placeFinderPattern(0, 0)
        placeFinderPattern(matrixSize - 7, 0)
        placeFinderPattern(0, matrixSize - 7)

        // Alignment patterns
        val alignPos = ALIGNMENT_PATTERN_POSITIONS[version]
        for (xPos in alignPos) {
            for (yPos in alignPos) {
                if ((xPos < 9 && yPos < 9) || (xPos > matrixSize - 10 && yPos < 9) || (xPos < 9 && yPos > matrixSize - 10)) continue
                if (isFunction[yPos][xPos]) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val isBlack = dx == -2 || dx == 2 || dy == -2 || dy == 2 || (dx == 0 && dy == 0)
                        setModule(xPos + dx, yPos + dy, isBlack, func = true)
                    }
                }
            }
        }

        // Timing patterns
        for (i in 8 until matrixSize - 8) {
            if (!isFunction[6][i]) setModule(i, 6, i % 2 == 0, func = true)
            if (!isFunction[i][6]) setModule(6, i, i % 2 == 0, func = true)
        }

        // Dark module
        setModule(8, 4 * version + 9, true)

        // Reserve Format Information areas — exactly 15 modules per copy (ISO/IEC 18004)
        // First copy: (8,0..5), (8,7), (8,8), (7,8), (5..0,8)
        for (i in 0..5) {
            isFunction[i][8] = true
            isFunction[8][i] = true
        }
        isFunction[7][8] = true
        isFunction[8][8] = true
        isFunction[8][7] = true
        // Second copy: vertical (size-1 .. size-7, 8) and horizontal (8, size-8 .. size-1)
        for (i in 0..6) {
            isFunction[matrixSize - 1 - i][8] = true
        }
        for (i in 0..7) {
            isFunction[8][matrixSize - 1 - i] = true
        }
        // Version information (required for version >= 7) — two 6x3 blocks
        if (version >= 7) {
            for (y in 0..5) {
                for (x in 0..2) {
                    isFunction[y][matrixSize - 11 + x] = true
                    isFunction[matrixSize - 11 + x][y] = true
                }
            }
        }

        // 9. Place Data bits
        val finalBits = mutableListOf<Int>()
        for (codeword in finalCodewords) {
            for (i in 7 downTo 0) {
                finalBits.add((codeword ushr i) and 1)
            }
        }

        var bitIndex = 0
        var right = matrixSize - 1
        while (right > 0) {
            if (right == 6) right-- // Skip vertical timing pattern
            for (vert in 0 until matrixSize) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) / 2) % 2 == 0
                    val y = if (upward) matrixSize - 1 - vert else vert
                    if (!isFunction[y][x]) {
                        val bit = if (bitIndex < finalBits.size) finalBits[bitIndex++] else 0
                        // Default Mask 0: (x + y) % 2 == 0
                        val mask = (x + y) % 2 == 0
                        modules[y][x] = if (mask) bit == 0 else bit == 1
                    }
                }
            }
            right -= 2
        }

        // 10. Format information: ECC M (00) + Mask 0 (000) = 0b00000 -> BCH format 0x5412
        val formatBits = 0x5412
        // First copy
        for (i in 0..5) {
            val bit = ((formatBits ushr i) and 1) == 1
            setModule(8, i, bit)
        }
        run {
            val bit6 = ((formatBits ushr 6) and 1) == 1
            setModule(8, 7, bit6)
        }
        run {
            val bit7 = ((formatBits ushr 7) and 1) == 1
            setModule(8, 8, bit7)
        }
        run {
            val bit8 = ((formatBits ushr 8) and 1) == 1
            setModule(7, 8, bit8)
        }
        for (i in 9..14) {
            val bit = ((formatBits ushr i) and 1) == 1
            // Maps 9..14 -> (5,8)..(0,8)
            setModule(14 - i, 8, bit)
        }
        // Second copy: bits 0..6 vertical at (8, size-1 .. size-7), bits 7..14 horizontal at (size-8 .. size-1, 8)
        for (i in 0..6) {
            val bit = ((formatBits ushr i) and 1) == 1
            setModule(8, matrixSize - 1 - i, bit)
        }
        for (i in 7..14) {
            val bit = ((formatBits ushr i) and 1) == 1
            setModule(matrixSize - 15 + i, 8, bit)
        }

        // 11. Version information for version >= 7 (BCH(18,6) with generator 0x1F25)
        if (version >= 7) {
            val versionInfo = getVersionInfoBits(version)
            // Top-right block
            for (i in 0..17) {
                val bit = ((versionInfo ushr i) and 1) == 1
                val x = matrixSize - 11 + (i % 3)
                val y = i / 3
                setModule(x, y, bit, func = true)
            }
            // Bottom-left block
            for (i in 0..17) {
                val bit = ((versionInfo ushr i) and 1) == 1
                val y = matrixSize - 11 + (i % 3)
                val x = i / 3
                setModule(x, y, bit, func = true)
            }
        }

        return QrMatrix(size = matrixSize, modules = modules)
    }
}
