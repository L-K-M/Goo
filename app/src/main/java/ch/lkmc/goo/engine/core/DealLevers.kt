package ch.lkmc.goo.engine.core

/**
 * Lever restoration across a Goo Me deal (proposal 0007).
 *
 * Levers are document state and deliberately not history (PLAN.md §4.1):
 * pulling a slider back is its own undo, so undo/redo stay stroke-only.
 * That is right for a slider and wrong for a deal — the user taps Undo
 * expecting the joke gone, and a deal that left a twirl pulled leaves the
 * photo visibly warped by an edit the document says never happened.
 *
 * So a deal pins the lever table to the two revisions it sits between,
 * and history restores a pin when it crosses that boundary.
 *
 * ### Why the pin is an EDGE and not a revision
 *
 * Pinning "revision R means levers L" is the obvious design and it is
 * wrong, because a revision is not only reachable through the deal:
 *
 * 1. pull Twirl to 0.5, deal (pinning 0.5 on the pre-deal revision),
 * 2. undo — levers correctly come back to 0.5,
 * 3. pull Twirl to 0.8 by hand,
 * 4. paint a stroke, which truncates the deal's branch and grows a new
 *    one from that same pre-deal revision,
 * 5. undo the stroke — and a revision-keyed pin fires, silently
 *    replacing the user's own 0.8 with the 0.5 a deal that no longer
 *    exists once saw.
 *
 * Keyed by the (from, to) pair, step 5 is not a deal boundary at all and
 * nothing fires. The rule this encodes: a deal owns the *transition* it
 * made, never the states on either side of it.
 *
 * Pins for truncated branches are left behind rather than swept. They are
 * inert — revision ids are never reused, so a stale edge can never match
 * again — and there are two per deal — one per direction — for as long as the
 * session lasts.
 */
class DealLevers {

    private val pins = HashMap<Edge, GlobalParams>()

    private data class Edge(val from: StrokeRevisionId, val to: StrokeRevisionId)

    /**
     * Record a deal that moved the document from [before] to [after],
     * finding the levers at [was] and leaving them at [dealt].
     */
    fun pin(
        before: StrokeRevisionId,
        after: StrokeRevisionId,
        was: GlobalParams,
        dealt: GlobalParams,
    ) {
        if (before == after) return
        pins[Edge(after, before)] = was
        pins[Edge(before, after)] = dealt
    }

    /**
     * The levers to restore for a history move from [from] to [to], or
     * null when that move is not a deal boundary (which is almost always).
     */
    fun crossing(from: StrokeRevisionId, to: StrokeRevisionId): GlobalParams? =
        if (from == to) null else pins[Edge(from, to)]

    /** Pins currently held; for tests and for reasoning about growth. */
    val size: Int get() = pins.size
}
