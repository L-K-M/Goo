package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GoovieHintTest {

    private fun stroke(cx: Float) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 0.5f,
        stamps = listOf(Stamp(cx, 0.5f, 0.01f, 0f)),
    )

    /** [strokes] distinct strokes — a snapshot of that document length. */
    private fun pin(strokes: Int, bulge: Float = 0f) = Keyframe(
        strokes = List(strokes) { stroke(it * 0.1f) },
        globals = GlobalParams(bulge = bulge),
    )

    @Test
    fun `an empty strip asks for a first punch, even mid-stroke`() {
        assertEquals(GoovieHint.EMPTY, goovieHint(emptyList(), -1, false, live = false))
        assertEquals(GoovieHint.EMPTY, goovieHint(emptyList(), -1, false, live = true))
    }

    @Test
    fun `gooing live names the keyframe Update would re-pin`() {
        val strip = listOf(pin(1), pin(4))
        assertEquals(GoovieHint.LIVE_PINNED, goovieHint(strip, 1, true, live = true))
        // Selected but not yet stale — the instant between dropping to
        // live and the first stamp landing. Still LIVE_PINNED: naming the
        // Update target is what makes the hint useful, and staleness is
        // one stroke away by definition. Deliberately not a third state.
        assertEquals(GoovieHint.LIVE_PINNED, goovieHint(strip, 1, false, live = true))
        // Nothing selected: Punch is the only verb on offer.
        assertEquals(GoovieHint.LIVE, goovieHint(strip, -1, false, live = true))
        // A selection past the end can't be re-pinned either.
        assertEquals(GoovieHint.LIVE, goovieHint(strip, 2, false, live = true))
    }

    @Test
    fun `one keyframe asks for a second`() {
        assertEquals(GoovieHint.NEEDS_SECOND, goovieHint(listOf(pin(3)), 0, false, live = false))
    }

    @Test
    fun `identical pins are called out as a movie that cannot move`() {
        // The classic first-run confusion: punch, punch, nothing happens.
        // Same stroke count AND same levers on every pin = a still frame.
        val twice = listOf(pin(2), pin(2))
        assertEquals(GoovieHint.ALL_SAME, goovieHint(twice, 1, false, live = false))
        val thrice = listOf(pin(2), pin(2), pin(2))
        assertEquals(GoovieHint.ALL_SAME, goovieHint(thrice, 0, false, live = false))
        // Levers alone are enough to make a strip move.
        val leversOnly = listOf(pin(2), pin(2, bulge = 0.5f))
        assertEquals(GoovieHint.READY, goovieHint(leversOnly, -1, false, live = false))
    }

    @Test
    fun `a lagging pin offers Punch or Update, and a matching one stays quiet`() {
        val strip = listOf(pin(1), pin(4))
        assertEquals(GoovieHint.STALE, goovieHint(strip, 1, true, live = false))
        assertEquals(GoovieHint.READY, goovieHint(strip, 1, false, live = false))
    }
}
