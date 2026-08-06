package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/**
 * The on-disk form of a [StrokeLog] — a NORMALIZED revision table, never
 * a recursive graph (AGENTS.md).
 *
 * Revisions are structurally shared: 64 keyframes can pin 64 nodes that
 * all hang off the same parent chain, and the current head usually shares
 * every ancestor with the pin behind it. Serializing a revision by writing
 * its parent inline would therefore rewrite that shared prefix once per
 * reference — quadratic on disk, and it would load back as a forest of
 * duplicated nodes with no sharing left, which is exactly the retained-
 * reference problem [StrokeLog] exists to avoid.
 *
 * So the wire form is flat: every reachable revision appears exactly once
 * in [revisions], parents before children, and everything else (the undo
 * history, the keyframe pins in the project file) refers to them by id.
 */
@Serializable
data class StrokeRevisionRecord(
    val id: Long,
    /** The state this revision was appended to; null = a root (Reset). */
    val parent: Long? = null,
    /** The stroke this revision added; null on roots. */
    val stroke: Stroke? = null,
)

/**
 * One serialized stroke log: the reachable revision table, the undo
 * history as ids, and the cursor into it.
 *
 * [revisions] is a superset of [history]: a keyframe can pin a revision
 * the history has since truncated (the redo branch after a new stroke),
 * and that pin has to survive a save/load round trip or the strip would
 * come back flattened. [StrokeLog.snapshot] takes those pins as roots.
 */
@Serializable
data class StrokeLogSnapshot(
    val revisions: List<StrokeRevisionRecord> = emptyList(),
    /** Undo history, oldest first, as revision ids. Never empty when valid. */
    val history: List<Long> = emptyList(),
    /** Index into [history] of the state that was on screen. */
    val cursor: Int = 0,
)
