package ch.lkmc.goo.engine.media

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The encoder is verified by DECODING what it writes: a GIF that parses
 * on this reader is a GIF that parses on a viewer, and an LZW that is one
 * code-width early or late fails here instead of in someone's chat app.
 * [TinyGifReader] at the bottom of this file is a plain GIF89a reader
 * with no knowledge of the encoder's internals.
 */
class GifEncoderTest {

    /** Colours whose channels survive the 5-bit palette bucketing exactly. */
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    @Test
    fun `a single frame round-trips pixel for pixel`() {
        val pixels = intArrayOf(
            black, white, red, blue,
            blue, red, white, black,
            white, white, black, red,
        )
        val gif = encode(width = 4, height = 3, loop = true) { it.addFrame(pixels, 5) }

        val decoded = TinyGifReader(gif).read()
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        assertEquals(1, decoded.frames.size)
        assertContentEquals(pixels, decoded.frames[0].pixels)
    }

    @Test
    fun `frames keep their own delays and order`() {
        val first = IntArray(4) { red }
        val second = IntArray(4) { blue }
        val gif = encode(width = 2, height = 2, loop = true) {
            it.addFrame(first, 5)
            it.addFrame(second, 33)
        }

        val decoded = TinyGifReader(gif).read()
        assertEquals(2, decoded.frames.size)
        assertContentEquals(first, decoded.frames[0].pixels)
        assertContentEquals(second, decoded.frames[1].pixels)
        assertEquals(5, decoded.frames[0].delayCentis)
        assertEquals(33, decoded.frames[1].delayCentis)
    }

    @Test
    fun `looping writes a NETSCAPE block with an infinite repeat count`() {
        val gif = encode(width = 2, height = 2, loop = true) { it.addFrame(IntArray(4) { red }, 5) }

        val decoded = TinyGifReader(gif).read()
        assertTrue(decoded.loops)
        assertEquals(0, decoded.repeatCount)
    }

