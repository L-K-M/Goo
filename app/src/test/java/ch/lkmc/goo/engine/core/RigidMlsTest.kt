package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Taffy Pins solver prototype (proposal 0016).
 *
 * These tests are the evidence the proposal asks for before the document
 * model is expanded: does bounded rigid MLS actually hold its pins, stay
 * finite at the singularities, and behave the same on a landscape photo
 * as on a portrait one. See `docs/decisions/0004-taffy-pins-prototype.md`
 * for what they concluded.
 */
class RigidMlsTest {

    private val wide = 16f / 9f

    private fun hold(u: Float, v: Float, weight: Float = 1f) =
        MlsControl(u, v, u, v, weight)

    private fun pull(su: Float, sv: Float, tu: Float, tv: Float) =
        MlsControl(su, sv, tu, tv)

    private fun assertNear(
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
        tol: Float,
        what: String = "",
    ) {
        val d = hypot(expected.first - actual.first, expected.second - actual.second)
        assertTrue(d <= tol, "$what expected $expected, got $actual (off by $d)")
    }

    // ---- Interpolation: the property the whole tool rests on -------------

    @Test
    fun `a hold pin does not move`() {
        // "Every explicit hold pin remains fixed while the pull puck
        // reaches its target" — the MVP's first acceptance line.
        val controls = listOf(
            hold(0.3f, 0.4f),
            hold(0.7f, 0.4f),
            pull(0.5f, 0.6f, 0.5f, 0.85f),
        )
        for (c in controls.filter { it.su == it.tu && it.sv == it.tv }) {
            assertNear(
                Pair(c.su, c.sv),
                RigidMls.deform(c.su, c.sv, controls, wide),
                1e-5f,
                "hold pin at (${c.su}, ${c.sv})",
            )
        }
    }

    @Test
    fun `the puck lands exactly where it was dragged`() {
        val controls = listOf(hold(0.2f, 0.2f), hold(0.8f, 0.2f), pull(0.5f, 0.5f, 0.6f, 0.7f))
        assertNear(Pair(0.6f, 0.7f), RigidMls.deform(0.5f, 0.5f, controls, wide), 1e-5f)
    }

    @Test
    fun `the backward map is the one the engine would render with`() {
        // For an output texel at the puck's NEW home, the source is its
        // OLD home — which is what makes the feature appear to have
        // moved there. Getting this backwards produces a deformation
        // that runs the wrong way and still looks like a deformation.
        val controls = listOf(hold(0.2f, 0.2f), hold(0.8f, 0.2f), pull(0.5f, 0.5f, 0.6f, 0.7f))
        assertNear(Pair(0.5f, 0.5f), RigidMls.sourceAt(0.6f, 0.7f, controls, wide), 1e-5f)
        // And a hold pin samples itself either way round.
        assertNear(Pair(0.2f, 0.2f), RigidMls.sourceAt(0.2f, 0.2f, controls, wide), 1e-5f)
    }

    // ---- Rigidity --------------------------------------------------------

    @Test
    fun `no controls moving means nothing moves`() {
        val controls = listOf(hold(0.2f, 0.3f), hold(0.8f, 0.3f), hold(0.5f, 0.9f))
        for (u in listOf(0.1f, 0.45f, 0.95f)) {
            for (v in listOf(0.15f, 0.5f, 0.8f)) {
                assertNear(Pair(u, v), RigidMls.deform(u, v, controls, wide), 1e-4f, "($u,$v)")
            }
        }
    }

    @Test
    fun `translating every target translates the whole picture`() {
        val base = listOf(hold(0.2f, 0.3f), hold(0.8f, 0.3f), hold(0.5f, 0.8f))
        val shifted = base.map { it.copy(tu = it.tu + 0.1f, tv = it.tv - 0.05f) }
        val (u, v) = Pair(0.42f, 0.61f)
        assertNear(
            Pair(u + 0.1f, v - 0.05f),
            RigidMls.deform(u, v, shifted, wide),
            1e-4f,
        )
    }

