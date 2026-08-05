package ch.lkmc.goo.engine.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GifPaletteTest {

    @Test
    fun `few colours get one entry each`() {
        val colors = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
        )
        val pixels = IntArray(400) { colors[it % colors.size] }

        val palette = GifPalette.from(pixels)

        assertEquals(colors.size, palette.size)
        // Round-trip exactly: these channels sit on 5-bit bucket edges.
        for (color in colors) {
            assertEquals(color and 0xFFFFFF, palette.colors[palette.indexOf(color)])
        }
    }

    @Test
    fun `a gradient is bounded by the 256-entry budget`() {
        val pixels = IntArray(4096) { (0xFF shl 24) or (it shl 8) }

        val palette = GifPalette.from(pixels)

        assertTrue(palette.size <= GifPalette.MAX_COLORS, "palette of ${palette.size}")
        assertTrue(palette.size > 1, "a gradient needs more than one entry")
    }

    @Test
    fun `entries go where the pixels are`() {
        // 99% of one exact colour plus a long tail: median cut splits by
        // pixel weight, so the dominant colour must survive untouched.
        // Channels chosen to sit exactly on 5-bit bucket centres, so an
        // untouched box round-trips to the same 24-bit colour.
        val dominant = 0xFF214263.toInt()
        val pixels = IntArray(10_000) { i ->
            if (i % 100 == 0) (0xFF shl 24) or (i and 0xFFFFFF) else dominant
        }

        val palette = GifPalette.from(pixels)

        val mapped = palette.colors[palette.indexOf(dominant)]
        assertEquals(dominant and 0xFFFFFF, mapped)
    }

    @Test
    fun `the colour table is padded to a power of two`() {
        val pixels = IntArray(9) { (0xFF shl 24) or (it * 0x201F03) }

        val palette = GifPalette.from(pixels)
        val entries = 1 shl palette.bits()

        assertTrue(entries >= palette.size)
        assertEquals(entries * 3, palette.tableBytes().size)
    }

    @Test
    fun `an empty frame still yields a legal table`() {
        val palette = GifPalette.from(IntArray(0))

        assertTrue(palette.size >= 2)
        assertEquals(0, palette.indexOf(0xFF123456.toInt()))
    }

    @Test
    fun `every pixel maps within the palette`() {
        val pixels = IntArray(50_000) { i ->
            val x = i % 250
            val y = i / 250
            (0xFF shl 24) or (x shl 16) or (y shl 8) or (x xor y)
        }

        val palette = GifPalette.from(pixels)
        val indices = palette.index(pixels)

        assertEquals(pixels.size, indices.size)
        assertTrue(indices.all { (it.toInt() and 0xFF) < palette.size })
    }
}
