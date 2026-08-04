package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrokeLogTest {

    private fun stroke(n: Int) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 1f,
        stamps = listOf(Stamp(n / 10f, 0.5f, 0.01f, 0f)),
    )

    @Test
    fun `starts empty with nothing to undo or redo`() {
        val log = StrokeLog()
        assertTrue(log.isEmpty)
        assertFalse(log.canUndo)
        assertFalse(log.canRedo)
        assertNull(log.undo())
        assertNull(log.redo())
    }

    @Test
    fun `push then undo then redo round-trips`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        assertEquals(2, log.strokes.size)

        assertEquals(1, log.undo()!!.size)
        assertEquals(2, log.redo()!!.size)
        assertFalse(log.canRedo)
    }

    @Test
    fun `empty strokes are not recorded`() {
        val log = StrokeLog()
        log.push(Stroke(BrushTool.SMEAR, 0.1f, 1f, stamps = emptyList()))
        assertTrue(log.isEmpty)
        assertFalse(log.canUndo)
    }

    @Test
    fun `pushing after undo drops the redoable future`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        log.undo()
        log.push(stroke(3))
        assertFalse(log.canRedo)
        assertEquals(listOf(1 / 10f, 3 / 10f), log.strokes.map { it.stamps[0].cx })
    }

    @Test
    fun `reset empties the picture but stays undoable`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        log.reset()
        assertTrue(log.isEmpty)
        assertTrue(log.canUndo)
        assertEquals(2, log.undo()!!.size)
    }

    @Test
    fun `reset on an empty picture is a no-op`() {
        val log = StrokeLog()
        log.reset()
        assertFalse(log.canUndo)
        // After undoing to empty, reset must not record anything — and a
        // true no-op leaves the redoable future intact.
        log.push(stroke(1))
        log.undo()
        log.reset()
        assertTrue(log.isEmpty)
        assertTrue(log.canRedo)
        assertEquals(1, log.redo()!!.size)
    }

    @Test
    fun `undo redo across a reset restores both directions`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.reset()
        log.push(stroke(2))
        assertEquals(1, log.strokes.size)

        assertEquals(0, log.undo()!!.size)
        assertEquals(1, log.undo()!!.size)
        assertEquals(1 / 10f, log.strokes[0].stamps[0].cx)
        assertEquals(0, log.redo()!!.size)
        assertEquals(1, log.redo()!!.size)
        assertEquals(2 / 10f, log.strokes[0].stamps[0].cx)
    }
}