    @Test
    fun `rigid stretches less than similarity, and similarity is exact`() {
        // Push every target twice as far from the centre and ask how
        // much a short segment near the middle grows.
        val c = 0.5f
        val base = listOf(
            hold(0.3f, 0.4f), hold(0.7f, 0.4f), hold(0.3f, 0.6f), hold(0.7f, 0.6f),
        )
        val scaled = base.map {
            it.copy(tu = c + (it.su - c) * 2f, tv = c + (it.sv - c) * 2f)
        }
        // Measured as LOCAL ISOMETRY — does a short segment keep its
        // length — rather than as distance from the frame centre.
        //
        // The distance-from-centre version is circular: rigid MLS
        // computes its answer as |ṽ| about the weighted centroid q*, so
        // asserting |f(v) − q*| = |v − p*| just restates the last line
        // of the solver. And measured from a FIXED point instead, it is
        // simply false — q* moves with the probe's own weights, so the
        // deformation legitimately translates as well as rotates.
        val a = Pair(0.34f, 0.44f)
        val b = Pair(0.36f, 0.46f)
        fun span(rubber: Float): Float {
            val fa = RigidMls.deform(a.first, a.second, scaled, wide, rubber = rubber)
            val fb = RigidMls.deform(b.first, b.second, scaled, wide, rubber = rubber)
            return hypot((fb.first - fa.first) * wide, fb.second - fa.second)
        }
        val before = hypot((b.first - a.first) * wide, b.second - a.second)
        // A similarity solve of a pure similarity transform reproduces
        // it EXACTLY — ×2 in, ×2 out. That is the strongest correctness
        // check available for the whole accumulation, and it is what
        // caught the two bugs this file was written with (a dropped
        // per-control weight and an inverted cross term): with either
        // wrong, this ratio is not 2.
        assertEquals(2f, span(1f) / before, 0.01f)
        // Rigid stretches LESS, but it is not an isometry and never was.
        // "Rigid" names the local transform — each one is a rotation,
        // with no scale of its own — not the map. Between the controls
        // the weighted blend still stretches, ×1.79 against similarity's
        // ×2 here. Asserting a ratio of 1 would be asserting something
        // MLS does not do, and the tool's real claim is only ever the
        // comparative one: less inflation of a face than the cheaper
        // variant, for the price of a normalization.
        assertTrue(
            span(0f) < span(1f) * 0.95f,
            "rigid should stretch less than similarity: ${span(0f)} vs ${span(1f)}",
        )
    }

    @Test
    fun `a rotation of every target rotates the picture`() {
        val c = 0.5f
        val theta = 0.4f
        val base = listOf(
            hold(0.25f, 0.35f), hold(0.75f, 0.35f), hold(0.25f, 0.65f), hold(0.75f, 0.65f),
        )
        // Rotate in ASPECT space, which is the space the solver turns in.
        fun rot(u: Float, v: Float): Pair<Float, Float> {
            val x = (u - c) * wide
            val y = v - c
            return Pair(c + (x * cos(theta) - y * sin(theta)) / wide, c + x * sin(theta) + y * cos(theta))
        }
        val rotated = base.map {
            val (tu, tv) = rot(it.su, it.sv)
            it.copy(tu = tu, tv = tv)
        }
        val probe = Pair(0.4f, 0.45f)
        assertNear(
            rot(probe.first, probe.second),
            RigidMls.deform(probe.first, probe.second, rotated, wide),
            1e-3f,
        )
    }

    // ---- The singularity guards ------------------------------------------

    @Test
    fun `an exact control hit is finite and exact`() {
        val controls = listOf(hold(0.2f, 0.2f), pull(0.5f, 0.5f, 0.6f, 0.7f))
        val got = RigidMls.deform(0.5f, 0.5f, controls, wide)
        assertTrue(got.first.isFinite() && got.second.isFinite())
        assertNear(Pair(0.6f, 0.7f), got, 0f)
    }

    @Test
    fun `points arbitrarily close to a control stay finite`() {
        // The weight goes as 1/d^2α, so this is where an unguarded
        // implementation produces an infinity and then a NaN — and a NaN
        // in a displacement field is permanent and replays faithfully.
        val controls = listOf(hold(0.2f, 0.2f), hold(0.8f, 0.8f), pull(0.5f, 0.5f, 0.6f, 0.7f))
        var d = 1e-1f
        repeat(12) {
            for (sign in listOf(1f, -1f)) {
                val (x, y) = RigidMls.deform(0.5f + sign * d, 0.5f, controls, wide)
                assertTrue(x.isFinite() && y.isFinite(), "non-finite at offset ${sign * d}: ($x, $y)")
            }
            d /= 10f
        }
    }

