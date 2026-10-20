package dev.lsposed.tiktoksticker

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * AnimatedGifEncoder - Bộ mã hóa GIF89a thuần Java/Kotlin.
 * Hỗ trợ tạo ảnh GIF động nhiều frame, lặp vô tận (loop = 0), độ trễ từng frame và màu trong suốt.
 */
class AnimatedGifEncoder {

    private var width: Int = 0
    private var height: Int = 0
    private var transparent: Int? = null
    private var transIndex: Int = 0
    private var repeat: Int = 0 // 0 = lặp vô tận
    private var delay: Int = 10 // Đơn vị: 1/100 giây (mặc định 10 = 100ms)
    private var started: Boolean = false
    private var out: OutputStream? = null
    private var image: Bitmap? = null
    private var pixels: ByteArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorDepth: Int = 8
    private var colorTab: ByteArray? = null
    private var usedEntry = BooleanArray(256)
    private var palSize: Int = 7
    private var dispose: Int = -1
    private var closeStream: Boolean = false
    private var firstFrame: Boolean = true
    private var sample: Int = 10

    fun setDelay(ms: Int) {
        delay = (ms / 10).coerceAtLeast(2)
    }

    fun setRepeat(iter: Int) {
        repeat = iter
    }

    fun setTransparent(color: Int) {
        transparent = color
    }

    fun setSample(sampleRatio: Int) {
        sample = sampleRatio.coerceAtLeast(1)
    }

    fun start(os: OutputStream): Boolean {
        out = os
        closeStream = false
        return runCatching {
            writeString("GIF89a")
            started = true
            true
        }.getOrDefault(false)
    }

