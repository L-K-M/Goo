package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/**
 * One committed pin pull (proposal 0016) — the first edit in this app
 * that is not a list of circular stamps.
 *
 * Every stroke before this one answered "what should happen near this
 * finger?". A pin pull answers "what must remain true while something
 * else moves?", and that is a relationship rather than a force. It
 * cannot be spelled as a stamp without being approximated by hundreds of
 * them, which the proposal explicitly refuses.
 *
 * ### Why the controls are one list, not three
 *
 * The proposal sketches `sourceControls`, `targetControls` and
 * `controlWeights` as parallel lists. They are one list of
 * [MlsControl] here instead, because three lists that must agree in
 * length are three chances to disagree — and a decoder handed a file
 * where they do would have to invent a policy (truncate? refuse?) for a
 * state the type system can simply not have.
 *
 * ### Solver version
 *
 * [solverVersion] is stamped at commit and never interpreted at replay
 * — today. It exists so that a future improvement to [RigidMls] can be
 * applied to NEW pulls without silently redrawing every old document
 * that was authored against the current one. A field the writer fills
 * and the reader ignores is cheap; retrofitting one after the fact is
 * not possible at all.
 */
@Serializable
data class PinWarp(
    val controls: List<MlsControl>,
    val reach: Float = RigidMls.DEFAULT_REACH,
    val rubber: Float = RigidMls.DEFAULT_RUBBER,
    val solverVersion: Int = SOLVER_VERSION,
) {
    /**
     * Clamped and bounded into what the solver and shader both assume.
     *
     * Non-finite values are REPLACED rather than clamped, on the lesson
     * `Lens.sanitized` records: `NaN.coerceIn(a, b)` is NaN, so a clamp
     * looks like a guard and is not one. A NaN control would put a NaN
     * in the displacement field, where it is permanent and replays
     * faithfully forever.
     */
    fun sanitized(): PinWarp = copy(
        controls = controls.take(RigidMls.MAX_CONTROLS).map { it.sanitized() },
        reach = reach.finiteOr(RigidMls.DEFAULT_REACH)
            .coerceIn(RigidMls.MIN_REACH, RigidMls.MAX_REACH),
        rubber = rubber.finiteOr(RigidMls.DEFAULT_RUBBER).coerceIn(0f, 1f),
    )

    /**
     * Whether this payload is fit to enter the document.
     *
     * Checked at the log boundary rather than trusted, because a pin
     * warp arrives from a file as readily as from a finger, and the
     * loader's contract is to refuse rather than half-restore.
     */
    val isValid: Boolean
        get() = controls.isNotEmpty() &&
            controls.size <= RigidMls.MAX_CONTROLS &&
            controls.all { it.isFinite } &&
            reach.isFinite() && rubber.isFinite() &&
            // A pull with nothing but holds is a very expensive identity.
            controls.any { it.su != it.tu || it.sv != it.tv }

    companion object {
        /** Bumped when [RigidMls]'s output changes for the same input. */
        const val SOLVER_VERSION = 1
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/** Every coordinate present and a weight that can carry an opinion. */
val MlsControl.isFinite: Boolean
    get() = su.isFinite() && sv.isFinite() && tu.isFinite() && tv.isFinite() &&
        weight.isFinite() && weight > 0f

/** Clamped into the frame, with a usable weight. */
fun MlsControl.sanitized(): MlsControl = MlsControl(
    su = su.orElse(0.5f).coerceIn(0f, 1f),
    sv = sv.orElse(0.5f).coerceIn(0f, 1f),
    tu = tu.orElse(0.5f).coerceIn(0f, 1f),
    tv = tv.orElse(0.5f).coerceIn(0f, 1f),
    weight = weight.orElse(1f).coerceIn(RigidMls.CORNER_WEIGHT, 1f),
)

private fun Float.orElse(fallback: Float): Float = if (isFinite()) this else fallback
