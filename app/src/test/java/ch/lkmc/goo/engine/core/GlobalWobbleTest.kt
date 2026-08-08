package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Wobbulator (proposal 0009).
 *
 * Two things here are load-bearing and neither is visible by looking at
 * a running app: that a wobbled loop is seamless, and that no document
 * can be authored above the flash-safety line. The second is the one
 * that reaches people who never chose it, since it ships inside an
 * exported file.
 */
class GlobalWobbleTest {

    private fun rig(vararg pairs: Pair<Int, Int>): GlobalWobble {
        // (leverIndex, rate) with full depth, which is the worst case for
        // everything these tests care about.
        var w = GlobalWobble()
        for ((index, rate) in pairs) w = w.with(index, LeverWobble(rate, 1f))
        return w
    }

    // ---- The loop is seamless --------------------------------------------

    @Test
    fun `a whole number of cycles returns to where it started`() {
        // The entire reason rate is an integer: phase 0 and phase 1 are
        // the same frame, so a GIF or MP4 loops with no stitch and
        // nothing to cross-fade.
        //
        // Not bit-exact, and it does not need to be: sin(TAU·n) in float
        // is not 0 but a residue that grows with the argument, so the
        // seam widens with the rate — about 1e-7 at one cycle and around
        // 1e-6 at eight.
        //
        // The tolerance is therefore a statement about what a lever DOES,
        // not about the float. At the engine's largest lever scale
        // (GlobalField.BULGE_SCALE = 0.35) a 1e-5 lever residue is 3.5e-6
        // UV, and one field texel is at worst 1/1024 ≈ 1e-3 UV — so the
        // widest seam this allows is some two orders of magnitude below
        // the smallest thing the field can represent, on the largest
        // lever. It cannot reach the output.
        val base = GlobalParams(bulge = 0.3f, twirl = -0.2f)
        for (rate in 1..LeverWobble.MAX_RATE) {
            val w = rig(0 to rate, 1 to rate)
            val start = leversAt(base, w, 0f).toArray()
            val end = leversAt(base, w, 1f).toArray()
            for (i in start.indices) {
                assertEquals(start[i], end[i], 1e-5f, "rate $rate leaves a seam on lever $i")
            }
        }
    }

    @Test
    fun `the swing is centred on the position the user set`() {
        // A wobble is what happens AROUND the lever, not instead of it:
        // the quarter-cycle peaks sit either side of the set value.
        val base = GlobalParams(bulge = 0.2f)
        val w = rig(0 to 1).with(0, LeverWobble(1, 0.5f))
        assertEquals(0.2f, leversAt(base, w, 0f).bulge, 1e-5f)
        assertEquals(0.7f, leversAt(base, w, 0.25f).bulge, 1e-5f)
        assertEquals(0.2f, leversAt(base, w, 0.5f).bulge, 1e-5f)
        assertEquals(-0.3f, leversAt(base, w, 0.75f).bulge, 1e-5f)
    }

    @Test
    fun `a wobbled lever never leaves its own range`() {
        // Levers are bipolar in [-1, 1]; a deep wobble on an already
        // extreme lever must clip rather than drive the warp past what
        // the rest of the engine assumes.
        val base = GlobalParams(bulge = 0.9f, twirl = -0.9f, spike = 1f)
        val w = rig(0 to 2, 1 to 3, 4 to 1)
        for (step in 0..200) {
            val values = leversAt(base, w, step / 200f).toArray()
            for (value in values) {
                assertTrue(value in -1f..1f, "lever left its range at $value")
            }
        }
    }

    @Test
    fun `a still rig is the levers themselves, untouched`() {
        val base = GlobalParams(bulge = 0.4f, static = 0.1f)
        assertTrue(GlobalWobble().isStill)
        assertEquals(base, leversAt(base, GlobalWobble(), 0.37f))
        // Rate without depth, and depth without rate, are both still.
        assertEquals(base, leversAt(base, GlobalWobble().with(0, LeverWobble(4, 0f)), 0.37f))
        assertEquals(base, leversAt(base, GlobalWobble().with(0, LeverWobble(0, 1f)), 0.37f))
    }

    @Test
    fun `only the levers with a wobble move`() {
        val base = GlobalParams(bulge = 0.2f, twirl = 0.2f, squeeze = 0.2f)
        val moved = leversAt(base, rig(1 to 1), 0.25f)
        assertEquals(base.bulge, moved.bulge)
        assertEquals(base.squeeze, moved.squeeze)
        assertTrue(abs(moved.twirl - base.twirl) > 0.5f)
    }

