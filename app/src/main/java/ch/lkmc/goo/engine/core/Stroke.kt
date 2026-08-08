package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/**
 * Coordinate conventions (PLAN.md §4.1, pinned here once for the whole
 * engine — CPU reference, resampler, and shaders all follow it):
 *
 * - Positions are source-image UV in [0,1]², origin top-left.
 * - Distances and radii are measured in *aspect space*: `(u·aspect, v)`
 *   where `aspect = imageWidth / imageHeight`. A brush circle is round in
 *   pixels, and a radius of 0.1 spans 10% of the image height regardless
 *   of image size or orientation.
 * - Displacements (deltas) are UV offsets (plain UV units, not aspect
 *   space): what you add to a UV to move a sample.
 */

/**
 * How a stamp modifies the field — the shader branch. [shaderId] is the
 * wire value of the `u_mode` uniform; explicit so enum reordering can
 * never silently re-map shader behavior.
 */
@Serializable
enum class StampMode(
    val shaderId: Int,
    /**
     * A mirrored twin negates this mode's `dx`. True both for modes whose
     * delta is a direction (reflecting a push reverses its x) and for
     * VORTEX, whose `dx` is a handedness that reflection reverses.
     */
    val mirrorsDelta: Boolean = false,
    /**
     * A rotated twin rotates this mode's delta, because the delta is a
     * VECTOR in the image plane. Deliberately not the same set as
     * [mirrorsDelta]: VORTEX's `dx` is a chirality flag, and rotation
     * preserves handedness, so rotating it would destroy the sign
     * instead of transforming it.
     */
    val rotatesDelta: Boolean = false,
) {
    /** b(p) = -delta·strength·w, composed warp-of-warp. */
    DIRECTIONAL(0, mirrorsDelta = true, rotatesDelta = true),

    /** Radial magnify: b points at the stamp center (sample nearer it). */
    INFLATE(1),

    /** Radial pinch: b points away from the stamp center. */
    DEFLATE(2),

    /** Field-space blur blend: D' = mix(D, blur₄(D), w·BLEND_STEP). */
    RELAX(3),

    /** Local fade to identity: D' = D·(1 − w·BLEND_STEP). */
    ERASE(4),

    /**
     * Fusion (PLAN.md §3): accumulate the through-paint mask —
     * M' = clamp(M + w·FUSE_STEP), displacement untouched. The mask is
     * the field's z channel, so it warps, tweens, undoes, and exports
     * with everything else for free.
     */
    FUSE(5),

    /**
     * Tangential swirl (proposal 0001): b is the outward radial unit
     * vector turned a right angle in aspect space, so content orbits the
     * stamp center. `dx`'s SIGN carries chirality rather than a
     * magnitude — which is why this mirrors its delta: reflecting a
     * whirlpool must reverse the way it turns, and the existing mirror
     * flip does exactly that for free.
     */
    VORTEX(6, mirrorsDelta = true),

    /**
     * Smear with teeth (proposal 0010): DIRECTIONAL modulated by a
     * cosine of the texel's offset ACROSS the drag axis, so one pass
     * lays down parallel strands instead of one smooth push.
     */
    COMB(7, mirrorsDelta = true, rotatesDelta = true),

    /**
     * Concentric ripple (proposal 0013): radial displacement whose sign
     * alternates over three bands, returning to zero at the rim. Adds a
     * closed rhythmic band the rest of the palette has no way to make.
     */
    RIPPLE(8),

    /**
     * Opposed shear across the stroke path (proposal 0014): the two
     * sides of the drawn line slide past each other along it. `dx`/`dy`
     * carry the local path tangent, so the delta mirrors like any other
     * direction.
     */
    FAULT(9, mirrorsDelta = true, rotatesDelta = true),
}

/** Brush falloff curve over normalized distance; `u_profile` wire values. */
@Serializable
enum class FalloffProfile(val shaderId: Int) {
    /** C1 smoothstep 1→0 — the default feel. */
    SMOOTHSTEP(0),

    /** Smoothstep squared: soft shoulders, feathered edge (Smudge). */
    FEATHER(1),

    /** Flat to 70% radius, then smoothstep down (Move drags rigidly). */
    PLATEAU(2),