    @Test
    fun `a one-shot GIF has no loop block at all`() {
        val gif = encode(width = 2, height = 2, loop = false) { it.addFrame(IntArray(4) { red }, 5) }

        assertFalse(TinyGifReader(gif).read().loops)
        // The bytes themselves must not carry it either — a viewer that
        // scans for the signature rather than parsing would still loop.
        assertFalse(String(gif, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
    }

    @Test
    fun `high-entropy frames survive dictionary growth and clears`() {
        // 300×300 of pseudorandom symbols runs the LZW table past 4096
        // entries several times, which is the one path where an off-by-one
        // code width silently corrupts everything after the first clear.
        val palette = intArrayOf(black, white, red, blue, 0xFF00FF00.toInt(), 0xFFFFFF00.toInt())
        var seed = 0x5EED
        val pixels = IntArray(300 * 300) {
            seed = seed * 1_103_515_245 + 12_345
            palette[(seed ushr 16 and 0x7FFF) % palette.size]
        }
        val gif = encode(width = 300, height = 300, loop = true) { it.addFrame(pixels, 5) }

        val decoded = TinyGifReader(gif).read()
        assertContentEquals(pixels, decoded.frames[0].pixels)
    }

    @Test
    fun `a frame with more than 256 colours still decodes at full size`() {
        // 4096 distinct colours through a 256-entry median cut: the point
        // is that every pixel maps to SOME palette entry and the frame
        // keeps its shape (quality is GifPaletteTest's business).
        val pixels = IntArray(64 * 64) { i ->
            val x = i % 64
            val y = i / 64
            (0xFF shl 24) or (x * 4 shl 16) or (y * 4 shl 8) or ((x + y) * 2)
        }
        val gif = encode(width = 64, height = 64, loop = true) { it.addFrame(pixels, 5) }

        val decoded = TinyGifReader(gif).read()
        assertEquals(64 * 64, decoded.frames[0].pixels.size)
        // Near, not equal: 256 entries cannot hold 4096 colours.
        val worst = pixels.indices.maxOf { distance(pixels[it], decoded.frames[0].pixels[it]) }
        assertTrue(worst < 32 * 32 * 3, "worst channel error too large: $worst")
    }

    private fun encode(
        width: Int,
        height: Int,
        loop: Boolean,
        body: (GifEncoder) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out, width, height, loop)
        body(encoder)
        encoder.finish()
        return out.toByteArray()
    }

    private fun distance(a: Int, b: Int): Int {
        val dr = (a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)
        val dg = (a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }
}

/** A minimal GIF89a reader — enough of the format to check the writer. */
private class TinyGifReader(private val bytes: ByteArray) {

    class Frame(val pixels: IntArray, val delayCentis: Int)
    class Gif(
        val width: Int,
        val height: Int,
        val loops: Boolean,
        val repeatCount: Int,
        val frames: List<Frame>,
    )

    private var at = 0

    fun read(): Gif {
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        at = 6
        val width = short()
        val height = short()
        val packed = byte()
        skip(2)
        if (packed and 0x80 != 0) skip(3 * (1 shl ((packed and 0x07) + 1)))

        var loops = false
        var repeatCount = -1
        var delay = 0
        val frames = mutableListOf<Frame>()
        while (true) {
            when (val block = byte()) {
                0x21 -> when (val label = byte()) {
                    0xF9 -> {
                        skip(1) // block size
                        skip(1) // packed fields
                        delay = short()
                        skip(1) // transparent index
                        skip(1) // terminator
                    }

                    0xFF -> {
                        val size = byte()
                        val name = String(bytes, at, size, Charsets.US_ASCII)
                        skip(size)
                        val payload = subBlocks()
                        if (name == "NETSCAPE2.0" && payload.size >= 3 && payload[0].toInt() == 1) {
                            loops = true
                            repeatCount = (payload[1].toInt() and 0xFF) or
                                ((payload[2].toInt() and 0xFF) shl 8)
                        }
                    }

                    else -> {
                        if (label == 0) error("bad extension label")
                        subBlocks()
                    }
                }

                0x2C -> frames += image(delay)
                0x3B -> return Gif(width, height, loops, repeatCount, frames)
                else -> error("unknown block 0x${block.toString(16)} at $at")
            }
        }
    }

    private fun image(delay: Int): Frame {
        skip(2) // left
        skip(2) // top
        val w = short()
        val h = short()
        val packed = byte()
        val palette = IntArray(1 shl ((packed and 0x07) + 1)) {
            (0xFF shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        }
        val minCodeSize = byte()
        val indices = lzw(subBlocks(), minCodeSize)
        assertEquals(w * h, indices.size)
        return Frame(IntArray(indices.size) { palette[indices[it]] }, delay)
    }

    /** GIF's LZW, decoded by the book — deliberately not the encoder's mirror. */
    private fun lzw(data: ByteArray, minCodeSize: Int): IntArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val dictionary = arrayOfNulls<IntArray>(4096)
        for (i in 0 until clear) dictionary[i] = intArrayOf(i)

        var codeSize = minCodeSize + 1
        var next = clear + 2
        var previous: IntArray? = null
        val out = mutableListOf<Int>()

        var bitPosition = 0
        fun nextCode(): Int {
            var code = 0
            for (bit in 0 until codeSize) {
                val index = bitPosition + bit
                val value = (data[index ushr 3].toInt() ushr (index and 7)) and 1
                code = code or (value shl bit)
            }
            bitPosition += codeSize
            return code
        }

        while (bitPosition + codeSize <= data.size * 8) {
            val code = nextCode()
            if (code == eoi) break
            if (code == clear) {
                codeSize = minCodeSize + 1
                // Drop the old generation: after a clear, a code at or
                // above `next` is the not-yet-added one (KwKwK), and a
                // stale entry left in the array would answer for it.
                dictionary.fill(null, clear, dictionary.size)
                next = clear + 2
                previous = null
                continue
            }
            val entry = dictionary[code] ?: previous!!.let { it + it[0] }
            out += entry.toList()
            val p = previous
            if (p != null && next < 4096) {
                dictionary[next++] = p + entry[0]
                if (next > (1 shl codeSize) - 1 && codeSize < 12) codeSize++
            }
            previous = entry
        }
        return out.toIntArray()
    }

    private fun subBlocks(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = byte()
            if (size == 0) return out.toByteArray()
            out.write(bytes, at, size)
            skip(size)
        }
    }

    private fun byte(): Int = bytes[at++].toInt() and 0xFF
    private fun short(): Int = byte() or (byte() shl 8)
    private fun skip(count: Int) { at += count }
}