    @Test
    fun `the same phase always gives the same levers`() {
        // Phase comes from the frame index, never a wall clock, so an
        // export reproduces the preview exactly and two renders of one
        // document are identical.
        //
        // Compared across two INDEPENDENT rigs — one rebuilt from the
        // other's saved pack — rather than a value against itself. The
        // self-comparison was tautological for a pure function and would
        // have survived a regression that made the result depend on
        // instance identity, which is exactly the shape "reloaded the
        // project and the movie renders differently" would take.
        val base = GlobalParams(bulge = 0.1f)
        val w = rig(0 to 3, 4 to 1)
        val reloaded = GlobalWobble.unpack(w.pack())
        assertEquals(w, reloaded)
        for (step in 0..50) {
            val p = step / 50f
            assertEquals(leversAt(base, w, p), leversAt(base, reloaded!!, p))
        }
    }

    // ---- The flash-safety cap --------------------------------------------

    @Test
    fun `no document can be authored above three hertz`() {
        // The proposal's own arithmetic, as a test. Every loop this app
        // can produce — two keyframes upward, every export speed — must
        // leave the offered rates under the WCAG 2.3.1 line.
        for (keyframes in 2..24) {
            for (speed in MovieSpeed.entries) {
                val loop = MovieSpec.durationSeconds(keyframes, speed.multiplier)
                val offered = GlobalWobble.ratesFor(loop)
                for (rate in offered) {
                    val hz = rate / loop
                    assertTrue(
                        hz <= GlobalWobble.MAX_HZ + 1e-4f,
                        "$keyframes keyframes at ${speed.multiplier}x offers " +
                            "$rate cycles over ${loop}s = $hz Hz",
                    )
                }
            }
        }
    }

    @Test
    fun `the shortest loop this app can make offers no wobble at all`() {
        // Two keyframes at 4x is 0.3 s. Eight cycles there would be
        // 26.7 Hz — the middle of the photosensitive band — which is
        // exactly the case the cap exists for.
        val shortest = MovieSpec.durationSeconds(2, MovieSpeed.QUADRUPLE.multiplier)
        assertTrue(shortest < 0.5f, "expected a very short loop, got $shortest")
        assertEquals(0, GlobalWobble.maxSafeRate(shortest))
        assertEquals(listOf(0), GlobalWobble.ratesFor(shortest))
    }

    @Test
    fun `a longer loop earns the higher rates back`() {
        // The cap is a property of the document, not a fixed detent list:
        // lengthen the strip and the fast wobbles become available again.
        val long = MovieSpec.durationSeconds(8, MovieSpeed.NORMAL.multiplier)
        assertTrue(
            GlobalWobble.maxSafeRate(long) > GlobalWobble.maxSafeRate(
                MovieSpec.durationSeconds(2, MovieSpeed.NORMAL.multiplier),
            ),
        )
        // And never past the dial's own ceiling.
        assertEquals(LeverWobble.MAX_RATE, GlobalWobble.maxSafeRate(1000f))
    }

    @Test
    fun `three hertz exactly is offered, and is the most that ever is`() {
        // WCAG 2.3.1 is "three flashes or below threshold" — three is
        // allowed, so a one-second loop offers 3 and not 2. Pinned
        // because the floor in maxSafeRate makes that a boundary the
        // next person to touch this will want to know was deliberate.
        assertEquals(3, GlobalWobble.maxSafeRate(1f))
        assertEquals(3f, 3 / 1f)
        // And just under a second cannot reach it.
        assertEquals(2, GlobalWobble.maxSafeRate(0.99f))
    }

    @Test
    fun `a document that cannot loop offers nothing to wobble`() {
        // Fewer than two keyframes is not a loop, so there is nothing for
        // a cycle count to be measured against — and no safe cap either.
        assertEquals(0f, MovieSpec.durationSeconds(1))
        assertEquals(0, GlobalWobble.maxSafeRate(0f))
        assertEquals(0, GlobalWobble.maxSafeRate(-1f))
    }

    // ---- The rig is document state ---------------------------------------

    @Test
    fun `a rig equals an identical rig`() {
        // Arrays would compare by reference here, and "unsaved" in this
        // app means the signature differs from disk — so a rig that never
        // equalled itself would report unwritten changes forever and
        // defeat the autosave checkpoint policy.
        assertEquals(GlobalWobble(), GlobalWobble())
        assertEquals(rig(0 to 2, 3 to 1), rig(0 to 2, 3 to 1))
        assertFalse(rig(0 to 2) == rig(0 to 3))
    }

