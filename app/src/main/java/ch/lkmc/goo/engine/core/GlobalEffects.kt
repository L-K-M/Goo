package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The global-effect levers (PLAN.md §4.1): parametric analytic warps
 * composed additively over the painted stroke field. Each lever is
 * bipolar in [-1, 1] with 0 = identity, so the whole rig is live and
 * non-destructive — levers are document state, not history entries
 * (pulling one back to center undoes it exactly; Reset zeroes them all).
 */
@Serializable
data class GlobalParams(
    val bulge: Float = 0f,
    val twirl: Float = 0f,
    val squeeze: Float = 0f,
    val stretch: Float = 0f,
    val spike: Float = 0f,
    val static: Float = 0f,
    /**
     * Placed warps (proposal 0006), at most [Lens.CAPACITY] of them.
     * They live here rather than beside the stroke log because they are
     * the same *kind* of thing as the levers — live, non-destructive,
     * analytic — so they inherit the levers' persistence, their keyframe
     * pinning, and their preview/export parity without any of it being
     * written twice.
     *
     * Defaulted, so a project file written before lenses existed loads
     * with none.
     */
    val lenses: List<Lens> = emptyList(),
) {
    val isIdentity: Boolean
        get() = bulge == 0f && twirl == 0f && squeeze == 0f &&
            stretch == 0f && spike == 0f && static == 0f &&
            lenses.all { it.isIdentity }

    /**
     * Uniform-upload order; the GLSL `u_g[6]` contract.
     *
     * Deliberately still six floats: lenses are a separate, differently
     * shaped uniform and adding them here would break the one array the
     * whole warp pass is written against.
     */
    fun toArray(): FloatArray = floatArrayOf(bulge, twirl, squeeze, stretch, spike, static)

    /** [lenses] with any pulled to zero dropped, capped at capacity. */
    fun activeLenses(): List<Lens> =
        lenses.asSequence().filterNot { it.isIdentity }.take(Lens.CAPACITY).toList()

    companion object {
        /**
         * Inverse of [toArray]; null if the array isn't a lever pack.
         * Lenses travel separately (see [Lens.pack]) — this is the six.
         */
        fun fromArray(a: FloatArray): GlobalParams? =
            if (a.size == 6) GlobalParams(a[0], a[1], a[2], a[3], a[4], a[5]) else null
    }
}

/**
 * CPU reference for the analytic global displacement `G(p; levers)`.
 * WARP_FRAG's `globalDisp` is a transliteration — keep the pair
 * trivially close (constants documented there with sync markers).
 *
 * Conventions match the brush engine: positions are image UV, radial
 * math happens in aspect space around the image center, results are UV
 * deltas added to the sampled displacement (backward mapping — a delta
 * *toward* the center magnifies).
 */
object GlobalField {

    /** Max twirl rotation at |twirl| = 1, radians. */
    const val TWIRL_MAX_RAD = 2.5f

    /** Bulge displacement scale at |bulge| = 1. */
    const val BULGE_SCALE = 0.35f

    /** Squeeze/stretch axis scale at full lever. */
    const val AXIS_SCALE = 0.30f

    /** Spike amplitude at full lever, and the star's point count. */
    const val SPIKE_SCALE = 0.08f
    const val SPIKE_COUNT = 8

    /** Static crumple amplitude at full lever, and its cell grid. */
    const val STATIC_SCALE = 0.05f
    const val STATIC_CELLS = 24

    /**
     * Radial displacement a lens applies at full strength, as a fraction
     * of its own radius. Scaling by the lens radius rather than by a
     * fixed UV amount is what makes a small lens a hard little bulge and
     * a large one a slow swell, instead of both moving content the same
     * absolute distance.
     */
    const val LENS_SCALE = 0.5f

    /** Max rotation a VORTEX lens applies at its center, radians. */
    const val LENS_TWIRL_RAD = 2.0f

    /**
     * Where a FISHEYE lens stops ramping, as a fraction of its radius.
     * Inside this the magnification is flat — the "core" — and the whole
     * falloff to the rim happens outside it, which is the compression
     * ring that makes it look like glass.
     */
    const val LENS_CORE = 0.35f

