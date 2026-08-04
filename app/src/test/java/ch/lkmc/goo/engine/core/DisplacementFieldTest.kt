package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplacementFieldTest {

    private fun smear(radius: Float = 0.2f, strength: Float = 1f) =
        Stroke(BrushTool.SMEAR, radius, strength, stamps = emptyList())

    @Test
    fun `identity field warps nothing`() {
        val field = DisplacementField(16, 16, aspect = 1f)
        val (su, sv) = field.warpedSource(0.3f, 0.7f)
        assertEquals(0.3f, su, 1e-6f)
        assertEquals(0.7f, sv, 1e-6f)
    }

    @Test
    fun `stamp center fetches content from against the drag`() {
        val field = DisplacementField(64, 64, aspect = 1f)
        val stamp = Stamp(cx = 0.5f, cy = 0.5f, dx = 0.05f, dy = 0f)
        field.applyStamp(smear(), stamp)
        // Backward mapping: at the stamp center the falloff is 1, so the
        // shown pixel comes from center − drag delta: content moved with
        // the finger.
        val (su, sv) = field.warpedSource(0.5f, 0.5f)
        assertEquals(0.45f, su, 2e-3f)
        assertEquals(0.5f, sv, 1e-3f)
    }

    @Test
    fun `field is untouched outside the brush radius`() {
        val field = DisplacementField(64, 64, aspect = 1f)
        field.applyStamp(smear(radius = 0.1f), Stamp(0.5f, 0.5f, 0.05f, 0f))
        // 0.25 aspect-space units from center = 2.5 radii away.
        assertEquals(0f, field.sampleX(0.75f, 0.5f), 1e-6f)
        assertEquals(0f, field.sampleY(0.5f, 0.75f), 1e-6f)
    }

    @Test
    fun `strength scales the displacement`() {
        val full = DisplacementField(64, 64, aspect = 1f)
        val half = DisplacementField(64, 64, aspect = 1f)
        val stamp = Stamp(0.5f, 0.5f, 0.04f, 0f)
        full.applyStamp(smear(strength = 1f), stamp)
        half.applyStamp(smear(strength = 0.5f), stamp)
        assertEquals(full.sampleX(0.5f, 0.5f) / 2f, half.sampleX(0.5f, 0.5f), 1e-4f)
    }

    @Test
    fun `aspect keeps the brush footprint circular in pixels`() {
        // Wide image (aspect 2): a UV offset of 0.1 horizontally is twice
        // the aspect-space distance of 0.1 vertically, so with radius 0.15
        // the horizontal probe (0.2 away) is outside, the vertical inside.
        val field = DisplacementField(64, 64, aspect = 2f)
        field.applyStamp(smear(radius = 0.15f), Stamp(0.5f, 0.5f, 0.02f, 0f))
        assertEquals(0f, field.sampleX(0.6f, 0.5f), 1e-6f)
        assertTrue(abs(field.sampleX(0.5f, 0.6f)) > 1e-4f)
    }

    @Test
    fun `replay reproduces incremental stamping exactly`() {
        val incremental = DisplacementField(32, 32, aspect = 1f)
        val stroke1 = Stroke(
            BrushTool.SMEAR, 0.2f, 1f,
            stamps = listOf(Stamp(0.4f, 0.5f, 0.02f, 0f), Stamp(0.45f, 0.5f, 0.02f, 0f)),
        )
        val stroke2 = Stroke(
            BrushTool.SMEAR, 0.15f, 0.8f,
            stamps = listOf(Stamp(0.5f, 0.6f, 0f, 0.03f)),
        )
        for (s in listOf(stroke1, stroke2)) for (st in s.stamps) incremental.applyStamp(s, st)

        val replayed = DisplacementField(32, 32, aspect = 1f)
        replayed.replay(listOf(stroke1, stroke2))

        incremental.data.zip(replayed.data).forEach { (a, b) -> assertEquals(a, b, 1e-7f) }
    }

    @Test
    fun `opposite drags approximately cancel`() {
        val field = DisplacementField(64, 64, aspect = 1f)
        val there = Stamp(0.5f, 0.5f, 0.03f, 0f)
        val back = Stamp(0.5f, 0.5f, -0.03f, 0f)
        field.applyStamp(smear(), there)
        field.applyStamp(smear(), back)
        // Warp-of-warp composition isn't a perfect group inverse, but a
        // small out-and-back must land within a fraction of the drag.
        assertTrue(abs(field.sampleX(0.5f, 0.5f)) < 0.03f * 0.2f)
    }

    @Test
    fun `bilinear sampling clamps at the edges`() {
        val field = DisplacementField(8, 8, aspect = 1f)
        field.data.fill(0.01f)
        // Sampling outside [0,1] must return edge values, never explode.
        assertEquals(0.01f, field.sampleX(-0.5f, 0.5f), 1e-6f)
        assertEquals(0.01f, field.sampleY(0.5f, 1.5f), 1e-6f)
    }
}
