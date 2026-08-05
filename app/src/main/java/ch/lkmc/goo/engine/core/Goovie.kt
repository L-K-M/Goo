package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/**
 * One GOOvie keyframe (PLAN.md §4.1): a pin into the document, not a
 * bitmap — the stroke-log position plus the lever values at capture.
 * Kilobytes for a whole movie; the GPU materializes fields on demand by
 * replay. Reordering keyframes reorders playback, the pins stay put.
 *
 * A pin is a bookmark, not a canvas: there is no "editing keyframe 2".
 * You change what a keyframe shows by gooing the photo and re-punching
 * it (EditorViewModel.repunchSelectedKeyframe).
 */
@Serializable
data class Keyframe(
    /** Number of committed strokes included (log prefix length). */
    val strokeCount: Int,
    val globals: GlobalParams,
)

/**
 * What the strip should say out loud under the beads.
 *
 * A GOOvie only moves when consecutive keyframes pin DIFFERENT document
 * states, and a pin is taken at punch time — the two facts every
 * first-time user has to discover, and the two that make "I punched two
 * keyframes and nothing happens" the classic first bug report. The strip
 * names the current situation instead of leaving them to guess.
 */
enum class GoovieHint {
    /** No keyframes yet. */
    EMPTY,

    /** Gooing live with a keyframe selected: Punch adds, Update re-pins. */
    LIVE_PINNED,

    /** Gooing live with nothing selected: Punch pins what you see. */
    LIVE,

    /** One keyframe — a strip needs two to tween. */
    NEEDS_SECOND,

    /** Two or more, all pinning the same state: a movie that can't move. */
    ALL_SAME,

    /** The selected pin is behind the live document. */
    STALE,

    /** Nothing to say; scrub or play. */
    READY,
}

/**
 * Pick the strip's nudge. Pure so the wording logic is unit-testable and
 * stays out of the composable (AGENTS.md).
 */
fun goovieHint(
    keyframes: List<Keyframe>,
    selected: Int,
    selectedStale: Boolean,
    live: Boolean,
): GoovieHint = when {
    keyframes.isEmpty() -> GoovieHint.EMPTY
    live && selected in keyframes.indices -> GoovieHint.LIVE_PINNED
    live -> GoovieHint.LIVE
    keyframes.size == 1 -> GoovieHint.NEEDS_SECOND
    // The user's punch-punch-nothing-moves case: identical pins tween to
    // a still frame. Say so rather than exporting a dead loop.
    keyframes.all { it == keyframes[0] } -> GoovieHint.ALL_SAME
    selectedStale -> GoovieHint.STALE
    else -> GoovieHint.READY
}

/**
 * Pure timeline math for scrubbing and playback over a keyframe list.
 * Position `p` is continuous in [0, size-1]: integer values sit on
 * keyframes, fractions tween between neighbors.
 */
object GoovieTimeline {

    /** Seconds per segment during playback — KPT-ish steady amble. */
    const val SECONDS_PER_SEGMENT = 1.2f

    /** Segment start index for position [p] in a [size]-keyframe strip. */
    fun segment(p: Float, size: Int): Int =
        if (size < 2) 0 else p.toInt().coerceIn(0, size - 2)

    /** Tween fraction within [p]'s segment. */
    fun fraction(p: Float, size: Int): Float {
        if (size < 2) return 0f
        val s = segment(p, size)
        return (p - s).coerceIn(0f, 1f)
    }

    /** Clamp a position to the strip. */
    fun clamp(p: Float, size: Int): Float =
        if (size == 0) 0f else p.coerceIn(0f, (size - 1).toFloat())

    /**
     * Advance playback by [dtSeconds], looping to the start past the end.
     * A strip needs two keyframes to move; otherwise stays at 0.
     */
    fun advance(p: Float, dtSeconds: Float, size: Int): Float {
        if (size < 2) return 0f
        val span = (size - 1).toFloat()
        val next = p + dtSeconds / SECONDS_PER_SEGMENT
        // mod, not a single subtraction: the frame clock stops while the
        // app is backgrounded, so a resume can deliver a dt spanning many
        // loops — one subtraction would leave p far past the end and the
        // playback pinned there for hundreds of frames.
        return if (next >= span) next.mod(span) else next
    }

    /**
     * A keyframe's stroke pin, clamped to what the log still holds —
     * undo followed by a new stroke truncates the redo branch, and any
     * keyframe pointing past the cut simply shows the truncated state.
     */
    fun clampCount(keyframe: Keyframe, logSize: Int): Int =
        keyframe.strokeCount.coerceIn(0, logSize)
}

/** Linear lever interpolation for tween scrubbing. */
fun GlobalParams.lerp(other: GlobalParams, t: Float): GlobalParams = GlobalParams(
    bulge = bulge + (other.bulge - bulge) * t,
    twirl = twirl + (other.twirl - twirl) * t,
    squeeze = squeeze + (other.squeeze - squeeze) * t,
    stretch = stretch + (other.stretch - stretch) * t,
    spike = spike + (other.spike - spike) * t,
    static = static + (other.static - static) * t,
)
