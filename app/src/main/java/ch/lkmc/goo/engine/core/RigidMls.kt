package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.pow

/**
 * One point handle: where it sits now, where it is being dragged to, and
 * how loudly it argues.
 *
 * A **hold pin** has [tu]/[tv] equal to [su]/[sv] — it asks for nothing
 * except to stay where it is, which is what makes "pin the eyes, pull
 * the nose" expressible at all. The **puck** is the one control whose
 * source and target differ.
 *
 * [weight] scales this control's influence. The four implicit frame
 * corners use a fraction, so they stabilize the frame — an otherwise
 * unconstrained pull translates the whole photograph — without competing
 * with pins the user actually placed.
 *
 * All coordinates are image UV.
 */
@Serializable
data class MlsControl(
    val su: Float,
    val sv: Float,
    val tu: Float,
    val tv: Float,
    val weight: Float = 1f,
)

/**
 * Rigid Moving Least Squares (proposal 0016), the solver prototype.
 *
 * This is the deformation Taffy Pins would need, and it exists on its
 * own — with no `PinWarp`, no schema change, no renderer branch — because
 * the proposal asks for exactly that: *"prototype it before committing
 * the document-model expansion"*. What is here is the math and the
 * evidence about it. Whether a stroke may carry something other than
 * stamps is a separate decision, and a bigger one.
 *
 * ### The direction that matters
 *
 * Meltorama renders by BACKWARD mapping: for an output texel it asks
 * where to sample. So the solver is called with the controls reversed —
 * targets as sources — and returns, for an output point, the source
 * point it should read. [sourceAt] does this so callers cannot get it
 * backwards; [deform] is the forward map, kept because it is the one the
 * paper states and the one the tests reason in.
 *
 * ### Rigid, and what "rubber" trades against it
 *
 * Schaefer et al. give three variants over the same weighted least
 * squares: affine, similarity, rigid. They differ only in what the
 * accumulated matrix is normalized by. Rigid refuses to scale — a
 * pinned region keeps its size, which is what carries a face as a
 * coherent patch rather than inflating it. Similarity allows uniform
 * scale, which bends more softly and folds less readily under a hard
 * pull.
 *
 * [RUBBER] is a blend between the two RESULTS, not a free solver
 * coefficient. A curated range is testable and explainable; a scientific
 * panel is neither.
 *
 * ### Aspect
 *
 * Every distance is measured in aspect space, like brush radii, so the
 * falloff is round in pixels. The control offsets are NOT converted:
 * they are a displacement in UV and the answer is wanted in UV. Getting
 * this backwards produces a deformation that is subtly sheared on any
 * non-square photo and exactly right on a square test image.
 */
object RigidMls {

    /** How local the influence is; the paper's α. */
    const val MIN_REACH = 0.5f
    const val MAX_REACH = 3f
    const val DEFAULT_REACH = 1f

    /** 0 = fully rigid, 1 = fully similarity. */
    const val DEFAULT_RUBBER = 0f

    /**
     * Squared aspect-space distance below which a point IS its control.
     *
     * Two guards, not one. This constant bounds every division so a
     * near-coincident point cannot produce an infinite weight; the
     * separate exact-hit branch returns the control's own answer, so the
     * one point where the bound is not enough is handled by not dividing
     * at all. A single epsilon looks sufficient and leaves a texel whose
     * weight is 1e18 while its neighbours are 1e3 — not infinite, so no
     * NaN, but numerically a hard singular dot.
     */
    const val EPSILON_SQ = 1e-12f

    /**
     * The same floor for quantities that are a LENGTH rather than a
     * squared distance — `sqrt(EPSILON_SQ)`.
     *
     * Separate because comparing an unsquared length against a squared
     * threshold is a unit error even when it happens to be harmless:
     * `fLen <= 1e-12` does fire for a true zero, so the guard works
     * today, and it would silently stop meaning what it says the moment
     * someone retuned EPSILON_SQ. It also catches slightly more: a
     * direction normalized out of a 1e-8 vector is finite and numerically
     * meaningless, and falling back to ṽ is the better answer.
     */
    const val EPSILON = 1e-6f

    /** Implicit corner anchors, weaker than anything the user placed. */
    const val CORNER_WEIGHT = 0.15f

    /** Five explicit holds, four corners, one puck. */
    const val MAX_CONTROLS = 10

    /** The four frame corners as hold controls at [CORNER_WEIGHT]. */
    fun corners(): List<MlsControl> = listOf(
        MlsControl(0f, 0f, 0f, 0f, CORNER_WEIGHT),
        MlsControl(1f, 0f, 1f, 0f, CORNER_WEIGHT),
        MlsControl(0f, 1f, 0f, 1f, CORNER_WEIGHT),
        MlsControl(1f, 1f, 1f, 1f, CORNER_WEIGHT),
    )

    /**
     * Where ([u], [v]) moves to under [controls] — the paper's `f(v)`.
     */
    fun deform(
        u: Float,
        v: Float,
        controls: List<MlsControl>,
        aspect: Float,
        reach: Float = DEFAULT_REACH,
        rubber: Float = DEFAULT_RUBBER,
    ): Pair<Float, Float> = solve(u, v, controls, aspect, reach, rubber, inverse = false)

    /**
     * Where the texel at ([u], [v]) should SAMPLE FROM — the backward
     * map the engine actually renders with. Same solve with the controls
     * read the other way round.
     */
    fun sourceAt(
        u: Float,
        v: Float,
        controls: List<MlsControl>,
        aspect: Float,
        reach: Float = DEFAULT_REACH,
        rubber: Float = DEFAULT_RUBBER,
    ): Pair<Float, Float> = solve(u, v, controls, aspect, reach, rubber, inverse = true)

