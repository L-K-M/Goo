package ch.lkmc.goo.ui.editor

import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeRevisionId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [EditorViewModel.UiState.selectedKeyframeStale] is what tells the user
 * their goo hasn't reached the keyframe yet — it lights the Update chip on
 * the brush rail and the Update bead in the strip. Getting it wrong either
 * hides the only affordance that re-pins a keyframe, or nags forever.
 *
 * Staleness is revision-id inequality: ids are never reused, and a pin
 * matching the live document always shares the log's revision instance,
 * so id equality IS "the pin describes what you see".
 */
class KeyframeStalenessTest {

    private fun stroke(cx: Float) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 0.5f,
        stamps = listOf(Stamp(cx, 0.5f, 0.01f, 0f)),
    )

    private fun state(
        keyframes: List<Keyframe>,
        selected: Int,
        revisionId: StrokeRevisionId?,
        globals: GlobalParams = GlobalParams(),
    ) = EditorViewModel.UiState(
        keyframes = keyframes,
        selectedKeyframe = selected,
        revisionId = revisionId,
        globals = globals,
    )

    private fun pin(log: StrokeLog, globals: GlobalParams = GlobalParams()) =
        Keyframe(revision = log.currentRevision, globals = globals)

    @Test
    fun `a pin that matches the document is not stale`() {
        val log = StrokeLog()
        repeat(3) { log.push(stroke(it * 0.1f)) }
        val kf = pin(log)
        assertFalse(
            state(listOf(kf), selected = 0, revisionId = log.currentRevision.id)
                .selectedKeyframeStale,
        )
    }

    @Test
    fun `gooing after the punch makes the pin stale`() {
        val log = StrokeLog()
        repeat(3) { log.push(stroke(it * 0.1f)) }
        val kf = pin(log)
        log.push(stroke(0.9f))
        assertTrue(
            state(listOf(kf), selected = 0, revisionId = log.currentRevision.id)
                .selectedKeyframeStale,
        )
    }

    @Test
    fun `undoing below the pin makes it stale too`() {
        val log = StrokeLog()
        repeat(3) { log.push(stroke(it * 0.1f)) }
        val kf = pin(log)
        log.undo()
        log.undo()
        assertTrue(
            state(listOf(kf), selected = 0, revisionId = log.currentRevision.id)
                .selectedKeyframeStale,
        )
    }

    @Test
    fun `moving a lever makes the pin stale without touching the log`() {
        val log = StrokeLog()
        repeat(3) { log.push(stroke(it * 0.1f)) }
        val kf = pin(log)
        val moved = state(
            keyframes = listOf(kf),
            selected = 0,
            revisionId = log.currentRevision.id,
            globals = GlobalParams(twirl = 0.4f),
        )
        assertTrue(moved.selectedKeyframeStale)
    }

    @Test
    fun `no selection means nothing to update`() {
        val log = StrokeLog()
        repeat(3) { log.push(stroke(it * 0.1f)) }
        val kf = pin(log)
        log.push(stroke(0.9f))
        val live = log.currentRevision.id
        assertFalse(state(listOf(kf), selected = -1, revisionId = live).selectedKeyframeStale)
        // Out of range (a delete raced the read) must not throw.
        assertFalse(state(listOf(kf), selected = 7, revisionId = live).selectedKeyframeStale)
        assertFalse(state(emptyList(), selected = 0, revisionId = live).selectedKeyframeStale)
    }
}