    /**
     * The one profile that is not radially symmetric (Melt, proposal
     * 0003). It reshapes the DISTANCE rather than the curve: below the
     * stamp center the measured distance is compressed by
     * [BrushDynamics.DRIP_LOBE], so the same weight is reached further
     * down and the lobe reaches toward the bottom of the picture.
     *
     * The direction of that factor is the tool. Below 1 reaches down;
     * above 1 would reach *up*, giving a brush that pulls harder above
     * the finger than below it. Counterintuitive but not subtle: weight
     * decreases with distance, so shrinking the distance a direction is
     * measured in is what makes a brush act further that way.
     *
     * [BrushFalloff.weight] stays a function of one scalar and is
     * untouched — the anisotropy lives in how `distA` is computed, in
     * both the CPU reference and the shader.
     */
    DRIP(3),
}

/**
 * The goo brush palette (PLAN.md §3). Each tool is a (mode, falloff,
 * strength-scale, cadence) row; the engine has no per-tool code paths
 * beyond these parameters.
 *
 * [pumped] tools apply continuously while the finger is held down (the
 * KPT pump feel) — stamps are emitted on a clock at the touch point
 * rather than along the drag path.
 */
@Serializable
enum class BrushTool(
    val mode: StampMode,
    val profile: FalloffProfile,
    val strengthScale: Float,
    val pumped: Boolean,
    /** A stationary touch has useful semantics and applies one initial stamp. */
    val stampsOnDown: Boolean = pumped,
) {
    /** Finger-through-wet-paint: content follows the drag. */
    SMEAR(StampMode.DIRECTIONAL, FalloffProfile.SMOOTHSTEP, 1f, pumped = false),

    /** Rigid drag: plateau falloff moves the region as a piece. */
    MOVE(StampMode.DIRECTIONAL, FalloffProfile.PLATEAU, 1f, pumped = false),

    /** Soft feathered smear at partial strength. */
    SMUDGE(StampMode.DIRECTIONAL, FalloffProfile.FEATHER, 0.45f, pumped = false),

    /** Fine-adjustment smear: tiny fraction of the drag. */
    NUDGE(StampMode.DIRECTIONAL, FalloffProfile.SMOOTHSTEP, 0.15f, pumped = false),

    /** Hold to bulge outward (magnify under the brush). */
    GROW(StampMode.INFLATE, FalloffProfile.SMOOTHSTEP, 1f, pumped = true),

    /** Hold to pinch inward. */
    SHRINK(StampMode.DEFLATE, FalloffProfile.SMOOTHSTEP, 1f, pumped = true),

    /** Hold to relax the goo — local blur of the displacement itself. */
    SMOOTH(StampMode.RELAX, FalloffProfile.SMOOTHSTEP, 1f, pumped = true),

    /** Hold to locally erase the warp back to the original image. */
    UNGOO(StampMode.ERASE, FalloffProfile.SMOOTHSTEP, 1f, pumped = true),

    /** Paint the second photo through — soft-edged reveal (Fusion room). */
    FUSE(
        StampMode.FUSE,
        FalloffProfile.SMOOTHSTEP,
        1f,
        pumped = false,
        stampsOnDown = true,
    ),

    /** Hold to wind a whirlpool clockwise (proposal 0001). */
    VORTEX(StampMode.VORTEX, FalloffProfile.SMOOTHSTEP, 1f, pumped = true),

    /** Vortex the other way; chirality rides the stamp's dx sign. */
    UNWIND(StampMode.VORTEX, FalloffProfile.SMOOTHSTEP, 1f, pumped = true),

    /** Hold and the picture runs downward like wax (proposal 0003). */
    MELT(StampMode.DIRECTIONAL, FalloffProfile.DRIP, 1f, pumped = true),

    /** Drag to lay down parallel strands — hair, fur, fire (0010). */
    COMB(StampMode.COMB, FalloffProfile.SMOOTHSTEP, 1f, pumped = false),

    /** Tap to drop concentric ripples; drag for a wake (0013). */
    POND(
        StampMode.RIPPLE,
        FalloffProfile.SMOOTHSTEP,
        1f,
        pumped = false,
        stampsOnDown = true,
    ),

    /** Draw a seam; the two sides slide past each other (0014). */
    FAULT(StampMode.FAULT, FalloffProfile.SMOOTHSTEP, 1f, pumped = false),

    /**
     * Clone: paint this photo through itself, offset (proposal 0004).
     * FEATHER because a graft wants soft edges — the same falloff Smudge
     * uses, doing clone-UI duty for free.
     */
    ECHO(
        StampMode.DIRECTIONAL,
        FalloffProfile.FEATHER,
        1f,
        pumped = false,
        // Explicit, not defaulted: planting is not painting, and a test
        // depends on it. If the default ever flips, the first touch of
        // an Echo stroke would stamp at the anchor instead of arming it.
        stampsOnDown = false,
    ),
    ;

    /**
     * Chirality for the two swirl rows, carried in each stamp's `dx`.
     * Zero for everything else, where `dx` means what it always did.
     */
    val chirality: Float
        get() = when (this) {
            VORTEX -> 1f
            UNWIND -> -1f
            else -> 0f
        }

    /**
     * The stamp's twin under the Mirror toggle: reflected across the
     * image's vertical center line. Directional deltas flip their x;
     * radial/field modes carry no direction, so only the center moves.
     */
    fun mirrorStamp(s: Stamp): Stamp = Stamp(
        cx = 1f - s.cx,
        cy = s.cy,
        dx = if (mode.mirrorsDelta) -s.dx else s.dx,
        dy = s.dy,
    )
}

