package ch.lkmc.goo.engine.core

import kotlin.math.sqrt

/**
 * Turns a raw touch path into evenly spaced stamps.
 *
 * Touch events arrive at unpredictable spacing (fast flicks skip whole
 * regions; MotionEvent batching clusters samples). Kernels stamped directly
 * at event positions would print gaps or clumps into the field, so the
 * path is resampled: one stamp every `SPACING_FRACTION · radius` of
 * aspect-space arc length, positions interpolated along the segment, each
 * stamp displacing by exactly its share of the segment's movement.
 *
 * The fractional remainder carries across segments within one stroke, so
 * stamp density is uniform no matter how the path was chopped into events.
 * One resampler instance serves one stroke: [begin], then [extend] per
 * input point, then discard.
 */
class StrokeResampler(
    private val radius: Float,
    private val aspect: Float,
    private val spacingFraction: Float = SPACING_FRACTION,
) {
    init {
        // A non-positive radius would make the spacing walk in extend()
        // loop forever. The editor clamps its radii, but this class is the
        // contract for every future caller (project-file loading included).
        require(radius > 0f && spacingFraction > 0f) {
            "radius and spacing must be positive: r=$radius sf=$spacingFraction"
        }
        require(aspect > 0f) { "aspect must be positive: $aspect" }
    }

    private var lastU = 0f
    private var lastV = 0f
    private var lastStampU = 0f
    private var lastStampV = 0f
    private var started = false
    /** Aspect-space distance still to travel before the next stamp drops. */
    private var toNext = 0f

    fun begin(u: Float, v: Float) {
        lastU = u
        lastV = v
        lastStampU = u
        lastStampV = v
        started = true
        // The first stamp drops much sooner than the rest (user-reported:
        // "I have to drag 20px before the image reacts").
        //
        // A stationary finger must still drag nothing, which is why this
        // is not zero. But that only needs a distance above touch jitter,
        // and it used to be a full spacing — a quarter of the brush
        // radius, so 0.03 of image height at the default size and 0.07 at
        // the largest. On a phone that is 30-80px of dead travel before
        // anything moves, and the brush felt like it had a lower
        // resolution than the screen.
        //
        // Bringing it forward costs no extra warp. Each stamp's delta is
        // measured from the PREVIOUS stamp's centre, so the deltas
        // telescope to the total path travelled however the path is
        // chopped up: an earlier first stamp splits the same displacement
        // into more pieces rather than adding any. It only starts sooner.
        //
        // Fixed distance rather than a fraction of the radius, so a fat
        // brush is as responsive as a thin one — CAPPED at the
        // steady-state spacing, since a very small brush should never
        // wait LONGER for its first stamp than for its second.
        toNext = minOf(spacingFraction * radius, FIRST_TRAVEL)
    }

    /**
     * Consume the path segment from the previous point to `(u, v)`,
     * appending any stamps that fall on it to [out]. Returns [out].
     */
    fun extend(u: Float, v: Float, out: MutableList<Stamp>): MutableList<Stamp> {
        check(started) { "extend() before begin()" }
        val du = u - lastU
        val dv = v - lastV
        // Arc length in aspect space, where circles are round.
        val stepU = du * aspect
        val segment = sqrt(stepU * stepU + dv * dv)
        if (segment <= 0f) return out

        // Walk the segment, dropping a stamp every spacing interval. Delta is
        // measured from the previous emitted center, not inferred from this
        // segment alone: one interval can straddle a path corner, and both
        // sides of that corner must contribute to the displacement.
        val spacing = spacingFraction * radius
        var travelled = 0f
        while (travelled + toNext <= segment) {
            travelled += toNext
            val t = travelled / segment
            val cx = lastU + du * t
            val cy = lastV + dv * t
            out.add(
                Stamp(
                    cx = cx,
                    cy = cy,
                    dx = cx - lastStampU,
                    dy = cy - lastStampV,
                ),
            )
            lastStampU = cx
            lastStampV = cy
            toNext = spacing
        }
        toNext -= segment - travelled
        lastU = u
        lastV = v
        return out
    }

    companion object {
        /**
         * Stamp every quarter radius: dense enough that smoothstep kernels
         * overlap into a crease-free trough, sparse enough to stay cheap
         * (research-standard ~25% for Liquify-style brushes).
         */
        const val SPACING_FRACTION = 0.25f

        /**
         * Aspect-space travel before the FIRST stamp of a stroke — a
         * fraction of image height, so roughly 3-4 screen pixels on a
         * phone-sized preview.
         *
         * Chosen to sit above finger jitter and below perception. Too
         * small and a tap that rolls slightly leaves an invisible smear
         * plus an undo entry for it; too large and the brush feels like
         * it is ignoring the start of every stroke, which is the
         * complaint this constant exists to answer.
         *
         * **Known imperfection: this is image space, and jitter is
         * screen space.** The physical distance it represents therefore
         * moves with how large the photo is drawn — a taller fitted
         * image, or a zoomed-in view, means more screen pixels for the
         * same fraction. Zoom is the awkward direction: at 4x the gate
         * is about four times its unzoomed size in pixels, exactly when
         * the user is working precisely.
         *
         * Left as is on purpose. This is strictly better than what it
         * replaced at every zoom and every screen size (the old gate
         * scaled the same way AND was up to twenty times larger), and
         * fixing it properly means plumbing the view transform and the
         * fitted size into a class that is currently pure geometry with
         * no idea a screen exists. Worth doing if precise work while
         * zoomed still feels laggy; not worth the coupling on
         * speculation.
         */
        const val FIRST_TRAVEL = 0.004f
    }
}
