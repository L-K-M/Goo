package ch.lkmc.goo.engine.core

/**
 * What a rebuild has to redo, and what it should save for the next one
 * (REVIEW.md G-6).
 *
 * Undo, redo and Reset all rebuild the field by replaying the stroke log
 * from identity. That is fine until a pumped tool is involved: Grow and
 * friends emit a stamp every [BrushDynamics.PUMP_INTERVAL_MS], so a
 * five-second hold is one stroke carrying ~300 stamps, and every undo
 * afterwards re-runs all 300 field passes to get back to a state it had
 * moments ago.
 *
 * Stamps cannot be merged away — warp-of-warp compounding *is* the pump
 * feel, so 300 small stamps are not one big one. What can be avoided is
 * replaying a prefix whose result is already known, which is what a field
 * snapshot buys: keep a copy of the field after the first K strokes and
 * a later rebuild starts there instead of at identity.
 */
data class ReplayPlan(
    /**
     * Restore the snapshot before replaying. When false the caller clears
     * to identity, exactly as it always did.
     */
    val restoreCheckpoint: Boolean,
    /** First index of `strokes` that still has to be replayed. */
    val replayFrom: Int,
    /**
     * Prefix length to snapshot once replay reaches it, or null to leave
     * the existing checkpoint alone. Always greater than [replayFrom] and
     * less than the stroke count, so the caller can take it inside the
     * replay loop.
     */
    val snapshotAfter: Int?,
)

object ReplayCheckpoints {

    /**
     * How many strokes at the head are deliberately left outside the
     * checkpoint.
     *
     * A checkpoint is only usable while it remains a prefix of the log,
     * so one placed at the very head would be destroyed by the first
     * undo — the exact operation it exists to accelerate. Holding it two
     * strokes back means single and double undo still land on or after
     * it and replay almost nothing. Undoing deeper than that invalidates
     * it and pays one full replay, after which a new checkpoint forms.
     */
    const val TAIL_STROKES = 2

    /**
     * Least replay work a checkpoint must save to be worth taking, in
     * stamps.
     *
     * A snapshot costs a full-field blit, which is roughly what a single
     * unscissored stamp pass used to cost. Below this the bookkeeping is
     * more expensive than the thing it avoids; ~192 stamps is around
     * three seconds of pumping, or a few dozen ordinary drag strokes.
     */
    const val MIN_SAVED_STAMPS = 192

    /**
     * Plan a rebuild of [strokes] given the prefix an existing checkpoint
     * was taken from ([checkpoint], null when there is none).
     *
     * The checkpoint is usable when it is still a prefix of the log.
     * Comparison is by value: `Stroke` is a data class whose generated
     * `equals` short-circuits on identity, so the common case (undo and
     * redo re-materialize structurally shared revisions — SOL-6) costs a
     * reference check per stroke, while a log rebuilt from disk with
     * fresh instances still matches instead of silently falling back.
     *
     * Being wrong in the conservative direction is free: a checkpoint
     * rejected when it would have matched costs one full replay, which
     * is what happened before this existed.
     */
    fun plan(strokes: List<Stroke>, checkpoint: List<Stroke>?): ReplayPlan {
        val usable = checkpoint != null &&
            checkpoint.isNotEmpty() &&
            checkpoint.size <= strokes.size &&
            isPrefix(checkpoint, strokes)
        // K2 smart-casts `checkpoint` here from the null check inside
        // `usable`, so no assertion is needed to read its size.
        val replayFrom = if (usable) checkpoint.size else 0
        return ReplayPlan(
            restoreCheckpoint = usable,
            replayFrom = replayFrom,
            snapshotAfter = nextCheckpoint(strokes, replayFrom),
        )
    }

    private fun isPrefix(prefix: List<Stroke>, strokes: List<Stroke>): Boolean {
        for (i in prefix.indices) if (prefix[i] != strokes[i]) return false
        return true
    }

    /**
     * The prefix length worth snapshotting on this rebuild, or null.
     *
     * Only ever moves forward: a checkpoint already covering more than
     * the candidate is left alone rather than rewound, so a rebuild
     * cannot make the next one slower.
     */
    private fun nextCheckpoint(strokes: List<Stroke>, replayFrom: Int): Int? {
        val candidate = strokes.size - TAIL_STROKES
        if (candidate <= replayFrom) return null
        var stamps = 0
        for (i in replayFrom until candidate) {
            stamps += strokes[i].stamps.size
            // Early exit: the total is only used against a threshold, and
            // a long log should not be counted twice over.
            if (stamps >= MIN_SAVED_STAMPS) return candidate
        }
        return null
    }
}
