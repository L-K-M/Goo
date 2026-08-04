package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrushFalloffTest {

    @Test
    fun `full weight at the center`() {
        assertEquals(1f, BrushFalloff.weight(0f))
        assertEquals(1f, BrushFalloff.weight(-0.5f), "negative distances clamp to center weight")
    }

    @Test
    fun `zero weight at and beyond the rim`() {
        assertEquals(0f, BrushFalloff.weight(1f))
        assertEquals(0f, BrushFalloff.weight(2.5f))
    }

    @Test
    fun `halfway is exactly half`() {
        // smoothstep is symmetric around its midpoint.
        assertEquals(0.5f, BrushFalloff.weight(0.5f), 1e-6f)
    }

    @Test
    fun `monotonically non-increasing across the radius`() {
        var previous = Float.MAX_VALUE
        var d = 0f
        while (d <= 1.2f) {
            val w = BrushFalloff.weight(d)
            assertTrue(w <= previous, "weight must not increase (d=$d, w=$w, prev=$previous)")
            previous = w
            d += 0.01f
        }
    }

    @Test
    fun `smooth at both ends - no crease at rim or center`() {
        // C1 continuity: the slope approaches zero at d=0 and d=1, so the
        // finite difference near the ends must be much flatter than in the
        // middle. A crease here would print rings into the warp field.
        val eps = 1e-3f
        val slopeAtCenter = (BrushFalloff.weight(0f) - BrushFalloff.weight(eps)) / eps
        val slopeAtRim = (BrushFalloff.weight(1f - eps) - BrushFalloff.weight(1f)) / eps
        val slopeAtMiddle = (BrushFalloff.weight(0.5f - eps) - BrushFalloff.weight(0.5f + eps)) / (2 * eps)
        assertTrue(slopeAtCenter < 0.01f, "flat at center, got $slopeAtCenter")
        assertTrue(slopeAtRim < 0.01f, "flat at rim, got $slopeAtRim")
        assertTrue(slopeAtMiddle > 1.4f, "steepest in the middle, got $slopeAtMiddle")
    }

    @Test
    fun `weights always within unit range`() {
        var d = -1f
        while (d <= 3f) {
            val w = BrushFalloff.weight(d)
            assertTrue(w in 0f..1f, "weight out of range at d=$d: $w")
            d += 0.05f
        }
    }
}
