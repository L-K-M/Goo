package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Lever restoration across a Goo Me deal — and specifically the reason it
 * is keyed by a transition rather than by a revision.
 *
 * The bug this file exists to prevent is not a crash: it is the user's
 * own lever position being silently replaced with one a deleted deal
 * once saw. That is invisible in a diff and obvious on screen.
 */
class DealLeversTest {

    private fun rev(n: Long) = StrokeRevisionId(n)

    private val quiet = GlobalParams()
    private val handSet = GlobalParams(twirl = 0.5f)
    private val dealt = GlobalParams(twirl = 0.3f)

    @Test
    fun `undoing across a deal restores the levers it found`() {
        val pins = DealLevers()
        pins.pin(before = rev(1), after = rev(2), was = handSet, dealt = dealt)
        assertEquals(handSet, pins.crossing(from = rev(2), to = rev(1)))
    }

    @Test
    fun `redoing across a deal restores the levers it set`() {
        val pins = DealLevers()
        pins.pin(before = rev(1), after = rev(2), was = handSet, dealt = dealt)
        assertEquals(dealt, pins.crossing(from = rev(1), to = rev(2)))
    }

    @Test
    fun `an ordinary history move restores nothing`() {
        val pins = DealLevers()
        pins.pin(before = rev(1), after = rev(2), was = handSet, dealt = dealt)
        // Neighbouring revisions that the deal did not connect.
        assertNull(pins.crossing(from = rev(2), to = rev(3)))
        assertNull(pins.crossing(from = rev(0), to = rev(1)))
        // And with no deal at all, nothing ever fires.
        assertNull(DealLevers().crossing(from = rev(1), to = rev(2)))
    }

    @Test
    fun `a deal's pin dies with the branch it was on`() {
        // The reported failure, step by step:
        //   1. the user pulls a lever by hand           (handSet)
        //   2. a deal lands, moving rev1 -> rev2
        //   3. undo — the hand-set levers come back
        //   4. the user pulls the lever somewhere else  (theirs)
        //   5. a stroke lands, truncating rev2 and growing rev3 from rev1
        //   6. undo the stroke: rev3 -> rev1
        // A revision-keyed pin fires at step 6 and replaces the user's own
        // position with one a deal that no longer exists once saw.
        val pins = DealLevers()
        pins.pin(before = rev(1), after = rev(2), was = handSet, dealt = dealt)

        assertEquals(handSet, pins.crossing(from = rev(2), to = rev(1)))

        // Step 6: a different edge into the same revision.
        assertNull(pins.crossing(from = rev(3), to = rev(1)))
    }

    @Test
    fun `a deal cannot pin a transition that goes nowhere`() {
        // pushBatch drops a batch with no stamps, leaving the head where
        // it was. Pinning that would make every no-op history move at
        // that revision restore a lever table.
        val pins = DealLevers()
        pins.pin(before = rev(1), after = rev(1), was = handSet, dealt = dealt)
        assertEquals(0, pins.size)
        assertNull(pins.crossing(from = rev(1), to = rev(1)))
    }

    @Test
    fun `several deals each own their own transition`() {
        val pins = DealLevers()
        val second = GlobalParams(spike = 0.9f)
        pins.pin(before = rev(1), after = rev(2), was = quiet, dealt = dealt)
        pins.pin(before = rev(2), after = rev(3), was = dealt, dealt = second)

        assertEquals(dealt, pins.crossing(from = rev(3), to = rev(2)))
        assertEquals(quiet, pins.crossing(from = rev(2), to = rev(1)))
        assertEquals(second, pins.crossing(from = rev(2), to = rev(3)))
        // Two edges per deal, and no growth beyond that.
        assertEquals(4, pins.size)
    }
}