    @Test
    fun `two coincident controls do not blow up`() {
        val controls = listOf(hold(0.4f, 0.4f), hold(0.4f, 0.4f), pull(0.5f, 0.5f, 0.55f, 0.55f))
        val (x, y) = RigidMls.deform(0.41f, 0.41f, controls, wide)
        assertTrue(x.isFinite() && y.isFinite(), "($x, $y)")
    }

    @Test
    fun `a single control is a pure translation, not a collapse`() {
        // With one control there is no rotation to recover, and the
        // degenerate branch has to notice rather than normalize a zero
        // vector.
        val controls = listOf(pull(0.5f, 0.5f, 0.6f, 0.5f))
        val (x, y) = RigidMls.deform(0.2f, 0.3f, controls, wide)
        assertTrue(x.isFinite() && y.isFinite())
        assertEquals(0.3f, x, 1e-4f)
        assertEquals(0.3f, y, 1e-4f)
    }

    @Test
    fun `no controls is the identity`() {
        assertNear(Pair(0.3f, 0.7f), RigidMls.deform(0.3f, 0.7f, emptyList(), wide), 0f)
    }

    @Test
    fun `the control cap is enforced rather than documented`() {
        val many = List(RigidMls.MAX_CONTROLS + 1) { hold(0.1f * it, 0.5f) }
        assertFailsWith<IllegalArgumentException> { RigidMls.deform(0.5f, 0.5f, many, wide) }
    }

    // ---- Shape independence ----------------------------------------------

    @Test
    fun `portrait and landscape agree in pixel space`() {
        // The acceptance sketch's "portrait and landscape photos agree in
        // pixel-space geometry". Build the same PIXEL arrangement on two
        // photo shapes and check the answer is the same pixel offset.
        //
        // A solver measuring distance in raw UV passes on a square image
        // and fails here, which is the only reason this test exists.
        fun probe(aspect: Float): Pair<Float, Float> {
            // Controls placed at the same aspect-space offsets from the
            // centre on both shapes.
            fun at(ax: Float, ay: Float) = Pair(0.5f + ax / aspect, 0.5f + ay)
            val (h1u, h1v) = at(-0.2f, -0.15f)
            val (h2u, h2v) = at(0.2f, -0.15f)
            val (psu, psv) = at(0f, 0.1f)
            val (ptu, ptv) = at(0.08f, 0.22f)
            val controls = listOf(hold(h1u, h1v), hold(h2u, h2v), pull(psu, psv, ptu, ptv))
            val (qu, qv) = at(-0.05f, 0.05f)
            val (ou, ov) = RigidMls.deform(qu, qv, controls, aspect)
            // Report the answer as an aspect-space offset, which IS the
            // pixel offset up to a common scale.
            return Pair((ou - qu) * aspect, ov - qv)
        }
        val a = probe(wide)
        val b = probe(9f / 16f)
        val c = probe(1f)
        assertNear(a, b, 2e-3f, "landscape vs portrait")
        assertNear(a, c, 2e-3f, "landscape vs square")
    }

    // ---- The frame anchors -----------------------------------------------

    @Test
    fun `corner anchors stop a lone pull from towing the whole photo`() {
        val puck = pull(0.5f, 0.5f, 0.62f, 0.5f)
        val far = Pair(0.05f, 0.95f)
        val alone = RigidMls.deform(far.first, far.second, listOf(puck), wide)
        val anchored = RigidMls.deform(far.first, far.second, RigidMls.corners() + puck, wide)
        val dAlone = hypot((alone.first - far.first) * wide, alone.second - far.second)
        val dAnchored = hypot((anchored.first - far.first) * wide, anchored.second - far.second)
        assertTrue(
            dAnchored < dAlone * 0.5f,
            "corners should hold the frame: $dAnchored vs $dAlone",
        )
    }

    @Test
    fun `corner anchors do not overrule a pin the user placed`() {
        // They stabilize the frame without dominating the interior —
        // which is the entire justification for their lower weight.
        val controls = RigidMls.corners() + listOf(
            hold(0.35f, 0.5f),
            pull(0.6f, 0.5f, 0.75f, 0.5f),
        )
        assertNear(Pair(0.35f, 0.5f), RigidMls.deform(0.35f, 0.5f, controls, wide), 1e-4f)
        assertNear(Pair(0.75f, 0.5f), RigidMls.deform(0.6f, 0.5f, controls, wide), 1e-4f)
    }

