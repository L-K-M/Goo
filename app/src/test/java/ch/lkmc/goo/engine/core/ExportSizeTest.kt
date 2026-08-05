package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportSizeTest {

    @Test
    fun `budget caps the export even when the GPU allows more`() {
        assertEquals(4096, ExportSize.exportLongSideCap(16384))
        assertEquals(4096, ExportSize.exportLongSideCap(8192))
    }

    @Test
    fun `GPU limit caps the export when it is the tighter bound`() {
        assertEquals(2048, ExportSize.exportLongSideCap(2048))
    }

    @Test
    fun `field stays at or below its cap with aspect preserved`() {
        assertEquals(Pair(1024, 768), ExportSize.fieldDimensions(4096, 3072))
        assertEquals(Pair(768, 1024), ExportSize.fieldDimensions(3072, 4096))
    }

    @Test
    fun `small images get a native-size field`() {
        assertEquals(Pair(640, 480), ExportSize.fieldDimensions(640, 480))
    }

    @Test
    fun `field never collapses below 2 texels`() {
        val (w, h) = ExportSize.fieldDimensions(3, 1000)
        assertTrue(w >= 2 && h >= 2)
    }

    @Test
    fun `degenerate inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> { ExportSize.exportLongSideCap(0) }
        assertFailsWith<IllegalArgumentException> { ExportSize.fieldDimensions(0, 10) }
        assertFailsWith<IllegalArgumentException> { ExportSize.fieldDimensions(10, 10, -1) }
    }
}