    @Test
    fun `a malformed rig is repaired rather than trusted`() {
        val wrong = GlobalWobble(listOf(LeverWobble(99, 5f), LeverWobble(-4, -2f)))
        val fixed = wrong.sanitized()
        assertEquals(GlobalWobble.SIZE, fixed.levers.size)
        assertEquals(LeverWobble(LeverWobble.MAX_RATE, 1f), fixed.levers[0])
        assertEquals(LeverWobble(0, 0f), fixed.levers[1])
        // A short list pads with still levers rather than throwing.
        assertEquals(GlobalWobble.SIZE, GlobalWobble(emptyList()).sanitized().levers.size)
    }

    @Test
    fun `an export caps a rig that was dialled against a longer loop`() {
        // The dial is computed for the document as it stands, but the
        // speed control can shorten the loop by 4x afterwards and the GIF
        // ladder can drop the frame rate on top of that. Without a clamp
        // at the encoder, a rig that was legal when dialled would ship
        // above the line. This is the property that makes "no exported
        // file flashes above MAX_HZ" true of the encoder rather than of
        // the user re-checking a panel.
        val long = MovieSpec.durationSeconds(8, MovieSpeed.NORMAL.multiplier)
        val dialled = GlobalWobble().with(0, LeverWobble(GlobalWobble.maxSafeRate(long), 1f))
        assertTrue(dialled.levers[0].rate > 0)

        val short = MovieSpec.durationSeconds(2, MovieSpeed.QUADRUPLE.multiplier)
        val capped = dialled.cappedFor(short)
        assertEquals(GlobalWobble.maxSafeRate(short), capped.levers[0].rate)
        assertTrue(capped.levers[0].rate / short <= GlobalWobble.MAX_HZ + 1e-4f)

        // A rig already inside the cap is returned untouched.
        assertEquals(dialled, dialled.cappedFor(long))
    }

    @Test
    fun `the encoder's cap is total, not merely an upper bound`() {
        // cappedFor is documented as the safety net at the point a file
        // is written, so it has to handle a rig that never went through
        // sanitized() — a hand-edited project, say. A negative rate would
        // otherwise pass a "<= ceiling" test and reach the walk.
        val hostile = GlobalWobble(
            listOf(LeverWobble(-5, 1f), LeverWobble(99, 1f)) + List(GlobalWobble.SIZE - 2) { LeverWobble() },
        )
        val capped = hostile.cappedFor(MovieSpec.durationSeconds(4))
        for (lever in capped.levers) {
            assertTrue(lever.rate >= 0, "negative rate survived: ${lever.rate}")
            assertTrue(lever.rate <= GlobalWobble.maxSafeRate(MovieSpec.durationSeconds(4)))
        }
    }

    @Test
    fun `the saved-state pack round-trips`() {
        val rig = GlobalWobble()
            .with(0, LeverWobble(3, 0.4f))
            .with(5, LeverWobble(1, 1f))
        assertEquals(rig, GlobalWobble.unpack(rig.pack()))
        assertEquals(GlobalWobble().sanitized(), GlobalWobble.unpack(GlobalWobble().pack()))
        assertEquals(null, GlobalWobble.unpack(FloatArray(3)))
    }

    @Test
    fun `a document too short to loop still previews against a real loop`() {
        // The wobble is meant to be the on-ramp the GOOvie never had, so
        // it has to be dialable and watchable before any pin is punched.
        // Flooring the preview loop at two pins is what keeps what you
        // dial, what you see and what you export the same thing.
        assertEquals(
            MovieSpec.durationSeconds(2),
            GlobalWobble.previewLoopSeconds(0),
        )
        assertEquals(
            MovieSpec.durationSeconds(2),
            GlobalWobble.previewLoopSeconds(1),
        )
        assertEquals(
            MovieSpec.durationSeconds(5),
            GlobalWobble.previewLoopSeconds(5),
        )
        assertTrue(GlobalWobble.ratesFor(GlobalWobble.previewLoopSeconds(0)).size > 1)
    }

    @Test
    fun `writing a lever leaves its neighbours alone`() {
        val w = GlobalWobble().with(2, LeverWobble(3, 0.5f))
        assertEquals(LeverWobble(3, 0.5f), w.levers[2])
        assertEquals(List(GlobalWobble.SIZE - 1) { LeverWobble() },
            w.levers.filterIndexed { i, _ -> i != 2 })
        // Out-of-range writes are ignored, not clamped onto a neighbour.
        assertEquals(w, w.with(-1, LeverWobble(5, 1f)))
        assertEquals(w, w.with(GlobalWobble.SIZE, LeverWobble(5, 1f)))
    }
}
