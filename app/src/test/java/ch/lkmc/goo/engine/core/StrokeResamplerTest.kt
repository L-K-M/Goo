package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrokeResamplerTest {

    private val radius = 0.1f
    private val spacing = StrokeResampler.SPACING_FRACTION * radius

    private fun stampsFor(aspect: Float, path: List<Pair<Float, Float>>): List<Stamp> {
        val r = StrokeResampler(radius = radius, aspect = aspect)
        r.begin(path.first().first, path.first().second)
        val out = mutableListOf<Stamp>()
        for ((u, v) in path.drop(1)) r.extend(u, v, out)
        return out
    }

    @Test
    fun `non-positive radius or aspect is rejected at construction`() {
        // A zero radius would otherwise spin extend()'s spacing walk
        // forever on the main thread.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            StrokeResampler(radius = 0f, aspect = 1f)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            StrokeResampler(radius = -0.1f, aspect = 1f)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            StrokeResampler(radius = 0.1f, aspect = 0f)
        }
    }

    @Test
    fun `stationary finger produces no stamps`() {
        assertEquals(0, stampsFor(1f, listOf(0.5f to 0.5f, 0.5f to 0.5f)).size)
    }

    @Test
    fun `movement shorter than the first travel produces no stamps`() {
        // A stationary finger must drag nothing. That is the property;
        // the DISTANCE it takes used to be a full spacing, which is what
        // made the brush feel like it ignored the first 30-80px of every
        // stroke.
        val out = stampsFor(
            1f,
            listOf(0.5f to 0.5f, 0.5f to (0.5f + StrokeResampler.FALLBACK_FIRST_TRAVEL * 0.9f)),
        )
        assertEquals(0, out.size)
    }

    @Test
    fun `the first stamp arrives long before a full spacing`() {
        // The complaint this answers: with the first stamp gated on a
        // full spacing, a default brush needed 0.03 of image height —
        // tens of pixels — before anything moved.
        val out = stampsFor(1f, listOf(0.5f to 0.5f, 0.5f to (0.5f + spacing * 0.5f)))
        assertEquals(1, out.size, "half a spacing of travel must already draw")
        assertEquals(0.5f + StrokeResampler.FALLBACK_FIRST_TRAVEL, out.single().cy, 1e-6f)
    }

    @Test
    fun `only the FIRST interval is short`() {
        val out = stampsFor(1f, listOf(0.5f to 0.2f, 0.5f to 0.6f))
        assertTrue(out.size > 3)
        assertEquals(0.2f + StrokeResampler.FALLBACK_FIRST_TRAVEL, out[0].cy, 1e-6f)
        // Everything after it is back to the steady-state spacing, which
        // is what keeps the kernels overlapping into a crease-free
        // trough.
        out.drop(1).zipWithNext().forEach { (a, b) ->
            assertEquals(spacing, b.cy - a.cy, 1e-5f)
        }
    }

    @Test
    fun `a fat brush starts as promptly as a thin one`() {
        // Fixed distance rather than a fraction of the radius: at the
        // largest brush a proportional gate meant 0.07 of image height
        // before the first stamp.
        for (r in listOf(0.04f, 0.12f, 0.28f)) {
            val res = StrokeResampler(radius = r, aspect = 1f)
            res.begin(0.5f, 0.2f)
            val out = res.extend(0.5f, 0.2f + StrokeResampler.FALLBACK_FIRST_TRAVEL * 1.01f, mutableListOf())
            assertEquals(1, out.size, "radius $r did not start promptly")
        }
    }

    @Test
    fun `a tiny brush never waits longer for its first stamp than its second`() {
        // The min() cap. A brush small enough that its spacing is
        // under FIRST_TRAVEL must not have its first stamp arrive after
        // its second would have.
        val tiny = 0.004f
        val tinySpacing = StrokeResampler.SPACING_FRACTION * tiny
        assertTrue(tinySpacing < StrokeResampler.FALLBACK_FIRST_TRAVEL, "pick a smaller radius")
        val res = StrokeResampler(radius = tiny, aspect = 1f)
        res.begin(0.5f, 0.2f)
        val out = res.extend(0.5f, 0.2f + tinySpacing * 1.01f, mutableListOf())
        assertEquals(1, out.size)
    }

    // ---- The gate means a fixed number of DP, not of image ---------------

    @Test
    fun `zooming in shrinks the gate in image space, not in pixels`() {
        // THE point of the conversion. One aspect unit is one image
        // height, so at 4x an image height covers four times the pixels
        // and the same physical 2dp is a quarter of the aspect-space
        // distance. Before this, the dead zone grew with zoom — worst
        // exactly when the user is working precisely.
        val fitted = 1100f
        val density = 2.75f
        val at1x = StrokeResampler.firstTravelFor(fitted * 1f, density)
        val at4x = StrokeResampler.firstTravelFor(fitted * 4f, density)
        assertEquals(at1x / 4f, at4x, 1e-9f)
        // And in SCREEN pixels the two are the same distance, which is
        // the property a user actually feels.
        assertEquals(at1x * fitted * 1f, at4x * fitted * 4f, 1e-4f)
    }

    @Test
    fun `a denser screen asks for more pixels, so the feel is constant`() {
        val fitted = 1100f
        val lo = StrokeResampler.firstTravelFor(fitted, density = 1f)
        val hi = StrokeResampler.firstTravelFor(fitted, density = 3f)
        assertEquals(lo * 3f, hi, 1e-9f)
    }

    @Test
    fun `a bigger screen does not mean a longer wait`() {
        // A tablet draws the photo taller in pixels. Under the old flat
        // fraction that meant a longer physical drag for the same
        // effect; now the same 2dp buys the same gesture everywhere.
        val phone = StrokeResampler.firstTravelFor(1100f, 2.75f)
        val tablet = StrokeResampler.firstTravelFor(2200f, 2.75f)
        assertEquals(phone * 1100f, tablet * 2200f, 1e-4f)
    }

    @Test
    fun `unknown or degenerate screen geometry falls back rather than dividing`() {
        // An unmeasured view would otherwise give an infinity here, and
        // an infinite gate reads as "the brush stopped working".
        for (bad in listOf(
            0f,
            -1f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
        )) {
            assertEquals(
                StrokeResampler.FALLBACK_FIRST_TRAVEL,
                StrokeResampler.firstTravelFor(bad, 2.75f),
                0f,
                "image height $bad",
            )
            assertEquals(
                StrokeResampler.FALLBACK_FIRST_TRAVEL,
                StrokeResampler.firstTravelFor(1100f, bad),
                0f,
                "density $bad",
            )
        }
    }

    @Test
    fun `the result guard is reachable, so it stays`() {
        // Review called this guard dead code and suggested deleting it,
        // having called it reachable one round earlier. It is reachable,
        // in BOTH directions, and these are the cases — pinned so the
        // question does not need re-litigating.
        //
        // Float.MAX_VALUE is finite and positive, so it clears the input
        // guard, and 2 * MAX overflows the multiply to Infinity.
        assertEquals(
            StrokeResampler.FALLBACK_FIRST_TRAVEL,
            StrokeResampler.firstTravelFor(1100f, Float.MAX_VALUE),
            0f,
            "an overflowing multiply must fall back, not return Infinity",
        )
        // And the other end: a denormal density over a huge height
        // underflows the divide to exactly zero, which would make the
        // first stamp land at the touch point with a zero delta.
        assertEquals(
            StrokeResampler.FALLBACK_FIRST_TRAVEL,
            StrokeResampler.firstTravelFor(Float.MAX_VALUE, Float.MIN_VALUE),
            0f,
            "an underflowing divide must fall back, not return 0",
        )
    }

    @Test
    fun `a non-positive gate is refused at construction`() {
        // Same reasoning as the radius check: a zero would drop a
        // zero-delta stamp at the touch point on every stroke.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            StrokeResampler(radius = 0.1f, aspect = 1f, firstTravel = 0f)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            StrokeResampler(radius = 0.1f, aspect = 1f, firstTravel = Float.NaN)
        }
    }

    @Test
    fun `the gate the caller passes is the gate that is used`() {
        val gate = 0.001f
        val r = StrokeResampler(radius = radius, aspect = 1f, firstTravel = gate)
        r.begin(0.5f, 0.2f)
        val out = r.extend(0.5f, 0.2f + gate * 1.01f, mutableListOf())
        assertEquals(1, out.size)
        assertEquals(0.2f + gate, out.single().cy, 1e-6f)
    }

    @Test
    fun `bringing the first stamp forward adds no warp`() {
        // THE safety property. Each delta is measured from the previous
        // stamp's centre, so the deltas telescope to the path travelled
        // however the path is chopped. An earlier first stamp splits the
        // same total displacement into more pieces rather than adding
        // any — which is why this change makes the brush start sooner
        // without making it stronger.
        val out = stampsFor(1f, listOf(0.2f to 0.5f, 0.6f to 0.5f))
        val total = out.sumOf { it.dx.toDouble() }.toFloat()
        val lastStamp = out.last().cx
        assertEquals(lastStamp - 0.2f, total, 1e-5f)
    }

    @Test
    fun `stamps are evenly spaced along a straight drag`() {
        val out = stampsFor(1f, listOf(0.2f to 0.5f, 0.6f to 0.5f))
        // 0.4 of travel: one stamp at FIRST_TRAVEL, then one every
        // 0.025 — (0.4 - 0.004) / 0.025 = 15.84, so 16 in total. Float
        // representation of the endpoints may shave the last one off.
        //
        // The same count as before the first stamp was brought forward,
        // which is a coincidence of these numbers and not a rule.
        assertTrue(out.size in 15..16, "got ${out.size} stamps")
        // Note these are INTERVALS, not positions, which is why moving
        // the first stamp does not disturb them: every consecutive gap
        // is still exactly one spacing, including the one from the early
        // first stamp to the second.
        out.zipWithNext().forEach { (a, b) ->
            assertEquals(spacing, b.cx - a.cx, 1e-5f)
            assertEquals(0f, b.cy - a.cy, 1e-6f)
        }
    }

    @Test
    fun `spacing is uniform regardless of event chopping`() {
        // Same path, one big segment vs many tiny ones: identical stamps.
        val whole = stampsFor(1f, listOf(0.2f to 0.3f, 0.7f to 0.6f))
        val chopped = stampsFor(
            1f,
            (0..100).map { i ->
                val t = i / 100f
                (0.2f + 0.5f * t) to (0.3f + 0.3f * t)
            },
        )
        assertEquals(whole.size, chopped.size)
        whole.zip(chopped).forEach { (a, b) ->
            assertEquals(a.cx, b.cx, 1e-4f)
            assertEquals(a.cy, b.cy, 1e-4f)
        }
    }

    @Test
    fun `summed stamp displacement tracks finger travel within one spacing`() {
        val out = stampsFor(1f, listOf(0.2f to 0.5f, 0.6f to 0.5f))
        val total = out.map { it.dx }.sum()
        // Each stamp carries exactly one spacing of travel; only the
        // sub-spacing remainder at the stroke's end goes unstamped.
        assertTrue(abs(0.4f - total) <= spacing + 1e-5f, "total $total")
        assertEquals(0f, out.map { it.dy }.sum(), 1e-6f)
    }

    @Test
    fun `aspect ratio makes brush spacing circular in pixels`() {
        // On a 2:1-wide image, a horizontal UV distance counts double in
        // aspect space, so half the UV travel drops the same stamp count
        // as vertical travel of the full length.
        val horizontal = stampsFor(2f, listOf(0.25f to 0.5f, 0.45f to 0.5f))
        val vertical = stampsFor(2f, listOf(0.5f to 0.3f, 0.5f to 0.7f))
        assertEquals(vertical.size, horizontal.size)
    }

    @Test
    fun `stamp deltas follow the path direction`() {
        val out = stampsFor(1f, listOf(0.3f to 0.3f, 0.5f to 0.5f))
        assertTrue(out.size > 1)
        out.forEach { s ->
            // Diagonal drag: dx == dy > 0 for every stamp.
            assertEquals(s.dx, s.dy, 1e-6f)
            assertTrue(s.dx > 0)
        }
        // The first carries only the short opening interval; every one
        // after it carries a full spacing.
        assertEquals(StrokeResampler.FALLBACK_FIRST_TRAVEL / sqrt(2f), abs(out[0].dx), 1e-5f)
        out.drop(1).forEach { assertEquals(spacing / sqrt(2f), abs(it.dx), 1e-5f) }
    }

    @Test
    fun `stamp delta preserves both sides of a crossed corner`() {
        val r = StrokeResampler(radius = radius, aspect = 1f)
        r.begin(0.2f, 0.2f)
        val out = mutableListOf<Stamp>()
        // Move right far enough to spend the short opening interval and
        // one full spacing (stamps at 0.004 and 0.029 of travel), leaving
        // 0.019 of the next interval unspent. Then cross the corner by
        // moving down 0.020, so that interval straddles it.
        r.extend(0.235f, 0.2f, out)
        r.extend(0.235f, 0.22f, out)

        // The straddling stamp is the last one, and it must carry BOTH
        // legs — the whole point of measuring delta from the previous
        // stamp's centre rather than from this segment alone.
        val corner = out.last()
        assertEquals(0.235f, corner.cx, 1e-6f)
        assertEquals(0.219f, corner.cy, 1e-5f)
        assertEquals(0.006f, corner.dx, 1e-5f)
        assertEquals(0.019f, corner.dy, 1e-5f)
    }

    @Test
    fun `corner path is invariant to chopping along its legs`() {
        val simple = stampsFor(
            1f,
            listOf(0.2f to 0.2f, 0.215f to 0.2f, 0.215f to 0.3f),
        )
        val chopped = stampsFor(
            1f,
            listOf(
                0.2f to 0.2f,
                0.205f to 0.2f,
                0.21f to 0.2f,
                0.215f to 0.2f,
                0.215f to 0.225f,
                0.215f to 0.25f,
                0.215f to 0.275f,
                0.215f to 0.3f,
            ),
        )

        assertEquals(simple.size, chopped.size)
        simple.zip(chopped).forEach { (a, b) ->
            assertEquals(a.cx, b.cx, 1e-5f)
            assertEquals(a.cy, b.cy, 1e-5f)
            assertEquals(a.dx, b.dx, 1e-5f)
            assertEquals(a.dy, b.dy, 1e-5f)
        }
    }
}