/**
 * Rate constants shared by the CPU reference and the GLSL literals in
 * GlShaders — change them together (the golden tests pin the CPU side).
 */
object BrushDynamics {
    /** UV displacement added per pumped radial stamp at strength 1. */
    const val RADIAL_STEP_UV = 0.004f

    /** Blend fraction per RELAX/ERASE stamp at strength 1. */
    const val BLEND_STEP = 0.22f

    /** Mask flow added per FUSE stamp at full weight. */
    const val FUSE_STEP = 0.30f

    /** Radial modes ramp in over [0, this] normalized distance so the
     *  direction singularity at the exact center contributes nothing. */
    const val CENTER_RAMP_END = 0.08f

    /** Pump cadence for [BrushTool.pumped] tools. */
    const val PUMP_INTERVAL_MS = 16L

    /**
     * Tangential UV displacement per pumped swirl stamp at strength 1.
     * A DISPLACEMENT, not an angle — the same units and order as
     * [RADIAL_STEP_UV]. Because a fixed tangential step is an arc
     * length, the angle it sweeps falls off as `m / r`: rotation is
     * differential, which is what makes it a whirlpool and not a
     * turntable. At mid-radius of a 0.1 brush that is ~0.035 rad per
     * stamp, so a one-second hold is ~120° before compounding.
     */
    const val SWIRL_STEP_UV = 0.0035f

    /** UV run added per pumped melt stamp, before acceleration. */
    const val DRIP_STEP_UV = 0.0009f

    /**
     * Ceiling on one melt stamp's run. Warp-of-warp assumes stamps are
     * small; past this the composition folds instead of stretching.
     */
    const val DRIP_MAX_UV = 0.02f

    /** Below-center distance compression for [FalloffProfile.DRIP]. */
    const val DRIP_LOBE = 0.45f

    /** Noise cells across the image width — the width of one wax run. */
    const val DRIP_CELLS = 40f

    /**
     * Comb teeth per brush RADIUS, not per image. Counting them against
     * normalized brush geometry is what makes the strand pattern
     * identical in the preview and in the full-resolution export
     * (PLAN.md §5 decision 4, applied to a texture).
     *
     * The field is ≤1024 texels and bilinearly sampled, so a tooth
     * narrower than ~3 texels shimmers rather than reading as a strand.
     * That caps the usable range at roughly one octave, which is the
     * reason this is a constant and not a third lever.
     */
    const val COMB_TEETH = 3f

    /** Radial UV displacement at a ripple's first band, strength 1. */
    const val RIPPLE_STEP_UV = 0.010f

    /** Signed bands inside one ripple, outermost returning to zero. */
    const val RIPPLE_BANDS = 3f

    /** Along-seam UV slip per fault stamp at strength 1. */
    const val FAULT_STEP_UV = 0.010f
}

/**
 * One resampled brush application: kernel center [cx],[cy] (UV) and the
 * content displacement [dx],[dy] (UV delta) it stamps under the falloff.
 */
@Serializable
data class Stamp(val cx: Float, val cy: Float, val dx: Float, val dy: Float)

/**
 * One finished brush gesture: the tool, its parameters, and the stamps the
 * resampler produced. Stamps (not raw touch points) are stored so replays —
 * undo rebuilds, full-resolution export, context-loss recovery — are
 * deterministic and never re-run input-dependent resampling.
 *
 * [radius] is in aspect-space units (fraction of image height); [strength]
 * scales stamp displacement (1 = content tracks the finger).
 */
@Serializable
data class Stroke(
    val tool: BrushTool,
    val radius: Float,
    val strength: Float,
    val stamps: List<Stamp>,
)
