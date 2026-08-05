package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Keyframe
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

    private fun state(
        keyframes: List<Keyframe>,
        selected: Int,
        strokeCount: Int,
        globals: GlobalParams = GlobalParams(),
    ) = EditorViewModel.UiState(
        keyframes = keyframes,
        selectedKeyframe = selected,
        strokeCount = strokeCount,
        globals = globals,
    )

    private fun pin(strokes: Int, globals: GlobalParams = GlobalParams()) =
        Keyframe(strokeCount = strokes, globals = globals)

    @Test
    fun `a pin that matches the document is not stale`() {
        assertFalse(state(listOf(pin(3)), selected = 0, strokeCount = 3).selectedKeyframeStale)
    }

    @Test
    fun `gooing after the punch makes the pin stale`() {
        assertTrue(state(listOf(pin(3)), selected = 0, strokeCount = 4).selectedKeyframeStale)
    }

    @Test
    fun `undoing below the pin makes it stale too`() {
        assertTrue(state(listOf(pin(3)), selected = 0, strokeCount = 1).selectedKeyframeStale)
    }

    @Test
    fun `moving a lever makes the pin stale without touching the log`() {
        val moved = state(
            keyframes = listOf(pin(3)),
            selected = 0,
            strokeCount = 3,
            globals = GlobalParams(twirl = 0.4f),
        )
        assertTrue(moved.selectedKeyframeStale)
    }

    @Test
    fun `no selection means nothing to update`() {
        assertFalse(state(listOf(pin(3)), selected = -1, strokeCount = 9).selectedKeyframeStale)
        // Out of range (a delete raced the read) must not throw.
        assertFalse(state(listOf(pin(3)), selected = 7, strokeCount = 9).selectedKeyframeStale)
        assertFalse(state(emptyList(), selected = 0, strokeCount = 9).selectedKeyframeStale)
    }
}