    private fun solve(
        u: Float,
        v: Float,
        controls: List<MlsControl>,
        aspect: Float,
        reach: Float,
        rubber: Float,
        inverse: Boolean,
    ): Pair<Float, Float> {
        require(controls.size <= MAX_CONTROLS) { "too many controls: ${controls.size}" }
        // Aspect is load-bearing for correctness, not a hint: the whole
        // "round in pixels" property is this one multiply. A zero
        // collapses every x-distance and quietly makes the deformation
        // one-dimensional; a negative mirrors it; a NaN falls through to
        // the identity return below and reads as "nothing happened".
        // All three are a caller bug that would otherwise show up as a
        // subtly wrong warp, which is the hardest thing to diagnose in a
        // deformation field.
        require(aspect > 0f && aspect.isFinite()) { "aspect must be positive: $aspect" }
        if (controls.isEmpty()) return Pair(u, v)
        val alpha = reach.coerceIn(MIN_REACH, MAX_REACH)
        val mix = rubber.coerceIn(0f, 1f)

        // p = what we are given, q = what we want back. Swapping them is
        // the whole of the forward/backward distinction.
        fun px(c: MlsControl) = if (inverse) c.tu else c.su
        fun py(c: MlsControl) = if (inverse) c.tv else c.sv
        fun qx(c: MlsControl) = if (inverse) c.su else c.tu
        fun qy(c: MlsControl) = if (inverse) c.sv else c.tv

        var sumW = 0f
        var pStarX = 0f
        var pStarY = 0f
        var qStarX = 0f
        var qStarY = 0f
        val weights = FloatArray(controls.size)
        for ((i, c) in controls.withIndex()) {
            val dx = (u - px(c)) * aspect
            val dy = v - py(c)
            val d2 = dx * dx + dy * dy
            // Exact hit: the answer is the control's own image. Returning
            // here rather than clamping is the difference between a
            // guard and a very large number — see EPSILON_SQ.
            if (d2 <= EPSILON_SQ) return Pair(qx(c), qy(c))
            // α = 1 is a plain divide. Worth the branch: pow() is the
            // only transcendental in the inner loop, it runs once per
            // control per texel, and the default reach is exactly 1 —
            // so the common case pays nothing for a knob most people
            // will never move. Worth 2.7× on the CPU reference, by
            // interleaved A/B against a forced pow path (ADR 0004).
            val w = if (alpha == 1f) c.weight / d2 else c.weight / d2.pow(alpha)
            weights[i] = w
            sumW += w
            pStarX += w * px(c)
            pStarY += w * py(c)
            qStarX += w * qx(c)
            qStarY += w * qy(c)
        }
        if (!sumW.isFinite() || sumW <= 0f) return Pair(u, v)
        pStarX /= sumW
        pStarY /= sumW
        qStarX /= sumW
        qStarY /= sumW

        // ṽ and its perpendicular, in aspect space — the frame the
        // rotation is measured in.
        val vx = (u - pStarX) * aspect
        val vy = v - pStarY

        // Σ q̂ᵢ Aᵢ, accumulated directly rather than through a 2×2, and
        // μ_s for the similarity normalizer.
        var fx = 0f
        var fy = 0f
        var muS = 0f
        for ((i, c) in controls.withIndex()) {
            val w = weights[i]
            val phx = (px(c) - pStarX) * aspect
            val phy = py(c) - pStarY
            val qhx = (qx(c) - qStarX) * aspect
            val qhy = qy(c) - qStarY
            muS += w * (phx * phx + phy * phy)
            // Aᵢ = wᵢ · [p̂ ; −p̂ᗮ] · [ṽ ; −ṽᗮ]ᵀ, with ᗮ the left turn
            // (x, y) → (−y, x). Multiplying that out collapses to a
            // scaled rotation, [[a, b], [−b, a]], where `a` is the dot
            // product and `b` the cross — which is why the accumulation
            // below is four multiplies rather than a 2×2.
            //
            // BOTH the weight and the sign of `b` are load-bearing and
            // neither shows up in the interpolation tests: at a control
            // point one weight dominates so completely that the pins
            // still hold with either wrong, and only the rigid-motion
            // tests (translate, rotate, refuse-to-scale) can tell.
            val a = phx * vx + phy * vy
            val b = phx * vy - phy * vx
            fx += w * (qhx * a - qhy * b)
            fy += w * (qhx * b + qhy * a)
        }

        val vLen = hypot(vx, vy)
        val fLen = hypot(fx, fy)
        // Rigid: keep the direction, take the LENGTH from |ṽ| — that is
        // exactly the refusal to scale.
        val rigidX: Float
        val rigidY: Float
        if (fLen <= EPSILON) {
            // Degenerate (every control collinear and cancelling). The
            // honest answer is "no rotation available here", not a
            // normalized zero vector, which is a NaN wearing a hat.
            rigidX = vx
            rigidY = vy
        } else {
            rigidX = fx / fLen * vLen
            rigidY = fy / fLen * vLen
        }
        val simX: Float
        val simY: Float
        if (muS <= EPSILON) {
            simX = vx
            simY = vy
        } else {
            simX = fx / muS
            simY = fy / muS
        }

        val outX = rigidX + (simX - rigidX) * mix
        val outY = rigidY + (simY - rigidY) * mix
        // Back to UV: only the x component was ever scaled.
        return Pair(qStarX + outX / aspect, qStarY + outY)
    }
}
