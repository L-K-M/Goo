package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A keyframe pins the immutable stroke revision it was punched from, so
 * the editor's undo cursor can go anywhere — including all the way back to
 * the untouched photo — without dragging the strip with it.
 *
 * This was a real bug: pins used to be prefix COUNTS into
 * `StrokeLog.strokes`, which shrinks on undo, so undoing toward the
 * original image collapsed every keyframe to the truncated state and made
 * "end the movie on the original photo" impossible to author.
 */
class KeyframePinTest {

    private fun stroke(cx: Float) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 0.5f,
        stamps = listOf(Stamp(cx, 0.5f, 0.01f, 0f)),
    )

    private fun punch(log: StrokeLog) = Keyframe(log.currentRevision, GlobalParams())

    @Test
    fun `undo does not move an already-punched pin`() {
        val log = StrokeLog()
        log.push(stroke(0.1f))
        log.push(stroke(0.2f))
        val pin = punch(log)

        log.undo()
        log.undo()

        assertEquals(0, log.strokes.size, "the editor really did rewind")
        assertEquals(2, pin.revision.strokeCount, "the keyframe did not")
    }

    @Test
    fun `the original photo can be punched as a closing frame`() {
        // The reported workflow: goo, punch, undo everything, punch the
        // bare photo, redo back to the goo. All three pins must survive.
        val log = StrokeLog()
        log.push(stroke(0.1f))
        val gooed = punch(log)

        log.undo()
        val original = punch(log)
        assertEquals(0, original.revision.strokeCount)

        log.redo()
        assertEquals(1, log.strokes.size, "redo restored the goo")
        assertEquals(1, gooed.revision.strokeCount, "the gooed pin is intact")
        assertEquals(0, original.revision.strokeCount, "the original-photo pin is intact")
    }

    @Test
    fun `a pin survives the redo branch being truncated`() {
        // undo-then-push discards the redo branch. A keyframe pinning a
        // revision from that branch keeps it alive and replayable.
        val log = StrokeLog()
        log.push(stroke(0.1f))
        log.push(stroke(0.2f))
        val doomed = punch(log)

        log.undo()
        log.push(stroke(0.9f))

        assertEquals(2, log.strokes.size)
        val pinned = doomed.revision.materialize()
        assertEquals(2, pinned.size)
        assertEquals(0.2f, pinned[1].stamps[0].cx, "the discarded stroke, still pinned")
    }

    @Test
    fun `reset leaves the strip alone`() {
        val log = StrokeLog()
        log.push(stroke(0.1f))
        val pin = punch(log)
        log.reset()
        assertEquals(1, pin.revision.strokeCount)
    }

    @Test
    fun `punching the same state twice shares one revision`() {
        // Identity matters: the renderer caches materialized endpoint
        // fields by revision id, and goovieHint's "nothing moves" check
        // leans on Keyframe equality, which compares the same revision.
        val log = StrokeLog()
        log.push(stroke(0.1f))
        assertSame(punch(log).revision, punch(log).revision)
        assertEquals(punch(log).revisionId, punch(log).revisionId)
        assertTrue(punch(log) == punch(log), "equal pins for the same state")
    }
}
