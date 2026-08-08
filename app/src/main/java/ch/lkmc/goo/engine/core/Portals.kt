package ch.lkmc.goo.engine.core

import kotlin.math.hypot

/**
 * Two linked places on the photo (proposal 0012). Both centers are in
 * image UV.
 *
 * The pair is an *input aid*, not document state: strokes made through it
 * are expanded into ordinary stamps at emission, exactly as Mirror and
 * [Symmetry] do, so nothing downstream — the log, undo, export replay,
 * project persistence, GOOvie caches — learns the word "portal".
 *
 * Which is also why it is deliberately NOT serialized anywhere. Rings are
 * ephemeral, like Echo's anchor and like the Mirror and Kaleidoscope
 * settings beside them: everything a portal stroke actually drew is
 * already fully materialized in the log, so losing an uncommitted pair on
 * process death costs nothing that exists.
 */
data class PortalPair(val au: Float, val av: Float, val bu: Float, val bv: Float)

/**
 * Goo through one ring and the same deformation comes out of the other
 * (proposal 0012).
 *
 * Mirror says "the picture has a vertical axis". Portals says "these two
 * places belong together" — the same generalization, minus the three
 * assumptions that limit Mirror: the relation need not be centered,
 * vertical, or reflective.
 *
 * ### Translation needs no aspect conversion, and saying so matters
 *
 * The proposal asks for the offset to be computed in aspect space and the
 * x component converted back to UV. For a pure translation that round
 * trip is the identity: `((bu − au) · aspect) / aspect = bu − au`. Doing
 * it anyway would not be harmless ceremony — it would suggest that
 * [twin]'s arithmetic depends on image shape, and the next person to add
 * rotation or scale here (which genuinely do) would have no way to tell
 * which of the two conventions this file was written in.
 *
 * Where aspect really is load-bearing is every DISTANCE: [directionAt]'s
 * ring test and [separated]'s minimum gap. Both are measured in aspect
 * space, so a ring is round in pixels on a landscape photo rather than an
 * ellipse — the same reason brush radii are aspect-space.
 *
 * ### Deltas ride along unchanged
 *
 * Translation preserves vectors and handedness, so a copy keeps its
 * delta whatever the mode is. This is why Portals needs no equivalent of
 * [StampMode.mirrorsDelta] or [StampMode.rotatesDelta]: those exist
 * because reflection reverses direction and rotation turns it, and
 * translation does neither. A twinned smear stays parallel to its
 * leader; a twinned VORTEX keeps its chirality.
 */
object Portals {

    /**
     * Smallest gap between ring centers, as a multiple of the brush
     * radius, measured in aspect space.
     *
     * Two nearly coincident rings apply almost the same stamp twice at
     * almost the same place, which does not read as a linked pair — it
     * reads as the brush having quietly doubled in strength. That is a
     * bad thing to discover by accident from a stray tap, so placement
     * refuses it.
     *
     * The gap is checked against the radius *at placement time*. Cranking
     * Size afterwards can bring the two discs back into heavy overlap,
     * and that is left alone on purpose: it takes a deliberate slider
     * move, so it is a choice rather than an accident, and it is the only
     * way to get the "one very fat linked blob" effect at all.
     */
    const val MIN_SEPARATION = 1.0f

    /**
     * Whether a second ring at ([bu], [bv]) is far enough from a first at
     * ([au], [av]) to be worth planting, for a brush of [radius].
     */
    fun separated(
        au: Float,
        av: Float,
        bu: Float,
        bv: Float,
        radius: Float,
        aspect: Float,
    ): Boolean = hypot((bu - au) * aspect, bv - av) >= MIN_SEPARATION * radius

    /**
     * Which way a stroke starting at ([u], [v]) copies, or null when the
     * touch is in neither ring — which is what keeps the modifier
     * selective: the pair can stay on screen while the rest of the photo
     * is gooed normally.
     *
     * Returns the UV shift to add to every stamp center: A→B for a stroke
     * that began in A, B→A for one that began in B.
     *
     * Nearest center wins, so overlapping rings resolve predictably
     * rather than by declaration order — the same rule the Funhouse rack
     * uses for the same reason.
     */
    fun shiftAt(
        u: Float,
        v: Float,
        radius: Float,
        aspect: Float,
        pair: PortalPair,
    ): Pair<Float, Float>? {
        val da = hypot((u - pair.au) * aspect, v - pair.av)
        val db = hypot((u - pair.bu) * aspect, v - pair.bv)
        val inA = da <= radius
        val inB = db <= radius
        return when {
            inA && (!inB || da <= db) -> Pair(pair.bu - pair.au, pair.bv - pair.av)
            inB -> Pair(pair.au - pair.bu, pair.av - pair.bv)
            else -> null
        }
    }

    /**
     * [stamp] translated by ([shiftU], [shiftV]).
     *
     * Constructed rather than `stamp.copy(...)`, matching
     * [BrushTool.mirrorStamp] and [Symmetry.expand]: every field of a
     * [Stamp] is geometric, so a field added later would otherwise be
     * carried through a translation untransformed and silently wrong.
     * Naming them all makes that a compile error and a decision.
     */
    fun twin(stamp: Stamp, shiftU: Float, shiftV: Float): Stamp = Stamp(
        cx = stamp.cx + shiftU,
        cy = stamp.cy + shiftV,
        dx = stamp.dx,
        dy = stamp.dy,
    )

    /**
     * The leader and its twin, in that order. Warp-of-warp composition is
     * ordered, so a stroke must replay in the order it was stamped, and
     * leader-then-twin is the order this fixes.
     *
     * A twin whose center leaves the frame is KEPT, on the same grounds
     * [Symmetry.expand] keeps its rotations: an off-canvas center still
     * moves in-canvas texels within its radius, and dropping it would
     * make a linked stroke stop dead at the border instead of running off
     * it. [StampBounds] discards the ones that genuinely cannot reach a
     * texel, which is the one place that decision belongs.
     */
    fun expand(stamp: Stamp, shift: Pair<Float, Float>?): List<Stamp> {
        if (shift == null) return listOf(stamp)
        return listOf(stamp, twin(stamp, shift.first, shift.second))
    }
}
