package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the five kernels added for proposals 0001, 0003, 0010,
 * 0013 and 0014 — Vortex/Unwind, Melt, Comb, Pond and Fault.
 *
 * These pin what each tool *does*, not how it is written: the shader is
 * a transliteration of `DisplacementField`, so a property proven here is
 * the only thing standing between a retuned constant and a tool that
 * quietly stops meaning what its name says.
 */
class SecondWaveKernelsTest {

    private val w = 64
    private val h = 64

    private fun field(aspect: Float = 1f) = DisplacementField(w, h, aspect)

    private fun stamp(
        tool: BrushTool,
        cx: Float = 0.5f,
        cy: Float = 0.5f,
        dx: Float = 0f,
        dy: Float = 0f,
        radius: Float = 0.25f,
        strength: Float = 1f,
    ): DisplacementField = field().also {
        it.applyStamp(Stroke(tool, radius, strength, emptyList()), Stamp(cx, cy, dx, dy))
    }

    private fun at(f: DisplacementField, u: Float, v: Float) =
        Pair(f.sampleX(u, v), f.sampleY(u, v))

    // ---- Vortex --------------------------------------------------------

    @Test
    fun `vortex displaces tangentially, not toward or away from the center`() {
        val f = stamp(BrushTool.VORTEX, dx = BrushTool.VORTEX.chirality)
        // Directly right of the center, a pure rotation moves content
        // vertically: the radial component must be ~0.
        val (dx, dy) = at(f, 0.7f, 0.5f)
        assertTrue("expected a tangential push, got ($dx, $dy)", abs(dy) > 1e-5f)
        assertTrue("radial leak $dx too large next to $dy", abs(dx) < abs(dy) * 0.05f)
    }

    @Test
    fun `unwind turns the opposite way from vortex`() {
        val cw = at(stamp(BrushTool.VORTEX, dx = BrushTool.VORTEX.chirality), 0.7f, 0.5f)
        val ccw = at(stamp(BrushTool.UNWIND, dx = BrushTool.UNWIND.chirality), 0.7f, 0.5f)
        // Both components, not just the dominant one: negating only y
        // would still pass a y-only assertion while leaving a radial
        // asymmetry between the two directions.
        assertEquals(-cw.first, ccw.first, 1e-7f)
        assertEquals(-cw.second, ccw.second, 1e-7f)
    }

    @Test
    fun `a mirrored vortex counter-rotates`() {
        // The whole reason chirality lives in the stamp's dx: mirrorStamp
        // negates dx for modes that carry a direction, so the reflected
        // whirlpool turns the other way with no mirror-specific code.
        // Getting this wrong gives two twins spinning the SAME way, which
        // is what a two-shader-mode design would have produced.
        val s = Stamp(0.3f, 0.5f, BrushTool.VORTEX.chirality, 0f)
        val twin = BrushTool.VORTEX.mirrorStamp(s)
        assertEquals(0.7f, twin.cx, 1e-6f)
        assertTrue("chirality must flip under mirror", twin.dx * s.dx < 0f)
    }

    @Test
    fun `vortex is round in pixels on a non-square image`() {
        val aspect = 2f
        val f = DisplacementField(w, h, aspect)
        f.applyStamp(
            Stroke(BrushTool.VORTEX, 0.25f, 1f, emptyList()),
            Stamp(0.5f, 0.5f, BrushTool.VORTEX.chirality, 0f),
        )
        // Two points at equal ASPECT-space distance from the center must
        // see equal-magnitude displacement. Rotating in UV space instead
        // would make one of them stronger.
        val r = 0.15f
        val right = at(f, 0.5f + r / aspect, 0.5f)
        val below = at(f, 0.5f, 0.5f + r)
        val mRight = sqrt(right.first * right.first * aspect * aspect + right.second * right.second)
        val mBelow = sqrt(below.first * below.first * aspect * aspect + below.second * below.second)
        assertEquals(mRight, mBelow, mRight * 0.02f)
    }

    // ---- Melt ----------------------------------------------------------

    @Test
    fun `melt moves content downward`() {
        // A positive recorded dy is a downward RUN: the DIRECTIONAL kernel
        // negates it, so the field samples from higher up and the content
        // appears lower. Backwards would be a brush that pulls upward, in
        // the one tool whose entire premise is gravity.
        val f = stamp(BrushTool.MELT, dy = 0.01f)
        val (_, dy) = at(f, 0.5f, 0.5f)
        assertTrue("expected a negative field dy, got $dy", dy < 0f)
    }

    @Test
    fun `melt reaches further below the stamp than above it`() {
        val f = stamp(BrushTool.MELT, dy = 0.01f, radius = 0.15f)
        val above = abs(at(f, 0.5f, 0.5f - 0.2f).second)
        val below = abs(at(f, 0.5f, 0.5f + 0.2f).second)
        assertTrue("above=$above should be ~0 beyond the lobe", above < 1e-6f)
        assertTrue("below=$below should still be pulling", below > 1e-5f)
    }

    @Test
    fun `the drip lobe extent matches the constant the scissor derives from`() {
        // StampBounds sizes the melt rect as radius / DRIP_LOBE. If the
        // kernel and the rect ever disagree the wax gets clipped, so this
        // pins the relationship rather than the number.
        val radius = 0.15f
        val reach = radius / BrushDynamics.DRIP_LOBE
        val f = stamp(BrushTool.MELT, dy = 0.01f, radius = radius)
        val justInside = abs(at(f, 0.5f, 0.5f + reach * 0.9f).second)
        val justOutside = abs(at(f, 0.5f, 0.5f + reach * 1.1f).second)
        assertTrue("expected pull at 90% of reach, got $justInside", justInside > 1e-6f)
        assertTrue("expected nothing past reach, got $justOutside", justOutside < 1e-6f)
    }

