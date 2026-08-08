package ch.lkmc.goo.engine.core

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Echo's arithmetic (proposal 0004), which the proposal got wrong twice.
 *
 * These tests exist mostly to pin the two corrections: the delta is a
 * constant offset rather than a moving target, and its sign is opposite
 * to what the proposal wrote. Both mistakes produce *something* on
 * screen, which is why neither would announce itself.
 */
class EchoOffsetTest {

    @Test
    fun `the graft shows the source, not the anchor pixel repeated`() {
        // The proposal's `delta = anchor - stampCenter` makes every texel
        // in the stroke sample the single pixel at the anchor. The whole
        // point of a clone is that the copy has structure.
        val anchor = Pair(0.2f, 0.3f)
        val start = Pair(0.6f, 0.5f)
        val delta = EchoOffset.delta(start.first, start.second, anchor.first, anchor.second)

        // Paint at the point the stroke began: sees exactly the anchor.
        val atStart = EchoOffset.sourceOf(start.first, start.second, delta)
        assertEquals(anchor.first, atStart.first, 1e-6f)
        assertEquals(anchor.second, atStart.second, 1e-6f)

        // Paint a little further along: sees a DIFFERENT source pixel,
        // offset by the same vector.
        val moved = EchoOffset.sourceOf(start.first + 0.1f, start.second - 0.05f, delta)
        assertEquals(anchor.first + 0.1f, moved.first, 1e-6f)
        assertEquals(anchor.second - 0.05f, moved.second, 1e-6f)
    }

    @Test
    fun `the offset is rigid for the whole stroke`() {
        val delta = EchoOffset.delta(0.6f, 0.5f, 0.2f, 0.3f)
        val a = EchoOffset.sourceOf(0.61f, 0.51f, delta)
        val b = EchoOffset.sourceOf(0.85f, 0.22f, delta)
        // Source separation must equal brush separation: a translation,
        // not a fan or a collapse.
        assertEquals(0.85f - 0.61f, b.first - a.first, 1e-6f)
        assertEquals(0.22f - 0.51f, b.second - a.second, 1e-6f)
    }

    @Test
    fun `the sign matches the backward-mapping kernel`() {
        // DIRECTIONAL computes b = -delta * w, and the field must end up
        // holding `offset` so the shader samples p + offset. Get this
        // backwards and the graft comes from 2P - S: the point reflected
        // through the brush, which looks like a plausible clone of the
        // wrong region.
        val anchor = Pair(0.2f, 0.3f)
        val start = Pair(0.6f, 0.5f)
        val delta = EchoOffset.delta(start.first, start.second, anchor.first, anchor.second)

        val bx = -delta.first
        val by = -delta.second
        assertEquals(anchor.first - start.first, bx, 1e-6f)
        assertEquals(anchor.second - start.second, by, 1e-6f)

        // Which is to say: sampling at p + b lands on the anchor.
        assertEquals(anchor.first, start.first + bx, 1e-6f)
        assertEquals(anchor.second, start.second + by, 1e-6f)
    }

    @Test
    fun `an anchor under the brush is not worth echoing`() {
        // Grafting a region onto itself is an expensive no-op, and a
        // stray tap plants one easily.
        assertFalse(EchoOffset.isUseful(0.5f, 0.5f, 0.5f, 0.5f))
        assertFalse(EchoOffset.isUseful(0.5f, 0.5f, 0.5f + EchoOffset.MIN_OFFSET / 2f, 0.5f))
        assertTrue(EchoOffset.isUseful(0.5f, 0.5f, 0.5f + EchoOffset.MIN_OFFSET * 2f, 0.5f))
        assertTrue(EchoOffset.isUseful(0.2f, 0.2f, 0.8f, 0.8f))
    }

    @Test
    fun `the largest legal offset stays far inside the field's range`() {
        // Corner to corner is the worst case. Half-float holds ±4 UV, so
        // an echo delta has three orders of magnitude of headroom — worth
        // pinning, because echo deltas are enormous next to smear deltas
        // and this is the one tool that could plausibly overflow.
        val delta = EchoOffset.delta(0f, 0f, 1f, 1f)
        assertTrue(abs(delta.first) <= 1f && abs(delta.second) <= 1f)
    }

    @Test
    fun `Echo is a directional tool, so it needs no kernel of its own`() {
        // The whole reason this file is arithmetic rather than a shader
        // branch: copying a region IS what a displacement field does.
        assertEquals(StampMode.DIRECTIONAL, BrushTool.ECHO.mode)
        assertEquals(FalloffProfile.FEATHER, BrushTool.ECHO.profile)
        assertFalse("a clone stamp must not pump", BrushTool.ECHO.pumped)
        assertFalse("planting is not painting", BrushTool.ECHO.stampsOnDown)
    }

    @Test
    fun `a mirrored echo clones from the mirrored source`() {
        // Mirror negates a DIRECTIONAL stamp's dx, and an echo stamp's dx
        // is not a drag but the clone offset — so it is worth checking
        // that the twin grafts from the reflection of the source region
        // rather than from somewhere arbitrary. It does, and for a reason
        // that generalizes: reflection is linear, so reflecting both the
        // brush position and the offset reflects the point they define.
        val delta = EchoOffset.delta(startU = 0.7f, startV = 0.4f, anchorU = 0.3f, anchorV = 0.55f)
        val stamp = Stamp(cx = 0.72f, cy = 0.42f, dx = delta.first, dy = delta.second)

        val twin = BrushTool.ECHO.mirrorStamp(stamp)
        val source = EchoOffset.sourceOf(stamp.cx, stamp.cy, delta)
        val twinSource = EchoOffset.sourceOf(twin.cx, twin.cy, Pair(twin.dx, twin.dy))

        assertEquals(1f - source.first, twinSource.first, 1e-6f)
        assertEquals(source.second, twinSource.second, 1e-6f)
    }
}
