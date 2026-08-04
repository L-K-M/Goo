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

/** The tools of the goo brush palette. PR-by-PR this enum grows. */
@Serializable
enum class BrushTool {
    /** Finger-through-wet-paint: content follows the drag. */
    SMEAR,
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
