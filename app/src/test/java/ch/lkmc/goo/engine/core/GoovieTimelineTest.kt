package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GoovieTimelineTest {

    @Test
    fun `segment and fraction split a position`() {
        assertEquals(0, GoovieTimeline.segment(0.4f, 4))
        assertEquals(0.4f, GoovieTimeline.fraction(0.4f, 4))
        assertEquals(2, GoovieTimeline.segment(2.75f, 4))
        assertEquals(0.75f, GoovieTimeline.fraction(2.75f, 4))
    }

    @Test
    fun `the last keyframe belongs to the final segment`() {
        // p = size-1 exactly: segment size-2, fraction 1 — no phantom
        // segment past the end.
        assertEquals(2, GoovieTimeline.segment(3f, 4))
        assertEquals(1f, GoovieTimeline.fraction(3f, 4))
    }

    @Test
    fun `degenerate strips pin to zero`() {
        assertEquals(0, GoovieTimeline.segment(0.9f, 1))
        assertEquals(0f, GoovieTimeline.fraction(0.9f, 1))
        assertEquals(0f, GoovieTimeline.clamp(5f, 0))
        assertEquals(0f, GoovieTimeline.advance(0.5f, 1f, 1))
    }

    @Test
    fun `clamp bounds a position to the strip`() {
        assertEquals(3f, GoovieTimeline.clamp(7f, 4))
        assertEquals(0f, GoovieTimeline.clamp(-1f, 4))
        assertEquals(1.5f, GoovieTimeline.clamp(1.5f, 4))
    }

    @Test
    fun `advance survives a giant dt from a backgrounded frame clock`() {
        // Ten minutes backgrounded mid-playback: the position must land
        // inside the strip, not pinned past the end.
        val p = GoovieTimeline.advance(1.9f, 600f, 3)
        kotlin.test.assertTrue(p in 0f..2f, "p=$p")
        // And keep looping normally afterwards.
        val next = GoovieTimeline.advance(p, GoovieTimeline.SECONDS_PER_SEGMENT / 2f, 3)
        kotlin.test.assertTrue(next in 0f..2f)
    }

    @Test
    fun `advance moves at the segment rate and loops`() {
        val dt = GoovieTimeline.SECONDS_PER_SEGMENT / 2f
        assertEquals(0.5f, GoovieTimeline.advance(0f, dt, 3), 1e-5f)
        // Crossing the end wraps to the start, preserving overshoot.
        val nearEnd = 1.9f
        val advanced = GoovieTimeline.advance(nearEnd, dt, 3)
        assertEquals(0.4f, advanced, 1e-5f)
    }

    @Test
    fun `lerp interpolates every lever and hits endpoints exactly`() {
        val a = GlobalParams(bulge = 1f, twirl = -1f, squeeze = 0.5f)
        val b = GlobalParams(stretch = 1f, spike = -0.5f, static = 0.25f)
        assertEquals(a, a.lerp(b, 0f))
        assertEquals(b, a.lerp(b, 1f))
        val mid = a.lerp(b, 0.5f)
        assertEquals(0.5f, mid.bulge)
        assertEquals(-0.5f, mid.twirl)
        assertEquals(0.25f, mid.squeeze)
        assertEquals(0.5f, mid.stretch)
        assertEquals(-0.25f, mid.spike)
        assertEquals(0.125f, mid.static)
    }
}
