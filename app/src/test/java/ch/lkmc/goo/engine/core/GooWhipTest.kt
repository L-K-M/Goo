package ch.lkmc.goo.engine.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The whip's ballistic tail (proposal 0015).
 *
 * The tail is generated once at release and stored as ordinary stamps,
 * so what these tests protect is a mark that gets baked into a document
 * — an over-long throw or a jitter-dependent path is not a transient
 * glitch but a permanent one, reproduced faithfully by every later
 * replay and export.
 */
class GooWhipTest {

    private val square = 1f
    private val wide = 16f / 9f

    /** Total aspect-space length of a tail launched from (u, v). */
    private fun reach(u: Float, v: Float, velU: Float, velV: Float, aspect: Float): Float {
        val points = GooWhip.tail(u, v, velU, velV, aspect)
        if (points.isEmpty()) return 0f
        val (lu, lv) = points.last()
        return hypot((lu - u) * aspect, lv - v)
    }

    @Test
    fun `a slow lift leaves no tail`() {
        // A tap and a gentle release must make no mark of their own, or
        // every ordinary smear would end with a flick on it.
        assertTrue(GooWhip.tail(0.5f, 0.5f, 0f, 0f, square).isEmpty())
        val justUnder = GooWhip.START_SPEED * 0.9f
        assertTrue(GooWhip.tail(0.5f, 0.5f, justUnder, 0f, square).isEmpty())
    }

    @Test
    fun `a flick past the threshold throws something`() {
        val justOver = GooWhip.START_SPEED * 1.5f
        assertTrue(GooWhip.tail(0.5f, 0.5f, justOver, 0f, square).isNotEmpty())
    }

    @Test
    fun `the threshold survives quantization`() {
        // Rounding can carry a release that just cleared START_SPEED back
        // under it. Whatever does get thrown must be thrown at a speed
        // the tool considers fast enough to throw.
        for (step in 0..40) {
            val raw = GooWhip.START_SPEED * (0.5f + step * 0.05f)
            if (GooWhip.tail(0.5f, 0.5f, raw, 0f, square).isEmpty()) continue
            assertTrue(
                GooWhip.quantize(raw) >= GooWhip.START_SPEED,
                "threw at ${GooWhip.quantize(raw)}, under the floor",
            )
        }
    }

    @Test
    fun `the tail runs in the direction of the flick`() {
        val points = GooWhip.tail(0.4f, 0.5f, 3f, -2f, square)
        assertTrue(points.isNotEmpty())
        val (lu, lv) = points.last()
        assertTrue(lu > 0.4f, "a rightward flick must travel right")
        assertTrue(lv < 0.5f, "an upward flick must travel up")
    }

    @Test
    fun `a harder flick throws further, up to the cap`() {
        val soft = reach(0.5f, 0.5f, 1.5f, 0f, square)
        val hard = reach(0.5f, 0.5f, 4f, 0f, square)
        assertTrue(hard > soft, "harder flick reached $hard vs $soft")

        // Past the cap it stops growing: a whip is a mark, not a physics
        // toy, and a violent flick must not fling content off the far
        // edge of a picture the user then has to undo.
        val atCap = reach(0.5f, 0.5f, GooWhip.MAX_SPEED, 0f, square)
        val absurd = reach(0.5f, 0.5f, GooWhip.MAX_SPEED * 20f, 0f, square)
        assertEquals(atCap, absurd, 1e-5f)
    }

    @Test
    fun `the tail always terminates`() {
        // The decay stops the walk long before the ceiling, but the
        // ceiling is what guarantees no arithmetic accident can put an
        // unbounded stroke into the document.
        for (speed in listOf(1f, 3f, 8f, 1000f)) {
            val points = GooWhip.tail(0.5f, 0.5f, speed, speed, square)
            assertTrue(
                points.size <= GooWhip.MAX_STEPS,
                "speed $speed produced ${points.size} steps",
            )
        }
    }

    @Test
    fun `the tail decelerates rather than running at a constant speed`() {
        // "Snapping a wet ribbon, not waiting for an animation": the
        // steps must crowd together toward the end, which is what makes
        // the goo thin into a point instead of stopping flat.
        val points = GooWhip.tail(0.3f, 0.5f, 5f, 0f, square)
        assertTrue(points.size > 4)
        val first = points[1].first - points[0].first
        val last = points.last().first - points[points.size - 2].first
        assertTrue(last < first * 0.7f, "steps did not shorten: $first then $last")
    }

