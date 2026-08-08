package ch.lkmc.goo.engine.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns of a circle, for the periodic kernels. Spelled the way Kotlin
 * renders this float32 (6.2831853 and 6.2831855 are the same value) so
 * the GLSL literal can be written identically and
 * `GlShaderContractTest` can derive one from the other.
 */
internal const val TAU = 6.2831855f

/**
 * Odd, C1, zero at 0 and ±1 by |x| = 1 — the two sides of a fault.
 * Mirrored literally in `STAMP_FRAG`.
 */
internal fun smoothOdd(x: Float): Float {
    val a = abs(x).coerceAtMost(1f)
    val shaped = a * a * (3f - 2f * a)
    return if (x < 0f) -shaped else shaped
}

/**
 * sign() that is +1 at zero, deliberately unlike `kotlin.math.sign`
 * (which returns 0 there) — a chirality of 0 must still swirl rather
 * than stall. Named apart so nobody reaches for it expecting stdlib
 * semantics. Mirrored as `signPos` in GLSL.
 */
internal fun signOrPositive(x: Float): Float = if (x < 0f) -1f else 1f

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
 * Grid layout: [width]×[height] texels, four floats each (dx, dy in UV
 * units, the Fusion mask m in [0,1], then the Freeze mask in [0,1] —
 * the GL side stores those in the field texture's z and w channels),
 * row-major, texel (ix, iy) centered at UV
 * `((ix+0.5)/width, (iy+0.5)/height)`.
 */
class DisplacementField(val width: Int, val height: Int, val aspect: Float) {

    val data = FloatArray(width * height * CHANNELS)

    fun reset() = data.fill(0f)

    /** Bilinearly sampled displacement x-component at UV (u, v). */
    fun sampleX(u: Float, v: Float): Float = sample(u, v, 0)

    /** Bilinearly sampled displacement y-component at UV (u, v). */
    fun sampleY(u: Float, v: Float): Float = sample(u, v, 1)

    /** Bilinearly sampled Fusion mask at UV (u, v). */
    fun sampleMask(u: Float, v: Float): Float = sample(u, v, 2)

    /**
     * Bilinearly sampled Freeze mask at UV (u, v) — 0 free, 1 pinned
     * (proposal 0002).
     *
     * Unlike the Fusion mask, this one is read in DOCUMENT space and
     * never through the warp-of-warp lookup. "This eye stays here" is a
     * statement about the document, and a protection that travelled with
     * the content it is simultaneously preventing from travelling would
     * be incoherent. It also matches Liquify's image-space mask, which
     * is what anyone arriving from there expects.
     */
    fun sampleFreeze(u: Float, v: Float): Float = sample(u, v, 3)

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
                // DRIP is the one anisotropic profile: below the center
                // the measured distance is compressed, so the lobe
                // reaches downward (FalloffProfile.DRIP).
                val mv = if (profile == FalloffProfile.DRIP && dv > 0f) {
                    dv * BrushDynamics.DRIP_LOBE
                } else {
                    dv
                }
                val distA = sqrt(du * du + mv * mv)
                val dist = distA / stroke.radius
                // The varnish (proposal 0002): a protect mask is a
                // multiplier on stamp weight, which is what makes one new
                // mode aim every brush in the palette — present and
                // future — without touching any of them.
                val guard = if (mode.respectsFreeze) {
                    1f - sampleFreeze(u, v).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val w = BrushFalloff.weight(dist, profile) * stroke.strength * guard
                // The radial direction is measured on the TRUE offset,
                // not the anisotropic metric — only the weight is
                // reshaped. (DRIP only ever pairs with DIRECTIONAL, so
                // the two agree in practice; kept separate so the shader
                // can be a transliteration rather than an equivalent.)
                val distR = sqrt(du * du + dv * dv)
                when (mode) {
                    StampMode.FUSE -> {
                        // Mask flow only; displacement passes through.
                        out[i] = at(ix, iy, 0)
                        out[i + 1] = at(ix, iy, 1)
                        out[i + 2] = (at(ix, iy, 2) + w * BrushDynamics.FUSE_STEP)
                            .coerceIn(0f, 1f)
                        out[i + 3] = at(ix, iy, 3)
                    }

                    StampMode.GUARD -> {
                        // Varnish flow only — the same trick FUSE pulls on
                        // the z channel, run again on w. Deliberately NOT
                        // subject to `guard` above: a brake that brakes
                        // itself makes a fully varnished region reachable
                        // by nothing but a global Reset.
                        out[i] = at(ix, iy, 0)
                        out[i + 1] = at(ix, iy, 1)
                        out[i + 2] = at(ix, iy, 2)
                        out[i + 3] = (at(ix, iy, 3) + w * BrushDynamics.FREEZE_STEP)
                            .coerceIn(0f, 1f)
                    }

                    StampMode.DIRECTIONAL, StampMode.INFLATE, StampMode.DEFLATE,
                    StampMode.VORTEX, StampMode.COMB, StampMode.RIPPLE,
                    StampMode.FAULT,
                    -> {
                        // b(p): falloff-weighted brush displacement,
                        // backward-mapped (negative = content moves with
                        // the gesture / bulges outward).
                        var bx: Float
                        var by: Float
                        if (mode == StampMode.DIRECTIONAL) {
                            bx = -stamp.dx * w
                            by = -stamp.dy * w
                        } else if (mode == StampMode.COMB) {
                            // Teeth cut ACROSS the drag: project onto the
                            // perpendicular of the (aspect-space) delta.
                            // The phase is a function of position relative
                            // to the stroke axis, not of stamp index, so
                            // consecutive stamps agree and the strands run
                            // unbroken; where the drag curves the axis
                            // rotates and they fan, as a comb does.
                            val ax = stamp.dx * aspect
                            val ay = stamp.dy
                            val len = sqrt(ax * ax + ay * ay)
                            if (len < 1e-9f) {
                                bx = 0f
                                by = 0f
                            } else {
                                val acrossX = -ay / len
                                val acrossY = ax / len
                                val s = (du * acrossX + dv * acrossY) / stroke.radius
                                val teeth = 0.5f + 0.5f *
                                    cos(TAU * s * BrushDynamics.COMB_TEETH)
                                bx = -stamp.dx * w * teeth
                                by = -stamp.dy * w * teeth
                            }
                        } else if (mode == StampMode.FAULT) {
                            // The path is a boundary, not a trail: the two
                            // sides slide along it in opposite directions.
                            // Reversing the drawn path flips both the
                            // tangent and the side function, and the two
                            // sign changes cancel — the same geometric
                            // seam makes the same fault either way.
                            val ax = stamp.dx * aspect
                            val ay = stamp.dy
                            val len = sqrt(ax * ax + ay * ay)
                            if (len < 1e-9f) {
                                bx = 0f
                                by = 0f
                            } else {
                                val tx = ax / len
                                val ty = ay / len
                                // Normal = tangent turned a right angle.
                                val side = smoothOdd((du * -ty + dv * tx) / stroke.radius)
                                val m = side * w * BrushDynamics.FAULT_STEP_UV
                                bx = (tx / aspect) * m
                                by = ty * m
                            }
                        } else {
                            // The radial family: INFLATE/DEFLATE push along
                            // the outward direction, VORTEX along its right
                            // angle, RIPPLE along it with an alternating
                            // sign. All share the center ramp, which exists
                            // because the direction is undefined at the
                            // exact center.
                            val ramp = w * BrushFalloff.centerRamp(dist)
                            if (distR < 1e-6f) {
                                bx = 0f
                                by = 0f
                            } else {
                                val ox = (du / distR) / aspect
                                val oy = dv / distR
                                when (mode) {
                                    StampMode.VORTEX -> {
                                        // Turn the unit vector a right angle
                                        // IN ASPECT SPACE, then divide the
                                        // aspect out — rotating the UV-space
                                        // vector would shear non-square
                                        // images. Chirality is dx's sign.
                                        val tx = -dv / distR
                                        val ty = du / distR
                                        val m = ramp * BrushDynamics.SWIRL_STEP_UV *
                                            signOrPositive(stamp.dx)
                                        bx = (tx / aspect) * m
                                        by = ty * m
                                    }

                                    StampMode.RIPPLE -> {
                                        val band = sin(TAU * BrushDynamics.RIPPLE_BANDS * dist)
                                        val m = ramp * BrushDynamics.RIPPLE_STEP_UV * band
                                        bx = ox * m
                                        by = oy * m
                                    }

                                    else -> {
                                        val m = ramp * BrushDynamics.RADIAL_STEP_UV
                                        val s = if (mode == StampMode.INFLATE) -1f else 1f
                                        bx = s * ox * m
                                        by = s * oy * m
                                    }
                                }
                            }
                        }
                        // D'(p) = b(p) + D(p + b(p)); the mask rides the
                        // same lookup so painted fusion moves with the goo.
                        out[i] = bx + sampleX(u + bx, v + by)
                        out[i + 1] = by + sampleY(u + bx, v + by)
                        out[i + 2] = sampleMask(u + bx, v + by)
                        // The varnish does NOT ride the lookup: it is
                        // pinned to the document, not to the content.
                        out[i + 3] = at(ix, iy, 3)
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
                        val blurM = 0.25f * (
                            sampleMask(u + tx, v) + sampleMask(u - tx, v) +
                                sampleMask(u, v + ty) + sampleMask(u, v - ty))
                        out[i] = at(ix, iy, 0) + (blurX - at(ix, iy, 0)) * k
                        out[i + 1] = at(ix, iy, 1) + (blurY - at(ix, iy, 1)) * k
                        out[i + 2] = at(ix, iy, 2) + (blurM - at(ix, iy, 2)) * k
                        // Smoothing the goo must not erode the varnish.
                        out[i + 3] = at(ix, iy, 3)
                    }

                    StampMode.ERASE -> {
                        // UnGoo un-fuses AND thaws: every channel back to
                        // the bare photo. Exempt from `guard` for the same
                        // reason GUARD is — otherwise varnish would be the
                        // one mark in the app that cannot be taken back
                        // except by Reset.
                        val k = 1f - w * BrushDynamics.BLEND_STEP
                        out[i] = at(ix, iy, 0) * k
                        out[i + 1] = at(ix, iy, 1) * k
                        out[i + 2] = at(ix, iy, 2) * k
                        out[i + 3] = at(ix, iy, 3) * k
                    }
                }
                i += CHANNELS
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

    private fun at(ix: Int, iy: Int, channel: Int): Float =
        data[(iy * width + ix) * CHANNELS + channel]

    companion object {
        /**
         * dx, dy, fusion mask, freeze mask.
         *
         * This is the last one. The GL field is RGBA16F, so w was already
         * allocated on every device and Freeze cost zero bytes — but the
         * next per-texel quantity needs a second texture and doubles the
         * ping-pong pair.
         */
        const val CHANNELS = 4
    }
}
