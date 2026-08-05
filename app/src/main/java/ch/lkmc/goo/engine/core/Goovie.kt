package ch.lkmc.goo.engine.core

/**
 * One GOOvie keyframe (PLAN.md §4.1): a stable revision pin plus the lever
 * values at capture, not a bitmap. The GPU materializes fields on demand by
 * replay. Reordering keyframes reorders playback; history branching cannot
 * change the immutable revision each frame points to.
 */
data class Keyframe(
    val revision: StrokeRevision,
    val globals: GlobalParams,
) {
    val revisionId: StrokeRevisionId
        get() = revision.id
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