    @Test
    fun `the same flick makes the same mark`() {
        // The reason the speed is quantized. Pointer timestamps jitter,
        // so two flicks a human cannot tell apart measure differently;
        // rounding means a mark you liked is one you can repeat.
        val a = GooWhip.tail(0.5f, 0.5f, 3.00f, 1f, square)
        val b = GooWhip.tail(0.5f, 0.5f, 3.01f, 1.0033f, square)
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].first, b[i].first, 2e-3f)
            assertEquals(a[i].second, b[i].second, 2e-3f)
        }
    }

    @Test
    fun `quantizing caps and rounds but never inverts`() {
        assertEquals(GooWhip.MAX_SPEED, GooWhip.quantize(GooWhip.MAX_SPEED * 3f))
        assertEquals(0f, GooWhip.quantize(0f))
        assertTrue(GooWhip.quantize(2.6f) >= 0f)
        // Rounding lands on a multiple of the quantum.
        val q = GooWhip.quantize(2.6f) / GooWhip.SPEED_QUANTUM
        assertEquals(q, kotlin.math.round(q), 1e-4f)
    }

    @Test
    fun `a flick throws the same distance whichever way it points`() {
        // Speed is measured in aspect space, where circles are round, so
        // a horizontal flick on a 16:9 photo must not out-throw an
        // identical vertical one.
        val horizontal = reach(0.5f, 0.5f, 3f / wide, 0f, wide)
        val vertical = reach(0.5f, 0.5f, 0f, 3f, wide)
        assertEquals(horizontal, vertical, 1e-4f)
    }

    @Test
    fun `the aim of a flick survives quantization`() {
        // Quantizing the SPEED must not rotate the throw: the direction
        // is what the user aimed, and the magnitude is what gets rounded.
        val velU = 2.7f
        val velV = -1.3f
        val points = GooWhip.tail(0.5f, 0.5f, velU, velV, square)
        val (lu, lv) = points.last()
        val flick = velV / velU
        val thrown = (lv - 0.5f) / (lu - 0.5f)
        assertEquals(flick, thrown, 1e-4f)
    }

    @Test
    fun `a non-positive aspect is rejected rather than thrown into`() {
        assertFails { GooWhip.tail(0.5f, 0.5f, 3f, 0f, 0f) }
        assertFails { GooWhip.tail(0.5f, 0.5f, 3f, 0f, -1f) }
    }

    @Test
    fun `a tail becomes ordinary stamps and nothing else`() {
        // The architectural claim: input timing chooses the mark once,
        // the resampler turns it into stamps, and the log stores the
        // answer. Replay must never see a velocity — a tail regenerated
        // at export time would depend on input timing that no longer
        // exists, and would be a different mark every render.
        val radius = 0.12f
        val resampler = StrokeResampler(radius = radius, aspect = square)
        resampler.begin(0.3f, 0.5f)
        val stamps = mutableListOf<Stamp>()
        for ((u, v) in GooWhip.tail(0.3f, 0.5f, 4f, 0f, square)) {
            resampler.extend(u, v, stamps)
        }
        assertTrue(stamps.isNotEmpty(), "a hard flick must land stamps")

        // And they obey the resampler's ordinary contract, so the tail is
        // indistinguishable from a very fast drag.
        for (i in 1 until stamps.size) {
            assertEquals(stamps[i].cx - stamps[i - 1].cx, stamps[i].dx, 1e-5f)
            assertEquals(stamps[i].cy - stamps[i - 1].cy, stamps[i].dy, 1e-5f)
        }

        // A stroke made of them replays deterministically, which is what
        // undo and full-resolution export both rely on.
        val stroke = Stroke(BrushTool.WHIP, radius, 1f, stamps.toList())
        val a = DisplacementField(48, 48, square).apply { replay(listOf(stroke)) }
        val b = DisplacementField(48, 48, square).apply { replay(listOf(stroke)) }
        assertTrue(a.data.contentEquals(b.data))
    }

    @Test
    fun `whip is a directional brush and needs no kernel of its own`() {
        assertEquals(StampMode.DIRECTIONAL, BrushTool.WHIP.mode)
        assertEquals(FalloffProfile.FEATHER, BrushTool.WHIP.profile)
        assertTrue(!BrushTool.WHIP.pumped, "a whip is a drag, not a hold")
        assertTrue(!BrushTool.WHIP.stampsOnDown, "a tap must not whip")
    }
}
