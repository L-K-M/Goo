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
    /**
     * Aspect-space travel before the FIRST stamp. Compute it with
     * [firstTravelFor] so it means a constant number of DP on screen
     * whatever the zoom; the default is the fallback that class uses
     * when the screen geometry is unknown.
     */
    private val firstTravel: Float = FALLBACK_FIRST_TRAVEL,
) {
    init {
        // A non-positive radius would make the spacing walk in extend()
        // loop forever. The editor clamps its radii, but this class is the
        // contract for every future caller (project-file loading included).
        require(radius > 0f && spacingFraction > 0f) {
            "radius and spacing must be positive: r=$radius sf=$spacingFraction"
        }
        require(aspect > 0f) { "aspect must be positive: $aspect" }
        require(firstTravel > 0f && firstTravel.isFinite()) {
            "first travel must be positive: $firstTravel"
        }
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
        //
        // [firstTravel] arrives already converted from DP by
        // [firstTravelFor], so the gate is a constant physical distance
        // however the photo is scaled on screen. It used to be a flat
        // fraction of image height, which meant the dead zone GREW with
        // zoom — four times its size at 4x, exactly when the user is
        // working precisely.
        toNext = minOf(spacingFraction * radius, firstTravel)
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
         * How far the finger must travel before the FIRST stamp, in DP.
         *
         * DP because the thing this sits above — finger jitter — is
         * physical. Chosen above jitter and below perception: too small
         * and a tap that rolls slightly leaves an invisible smear plus
         * an undo entry for it; too large and the brush feels like it is
         * ignoring the start of every stroke.
         *
         * Well under `ViewConfiguration`'s ~8dp touch slop, deliberately.
         * That number exists to tell a tap from a scroll in a list; a
         * drawing surface has no such ambiguity to resolve and should
         * not pay for one.
         */
        const val FIRST_TRAVEL_DP = 2f

        /**
         * The gate when the screen geometry is unknown — a plain
         * fraction of image height, roughly [FIRST_TRAVEL_DP] on a
         * phone-sized preview at zoom 1.
         *
         * Used by callers that have no view (tests, replay) and as the
         * answer when [firstTravelFor] is handed something it cannot
         * divide by. Being wrong here costs responsiveness, never
         * correctness: stamps are stored, and a replay never runs this
         * class at all.
         */
        const val FALLBACK_FIRST_TRAVEL = 0.004f

        /**
         * [FIRST_TRAVEL_DP] expressed in aspect space, given how tall
         * the photo is drawn RIGHT NOW.
         *
         * One aspect unit is one image height, so the conversion is just
         * "how many screen pixels is an image height" — the fitted
         * height times the view's zoom. Feeding the zoomed height in is
         * the whole point: at 4x an image height covers four times the
         * pixels, so the same physical 2dp is a quarter of the
         * aspect-space distance, and precise work stays precise.
         *
         * @param imageHeightPx fitted image height × view scale.
         * @param density screen pixels per DP.
         */
        fun firstTravelFor(imageHeightPx: Float, density: Float): Float {
            // A view that has not been measured yet, or a degenerate
            // transform. Fall back rather than divide — an infinity here
            // would make the first stamp wait forever, which reads as
            // "the brush stopped working".
            if (!imageHeightPx.isFinite() || imageHeightPx <= 0f) return FALLBACK_FIRST_TRAVEL
            if (!density.isFinite() || density <= 0f) return FALLBACK_FIRST_TRAVEL
            // The two guards above bound the INPUTS; this one bounds the
            // RESULT, and it is reachable in both directions. A density
            // of Float.MAX_VALUE is finite and positive and overflows
            // the multiply to Infinity; a denormal density over a huge
            // height underflows the divide to exactly 0. Both were
            // checked, and both are pinned by test — one review round
            // called this dead code, and its own previous round had
            // called it reachable.
            val travel = FIRST_TRAVEL_DP * density / imageHeightPx
            return if (travel.isFinite() && travel > 0f) travel else FALLBACK_FIRST_TRAVEL
        }
    }
}
