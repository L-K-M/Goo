package ch.lkmc.goo.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GOOvie segment curves (proposal 0011).
 *
 * The properties that matter are the endpoints — a segment must start
 * and end ON its keyframes whatever it does between them — and the
 * bound on how far BOING is allowed to extrapolate, because past some
 * overshoot the field self-intersects and the picture tears instead of
 * exaggerating.
 */
class EasingTest {

    /** The revision an untouched document sits at. */
    private fun emptyRevision(): StrokeRevision = StrokeLog().currentRevision

    @Test
    fun `every curve starts and ends exactly on its keyframes`() {
        // Not "within a tolerance": a segment that ended at 0.999 would
        // leave the strip showing something that is not the pose the
        // user punched.
        for (e in Easing.entries) {
            assertEquals("$e at 0", 0f, e.at(0f), 0f)
            assertEquals("$e at 1", 1f, e.at(1f), 0f)
        }
    }

    @Test
    fun `progress outside the segment is clamped, not extrapolated by accident`() {
        for (e in Easing.entries) {
            assertEquals("$e below", 0f, e.at(-0.5f), 0f)
            assertEquals("$e above", 1f, e.at(1.5f), 0f)
        }
    }

    @Test
    fun `linear is the identity`() {
        for (i in 0..10) {
            val p = i / 10f
            assertEquals(p, Easing.LINEAR.at(p), 1e-7f)
        }
    }

    @Test
    fun `linear and ease never leave the keyframe pair`() {
        // Only BOING extrapolates. If EASE ever did, it would be
        // overshooting without anyone asking for it.
        for (i in 0..20) {
            val p = i / 20f
            for (e in listOf(Easing.LINEAR, Easing.EASE)) {
                val t = e.at(p)
                assertTrue("$e at $p produced $t", t in 0f..1f)
            }
        }
    }

    @Test
    fun `boing actually overshoots and then comes back`() {
        val samples = (0..100).map { Easing.BOING.at(it / 100f) }
        val peak = samples.max()
        assertTrue("expected an overshoot, peaked at $peak", peak > 1f)
        // Descending INTO the pose, not still climbing at the end. The
        // obvious assertion here — last() <= peak — is tautological,
        // since last() is exactly 1 and peak is above it, so it would
        // pass for a curve that rose all the way to the endpoint and
        // then dropped.
        assertTrue("must be settling, not still climbing", samples[99] > samples.last())
        assertEquals(1f, samples.last(), 0f)
    }

    @Test
    fun `boing is visibly different from ease, not just arithmetically`() {
        // A curve named Boing has to READ as one. An overshoot of a few
        // percent is an overshoot by the numbers and indistinguishable
        // from EASE on screen, which is the failure this pins: the peak
        // must clear the pose by an amount someone would notice.
        val peak = (0..1000).maxOf { Easing.BOING.at(it / 1000f) }
        assertTrue("peak $peak is too subtle to read as a bounce", peak >= 1.08f)
        // And it must differ from EASE somewhere obvious, not only at
        // the peak.
        val biggestGap = (0..100).maxOf {
            val p = it / 100f
            kotlin.math.abs(Easing.BOING.at(p) - Easing.EASE.at(p))
        }
        assertTrue("BOING and EASE are near-identical (max gap $biggestGap)", biggestGap > 0.1f)
    }

    @Test
    fun `the overshoot stays inside the tested bound`() {
        // The rail behind OVERSHOOT. There is no general bound on how
        // far a field can be extrapolated before it folds — it depends
        // on what the user painted — so the constant is fixed and
        // playtested rather than exposed, and this is what pins it.
        val peak = (0..1000).maxOf { Easing.BOING.at(it / 1000f) }
        assertTrue("peak $peak exceeds MAX_T", peak <= Easing.MAX_T)
    }

    @Test
    fun `the tween may leave the pair but the levers may not`() {
        // The asymmetry that keeps Boing safe: mix() extrapolating a
        // FIELD is the effect, while running the lever lerp past 1 would
        // push a lever outside [-1, 1], where the analytic warps were
        // never characterized.
        var sawOvershoot = false
        for (i in 0..100) {
            val p = i / 100f
            val tween = tweenProgress(p, Easing.BOING)
            val lever = leverProgress(p, Easing.BOING)
            if (tween > 1f) sawOvershoot = true
            assertTrue("lever $lever left [0,1] at $p", lever in 0f..1f)
            assertTrue("tween $tween exceeded the rail at $p", tween <= Easing.MAX_T)
        }
        assertTrue("the whole point is that the tween overshoots", sawOvershoot)
    }

    @Test
    fun `old projects default to the behaviour they were made with`() {
        // Every GOOvie punched before this feature existed was linear,
        // and must still be.
        assertEquals(Easing.LINEAR, Easing.DEFAULT)
        val k = Keyframe(emptyRevision(), GlobalParams())
        assertEquals(Easing.LINEAR, k.easing)
    }

    @Test
    fun `a curve travels with the keyframe it leaves`() {
        // Easing lives on the pin rather than in a parallel segment list
        // because a segment has no identity — reordering the strip
        // reorders pins, and the curve should follow the pose it leaves.
        val k = Keyframe(emptyRevision(), GlobalParams(), Easing.BOING)
        assertEquals(Easing.BOING, k.copy(globals = GlobalParams(bulge = 1f)).easing)
    }
}
