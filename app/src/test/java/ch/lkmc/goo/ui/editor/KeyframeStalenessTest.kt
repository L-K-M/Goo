package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EditorViewModel.UiState.selectedKeyframeStale] is what tells the user
 * their goo hasn't reached the keyframe yet — it lights the Update chip on
 * the brush rail and the Update bead in the strip. Getting it wrong either
 * hides the only affordance that re-pins a keyframe, or nags forever.
 */
class KeyframeStalenessTest {

    private fun stroke(cx: Float) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 0.5f,
        stamps = listOf(Stamp(cx, 0.5f, 0.01f, 0f)),
    )

    /** A document of [n] distinct strokes. */
    private fun doc(n: Int): List<Stroke> = List(n) { stroke(it * 0.1f) }

    private fun state(
        keyframes: List<Keyframe>,
        selected: Int,
        strokes: List<Stroke>,
        globals: GlobalParams = GlobalParams(),
    ) = EditorViewModel.UiState(
        keyframes = keyframes,
        selectedKeyframe = selected,
        strokes = strokes,
        globals = globals,
    )

    private fun pin(strokes: List<Stroke>, globals: GlobalParams = GlobalParams()) =
        Keyframe(strokes = strokes, globals = globals)

    @Test
    fun `a pin that matches the document is not stale`() {
        val live = doc(3)
        assertFalse(state(listOf(pin(live)), selected = 0, strokes = live).selectedKeyframeStale)
        // Equal content in a different list instance counts as a match too:
        // the pin describes a state, not an object.
        assertFalse(state(listOf(pin(doc(3))), selected = 0, strokes = doc(3)).selectedKeyframeStale)
    }

    @Test
    fun `gooing after the punch makes the pin stale`() {
        assertTrue(state(listOf(pin(doc(3))), selected = 0, strokes = doc(4)).selectedKeyframeStale)
    }

    @Test
    fun `undoing below the pin makes it stale too`() {
        assertTrue(state(listOf(pin(doc(3))), selected = 0, strokes = doc(1)).selectedKeyframeStale)
    }

    @Test
    fun `moving a lever makes the pin stale without touching the log`() {
        val live = doc(3)
        val moved = state(
            keyframes = listOf(pin(live)),
            selected = 0,
            strokes = live,
            globals = GlobalParams(twirl = 0.4f),
        )
        assertTrue(moved.selectedKeyframeStale)
    }

    @Test
    fun `no selection means nothing to update`() {
        assertFalse(state(listOf(pin(doc(3))), selected = -1, strokes = doc(9)).selectedKeyframeStale)
        // Out of range (a delete raced the read) must not throw.
        assertFalse(state(listOf(pin(doc(3))), selected = 7, strokes = doc(9)).selectedKeyframeStale)
        assertFalse(state(emptyList(), selected = 0, strokes = doc(9)).selectedKeyframeStale)
    }
}