    // ---- Reach and rubber ------------------------------------------------

    @Test
    fun `a bigger reach exponent makes the pull more local`() {
        val controls = RigidMls.corners() + pull(0.5f, 0.5f, 0.62f, 0.5f)
        // A probe much nearer a corner anchor than the puck. Picked
        // deliberately: at (0.2, 0.5) on a 16:9 frame the PUCK is the
        // nearest control, and raising α there increases the pull rather
        // than localizing it away — correct behaviour, and the opposite
        // of what this test is about.
        val far = Pair(0.08f, 0.9f)
        fun moved(alpha: Float): Float {
            val (x, y) = RigidMls.deform(far.first, far.second, controls, wide, reach = alpha)
            return hypot((x - far.first) * wide, y - far.second)
        }
        assertTrue(moved(RigidMls.MAX_REACH) < moved(RigidMls.MIN_REACH))
    }

    @Test
    fun `rubber is bounded and continuous at both ends`() {
        val controls = RigidMls.corners() + pull(0.5f, 0.5f, 0.6f, 0.58f)
        val probe = Pair(0.42f, 0.47f)
        val hard = RigidMls.deform(probe.first, probe.second, controls, wide, rubber = 0f)
        val soft = RigidMls.deform(probe.first, probe.second, controls, wide, rubber = 1f)
        // Out-of-range values clamp rather than extrapolate: "rubber
        // cannot expose arbitrary solver coefficients".
        assertNear(hard, RigidMls.deform(probe.first, probe.second, controls, wide, rubber = -5f), 0f)
        assertNear(soft, RigidMls.deform(probe.first, probe.second, controls, wide, rubber = 9f), 0f)
        assertTrue(hard.first.isFinite() && soft.first.isFinite())
    }

    @Test
    fun `rubber does not move the pins`() {
        // Whatever the blend does in between, interpolation at the
        // controls is not negotiable — a "softer" setting that let a
        // hold pin drift would break the tool's only promise.
        val controls = listOf(hold(0.3f, 0.4f), hold(0.7f, 0.4f), pull(0.5f, 0.7f, 0.5f, 0.9f))
        for (r in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertNear(
                Pair(0.3f, 0.4f),
                RigidMls.deform(0.3f, 0.4f, controls, wide, rubber = r),
                1e-4f,
                "rubber=$r",
            )
        }
    }

    // ---- Determinism -----------------------------------------------------

    @Test
    fun `the same controls give the same field, every time`() {
        // "Preview is recomputed from the mode-entry base, so identical
        // final controls produce identical output regardless of pointer
        // event count." The solver half of that promise is that it is a
        // pure function — no accumulation, no order dependence.
        val controls = RigidMls.corners() + listOf(
            hold(0.3f, 0.3f),
            pull(0.5f, 0.5f, 0.65f, 0.4f),
        )
        val once = RigidMls.deform(0.44f, 0.52f, controls, wide)
        repeat(50) {
            assertEquals(once, RigidMls.deform(0.44f, 0.52f, controls, wide))
        }
    }

    @Test
    fun `no probe over a whole field produces a non-finite value`() {
        // A NaN reaching a displacement field is permanent and replays
        // faithfully, so this sweeps the awkward arrangement — controls
        // on the frame edge, one exactly on a texel centre, a hard pull
        // — across every texel of a small field.
        val controls = RigidMls.corners() + listOf(
            hold(0f, 0.5f),
            hold(0.5f, 0f),
            pull(0.5f, 0.5f, 0.95f, 0.05f),
        )
        val w = 61
        val h = 37
        var worst = 0f
        for (iy in 0 until h) {
            for (ix in 0 until w) {
                val u = (ix + 0.5f) / w
                val v = (iy + 0.5f) / h
                val (x, y) = RigidMls.sourceAt(u, v, controls, wide)
                assertTrue(x.isFinite() && y.isFinite(), "non-finite at ($u, $v): ($x, $y)")
                worst = maxOf(worst, abs(x - u), abs(y - v))
            }
        }
        assertTrue(worst > 0.01f, "the sweep must actually deform something, got $worst")
    }
}
