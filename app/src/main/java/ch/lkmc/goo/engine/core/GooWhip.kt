package ch.lkmc.goo.engine.core

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The ballistic tail a flick leaves behind (proposal 0015).
 *
 * Every other brush stops when the finger stops. Whip keeps going: at
 * release, the drag's velocity launches a *virtual* brush that carries
 * on past the lift and decays to a stop, so the goo streams out into a
 * point the way snapping a wet ribbon does.
 *
 * ### The tail is computed once and then it is just stamps
 *
 * This is the whole design. Input timing chooses the mark, these fixed
 * constants generate its path, the resampler turns that path into
 * ordinary stamps, and the log stores the answer. Replay — undo,
 * full-resolution export, context-loss recovery, a GOOvie tween — never
 * sees a velocity or a decay constant, only centers and deltas.
 *
 * A tail regenerated at replay time would be a different mark on every
 * export, because it would depend on input timing that no longer exists.
 *
 * ### Why the speed is quantized
 *
 * Pointer timestamps jitter, so two flicks a human cannot tell apart
 * produce measurably different velocities. Rounding the release speed to
 * a step means the same flick makes the same mark, and — more usefully —
 * that a mark you liked is one you can repeat.
 */
object GooWhip {

    /**
     * All speeds are aspect-space units per second: 1.0 is one image
     * height per second, the same units [Stroke.radius] is measured in.
     */

    /** Below this release speed there is no tail at all — a slow lift. */
    const val START_SPEED = 0.8f

    /**
     * The cap. A whip is a mark, not a physics toy: past this the tail
     * stops getting longer, so a violent flick cannot fling content
     * across the whole picture and off the far edge.
     */
    const val MAX_SPEED = 8f

    /** Release speeds round to this step. See the note above. */
    const val SPEED_QUANTUM = 0.25f

    /** The virtual brush stops when it slows below this. */
    const val STOP_SPEED = 0.15f

    /** Simulation step. Fixed, and unrelated to the display's frame rate. */
    const val STEP_SECONDS = 1f / 60f

    /** Velocity retained per step. */
    const val DECAY = 0.88f

    /**
     * Hard ceiling on tail steps. With [DECAY] the walk stops itself
     * long before this; the bound is here so no arithmetic accident can
     * produce an unbounded stroke in the document.
     */
    const val MAX_STEPS = 64

    /**
     * The path a release at ([u], [v]) traces, given a release velocity
     * of ([velU], [velV]) in UV units per second.
     *
     * Returns the points AFTER the release point, in order, in UV — feed
     * them to the same [StrokeResampler] a real drag uses, so a whip's
     * stamp spacing and per-stamp deltas are produced by exactly the
     * code that produces them for a finger.
     *
     * Empty when the flick was too slow to throw anything, which is also
     * what makes a tap and a slow lift leave no tail.
     */
    fun tail(
        u: Float,
        v: Float,
        velU: Float,
        velV: Float,
        aspect: Float,
    ): List<Pair<Float, Float>> {
        require(aspect > 0f) { "aspect must be positive: $aspect" }
        // Speed is measured in aspect space, where a circle is round, so
        // a horizontal flick and a vertical one of the same visual speed
        // throw the same distance on a non-square image.
        val ax = velU * aspect
        val ay = velV
        val raw = sqrt(ax * ax + ay * ay)
        // isFinite first, and not merely for tidiness. A velocity
        // tracker fed two samples with the same timestamp can return an
        // infinity, which passes every ordinary comparison (IEEE 754
        // says NaN compares false, and Infinity is not < START_SPEED) —
        // and then `quantized / raw` is zero, `Infinity * 0` is NaN, and
        // NaN centers reach the resampler. A NaN stamp committed to the
        // log is permanent: every later replay and export reproduces it.
        if (!raw.isFinite() || raw < START_SPEED) return emptyList()

        // Compared against START_SPEED and not against zero: rounding
        // can carry a release that just cleared the threshold back under
        // it, and a tail thrown at a speed the tool documents as "too
        // slow to throw" is a mark the rules say should not exist.
        val quantized = quantize(raw)
        if (quantized < START_SPEED) return emptyList()
        // Rescale the direction vector to the quantized speed rather than
        // rebuilding it, so the aim of the flick survives exactly.
        val scale = quantized / raw
        var vx = ax * scale
        var vy = ay * scale

        val out = ArrayList<Pair<Float, Float>>(MAX_STEPS)
        var x = u * aspect
        var y = v
        for (step in 0 until MAX_STEPS) {
            x += vx * STEP_SECONDS
            y += vy * STEP_SECONDS
            out.add(Pair(x / aspect, y))
            vx *= DECAY
            vy *= DECAY
            if (sqrt(vx * vx + vy * vy) < STOP_SPEED) break
        }
        return out
    }

    /** Release speed rounded to [SPEED_QUANTUM] and capped at [MAX_SPEED]. */
    fun quantize(speed: Float): Float {
        val capped = speed.coerceAtMost(MAX_SPEED)
        return (capped / SPEED_QUANTUM).roundToInt() * SPEED_QUANTUM
    }
}
