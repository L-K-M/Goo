package ch.lkmc.goo.engine.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The save/load half of the document (PLAN.md §5.5): a [StrokeLog] has to
 * survive a round trip through [StrokeLogSnapshot] with its history, its
 * cursor and — the part that is easy to get wrong — the revisions that
 * only GOOvie keyframes still point at.
 */
class StrokeLogSnapshotTest {

    private fun stroke(n: Int) = Stroke(
        tool = BrushTool.SMEAR,
        radius = 0.1f,
        strength = 1f,
        stamps = listOf(Stamp(n / 10f, 0.5f, 0.01f, 0f)),
    )

    private fun roundTrip(snapshot: StrokeLogSnapshot): StrokeLogSnapshot =
        Json.decodeFromString(Json.encodeToString(snapshot))

    @Test
    fun `an untouched log round-trips as a single root`() {
        val log = StrokeLog()
        val snapshot = log.snapshot()
        assertEquals(listOf(0L), snapshot.history)
        assertEquals(0, snapshot.cursor)

        val restored = StrokeLog()
        assertNotNull(restored.restore(roundTrip(snapshot)))
        assertTrue(restored.isEmpty)
        assertFalse(restored.canUndo)
        assertFalse(restored.canRedo)
    }

    @Test
    fun `strokes history and cursor all come back`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        log.push(stroke(3))
        log.undo()

        val restored = StrokeLog()
        assertNotNull(restored.restore(roundTrip(log.snapshot())))
        assertEquals(2, restored.strokes.size)
        assertEquals(log.strokes, restored.strokes)
        // The redo branch is part of the document too: leaving with one
        // undo pending should not silently discard the stroke it holds.
        assertTrue(restored.canRedo)
        assertEquals(3, restored.redo()!!.size)
        assertEquals(2, restored.undo()!!.size)
        assertEquals(1, restored.undo()!!.size)
        // All the way back to the untouched photo — the state a closing
        // keyframe is punched from.
        assertEquals(0, restored.undo()!!.size)
        assertFalse(restored.canUndo)
    }

    @Test
    fun `each revision is written exactly once however many pins share it`() {
        val log = StrokeLog()
        val pins = mutableListOf<StrokeRevision>()
        repeat(4) {
            log.push(stroke(it))
            pins += log.currentRevision
        }
        // Four pins over a four-deep chain: a recursive encoding would
        // write 1+2+3+4 nodes. The flat table writes root + 4.
        val snapshot = log.snapshot(pins)
        assertEquals(5, snapshot.revisions.size)
        assertEquals(snapshot.revisions.map { it.id }.toSet().size, snapshot.revisions.size)
        // Parents before children, so restore never chases forward refs.
        assertEquals(snapshot.revisions.map { it.id }.sorted(), snapshot.revisions.map { it.id })
    }

    @Test
    fun `a keyframe pin on a truncated redo branch survives`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        val pinned = log.currentRevision
        log.undo()
        // This push truncates the future: `pinned` leaves the history but
        // stays alive because a keyframe holds it. That is exactly the
        // independence the revision model promises (Goovie.kt) — and it
        // has to reach disk, or reopening a project would flatten the
        // strip the way prefix counts used to.
        log.push(stroke(3))
        assertFalse(log.canRedo)

        val snapshot = roundTrip(log.snapshot(pins = listOf(pinned)))
        assertFalse(pinned.id.value in snapshot.history)

        val restored = StrokeLog()
        val table = assertNotNull(restored.restore(snapshot))
        val restoredPin = assertNotNull(table[pinned.id])
        assertEquals(pinned.materialize(), restoredPin.materialize())
        assertEquals(log.strokes, restored.strokes)
    }

    @Test
    fun `restored ids are never handed out again`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.push(stroke(2))
        val ids = log.snapshot().revisions.map { it.id }.toSet()

        val restored = StrokeLog()
        restored.restore(log.snapshot())
        restored.push(stroke(3))
        // Renderer endpoint caches and keyframe pins are keyed by id, so a
        // reused id would let a cache answer for the wrong document state.
        assertFalse(restored.currentRevision.id.value in ids)
    }

    @Test
    fun `a Reset in the history restores as a root`() {
        val log = StrokeLog()
        log.push(stroke(1))
        log.reset()

        val restored = StrokeLog()
        assertNotNull(restored.restore(roundTrip(log.snapshot())))
        assertTrue(restored.isEmpty)
        // Reset stays undoable across a save — KPT's irreversible Reset is
        // the flaw StrokeLog exists to fix, and persistence must not
        // quietly reintroduce it.
        assertEquals(1, restored.undo()!!.size)
    }

    @Test
    fun `malformed snapshots are refused and leave the log untouched`() {
        val good = StrokeLog().apply { push(stroke(1)) }.snapshot()
        val record = { id: Long, parent: Long? ->
            StrokeRevisionRecord(id = id, parent = parent, stroke = stroke(1))
        }
        val cases = listOf(
            StrokeLogSnapshot(),
            good.copy(history = emptyList()),
            good.copy(cursor = 7),
            good.copy(cursor = -1),
            // History id with no record behind it.
            good.copy(history = good.history + 99L),
            // Duplicate id: two revisions sharing one identity.
            good.copy(revisions = good.revisions + good.revisions.last()),
            // Forward (or cyclic) parent reference.
            good.copy(
                revisions = listOf(record(1L, 2L), record(2L, null)),
                history = listOf(1L),
                cursor = 0,
            ),
            // A stroke that stamps nothing: push() cannot make one.
            good.copy(
                revisions = listOf(
                    StrokeRevisionRecord(id = 0L),
                    StrokeRevisionRecord(
                        id = 1L,
                        parent = 0L,
                        stroke = stroke(1).copy(stamps = emptyList()),
                    ),
                ),
                history = listOf(0L, 1L),
                cursor = 1,
            ),
        )
        for (case in cases) {
            val log = StrokeLog()
            log.push(stroke(9))
            val before = log.strokes
            assertNull(log.restore(case), "expected refusal for $case")
            assertSame(before, log.strokes)
        }
    }
}
