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
    private var lastU = 0f
    private var lastV = 0f
    private var started = false
    /** Aspect-space distance still to travel before the next stamp drops. */
    private var toNext = 0f

    fun begin(u: Float, v: Float) {
        lastU = u
        lastV = v
        started = true
        // The first stamp drops after a full spacing of travel, not at the
        // touch-down point: a Smear with zero movement must not disturb the
        // image (a stationary finger drags nothing).
        toNext = spacingFraction * radius
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

        // Walk the segment, dropping a stamp every spacing interval. Each
        // stamp displaces by the spacing's worth of movement so the summed
        // displacement over the stroke matches the finger's travel.
        val spacing = spacingFraction * radius
        var travelled = 0f
        while (travelled + toNext <= segment) {
            travelled += toNext
            val t = travelled / segment
            out.add(
                Stamp(
                    cx = lastU + du * t,
                    cy = lastV + dv * t,
                    dx = du * (spacing / segment),
                    dy = dv * (spacing / segment),
                ),
            )
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
    }
}
