package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/** Stable identity for one immutable painted-field revision. */
@JvmInline
@Serializable
value class StrokeRevisionId(val value: Long)

/**
 * One immutable, structurally shared stroke-list state.
 *
 * [stateParent] is field ancestry, not undo chronology: Reset deliberately
 * cuts it to null, while [StrokeLog]'s history list still remembers the state
 * before Reset so the operation remains undoable.
 */
class StrokeRevision internal constructor(
    val id: StrokeRevisionId,
    internal val stateParent: StrokeRevision?,
    internal val appendedStroke: Stroke?,
    val strokeCount: Int,
) {
    /** Materialize oldest-first strokes for renderer/export boundaries. */
    fun materialize(): List<Stroke> {
        if (strokeCount == 0) return emptyList()
        val newestFirst = ArrayList<Stroke>(strokeCount)
        var revision: StrokeRevision? = this
        while (revision != null) {
            revision.appendedStroke?.let(newestFirst::add)
            revision = revision.stateParent
        }
        newestFirst.reverse()
        return newestFirst
    }
}

/**
 * The document (PLAN.md §5.5): an undoable history of committed strokes.
 *
 * History is a list of immutable, structurally shared revisions with a cursor
 * — undo/redo just move the cursor, so their semantics are trivially correct
 * and every state still in history remains reachable. A normal commit retains
 * one revision node and one `Stroke`, rather than copying the whole prefix.
 *
 * Reset is an ordinary entry (an empty snapshot): undo brings the goo
 * back. KPT Goo's one-click irreversible Reset was its most-criticized
 * flaw; this class is where that flaw is structurally fixed.
 *
 * Not thread-safe; confine to one thread (the ViewModel's main thread).
 */
class StrokeLog {

    private val root = StrokeRevision(
        id = StrokeRevisionId(0),
        stateParent = null,
        appendedStroke = null,
        strokeCount = 0,
    )
    private val history = mutableListOf(root)
    private var cursor = 0
    private var nextRevisionId = 1L
    // Only the active state is cached. Caching every revision's full prefix
    // would recreate the quadratic retained-reference problem this model
    // removes, while repeated playback/export reads need a stable O(1) view.
    private var materializedRevision = root
    private var materializedStrokes: List<Stroke> = emptyList()

    /** Stable handle for the current field state. */
    val currentRevision: StrokeRevision
        get() = history[cursor]

    /** The strokes that currently make up the picture, oldest first. */
    val strokes: List<Stroke>
        get() {
            val revision = currentRevision
            if (materializedRevision !== revision) {
                materializedRevision = revision
                materializedStrokes = revision.materialize()
            }
            return materializedStrokes
        }

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < history.lastIndex
    val isEmpty: Boolean get() = strokes.isEmpty()

    /** Commit a finished stroke. Drops any redoable future. */
    fun push(stroke: Stroke) {
        if (stroke.stamps.isEmpty()) return
        truncateFuture()
        // Read-only List does not guarantee immutable ownership. Freeze the
        // stamp batch at the document boundary so a caller cannot mutate a
        // committed revision through a retained MutableList reference.
        val ownedStroke = stroke.copy(stamps = stroke.stamps.toList())
        history.add(
            StrokeRevision(
                id = nextId(),
                stateParent = currentRevision,
                appendedStroke = ownedStroke,
                strokeCount = currentRevision.strokeCount + 1,
            ),
        )
        cursor++
    }

    /** Clear the picture as an undoable step. No-op when already empty. */
    fun reset() {
        if (currentRevision.strokeCount == 0) return
        truncateFuture()
        history.add(
            StrokeRevision(
                id = nextId(),
                stateParent = null,
                appendedStroke = null,
                strokeCount = 0,
            ),
        )
        cursor++
    }

    /** @return the restored strokes, or null if there was nothing to undo. */
    fun undo(): List<Stroke>? {
        if (!canUndo) return null
        cursor--
        return strokes
    }

    /** @return the restored strokes, or null if there was nothing to redo. */
    fun redo(): List<Stroke>? {
        if (!canRedo) return null
        cursor++
        return strokes
    }

    private fun truncateFuture() {
        while (history.lastIndex > cursor) history.removeAt(history.lastIndex)
    }

    private fun nextId(): StrokeRevisionId = StrokeRevisionId(nextRevisionId++)
}
