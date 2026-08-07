package ch.lkmc.goo.engine.core

/**
 * The clone brush's arithmetic (proposal 0004).
 *
 * Copying region S onto point P is what a displacement field expresses
 * natively — `D(P) = S − P` — which is why Echo needs no stamp mode and
 * no shader change. It is an ordinary DIRECTIONAL stroke whose stamps
 * carry a particular delta.
 *
 * ### The delta is a constant OFFSET, not a moving target
 *
 * The proposal wrote `delta = anchor − stampCenter` and called it
 * "constant per stroke". It is neither constant nor correct:
 *
 * - It is not constant, because `stampCenter` moves as the finger does.
 * - Worse, it makes `D(P) = anchor − P` for every stamp, so *every*
 *   texel in the stroke samples the single pixel at the anchor. That is
 *   not a clone; it is a smear of one colour.
 *
 * A clone stamp holds a fixed **offset** between the brush and its
 * source, so the graft is a translated copy of a region rather than one
 * pixel repeated: `D(P) = offset`, giving `src(P + offset)`. The offset
 * is fixed when the stroke starts, from the anchor the user planted.
 *
 * ### And the sign is the other way round
 *
 * The DIRECTIONAL kernel is `b = −delta · w` (backward mapping — a stamp
 * that should move content *with* a drag writes the negated drag). So
 * producing `b = offset` needs `delta = −offset`. Writing the proposal's
 * expression literally would have sampled `2P − S`: the point reflected
 * through the brush, which looks plausible and is wrong.
 */
object EchoOffset {

    /**
     * The per-stamp delta for an echo stroke that began at
     * ([startU], [startV]) with its anchor at ([anchorU], [anchorV]).
     *
     * Constant for the whole stroke, which is what makes the graft a
     * rigid translation of the source region.
     */
    fun delta(startU: Float, startV: Float, anchorU: Float, anchorV: Float): Pair<Float, Float> =
        Pair(startU - anchorU, startV - anchorV)

    /**
     * Where the content painted at ([u], [v]) is fetched from, given the
     * stroke's [delta]. Useful for reasoning and for tests; the engine
     * itself never calls it, since the shader does this by construction.
     */
    fun sourceOf(u: Float, v: Float, delta: Pair<Float, Float>): Pair<Float, Float> =
        Pair(u - delta.first, v - delta.second)

    /**
     * Whether an anchor is far enough from the brush to be worth
     * painting. An echo of nearly zero offset is a very expensive no-op
     * — it grafts a region onto itself — and it is easy to plant by
     * accident with a stray tap.
     */
    fun isUseful(startU: Float, startV: Float, anchorU: Float, anchorV: Float): Boolean {
        val du = startU - anchorU
        val dv = startV - anchorV
        return du * du + dv * dv >= MIN_OFFSET * MIN_OFFSET
    }

    /** Smallest UV separation between anchor and brush worth echoing. */
    const val MIN_OFFSET = 0.01f
}
