package net.prototux.aare.host.templates

/**
 * Minimal QR code encoder for QRCodeSignInMethod (byte mode, error correction M, versions 1-40).
 *
 * The app only hands us a URI, so the host has to draw the code itself; pulling in a library
 * for one screen is not worth it. Follows ISO/IEC 18004 (the structure is the classic one used
 * by most small encoders: data bits -> Reed-Solomon blocks -> interleave -> place -> mask).
 */
internal class QrCode private constructor(val size: Int, private val modules: BooleanArray) {

    /** True when the module at column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]

    companion object {
        private const val ECC_M = 0 // format bits for level M

        // per version (index 0 unused): ECC codewords per block, and number of blocks, level M
        private val ECC_PER_BLOCK = intArrayOf(
            -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26,
            26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
        )
        private val NUM_BLOCKS = intArrayOf(
            -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16,
            17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
        )

        /** Encodes [text] as UTF-8, or null when it does not fit in a version 40 code. */
        fun encode(text: String): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..40).firstOrNull { v ->
                4 + (if (v <= 9) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8
            } ?: return null
            return Builder(version).build(data)
        }

        internal fun rawDataModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(ver: Int): Int = rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]

        internal fun alignmentPositions(ver: Int): IntArray {
            if (ver == 1) return IntArray(0)
            val numAlign = ver / 7 + 2
            val step = (ver * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = ver * 4 + 17 - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        // GF(2^8) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1
        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
            }
            return result
        }
    }

    private class Builder(val version: Int) {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        fun build(data: ByteArray): QrCode {
            drawFunctionPatterns()
            drawCodewords(addEccAndInterleave(dataCodewordsFor(data)))
            // pick the mask with the lowest penalty, as decoders read any of them
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask)
                drawFormatBits(mask)
                val p = penalty()
                if (p < bestPenalty) {
                    bestPenalty = p
                    bestMask = mask
                }
                applyMask(mask) // XOR again to undo
            }
            applyMask(bestMask)
            drawFormatBits(bestMask)
            return QrCode(size, modules)
        }

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
        }

        private fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
            isFunction[y * size + x] = true
        }

        private fun get(x: Int, y: Int) = modules[y * size + x]

        private fun dataCodewordsFor(data: ByteArray): IntArray {
            val capacityBits = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>(capacityBits)
            fun append(value: Int, len: Int) {
                for (i in len - 1 downTo 0) bits.add(((value ushr i) and 1) != 0)
            }
            append(0b0100, 4) // byte mode
            append(data.size, if (version <= 9) 8 else 16)
            for (b in data) append(b.toInt() and 0xFF, 8)
            append(0, minOf(4, capacityBits - bits.size))
            append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) {
                append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            return IntArray(bits.size / 8) { i ->
                var v = 0
                for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                v
            }
        }

        private fun addEccAndInterleave(data: IntArray): IntArray {
            val numBlocks = NUM_BLOCKS[version]
            val blockEccLen = ECC_PER_BLOCK[version]
            val rawCodewords = rawDataModules(version) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks
            val divisor = rsDivisor(blockEccLen)
            val blocks = ArrayList<IntArray>(numBlocks)
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
                val dat = data.copyOfRange(k, k + datLen)
                k += datLen
                val ecc = rsRemainder(dat, divisor)
                // short blocks get a placeholder byte so all blocks have the same length
                val block = IntArray(shortBlockLen + 1)
                System.arraycopy(dat, 0, block, 0, dat.size)
                System.arraycopy(ecc, 0, block, shortBlockLen + 1 - blockEccLen, blockEccLen)
                blocks.add(block)
            }
            val result = IntArray(rawCodewords)
            var n = 0
            for (i in 0..shortBlockLen) {
                for (j in 0 until numBlocks) {
                    if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[n++] = blocks[j][i]
                }
            }
            return result
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val pos = alignmentPositions(version)
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) {
                    setFunction(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
                }
            }
            drawFormatBits(0) // reserve the area; real bits are drawn after masking
            drawVersion()
        }

        private fun drawFinder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) setFunction(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun drawFormatBits(mask: Int) {
            val data = (ECC_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) setFunction(8, i, bit(i))
            setFunction(8, 7, bit(6))
            setFunction(8, 8, bit(7))
            setFunction(7, 8, bit(8))
            for (i in 9 until 15) setFunction(14 - i, 8, bit(i))
            for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) setFunction(8, size - 15 + i, bit(i))
            setFunction(8, size - 8, true) // the always-dark module
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                setFunction(a, b, bit)
                setFunction(b, a, bit)
            }
        }

        private fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y * size + x] && i < data.size * 8) {
                            set(x, y, ((data[i ushr 3] ushr (7 - (i and 7))) and 1) != 0)
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y * size + x]) modules[y * size + x] = !modules[y * size + x]
            }
        }

        /** The standard penalty rules N1-N4 (rule 3 checked in-bounds only, which is enough to rank masks). */
        private fun penalty(): Int {
            var result = 0
            // N1: runs of 5+ same-colour modules; N3: finder-like 1:1:3:1:1 patterns
            val finderA = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            val finderB = finderA.reversedArray()
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    var runColor = false
                    var run = 0
                    for (b in 0 until size) {
                        val c = if (horizontal) get(b, a) else get(a, b)
                        if (b == 0 || c != runColor) {
                            runColor = c
                            run = 1
                        } else {
                            run++
                            if (run == 5) result += 3 else if (run > 5) result++
                        }
                        if (b + 11 <= size) {
                            var matchA = true
                            var matchB = true
                            for (k in 0 until 11) {
                                val m = if (horizontal) get(b + k, a) else get(a, b + k)
                                if (m != finderA[k]) matchA = false
                                if (m != finderB[k]) matchB = false
                            }
                            if (matchA) result += 40
                            if (matchB) result += 40
                        }
                    }
                }
            }
            // N2: 2x2 blocks
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = get(x, y)
                if (c == get(x + 1, y) && c == get(x, y + 1) && c == get(x + 1, y + 1)) result += 3
            }
            // N4: dark/light balance
            val dark = modules.count { it }
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += maxOf(k, 0) * 10
            return result
        }
    }
}
