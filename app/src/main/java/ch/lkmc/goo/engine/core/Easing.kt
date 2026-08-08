package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/**
 * How one GOOvie segment moves between its two keyframes (proposal
 * 0011).
 *
 * The tween has always been linear, which is the visual signature of
 * amateur animation and the first thing any animation course says to
 * stop doing. A curve on one scalar fixes it, and one of the curves is
 * free in a way that is easy to miss: `mix()` does not clamp, and
 * `u_tween` is a plain uniform float, so feeding it 1.15 EXTRAPOLATES
 * the field past the pose that was punched — the warp that would exist
 * if the user had kept gooing in the same direction. That is
 * squash-and-stretch with no new field, no stroke mode, no memory and no
 * export path.
 */
@Serializable
enum class Easing {
    /** Constant rate. What every existing GOOvie does. */
    LINEAR,

    /** Smoothstep: starts and stops gently, never leaves [0, 1]. */
    EASE,

    /**
     * Back-out: overshoots the pose, then settles into it. This is the
     * one that leaves [0, 1], and the only one whose constant has to be
     * chosen rather than derived.
     */
    BOING,
    ;

    /**
     * Maps segment progress [p] in [0, 1] to the tween parameter, which
     * for [BOING] may exceed 1.
     *
     * Every curve satisfies `at(0) == 0` and `at(1) == 1` exactly: a
     * segment must start and end ON its keyframes whatever it does in
     * between, or the strip would no longer mean what it shows.
     */
    fun at(p: Float): Float {
        val t = p.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EASE -> t * t * (3f - 2f * t)
            BOING -> {
                // Standard back-out, written out rather than via a cube
                // helper so the curve's shape is readable:
                //   1 + (1+s)(t-1)³ + s(t-1)²
                val q = t - 1f
                1f + (1f + OVERSHOOT) * q * q * q + OVERSHOOT * q * q
            }
        }
    }

    companion object {
        /**
         * How far past the keyframe [BOING] reaches.
         *
         * Fixed rather than exposed as a lever, and that is a deliberate
         * refusal: past some overshoot the extrapolated field
         * self-intersects and the picture TEARS instead of exaggerating,
         * and there is no general bound — how far is safe depends on the
         * field the user painted. A slider here would be a tearing
         * generator.
         *
         * This is the standard back-out constant, which puts the peak at
         * 10% past the pose (`t ≈ 0.58`). An earlier 1.2 gave 5.3%, which
         * is arithmetically an overshoot and visually almost
         * indistinguishable from [EASE] — a curve called Boing has to be
         * legible as one. Still far inside [MAX_T]. The exact value is
         * the one thing here that wants a look on a device rather than a
         * test.
         */
        const val OVERSHOOT = 1.70158f

        /**
         * Hard ceiling on the tween parameter, whatever a curve asks for.
         * The safety rail behind [OVERSHOOT] rather than a shape control.
         */
        const val MAX_T = 1.35f

        /** The curve a segment uses when a project predates this feature. */
        val DEFAULT = LINEAR
    }
}

/**
 * The tween parameter for a segment at progress [p] under [easing],
 * clamped to the safe extrapolation range.
 *
 * The floor is 0, not `-MAX_T`: no curve here undershoots, and a
 * symmetric bound would imply that anticipation (dipping below the
 * starting pose before moving) is supported and tested when it is
 * neither. A future ease-in-back should widen this deliberately.
 *
 * Levers do NOT get this treatment: `GlobalParams` is lerped CPU-side
 * between pins, and running that past 1 pushes a lever outside [-1, 1],
 * where the analytic warps were never characterized. The field's
 * overshoot is the effect; a lever's overshoot is undefined behaviour.
 * [leverProgress] is what the lever lerp should use instead.
 */
fun tweenProgress(p: Float, easing: Easing): Float =
    easing.at(p).coerceIn(0f, Easing.MAX_T)

/** Segment progress for interpolating levers: never leaves [0, 1]. */
fun leverProgress(p: Float, easing: Easing): Float =
    easing.at(p).coerceIn(0f, 1f)
