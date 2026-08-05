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
    fun `movement shorter than one spacing produces no stamps`() {
        val out = stampsFor(1f, listOf(0.5f to 0.5f, 0.5f to (0.5f + spacing * 0.9f)))
        assertEquals(0, out.size)
    }

    @Test
    fun `stamps are evenly spaced along a straight drag`() {
        val out = stampsFor(1f, listOf(0.2f to 0.5f, 0.6f to 0.5f))
        // 0.4 of travel at spacing 0.025 -> 16 stamps ideally; float
        // representation of the endpoints may shave the last one off.
        assertTrue(out.size in 15..16, "got ${out.size} stamps")
        out.zipWithNext { a, b ->
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
        assertTrue(out.isNotEmpty())
        out.forEach { s ->
            // Diagonal drag: dx == dy > 0, magnitude = spacing/√2 per axis.
            assertEquals(s.dx, s.dy, 1e-6f)
            assertTrue(s.dx > 0)
            assertEquals(spacing / sqrt(2f), abs(s.dx), 1e-5f)
        }
    }
}
