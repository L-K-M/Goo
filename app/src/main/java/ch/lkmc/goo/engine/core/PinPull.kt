package ch.lkmc.goo.engine.core

import kotlin.math.hypot

/**
 * Turning a Pins gesture into a [PinWarp] (proposal 0016).
 *
 * The arithmetic between "five silver pins and a magenta puck on screen"
 * and "a payload the log will accept", kept out of the ViewModel so it
 * is testable without one.
 */
object PinPull {

    /** Explicit hold pins the user may place. Corners are extra. */
    const val MAX_HOLDS = 5

    /**
     * How far the puck must travel, in aspect space, before a release is
     * a pull rather than a tap.
     *
     * Without this every stray touch inside Pins mode would commit an
     * almost-identity warp: one undo step and one full-field replay pass
     * for a deformation nobody can see. Same reasoning as
     * `EchoOffset.MIN_OFFSET`, and the same order of magnitude.
     */
    const val MIN_PULL = 0.015f

    /** Aspect-space radius within which a tap grabs an existing pin. */
    const val PIN_TOUCH_RADIUS = 0.05f

    /** Index of the hold pin a touch lands on, or null. Nearest wins. */
    fun nearestHold(
        holds: List<Pair<Float, Float>>,
        u: Float,
        v: Float,
        aspect: Float,
    ): Int? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        holds.forEachIndexed { i, (hu, hv) ->
            val d = hypot((u - hu) * aspect, v - hv)
            if (d <= PIN_TOUCH_RADIUS && d < bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * The warp for a rack of [holds] and a [puck], or null when the pull
     * is too short to be worth a revision.
     *
     * The four frame corners come along implicitly, at
     * [RigidMls.CORNER_WEIGHT]. Without them a lone pull translates the
     * whole photograph — MLS has no notion of a frame, so every texel
     * follows the only control that moved. With them the frame stays
     * put, and because their weight is a fraction they do it without
     * overruling a pin the user actually placed.
     *
     * Holds are capped and the corner count is fixed, so the control
     * total is bounded by [RigidMls.MAX_CONTROLS] by construction rather
     * than by a check — 4 corners + 5 holds + 1 puck is exactly 10.
     */
    fun warpFor(
        holds: List<Pair<Float, Float>>,
        puck: MlsControl,
        reach: Float = RigidMls.DEFAULT_REACH,
        rubber: Float = RigidMls.DEFAULT_RUBBER,
    ): PinWarp? {
        if (!isPull(puck)) return null
        val explicit = holds.take(MAX_HOLDS).map { (u, v) -> MlsControl(u, v, u, v) }
        val warp = PinWarp(
            controls = RigidMls.corners() + explicit + puck,
            reach = reach,
            rubber = rubber,
        ).sanitized()
        return warp.takeIf { it.isValid }
    }

    /** Whether the puck moved far enough to mean anything. */
    fun isPull(puck: MlsControl, aspect: Float = 1f): Boolean =
        hypot((puck.tu - puck.su) * aspect, puck.tv - puck.sv) >= MIN_PULL
}
