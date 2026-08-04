package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FitTransformTest {

    @Test
    fun `wide image in tall view letterboxes vertically centered`() {
        val fit = FitTransform(viewWidth = 1000f, viewHeight = 2000f, imageWidth = 4000f, imageHeight = 2000f)
        assertEquals(0.25f, fit.scale)
        assertEquals(1000f, fit.fittedWidth)
        assertEquals(500f, fit.fittedHeight)
        assertEquals(0f, fit.offsetX)
        assertEquals(750f, fit.offsetY)
    }

    @Test
    fun `view to uv and back round-trips`() {
        val fit = FitTransform(1080f, 2280f, 3000f, 4000f)
        val (u, v) = fit.viewToUv(540f, 1140f)
        val (x, y) = fit.uvToView(u, v)
        assertEquals(540f, x, 1e-3f)
        assertEquals(1140f, y, 1e-3f)
    }

    @Test
    fun `image corners map to uv corners`() {
        val fit = FitTransform(1000f, 2000f, 4000f, 2000f)
        assertEquals(0f to 0f, fit.viewToUv(fit.offsetX, fit.offsetY))
        val (u, v) = fit.viewToUv(fit.offsetX + fit.fittedWidth, fit.offsetY + fit.fittedHeight)
        assertEquals(1f, u, 1e-6f)
        assertEquals(1f, v, 1e-6f)
    }

    @Test
    fun `contains is true on the image and false on the letterbox`() {
        val fit = FitTransform(1000f, 2000f, 4000f, 2000f)
        assertTrue(fit.contains(500f, 1000f))
        assertFalse(fit.contains(500f, 100f))
        assertFalse(fit.contains(500f, 1900f))
    }
}
