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
    /**
     * Materialize oldest-first strokes for renderer/export boundaries.
     * Each call walks this revision's parent chain; repeated reads of the
     * current state should use [StrokeLog.strokes], which caches one result.
     */
    fun materialize(): List<Stroke> {
        if (strokeCount == 0) return emptyList()
        return buildList(strokeCount) {
            var revision: StrokeRevision? = this@StrokeRevision
            while (revision != null) {
                revision.appendedStroke?.let(::add)
                revision = revision.stateParent
            }
            reverse()
        }
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

    /**
     * Commit several strokes as ONE undoable step (the Goo Me deal,
     * proposal 0007). The strokes still become separate revisions — the
     * field is built by replaying them in order, and a batch is not a
     * different kind of edit — but only the last one enters the history
     * list, so the cursor steps over the whole batch at once. "Undo the
     * joke" is one tap, not three.
     *
     * The intermediate revisions stay reachable as ancestors, which is
     * all [snapshot] and [materialize] ever needed of them.
     */
    fun pushBatch(strokes: List<Stroke>) {
        val batch = strokes.filter { it.stamps.isNotEmpty() }
        if (batch.isEmpty()) return
        truncateFuture()
        var head = currentRevision
        for (stroke in batch) {
            head = StrokeRevision(
                id = nextId(),
                stateParent = head,
                appendedStroke = stroke.copy(stamps = stroke.stamps.toList()),
                strokeCount = head.strokeCount + 1,
            )
        }
        history.add(head)
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

    /**
     * Erase the document AND its history. For document-space changes
     * (crop) where every recorded stroke's UVs stop meaning what they
     * meant: unlike [reset], nothing is reachable afterwards — undoing
     * across a reframe would replay old-space strokes onto new-space
     * pixels. The one sanctioned exception to "every state ever shown
     * remains reachable" above.
     */
    fun clearHistory() {
        // A fresh id, not a reused root: two distinct revisions must never
        // share an id (endpoint caches and keyframe pins rely on it).
        val fresh = StrokeRevision(
            id = nextId(),
            stateParent = null,
            appendedStroke = null,
            strokeCount = 0,
        )
        history.clear()
        history.add(fresh)
        cursor = 0
        materializedRevision = fresh
        materializedStrokes = emptyList()
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

    // ---- Persistence ---------------------------------------------------
    // The document goes to disk as a flat revision table (see
    // StrokeLogSnapshot). Everything below is pure data movement: no
    // Android types, so the round trip is covered by the JVM suite.

    /**
     * Serialize this log. [pins] are extra revisions to keep alive —
     * GOOvie keyframes, which may pin states the history has truncated —
     * and their ancestors ride along, so a loaded project can rebuild
     * every pin by id.
     *
     * Records come out parent-before-child: a revision's parent always
     * has a smaller id (it existed first), so sorting by id topologically
     * sorts the table, and [restore] can insist on that order rather than
     * chasing forward references.
     */
    fun snapshot(pins: List<StrokeRevision> = emptyList()): StrokeLogSnapshot {
        val reachable = HashMap<Long, StrokeRevision>()
        for (root in history + pins) {
            var revision: StrokeRevision? = root
            // Stop at the first ancestor already collected: everything
            // above it came in with that earlier walk.
            while (revision != null && reachable.put(revision.id.value, revision) == null) {
                revision = revision.stateParent
            }
        }
        return StrokeLogSnapshot(
            revisions = reachable.values
                .sortedBy { it.id.value }
                .map { revision ->
                    StrokeRevisionRecord(
                        id = revision.id.value,
                        parent = revision.stateParent?.id?.value,
                        stroke = revision.appendedStroke,
                    )
                },
            history = history.map { it.id.value },
            cursor = cursor,
        )
    }

    /**
     * Replace this log's contents with [snapshot].
     *
     * @return every restored revision by id — the caller needs it to
     * re-resolve keyframe pins — or null when the snapshot is malformed,
     * in which case this log is left exactly as it was. A corrupt or
     * hand-edited project file must degrade to "can't open that", never
     * to a half-restored document that undoes into nonsense.
     *
     * Validity is structural: unique ids, parents that appear earlier in
     * the table (so the graph is acyclic by construction), a non-empty
     * history whose ids all resolve, and a cursor inside it.
     */
    fun restore(snapshot: StrokeLogSnapshot): Map<StrokeRevisionId, StrokeRevision>? {
        if (snapshot.history.isEmpty()) return null
        if (snapshot.cursor !in snapshot.history.indices) return null
        val table = HashMap<Long, StrokeRevision>(snapshot.revisions.size)
        for (record in snapshot.revisions) {
            if (table.containsKey(record.id)) return null
            val parent = record.parent?.let { table[it] ?: return null }
            // A stroke with no stamps cannot be produced by push (it is
            // dropped there), and it would make strokeCount lie about
            // what a replay draws. Treat it as corruption.
            if (record.stroke != null && record.stroke.stamps.isEmpty()) return null
            table[record.id] = StrokeRevision(
                id = StrokeRevisionId(record.id),
                stateParent = parent,
                appendedStroke = record.stroke,
                strokeCount = (parent?.strokeCount ?: 0) + if (record.stroke != null) 1 else 0,
            )
        }
        val restored = snapshot.history.map { table[it] ?: return null }
        history.clear()
        history.addAll(restored)
        cursor = snapshot.cursor
        // Ids are handed out fresh from here on: reusing one would let a
        // renderer cache (or a keyframe pin) answer for the wrong state.
        nextRevisionId = (table.keys.maxOrNull() ?: -1L) + 1L
        materializedRevision = currentRevision
        materializedStrokes = currentRevision.materialize()
        return table.mapKeys { (id, _) -> StrokeRevisionId(id) }
    }
}
