package ch.lkmc.goo.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun `revisions share their prefix and materialize oldest first`() {
        val log = StrokeLog()
        log.push(stroke(1))
        val first = log.currentRevision
        log.push(stroke(2))
        val second = log.currentRevision

        assertSame(first, second.stateParent)
        assertEquals(2, second.strokeCount)
        assertEquals(listOf(0.1f, 0.2f), second.materialize().map { it.stamps[0].cx })
    }

    @Test
    fun `repeated current-state reads reuse one materialized list`() {
        val log = StrokeLog()
        log.push(stroke(1))

        val first = log.strokes

        assertSame(first, log.strokes)
        log.push(stroke(2))
        assertTrue(first !== log.strokes)
    }

    @Test
    fun `materialized state cannot mutate the active cache`() {
        val log = StrokeLog()
        log.push(stroke(1))
        val materialized = log.strokes

        assertFails { (materialized as MutableList<Stroke>).add(stroke(2)) }
        assertEquals(listOf(0.1f), log.strokes.map { it.stamps[0].cx })
    }

    @Test
    fun `revision ids are not reused after branch truncation`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        val abandoned = log.currentRevision

        log.undo()
        log.push(stroke(3))
        val replacement = log.currentRevision

        assertEquals(abandoned.strokeCount, replacement.strokeCount)
        assertNotEquals(abandoned.id, replacement.id)
        assertEquals(listOf(0.1f, 0.2f), abandoned.materialize().map { it.stamps[0].cx })
        assertEquals(listOf(0.1f, 0.3f), replacement.materialize().map { it.stamps[0].cx })
    }

    @Test
    fun `committed stamps cannot be mutated through the caller list`() {
        val stamps = mutableListOf(Stamp(0.1f, 0.5f, 0.01f, 0f))
        val log = StrokeLog()
        log.push(Stroke(BrushTool.SMEAR, 0.1f, 1f, stamps))

        stamps += Stamp(0.2f, 0.5f, 0.01f, 0f)

        assertEquals(1, log.strokes.single().stamps.size)
    }

    @Test
    fun `reset empties the picture but stays undoable`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        log.reset()
        val reset = log.currentRevision
        assertTrue(log.isEmpty)
        assertNull(reset.stateParent)
        assertEquals(0, reset.strokeCount)
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

    @Test
    fun `clearHistory leaves nothing reachable in either direction`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        log.undo()

        log.clearHistory()

        // Unlike reset, the old document must be gone in BOTH directions:
        // its strokes recorded UVs of a frame that no longer exists.
        assertTrue(log.isEmpty)
        assertFalse(log.canUndo)
        assertFalse(log.canRedo)
        assertNull(log.undo())
        assertNull(log.redo())

        // And the log keeps working afterwards.
        log.push(stroke(3))
        assertEquals(1, log.strokes.size)
        assertTrue(log.canUndo)
    }

    // ---- Batches (the Goo Me deal) --------------------------------------

    @Test
    fun `a batch is three strokes but one undo step`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.pushBatch(listOf(stroke(2), stroke(3), stroke(4)))

        assertEquals(4, log.strokes.size)
        // One tap takes the whole deal back — and lands on the document
        // as it was before it, not part-way through the recipe.
        assertEquals(listOf(stroke(1)), log.undo())
        assertEquals(4, log.redo()!!.size)
        // And the step before it is still the ordinary single stroke:
        // batching does not swallow its neighbours into one entry.
        log.undo()
        assertTrue(log.undo()!!.isEmpty())
        assertFalse(log.canUndo)
    }

    @Test
    fun `a batch replays in the order it was dealt`() {
        val log = StrokeLog()
        log.pushBatch(listOf(stroke(1), stroke(2), stroke(3)))
        assertEquals(listOf(stroke(1), stroke(2), stroke(3)), log.strokes)
    }

    @Test
    fun `a batch drops the redoable future like any other commit`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        log.undo()

        log.pushBatch(listOf(stroke(3), stroke(4)))

        assertFalse(log.canRedo)
        assertEquals(listOf(stroke(1), stroke(3), stroke(4)), log.strokes)
    }

    @Test
    fun `a batch of nothing is not an undo step`() {
        val log = StrokeLog()
        log.push(stroke(1))
        val before = log.currentRevision

        log.pushBatch(emptyList())
        log.pushBatch(listOf(stroke(2).copy(stamps = emptyList())))

        assertSame(before, log.currentRevision)
        assertEquals(1, log.strokes.size)
    }

    @Test
    fun `a batch survives a snapshot round trip as one step`() {
        // The strokes of a batch become revisions that are NOT in the
        // history list — only ancestors of the one that is. If snapshot
        // walked history alone they would vanish on save, and a reloaded
        // deal would be missing two of its three strokes.
        val log = StrokeLog()
        log.push(stroke(1))
        log.pushBatch(listOf(stroke(2), stroke(3)))

        val restored = StrokeLog()
        assertTrue(restored.restore(log.snapshot()) != null)

        assertEquals(log.strokes, restored.strokes)
        assertEquals(listOf(stroke(1)), restored.undo())
        assertEquals(3, restored.redo()!!.size)
    }
}