    fun addFrame(im: Bitmap): Boolean {
        if (!started || out == null) return false
        return runCatching {
            image = im
            getImagePixels()
            analyzePixels()
            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) {
                    writeNetscapeExt()
                }
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame) {
                writePalette()
            }
            writePixels()
            firstFrame = false
            true
        }.getOrDefault(false)
    }

    fun finish(): Boolean {
        if (!started) return false
        started = false
        var ok = true
        runCatching {
            out?.write(0x3B) // GIF trailer
            out?.flush()
            if (closeStream) {
                out?.close()
            }
        }.onFailure { ok = false }
        transIndex = 0
        out = null
        image = null
        pixels = null
        indexedPixels = null
        colorTab = null
        firstFrame = true
        return ok
    }

    private fun analyzePixels() {
        val len = pixels!!.size
        val nPix = len / 3
        indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels!!, len, sample)
        colorTab = nq.process()

        var k = 0
        for (i in 0 until nPix) {
            val r = pixels!![k++].toInt() and 0xff
            val g = pixels!![k++].toInt() and 0xff
            val b = pixels!![k++].toInt() and 0xff
            val index = nq.map(b, g, r)
            usedEntry[index] = true
            indexedPixels!![i] = index.toByte()
        }
        pixels = null
        colorDepth = 8
        palSize = 7

        transparent?.let { trans ->
            transIndex = findClosest(trans)
        }
    }

    private fun findClosest(c: Int): Int {
        if (colorTab == null) return -1
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        var minpos = 0
        var dmin = 256 * 256 * 256
        val len = colorTab!!.size
        var i = 0
        while (i < len) {
            val dr = r - (colorTab!![i++].toInt() and 0xff)
            val dg = g - (colorTab!![i++].toInt() and 0xff)
            val db = b - (colorTab!![i++].toInt() and 0xff)
            val d = dr * dr + dg * dg + db * db
            val index = i / 3
            if (usedEntry[index] && (d < dmin)) {
                dmin = d
                minpos = index
            }
        }
        return minpos
    }

    private fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height
        if (width == 0 || height == 0) {
            width = w
            height = h
        }
        val data = IntArray(w * h)
        image!!.getPixels(data, 0, w, 0, 0, w, h)
        pixels = ByteArray(data.size * 3)

        var count = 0
        var hasAlpha = false
        var transparentColor = transparent

        for (i in data.indices) {
            val rgb = data[i]
            val a = Color.alpha(rgb)
            if (a < 128) {
                hasAlpha = true
                if (transparentColor == null) {
                    transparentColor = Color.MAGENTA
                    transparent = transparentColor
                }
                pixels!![count++] = Color.blue(transparentColor).toByte()
                pixels!![count++] = Color.green(transparentColor).toByte()
                pixels!![count++] = Color.red(transparentColor).toByte()
            } else {
                pixels!![count++] = Color.blue(rgb).toByte()
                pixels!![count++] = Color.green(rgb).toByte()
                pixels!![count++] = Color.red(rgb).toByte()
            }
        }
        if (hasAlpha) {
            dispose = 2 // Restore to background color
        }
    }

    private fun writeGraphicCtrlExt() {
        out!!.write(0x21) // Extension introducer
        out!!.write(0xF9) // GCE label
        out!!.write(4)    // Block size
        val transp: Int
        val disp: Int
        if (transparent == null) {
            transp = 0
            disp = 0
        } else {
            transp = 1
            disp = if (dispose >= 0) dispose and 7 else 2
        }
        out!!.write(0 or (disp shl 2) or 0 or transp)
        writeShort(delay)
        out!!.write(transIndex)
        out!!.write(0) // Block terminator
    }

    private fun writeImageDesc() {
        out!!.write(0x2C) // Image separator
        writeShort(0)     // Image left
        writeShort(0)     // Image top
        writeShort(width)
        writeShort(height)
        if (firstFrame) {
            out!!.write(0)
        } else {
            out!!.write(0x80 or 0 or 0 or 0 or palSize)
        }
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out!!.write(0x80 or 0x70 or 0x00 or palSize)
        out!!.write(0) // Background color index
        out!!.write(0) // Pixel aspect ratio
    }

    private fun writeNetscapeExt() {
        out!!.write(0x21) // Extension introducer
        out!!.write(0xFF) // App extension label
        out!!.write(11)   // Block size
        writeString("NETSCAPE2.0")
        out!!.write(3)    // Sub-block size
        out!!.write(1)    // Loop sub-block id
        writeShort(repeat) // Loop count (0 = vô tận)
        out!!.write(0)    // Block terminator
    }

    private fun writePalette() {
        out!!.write(colorTab!!, 0, colorTab!!.size)
        val n = (3 * 256) - colorTab!!.size
        for (i in 0 until n) {
            out!!.write(0)
        }
    }

    private fun writePixels() {
        val encoder = LZWEncoder(width, height, indexedPixels!!, colorDepth)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        out!!.write(value and 0xFF)
        out!!.write((value shr 8) and 0xFF)
    }

    private fun writeString(s: String) {
        for (element in s) {
            out!!.write(element.code)
        }
    }

    // =========================================================================
    // LZW ENCODER & NEUQUANT COLOR QUANTIZATION
    // =========================================================================

    private class LZWEncoder(
        private val imgW: Int,
        private val imgH: Int,
        private val pixAry: ByteArray,
        private val initCodeSize: Int
    ) {
        private var cur_accum = 0
        private var cur_bits = 0
        private val accum = ByteArray(256)
        private val htab = IntArray(5003)
        private val codetab = IntArray(5003)
        private var n_bits = 0
        private var maxcode = 0
        private var free_ent = 0
        private var clear_flg = false
        private var g_init_bits = 0
        private var ClearCode = 0
        private var EOFCode = 0
        private var curx = 0
        private var cury = 0

        fun encode(os: OutputStream) {
            os.write(initCodeSize)
            var remaining = imgW * imgH
            curx = 0
            cury = 0
            compress(initCodeSize + 1, os)
            os.write(0)
        }

        private fun compress(init_bits: Int, outs: OutputStream) {
            g_init_bits = init_bits
            clear_flg = false
            n_bits = g_init_bits
            maxcode = (1 shl n_bits) - 1
            ClearCode = 1 shl (init_bits - 1)
            EOFCode = ClearCode + 1
            free_ent = ClearCode + 2
            char_out_init()

            var ent = nextPixel()
            var hshift = 0
            var fcode = 5003
            while (fcode < 65536) {
                hshift++
                fcode *= 2
            }
            hshift = 8 - hshift
            cl_hash(5003)
            output(ClearCode, outs)

            var c: Int
            while (nextPixel().also { c = it } != -1) {
                fcode = (c shl 12) + ent
                var i = (c shl hshift) xor ent
                if (htab[i] == fcode) {
                    ent = codetab[i]
                    continue
                } else if (htab[i] >= 0) {
                    var disp = 5003 - i
                    if (i == 0) disp = 1
                    do {
                        i -= disp
                        if (i < 0) i += 5003
                        if (htab[i] == fcode) {
                            ent = codetab[i]
                            break
                        }
                    } while (htab[i] >= 0)
                    if (htab[i] == fcode) continue
                }
                output(ent, outs)
                ent = c
                if (free_ent < 4096) {
                    codetab[i] = free_ent++
                    htab[i] = fcode
                } else {
                    cl_block(outs)
                }
            }
            output(ent, outs)
            output(EOFCode, outs)
        }

        private fun output(code: Int, outs: OutputStream) {
            cur_accum = cur_accum and ((1 shl cur_bits) - 1)
            if (cur_bits > 0) cur_accum = cur_accum or (code shl cur_bits) else cur_accum = code
            cur_bits += n_bits
            while (cur_bits >= 8) {
                char_out((cur_accum and 0xFF).toByte(), outs)
                cur_accum = cur_accum shr 8
                cur_bits -= 8
            }
            if (free_ent > maxcode || clear_flg) {
                if (clear_flg) {
                    n_bits = g_init_bits
                    maxcode = (1 shl n_bits) - 1
                    clear_flg = false
                } else {
                    n_bits++
                    maxcode = if (n_bits == 12) 4096 else (1 shl n_bits) - 1
                }
            }
            if (code == EOFCode) {
                while (cur_bits > 0) {
                    char_out((cur_accum and 0xFF).toByte(), outs)
                    cur_accum = cur_accum shr 8
                    cur_bits -= 8
                }
                flush_char(outs)
            }
        }

        private fun cl_block(outs: OutputStream) {
            cl_hash(5003)
            free_ent = ClearCode + 2
            clear_flg = true
            output(ClearCode, outs)
        }

        private fun cl_hash(hsize: Int) {
            for (i in 0 until hsize) htab[i] = -1
        }

        private fun nextPixel(): Int {
            if (curx >= imgW) {
                curx = 0
                cury++
            }
            if (cury >= imgH) return -1
            val idx = cury * imgW + curx++
            return pixAry[idx].toInt() and 0xFF
        }

        private var a_count = 0
        private fun char_out_init() { a_count = 0 }
        private fun char_out(c: Byte, outs: OutputStream) {
            accum[a_count++] = c
            if (a_count >= 254) flush_char(outs)
        }
        private fun flush_char(outs: OutputStream) {
            if (a_count > 0) {
                outs.write(a_count)
                outs.write(accum, 0, a_count)
                a_count = 0
            }
        }
    }

    private class NeuQuant(
        private val thepicture: ByteArray,
        private val lengthcount: Int,
        private val samplefac: Int
    ) {
        private val network = Array(256) { IntArray(4) }
        private val netindex = IntArray(256)
        private val bias = IntArray(256)
        private val freq = IntArray(256)
        private val radpower = IntArray(32)

        init {
            for (i in 0 until 256) {
                network[i][0] = (i shl 12) / 256
                network[i][1] = (i shl 12) / 256
                network[i][2] = (i shl 12) / 256
                freq[i] = 256
                bias[i] = 0
            }
        }

        fun process(): ByteArray {
            learn()
            unbiasnet()
            inxbuild()
            val colorTab = ByteArray(3 * 256)
            var k = 0
            for (i in 0 until 256) {
                colorTab[k++] = network[i][0].toByte()
                colorTab[k++] = network[i][1].toByte()
                colorTab[k++] = network[i][2].toByte()
            }
            return colorTab
        }

        fun map(b: Int, g: Int, r: Int): Int {
            var bestd = 1000
            var best = -1
            var i = netindex[g]
            var j = i - 1
            while (i < 256 || j >= 0) {
                if (i < 256) {
                    val dist = network[i][1] - g
                    if (dist >= bestd) i = 256
                    else {
                        val d = abs(network[i][0] - b) + abs(network[i][2] - r) + abs(dist)
                        if (d < bestd) { bestd = d; best = network[i][3] }
                        i++
                    }
                }
                if (j >= 0) {
                    val dist = g - network[j][1]
                    if (dist >= bestd) j = -1
                    else {
                        val d = abs(network[j][0] - b) + abs(network[j][2] - r) + abs(dist)
                        if (d < bestd) { bestd = d; best = network[j][3] }
                        j--
                    }
                }
            }
            return best
        }

        private fun unbiasnet() {
            for (i in 0 until 256) {
                network[i][0] = network[i][0] shr 4
                network[i][1] = network[i][1] shr 4
                network[i][2] = network[i][2] shr 4
                network[i][3] = i
            }
        }

        private fun inxbuild() {
            var previouscol = 0
            var startpos = 0
            for (i in 0 until 256) {
                val p = network[i]
                var smallpos = i
                var smallval = p[1]
                for (j in i + 1 until 256) {
                    val q = network[j]
                    if (q[1] < smallval) {
                        smallpos = j
                        smallval = q[1]
                    }
                }
                val q = network[smallpos]
                if (i != smallpos) {
                    var t = q[0]; q[0] = p[0]; p[0] = t
                    t = q[1]; q[1] = p[1]; p[1] = t
                    t = q[2]; q[2] = p[2]; p[2] = t
                    t = q[3]; q[3] = p[3]; p[3] = t
                }
                if (smallval != previouscol) {
                    netindex[previouscol] = (startpos + i) shr 1
                    for (j in previouscol + 1 until smallval) netindex[j] = i
                    previouscol = smallval
                    startpos = i
                }
            }
            netindex[previouscol] = (startpos + 255) shr 1
            for (j in previouscol + 1 until 256) netindex[j] = 255
        }

        private fun learn() {
            if (lengthcount < 3) return
            val samplepixels = lengthcount / (3 * samplefac)
            val delta = (samplepixels / 100).coerceAtLeast(1)
            val alpha = 1024
            val radius = (256 * 32) shr 6
            var rad = radius shr 6
            if (rad <= 1) rad = 0
            for (i in 0 until rad) radpower[i] = alpha * (((rad * rad - i * i) * 256) / (rad * rad))
            val step = if (lengthcount % 499 != 0) 499 * 3 else 503 * 3
            var pix = 0
            for (i in 0 until samplepixels) {
                val b = (thepicture[pix].toInt() and 0xFF) shl 4
                val g = (thepicture[pix + 1].toInt() and 0xFF) shl 4
                val r = (thepicture[pix + 2].toInt() and 0xFF) shl 4
                var j = contest(b, g, r)
                altersingle(alpha, j, b, g, r)
                if (rad != 0) alterneigh(rad, j, b, g, r)
                pix += step
                if (pix >= lengthcount) pix -= lengthcount
            }
        }

        private fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
            network[i][0] -= (alpha * (network[i][0] - b)) / 1024
            network[i][1] -= (alpha * (network[i][1] - g)) / 1024
            network[i][2] -= (alpha * (network[i][2] - r)) / 1024
        }

        private fun alterneigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
            val lo = (i - rad).coerceAtLeast(0)
            val hi = (i + rad).coerceAtMost(255)
            var j = i + 1
            var k = i - 1
            var m = 1
            while (j < hi || k > lo) {
                val a = radpower[m++]
                if (j < hi) {
                    val p = network[j++]
                    p[0] -= (a * (p[0] - b)) / (1024 * 256)
                    p[1] -= (a * (p[1] - g)) / (1024 * 256)
                    p[2] -= (a * (p[2] - r)) / (1024 * 256)
                }
                if (k > lo) {
                    val p = network[k--]
                    p[0] -= (a * (p[0] - b)) / (1024 * 256)
                    p[1] -= (a * (p[1] - g)) / (1024 * 256)
                    p[2] -= (a * (p[2] - r)) / (1024 * 256)
                }
            }
        }

        private fun contest(b: Int, g: Int, r: Int): Int {
            var bestd = 0x7FFFFFFF
            var bestbiasd = bestd
            var bestpos = -1
            var bestbiaspos = bestpos
            for (i in 0 until 256) {
                val p = network[i]
                val dist = abs(p[0] - b) + abs(p[1] - g) + abs(p[2] - r)
                if (dist < bestd) { bestd = dist; bestpos = i }
                val biasdist = dist - (bias[i] shr 12)
                if (biasdist < bestbiasd) { bestbiasd = biasdist; bestbiaspos = i }
                val betafreq = freq[i] shr 10
                freq[i] -= betafreq
                bias[i] += betafreq shl 10
            }
            freq[bestpos] += 64
            bias[bestpos] -= 64 shl 10
            return bestbiaspos
        }
    }
}
