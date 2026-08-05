package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral pins for the four new stamp modes on the CPU reference —
 * the properties a KPT-correct implementation must have, independent of
 * exact rate constants.
 */
class BrushModeBehaviorTest {

    private fun field() = DisplacementField(width = 64, height = 64, aspect = 1f)

    private fun stroke(tool: BrushTool, strength: Float = 1f) = Stroke(
        tool = tool,
        radius = 0.4f,
        strength = strength * tool.strengthScale,
        stamps = emptyList(),
    )

    private val center = Stamp(0.5f, 0.5f, 0f, 0f)

    @Test
    fun `grow magnifies - samples pull toward the stamp center`() {
        val f = field()
        repeat(30) { f.applyStamp(stroke(BrushTool.GROW), center) }
        // A point right of center must fetch its pixel from nearer the
        // center (backward mapping): u + D.x < u.
        val (sx, _) = f.warpedSource(0.62f, 0.5f)
        assertTrue(sx < 0.62f - 1e-4f, "expected pull toward center, got $sx")
        // And symmetrically on the left.
        val (sxL, _) = f.warpedSource(0.38f, 0.5f)
        assertTrue(sxL > 0.38f + 1e-4f)
    }

    @Test
    fun `shrink pinches - samples push away from the stamp center`() {
        val f = field()
        repeat(30) { f.applyStamp(stroke(BrushTool.SHRINK), center) }
        val (sx, _) = f.warpedSource(0.62f, 0.5f)
        assertTrue(sx > 0.62f + 1e-4f, "expected push away from center, got $sx")
    }

    @Test
    fun `grow is rotationally symmetric on a square field`() {
        val f = field()
        repeat(10) { f.applyStamp(stroke(BrushTool.GROW), center) }
        val right = f.warpedSource(0.6f, 0.5f).first - 0.6f
        val below = f.warpedSource(0.5f, 0.6f).second - 0.6f
        assertEquals(right, below, 2e-3f)
    }

    @Test
    fun `ungoo decays displacement toward identity`() {
        val f = field()
        val smear = Stroke(BrushTool.SMEAR, radius = 0.4f, strength = 1f, stamps = emptyList())
        f.applyStamp(smear, Stamp(0.5f, 0.5f, 0.08f, 0f))
        val before = abs(f.sampleX(0.5f, 0.5f))
        assertTrue(before > 0.01f)
        repeat(40) { f.applyStamp(stroke(BrushTool.UNGOO), center) }
        val after = abs(f.sampleX(0.5f, 0.5f))
        assertTrue(after < before * 0.05f, "ungoo left $after of $before")
    }

    @Test
    fun `ungoo leaves the field outside its radius untouched`() {
        val f = field()
        val smear = Stroke(BrushTool.SMEAR, radius = 0.15f, strength = 1f, stamps = emptyList())
        f.applyStamp(smear, Stamp(0.15f, 0.15f, 0.05f, 0f))
        val corner = f.sampleX(0.15f, 0.15f)
        // Erase far away from the smear.
        repeat(10) {
            f.applyStamp(
                Stroke(BrushTool.UNGOO, radius = 0.15f, strength = 1f, stamps = emptyList()),
                Stamp(0.85f, 0.85f, 0f, 0f),
            )
        }
        assertEquals(corner, f.sampleX(0.15f, 0.15f), 1e-6f)
    }

    @Test
    fun `smooth reduces a sharp bump without erasing it`() {
        val f = field()
        // Sharp synthetic bump: one texel row of displacement.
        val ix = 32
        val iy = 32
        f.data[(iy * 64 + ix) * 2] = 0.1f
        val peakBefore = f.sampleX(0.5f + 0.5f / 64, 0.5f + 0.5f / 64)
        var sumBefore = 0f
        for (i in f.data.indices step 2) sumBefore += abs(f.data[i])
        repeat(15) { f.applyStamp(stroke(BrushTool.SMOOTH), center) }
        val peakAfter = abs(f.sampleX(0.5f + 0.5f / 64, 0.5f + 0.5f / 64))
        var sumAfter = 0f
        for (i in f.data.indices step 2) sumAfter += abs(f.data[i])
        assertTrue(peakAfter < peakBefore * 0.6f, "peak $peakBefore -> $peakAfter")
        assertTrue(sumAfter > sumBefore * 0.25f, "smooth should spread, not erase")
    }

    @Test
    fun `radial stamps write no NaN at their exact center`() {
        val f = field()
        repeat(5) { f.applyStamp(stroke(BrushTool.GROW), center) }
        f.data.forEach { assertTrue(it.isFinite()) }
    }

    @Test
    fun `strength scales pump rate`() {
        val strong = field()
        val weak = field()
        repeat(10) { strong.applyStamp(stroke(BrushTool.GROW, strength = 1f), center) }
        repeat(10) { weak.applyStamp(stroke(BrushTool.GROW, strength = 0.3f), center) }
        val strongPull = abs(strong.warpedSource(0.62f, 0.5f).first - 0.62f)
        val weakPull = abs(weak.warpedSource(0.62f, 0.5f).first - 0.62f)
        assertTrue(strongPull > weakPull * 2f)
    }

    @Test
    fun `directional replay is unchanged by the mode refactor`() {
        // The PR-2 golden: a known smear still produces the same field.
        val f = field()
        val smear = Stroke(BrushTool.SMEAR, radius = 0.3f, strength = 1f, stamps = emptyList())
        f.applyStamp(smear, Stamp(0.5f, 0.5f, 0.04f, 0.02f))
        // Center texel: full weight, b = (-0.04, -0.02), empty prior field.
        assertEquals(-0.04f, f.sampleX(0.5f, 0.5f), 1e-3f)
        assertEquals(-0.02f, f.sampleY(0.5f, 0.5f), 1e-3f)
    }
}
