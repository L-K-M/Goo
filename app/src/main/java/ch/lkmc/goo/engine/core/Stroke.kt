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
enum class StampMode(val shaderId: Int) {
    /** b(p) = -delta·strength·w, composed warp-of-warp. */
    DIRECTIONAL(0),

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
    ;

    /**
     * The stamp's twin under the Mirror toggle: reflected across the
     * image's vertical center line. Directional deltas flip their x;
     * radial/field modes carry no direction, so only the center moves.
     */
    fun mirrorStamp(s: Stamp): Stamp = Stamp(
        cx = 1f - s.cx,
        cy = s.cy,
        dx = if (mode == StampMode.DIRECTIONAL) -s.dx else s.dx,
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
