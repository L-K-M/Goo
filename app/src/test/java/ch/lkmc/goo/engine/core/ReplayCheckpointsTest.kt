package ch.lkmc.goo.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A checkpoint is an optimization whose only correctness obligation is
 * negative: it must never claim a prefix it does not actually hold. The
 * planner is therefore tested mostly for what it *refuses*.
 */
class ReplayCheckpointsTest {

    private var nextId = 0

    /** A stroke carrying [stamps] stamps, distinct from every other. */
    private fun stroke(stamps: Int): Stroke {
        val id = nextId++
        return Stroke(
            tool = BrushTool.SMEAR,
            radius = 0.1f,
            strength = 1f,
            stamps = List(stamps) { Stamp(0.5f, 0.5f + id * 1e-4f, 0.01f, 0.01f) },
        )
    }

    private fun log(vararg stampCounts: Int) = stampCounts.map { stroke(it) }

    // ---- Refusing to reuse --------------------------------------------

    @Test
    fun noCheckpointMeansReplayFromIdentity() {
        val strokes = log(10, 10, 10)
        val plan = ReplayCheckpoints.plan(strokes, checkpoint = null)
        assertFalse(plan.restoreCheckpoint)
        assertEquals(0, plan.replayFrom)
    }

    @Test
    fun aCheckpointOnADivergedBranchIsRefused() {
        // Redo after a truncating edit: same length, different content.
        val strokes = log(10, 10, 10, 10)
        val other = log(10, 10)
        val plan = ReplayCheckpoints.plan(strokes, checkpoint = other)
        assertFalse(plan.restoreCheckpoint)
        assertEquals(0, plan.replayFrom)
    }

    @Test
    fun aCheckpointLongerThanTheLogIsRefused() {
        // Undo below the checkpoint — the case that must not read a
        // snapshot of a future the log no longer has.
        val strokes = log(10, 10, 10, 10, 10)
        val checkpoint = strokes.take(3)
        val undone = strokes.take(2)
        val plan = ReplayCheckpoints.plan(undone, checkpoint)
        assertFalse(plan.restoreCheckpoint)
        assertEquals(0, plan.replayFrom)
    }

    @Test
    fun anEmptyCheckpointIsNotWorthRestoring() {
        // Restoring "the field after zero strokes" is just clear().
        val plan = ReplayCheckpoints.plan(log(10, 10, 10), checkpoint = emptyList())
        assertFalse(plan.restoreCheckpoint)
        assertEquals(0, plan.replayFrom)
    }

    // ---- Reusing ------------------------------------------------------

    @Test
    fun aPrefixCheckpointSkipsTheStrokesItCovers() {
        val strokes = log(10, 10, 10, 10, 10)
        val plan = ReplayCheckpoints.plan(strokes, strokes.take(3))
        assertTrue(plan.restoreCheckpoint)
        assertEquals(3, plan.replayFrom)
    }

    @Test
    fun structurallyEqualStrokesMatchAcrossAReload() {
        // A project reloaded from disk deserializes fresh instances; the
        // checkpoint should survive that, not silently stop working.
        val strokes = log(10, 10, 10, 10)
        val reloaded = strokes.map { it.copy(stamps = it.stamps.toList()) }
        val plan = ReplayCheckpoints.plan(reloaded, strokes.take(2))
        assertTrue(plan.restoreCheckpoint)
        assertEquals(2, plan.replayFrom)
    }

    @Test
    fun theSingleUndoCaseReplaysNothing() {
        // The motivating case. A long pumped hold, then two more strokes,
        // then undo: the checkpoint sits at size - TAIL_STROKES, so after
        // one undo it still covers everything that remains.
        val strokes = log(300, 5, 5)
        val checkpointAt = ReplayCheckpoints.plan(strokes, null).snapshotAfter
        assertEquals(1, checkpointAt)

        val afterUndo = strokes.dropLast(1)
        val plan = ReplayCheckpoints.plan(afterUndo, strokes.take(checkpointAt!!))
        assertTrue(plan.restoreCheckpoint)
        // One stroke left to replay instead of 300 stamps' worth.
        assertEquals(1, plan.replayFrom)
        assertEquals(2, afterUndo.size)
    }

    @Test
    fun undoingPastTheTailCostsOneFullReplayAndThenRecovers() {
        val strokes = log(300, 5, 5)
        val checkpoint = strokes.take(1)
        val deepUndo = strokes.take(1).dropLast(1) // back to empty
        val plan = ReplayCheckpoints.plan(deepUndo, checkpoint)
        assertFalse(plan.restoreCheckpoint)
        assertEquals(0, plan.replayFrom)
    }

    // ---- Deciding when to snapshot ------------------------------------

    @Test
    fun aShortLogIsNotWorthSnapshotting() {
        assertNull(ReplayCheckpoints.plan(log(5, 5, 5), null).snapshotAfter)
        assertNull(ReplayCheckpoints.plan(emptyList(), null).snapshotAfter)
        assertNull(ReplayCheckpoints.plan(log(5), null).snapshotAfter)
    }

    @Test
    fun onePumpedHoldIsEnoughToEarnACheckpoint() {
        // The whole point: a single stroke can carry the cost that makes
        // a checkpoint worth taking, so the trigger is stamps, not
        // strokes. A stroke-count rule would never fire here.
        val strokes = log(ReplayCheckpoints.MIN_SAVED_STAMPS, 1, 1)
        assertEquals(1, ReplayCheckpoints.plan(strokes, null).snapshotAfter)
    }

    @Test
    fun manySmallStrokesAlsoAddUp() {
        val strokes = List(60) { stroke(4) } // 240 stamps
        val plan = ReplayCheckpoints.plan(strokes, null)
        assertEquals(58, plan.snapshotAfter)
    }

    @Test
    fun theCheckpointLeavesRoomForUndo() {
        val strokes = List(60) { stroke(4) }
        val at = ReplayCheckpoints.plan(strokes, null).snapshotAfter!!
        assertEquals(ReplayCheckpoints.TAIL_STROKES, strokes.size - at)
    }

    @Test
    fun aCheckpointNeverMovesBackwards() {
        // Replaying from a checkpoint must not propose an earlier one:
        // that would make the next rebuild slower than this one.
        val strokes = List(60) { stroke(4) }
        val plan = ReplayCheckpoints.plan(strokes, strokes.take(59))
        val at = plan.snapshotAfter
        assertTrue(at == null || at > plan.replayFrom)
    }

    @Test
    fun aSecondCheckpointWaitsUntilItWouldSaveSomethingAgain() {
        val strokes = List(60) { stroke(4) }
        // Already covered to 58; the two remaining strokes are 8 stamps,
        // nowhere near the threshold.
        assertNull(ReplayCheckpoints.plan(strokes, strokes.take(58)).snapshotAfter)
    }

    @Test
    fun theProposedSnapshotIsAlwaysInsideTheReplayLoop() {
        // The caller takes the snapshot while walking replayFrom until
        // size, so an index outside that half-open range would silently
        // never be taken.
        val cases = listOf(
            log(300, 5, 5),
            List(60) { stroke(4) },
            log(1000, 1, 1, 1, 1),
            log(200, 200, 200),
        )
        for (strokes in cases) {
            val plan = ReplayCheckpoints.plan(strokes, null)
            val at = plan.snapshotAfter ?: continue
            assertTrue("$at not in [${plan.replayFrom}, ${strokes.size})", at > plan.replayFrom)
            assertTrue("$at not in [${plan.replayFrom}, ${strokes.size})", at < strokes.size)
        }
    }
}