    /**
     * The displacement added at UV ([u], [v]) for [p] — (du, dv) in UV
     * units. Pure and allocation-free per PLAN's core rules… except the
     * Pair; call sites are tests and docs, the shader is the hot path.
     */
    fun displacement(u: Float, v: Float, aspect: Float, p: GlobalParams): Pair<Float, Float> {
        // Aspect-space position relative to the image center.
        val ax = (u - 0.5f) * aspect
        val ay = v - 0.5f
        val r = sqrt(ax * ax + ay * ay)
        // Radial shape: 1 at center → 0 at the frame corner, so radial
        // effects never drag content in from beyond the frame edge.
        val rMax = sqrt(aspect * aspect + 1f) * 0.5f
        val shape = smooth(1f - (r / rMax).coerceIn(0f, 1f))

        var dx = 0f
        var dy = 0f

        // Bulge: displacement along -r̂ magnifies (backward map).
        if (p.bulge != 0f && r > 1e-6f) {
            val m = -p.bulge * BULGE_SCALE * shape * r / rMax
            dx += (ax / r) * m
            dy += (ay / r) * m
        }

        // Twirl: exact rotation about the center, angle falling off with r.
        if (p.twirl != 0f) {
            val theta = p.twirl * TWIRL_MAX_RAD * shape
            val c = cos(theta)
            val s = sin(theta)
            dx += ax * c - ay * s - ax
            dy += ax * s + ay * c - ay
        }

        // Squeeze: x in, y out (bipolar). Stretch: y only.
        if (p.squeeze != 0f) {
            dx += ax * p.squeeze * AXIS_SCALE
            dy += -ay * p.squeeze * AXIS_SCALE * 0.5f
        }
        if (p.stretch != 0f) {
            dy += -ay * p.stretch * AXIS_SCALE
        }

        // Spike: angular star modulation along r̂.
        if (p.spike != 0f && r > 1e-6f) {
            val phi = kotlin.math.atan2(ay, ax)
            val m = p.spike * SPIKE_SCALE * shape * sin(SPIKE_COUNT * phi)
            dx += (ax / r) * m
            dy += (ay / r) * m
        }

        // Static: fixed value-noise crumple (integer hash → GPU parity).
        if (p.static != 0f) {
            val nx = valueNoise(u * STATIC_CELLS, v * STATIC_CELLS, seed = 1u)
            val ny = valueNoise(u * STATIC_CELLS, v * STATIC_CELLS, seed = 2u)
            dx += (nx * 2f - 1f) * p.static * STATIC_SCALE
            dy += (ny * 2f - 1f) * p.static * STATIC_SCALE
        }

        // Placed warps, on top of the frame-centered ones. Bounded loop:
        // at most Lens.CAPACITY, so the pass cost never depends on the
        // document. Positions here are in the SAME aspect space as the
        // levers above, which is why the lens contribution can simply be
        // summed in before the one conversion back to UV.
        // Iterated in place rather than through activeLenses(): this
        // function is documented allocation-free and is evaluated per
        // texel, so materializing a list here would allocate once per
        // texel per stamp. The renderer still uses activeLenses(), where
        // it costs one list per FRAME.
        var placed = 0
        for (lens in p.lenses) {
            if (lens.isIdentity) continue
            if (placed >= Lens.CAPACITY) break
            placed++
            val lx = (u - lens.u) * aspect
            val ly = v - lens.v
            val d = sqrt(lx * lx + ly * ly)
            if (d >= lens.radius) continue
            // 1 at the lens center → 0 at its rim, so a lens never drags
            // content in from outside the glass.
            val window = smooth(1f - d / lens.radius)
            when (lens.type) {
                LensType.BULGE, LensType.PINCH, LensType.FISHEYE -> {
                    if (d <= 1e-6f) continue
                    // Ramp from the center outward: with a constant
                    // magnitude the whole disc would slide as one piece.
                    // FISHEYE saturates that ramp early, leaving a flat
                    // core and piling the falloff up at the rim.
                    val ramp = when (lens.type) {
                        LensType.FISHEYE -> (d / (lens.radius * LENS_CORE)).coerceAtMost(1f)
                        else -> d / lens.radius
                    }
                    // Negative displacement points at the lens center, so
                    // the sample comes from nearer it: magnification.
                    val sign = if (lens.type == LensType.PINCH) 1f else -1f
                    val m = sign * lens.strength * LENS_SCALE * lens.radius * window * ramp
                    dx += (lx / d) * m
                    dy += (ly / d) * m
                }

                LensType.VORTEX -> {
                    val theta = lens.strength * LENS_TWIRL_RAD * window
                    val c = cos(theta)
                    val s = sin(theta)
                    dx += lx * c - ly * s - lx
                    dy += lx * s + ly * c - ly
                }
            }
        }

        // Back to UV units (aspect-space x → divide out).
        return Pair(dx / aspect, dy)
    }

    /** smoothstep(0,1,t) for t already clamped. */
    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * Bilinear value noise in [0,1] from an integer LCG hash — identical
     * arithmetic on CPU and GPU (no transcendental hashes, whose
     * precision differs per driver).
     */
    fun valueNoise(x: Float, y: Float, seed: UInt): Float {
        val x0 = floorToInt(x)
        val y0 = floorToInt(y)
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = hash(x0.toUInt(), y0.toUInt(), seed)
        val b = hash((x0 + 1).toUInt(), y0.toUInt(), seed)
        val c = hash(x0.toUInt(), (y0 + 1).toUInt(), seed)
        val d = hash((x0 + 1).toUInt(), (y0 + 1).toUInt(), seed)
        val top = a + (b - a) * sx
        val bottom = c + (d - c) * sx
        return top + (bottom - top) * sy
    }

    /** LCG-mix hash → [0,1). Mirrored literally in GLSL. */
    fun hash(x: UInt, y: UInt, seed: UInt): Float {
        var h = x * 1664525u + y * 1013904223u + seed * 2654435761u
        h = h xor (h shr 16)
        h *= 2246822519u
        h = h xor (h shr 13)
        return (h and 0x00FFFFFFu).toFloat() / 16777216f
    }

    private fun floorToInt(f: Float): Int = kotlin.math.floor(f).toInt()
}
