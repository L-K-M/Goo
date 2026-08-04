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
     * per-fragment body, looped. `b(p)` is the brush displacement —
     * negative drag delta scaled by strength and the shared smoothstep
     * falloff over aspect-space distance from the stamp center.
     */
    fun applyStamp(stroke: Stroke, stamp: Stamp) {
        val out = FloatArray(data.size)
        var i = 0
        for (iy in 0 until height) {
            val v = (iy + 0.5f) / height
            for (ix in 0 until width) {
                val u = (ix + 0.5f) / width
                // b(p): falloff-weighted content displacement, negated for
                // backward mapping.
                val du = (u - stamp.cx) * aspect
                val dv = v - stamp.cy
                val dist = sqrt(du * du + dv * dv) / stroke.radius
                val w = BrushFalloff.weight(dist)
                val bx = -stamp.dx * stroke.strength * w
                val by = -stamp.dy * stroke.strength * w
                // D'(p) = b(p) + D(p + b(p))
                out[i] = bx + sampleX(u + bx, v + by)
                out[i + 1] = by + sampleY(u + bx, v + by)
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
