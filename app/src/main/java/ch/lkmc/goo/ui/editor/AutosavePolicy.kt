package ch.lkmc.goo.ui.editor

/**
 * When the editor writes a checkpoint while you are still gooing.
 *
 * `ON_STOP` already covers the way processes normally die — the system
 * reclaims a backgrounded app — but it cannot help a hard crash with the
 * editor in the foreground, and that is exactly the session with the most
 * unwritten work in it. So the document also checkpoints as it goes.
 *
 * Two clocks, because either one alone gets it wrong:
 *
 * - **Quiet.** Wait [QUIET_MS] after the last change and write then. Goo
 *   is made in bursts with pauses between them, and a pause is the moment
 *   a write costs the user nothing — no stroke in flight, and the frame
 *   the preview render borrows is one nobody is watching.
 * - **Ceiling.** Never let the document go unwritten for longer than
 *   [ONE_CHECKPOINT_MS]. Someone gooing steadily for ten minutes never
 *   pauses long enough to trip the quiet clock, and they are precisely
 *   the person with the most to lose.
 *
 * Pure so the arithmetic is unit-tested rather than eyeballed against a
 * running app (AGENTS.md: decision logic stays out of the loop that uses it).
 */
object AutosavePolicy {

    /** Idle time after a change that counts as "they stopped to look". */
    const val QUIET_MS = 10_000L

    /** Longest the document may differ from disk while editing continues. */
    const val ONE_CHECKPOINT_MS = 90_000L

    /**
     * How long to wait before checkpointing, given how long the document
     * has already differed from what is on disk ([dirtyForMs]).
     *
     * The quiet wait, clamped so it can never push the write past the
     * ceiling — and 0 once the ceiling is reached or passed, which is
     * what makes a continuous edit still produce checkpoints.
     */
    fun delayMs(dirtyForMs: Long): Long =
        minOf(QUIET_MS, (ONE_CHECKPOINT_MS - dirtyForMs).coerceAtLeast(0L))
}