    // ---- Comb ----------------------------------------------------------

    @Test
    fun `comb alternates across the drag axis instead of pushing uniformly`() {
        // One horizontal drag; sample a line ACROSS it. A plain Smear
        // would vary smoothly and monotonically away from the center —
        // Comb must change direction of variation several times.
        val f = stamp(BrushTool.COMB, dx = 0.02f, radius = 0.4f)
        val samples = (0..40).map { i ->
            abs(at(f, 0.5f, 0.12f + 0.019f * i).first)
        }
        var reversals = 0
        for (i in 1 until samples.size - 1) {
            val rising = samples[i] > samples[i - 1]
            val nextRising = samples[i + 1] > samples[i]
            if (rising != nextRising) reversals++
        }
        assertTrue("expected several teeth across the brush, got $reversals", reversals >= 4)
    }

    @Test
    fun `comb with no drag direction does nothing`() {
        // No axis means no teeth; a tap must be a no-op rather than a
        // division by a zero-length delta.
        val f = stamp(BrushTool.COMB, dx = 0f, dy = 0f)
        val (dx, dy) = at(f, 0.5f, 0.5f)
        assertEquals(0f, dx, 1e-9f)
        assertEquals(0f, dy, 1e-9f)
    }

    // ---- Pond ----------------------------------------------------------

    @Test
    fun `a ripple alternates sign with radius and returns to zero at the rim`() {
        val radius = 0.3f
        val f = stamp(BrushTool.POND, radius = radius)
        // Radial component along a horizontal ray out of the center.
        val radial = (1..29).map { i ->
            val d = i / 30f
            at(f, 0.5f + radius * d, 0.5f).first
        }
        val signChanges = (1 until radial.size).count {
            radial[it] != 0f && radial[it - 1] != 0f &&
                (radial[it] > 0f) != (radial[it - 1] > 0f)
        }
        assertTrue("expected alternating bands, got $signChanges sign changes", signChanges >= 2)
        val atRim = abs(at(f, 0.5f + radius * 0.999f, 0.5f).first)
        assertTrue("ripple must vanish at the rim, got $atRim", atRim < 1e-5f)
    }

    @Test
    fun `a ripple is finite and still at its exact center`() {
        // The radial direction is undefined at the center; the ramp that
        // exists for Grow and Shrink has to cover this kernel too.
        val f = stamp(BrushTool.POND)
        val (dx, dy) = at(f, 0.5f, 0.5f)
        assertTrue(dx.isFinite() && dy.isFinite())
        assertTrue("center must not blow up", abs(dx) < 1e-3f && abs(dy) < 1e-3f)
    }

    // ---- Fault ---------------------------------------------------------

    @Test
    fun `a fault moves its two sides in opposite directions`() {
        // Horizontal seam: above and below must slide opposite ways along
        // it. This is the property that makes it a fault rather than a
        // smear with a soft edge.
        val f = stamp(BrushTool.FAULT, dx = 0.02f, radius = 0.3f)
        val above = at(f, 0.5f, 0.42f).first
        val below = at(f, 0.5f, 0.58f).first
        assertTrue("above=$above below=$below should oppose", above * below < 0f)
    }

    @Test
    fun `drawing the same seam backwards makes the same fault`() {
        // Both the tangent and the side function flip, and the two sign
        // changes cancel. Without that, which way the user happened to
        // drag would silently decide the result.
        val forward = stamp(BrushTool.FAULT, dx = 0.02f, radius = 0.3f)
        val backward = stamp(BrushTool.FAULT, dx = -0.02f, radius = 0.3f)
        for (v in listOf(0.42f, 0.5f, 0.58f)) {
            assertEquals(
                "seam direction must not change the fault at v=$v",
                forward.sampleX(0.5f, v),
                backward.sampleX(0.5f, v),
                1e-7f,
            )
        }
    }

    @Test
    fun `a fault is continuous across the seam itself`() {
        // smoothOdd is zero on the line, so the two sides meet rather
        // than tearing — a continuous field cannot open a gap, and the
        // product copy depends on that being true.
        val f = stamp(BrushTool.FAULT, dx = 0.02f, radius = 0.3f)
        val onSeam = abs(at(f, 0.5f, 0.5f).first)
        assertTrue("seam displacement should vanish, got $onSeam", onSeam < 1e-5f)
    }

    // ---- Shared invariants ---------------------------------------------

    @Test
    fun `every new kernel leaves the field finite`() {
        val tools = listOf(
            BrushTool.VORTEX, BrushTool.UNWIND, BrushTool.MELT,
            BrushTool.COMB, BrushTool.POND, BrushTool.FAULT,
        )
        for (tool in tools) {
            for (aspect in listOf(1f, 16f / 9f, 9f / 16f)) {
                val f = DisplacementField(w, h, aspect)
                val stroke = Stroke(tool, 0.3f, 1f, emptyList())
                // Include the exact center and the corners, where radial
                // directions and normalizations are undefined.
                for (c in listOf(0.5f to 0.5f, 0f to 0f, 1f to 1f)) {
                    f.applyStamp(stroke, Stamp(c.first, c.second, tool.chirality, 0.01f))
                }
                assertTrue("$tool at aspect $aspect produced a non-finite field",
                    f.data.all { it.isFinite() })
            }
        }
    }
}
