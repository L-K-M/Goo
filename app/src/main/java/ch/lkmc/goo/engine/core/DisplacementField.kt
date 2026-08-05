package ch.lkmc.goo.engine.core

import kotlin.math.sqrt

/**
 * CPU reference implementation of the displacement field (PLAN.md §4.1).
 *
 * The GL engine is a translation of this class into two fragment shaders;
 * unit tests pin the semantics here, and the shaders are kept trivially
 * close (GlShaders documents the correspondence line by line). Rendering
 * samples the source at `p + D(p)` (backward mapping), so a stamp that
 * should move content *with* a drag writes the *negative* drag delta.
 *
 * Stamp composition is warp-of-warp, not naive addition:
 *
 *     D'(p) = b(p) + D(p + b(p))
 *
 * — the new brush warp applies first, then the existing field is looked up
 * at the pre-warped position. For the small per-stamp deltas the resampler
 * produces the difference from `D + b` is second-order, but warp-of-warp
 * keeps long strokes from drifting off their own trail.
 *
 * Grid layout: [width]×[height] texels, two floats each (dx, dy in UV
 * units), row-major, texel (ix, iy) centered at UV
 * `((ix+0.5)/width, (iy+0.5)/height)`.
 */
class DisplacementField(val width: Int, val height: Int, val aspect: Float) {

    val data = FloatArray(width * height * 2)

    fun reset() = data.fill(0f)

    /** Bilinearly sampled displacement x-component at UV (u, v). */
    fun sampleX(u: Float, v: Float): Float = sample(u, v, 0)

    /** Bilinearly sampled displacement y-component at UV (u, v). */
    fun sampleY(u: Float, v: Float): Float = sample(u, v, 1)

    /**
     * Where the pixel shown at (u, v) is fetched from: `p + D(p)` — the
     * whole engine in one line.
     */
    fun warpedSource(u: Float, v: Float): Pair<Float, Float> =
        Pair(u + sampleX(u, v), v + sampleY(u, v))

    /**
     * Apply one [Stamp] of a [Stroke] to every texel: the shader's
     * per-fragment body, looped, switched on the tool's [StampMode].
     *
     * Warp modes (DIRECTIONAL, INFLATE, DEFLATE) compute a brush
     * displacement `b(p)` and compose warp-of-warp; field modes (RELAX,
     * ERASE) operate on the stored displacement itself — blurring it or
     * fading it toward identity — with no resampling.
     */
    fun applyStamp(stroke: Stroke, stamp: Stamp) {
        val out = FloatArray(data.size)
        val mode = stroke.tool.mode
        val profile = stroke.tool.profile
        var i = 0
        for (iy in 0 until height) {
            val v = (iy + 0.5f) / height
            for (ix in 0 until width) {
                val u = (ix + 0.5f) / width
                val du = (u - stamp.cx) * aspect
                val dv = v - stamp.cy
                val distA = sqrt(du * du + dv * dv)
                val dist = distA / stroke.radius
                val w = BrushFalloff.weight(dist, profile) * stroke.strength
                when (mode) {
                    StampMode.DIRECTIONAL, StampMode.INFLATE, StampMode.DEFLATE -> {
                        // b(p): falloff-weighted brush displacement,
                        // backward-mapped (negative = content moves with
                        // the gesture / bulges outward).
                        var bx: Float
                        var by: Float
                        if (mode == StampMode.DIRECTIONAL) {
                            bx = -stamp.dx * w
                            by = -stamp.dy * w
                        } else {
                            // Outward unit direction in aspect space,
                            // converted back to a UV delta; ramped to zero
                            // at the center where it is undefined.
                            val m = w * BrushFalloff.centerRamp(dist) *
                                BrushDynamics.RADIAL_STEP_UV
                            if (distA < 1e-6f) {
                                bx = 0f
                                by = 0f
                            } else {
                                val ox = (du / distA) / aspect
                                val oy = dv / distA
                                val s = if (mode == StampMode.INFLATE) -1f else 1f
                                bx = s * ox * m
                                by = s * oy * m
                            }
                        }
                        // D'(p) = b(p) + D(p + b(p))
                        out[i] = bx + sampleX(u + bx, v + by)
                        out[i + 1] = by + sampleY(u + bx, v + by)
                    }

                    StampMode.RELAX -> {
                        val k = w * BrushDynamics.BLEND_STEP
                        val tx = 1f / width
                        val ty = 1f / height
                        val blurX = 0.25f * (
                            sampleX(u + tx, v) + sampleX(u - tx, v) +
                                sampleX(u, v + ty) + sampleX(u, v - ty))
                        val blurY = 0.25f * (
                            sampleY(u + tx, v) + sampleY(u - tx, v) +
                                sampleY(u, v + ty) + sampleY(u, v - ty))
                        out[i] = at(ix, iy, 0) + (blurX - at(ix, iy, 0)) * k
                        out[i + 1] = at(ix, iy, 1) + (blurY - at(ix, iy, 1)) * k
                    }

                    StampMode.ERASE -> {
                        val k = 1f - w * BrushDynamics.BLEND_STEP
                        out[i] = at(ix, iy, 0) * k
                        out[i + 1] = at(ix, iy, 1) * k
                    }
                }
                i += 2
            }
        }
        out.copyInto(data)
    }

    /** Replay a whole log from identity — undo, export, and recovery path. */
    fun replay(strokes: List<Stroke>) {
        reset()
        for (stroke in strokes) for (stamp in stroke.stamps) applyStamp(stroke, stamp)
    }

    private fun sample(u: Float, v: Float, channel: Int): Float {
        // Texel-center bilinear with clamp-to-edge, mirroring the GL
        // sampler state (LINEAR + CLAMP_TO_EDGE).
        val x = u * width - 0.5f
        val y = v * height - 0.5f
        val x0 = x.toInt().coerceIn(0, width - 1)
        val y0 = y.toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val a = at(x0, y0, channel)
        val b = at(x1, y0, channel)
        val c = at(x0, y1, channel)
        val d = at(x1, y1, channel)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    private fun at(ix: Int, iy: Int, channel: Int): Float = data[(iy * width + ix) * 2 + channel]
}
