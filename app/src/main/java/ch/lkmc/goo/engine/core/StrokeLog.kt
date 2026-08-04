package ch.lkmc.goo.engine.core

/**
 * The document (PLAN.md §5.5): an undoable history of committed strokes.
 *
 * History is a list of immutable log snapshots with a cursor — undo/redo
 * just move the cursor, so their semantics are trivially correct and every
 * state ever shown remains reachable. Snapshots share `Stroke` instances,
 * so memory cost is one list cell per entry, not a copy of the strokes.
 *
 * Reset is an ordinary entry (an empty snapshot): undo brings the goo
 * back. KPT Goo's one-click irreversible Reset was its most-criticized
 * flaw; this class is where that flaw is structurally fixed.
 *
 * Not thread-safe; confine to one thread (the ViewModel's main thread).
 */
class StrokeLog {

    private val history = mutableListOf<List<Stroke>>(emptyList())
    private var cursor = 0

    /** The strokes that currently make up the picture, oldest first. */
    val strokes: List<Stroke>
        get() = history[cursor]

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < history.lastIndex
    val isEmpty: Boolean get() = strokes.isEmpty()

    /** Commit a finished stroke. Drops any redoable future. */
    fun push(stroke: Stroke) {
        if (stroke.stamps.isEmpty()) return
        truncateFuture()
        history.add(strokes + stroke)
        cursor++
    }

    /** Clear the picture as an undoable step. No-op when already empty. */
    fun reset() {
        if (strokes.isEmpty()) return
        truncateFuture()
        history.add(emptyList())
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
}
