package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GoovieKeyframeTest {

    private fun stroke(n: Int) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 1f,
        stamps = listOf(Stamp(n / 10f, 0.5f, 0.01f, 0f)),
    )

    @Test
    fun `keyframe keeps its state after undo and replacement push`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        val frame = Keyframe(log.currentRevision, GlobalParams(bulge = 0.4f))

        log.undo()
        log.push(stroke(3))

        assertNotEquals(frame.revisionId, log.currentRevision.id)
        assertEquals(listOf(0.1f, 0.2f), centers(frame.revision))
        assertEquals(listOf(0.1f, 0.3f), centers(log.currentRevision))
        assertEquals(0.4f, frame.globals.bulge)
    }

    @Test
    fun `keyframe keeps its state across reset and new work`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        val frame = Keyframe(log.currentRevision, GlobalParams(twirl = -0.5f))

        log.reset()
        log.push(stroke(3))

        assertEquals(listOf(0.1f, 0.2f), centers(frame.revision))
        assertEquals(listOf(0.3f), centers(log.currentRevision))
        assertEquals(-0.5f, frame.globals.twirl)
    }

    private fun centers(revision: StrokeRevision): List<Float> =
        revision.materialize().map { it.stamps.single().cx }
}
