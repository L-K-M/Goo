package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable

/**
 * What a lens does to what is under it. [shaderId] is the wire value of
 * the `u_lensType` uniform — explicit, so reordering the enum can never
 * silently re-map a saved project's lenses.
 */
@Serializable
enum class LensType(val shaderId: Int) {
    /** Magnify: sample nearer the lens center. */
    BULGE(0),

    /** Shrink: sample further from it. */
    PINCH(1),

    /**
     * Bulge with a flat core — magnification reaches full strength well
     * inside the rim and stays there, so the middle of the lens is
     * uniformly enlarged and the compression piles up at the edge. That
     * ring of compression is what makes it read as glass rather than as
     * a soft swell.
     */
    FISHEYE(2),

    /** Rotate about the lens center, the angle falling off to the rim. */
    VORTEX(3),
}

/**
 * A placed, persistent warp (proposal 0006) — the missing cell between
 * the two families the app already had. Brushes are local and one-shot;
 * levers are persistent but glued to the frame's center. A lens is
 * persistent *and* placed: furniture on the photo rather than graffiti
 * on it.
 *
 * Lenses are document state, exactly like the levers they are made of:
 * they ride in [GlobalParams], Reset clears them, undo stays stroke-only,
 * and a keyframe pin carries them, which is where the traveling-bulge
 * animation comes from for free.
 *
 * ([u], [v]) is the center in image UV; [radius] is in aspect-space units
 * (fraction of image height, like a brush radius) so a lens is round in
 * pixels on any image shape. [strength] is bipolar in [-1, 1] like a
 * lever: zero is identity, and negative runs the effect backwards (a
 * BULGE at -0.5 pinches), which is what makes a lens tween through zero
 * smoothly instead of jumping when it changes sides.
 */
@Serializable
data class Lens(
    val u: Float,
    val v: Float,
    val radius: Float = DEFAULT_RADIUS,
    val type: LensType = LensType.BULGE,
    val strength: Float = DEFAULT_STRENGTH,
) {
    /** A lens pulled to zero does nothing and should not cost a slot. */
    val isIdentity: Boolean get() = strength == 0f

    /** Clamped into the ranges the shader and the UI both assume. */
    fun sanitized(): Lens = copy(
        u = u.coerceIn(0f, 1f),
        v = v.coerceIn(0f, 1f),
        radius = radius.coerceIn(MIN_RADIUS, MAX_RADIUS),
        strength = strength.coerceIn(-1f, 1f),
    )

    companion object {
        /**
         * How many lenses can stand on the photo at once.
         *
         * Fixed, and small, for two reasons that happen to agree: the
         * uniform pack stays a bounded `vec4[4]` so the warp pass cost
         * does not depend on the document, and four is about as many
         * pieces of apparatus as fit on a phone screen before they stop
         * being individually grabbable.
         */
        const val CAPACITY = 4

        const val MIN_RADIUS = 0.06f
        const val MAX_RADIUS = 0.45f
        const val DEFAULT_RADIUS = 0.18f

        /** Strong enough to see on the first tap, short of grotesque. */
        const val DEFAULT_STRENGTH = 0.6f

        /** Floats per lens in the saved-state pack: u, v, radius, strength, type. */
        const val PACK_STRIDE = 5

        /**
         * Pack [lenses] for `SavedStateHandle`, which takes primitive
         * arrays but not lists of data classes. The type rides as its
         * [LensType.shaderId] rather than its ordinal, so the wire value
         * is the same one the shader sees.
         */
        fun pack(lenses: List<Lens>): FloatArray {
            // Capped here, because [unpack] REFUSES an oversized pack and
            // the caller reads that as "no lenses". Writing more than the
            // rack holds would therefore lose the whole rack on the next
            // process death rather than just the overflow — the wrong
            // failure by a wide margin.
            val capped = lenses.take(CAPACITY)
            val out = FloatArray(capped.size * PACK_STRIDE)
            capped.forEachIndexed { i, lens ->
                val base = i * PACK_STRIDE
                out[base] = lens.u
                out[base + 1] = lens.v
                out[base + 2] = lens.radius
                out[base + 3] = lens.strength
                out[base + 4] = lens.type.shaderId.toFloat()
            }
            return out
        }

        /** Inverse of [pack]; null when [packed] is not a lens pack. */
        fun unpack(packed: FloatArray): List<Lens>? {
            if (packed.size % PACK_STRIDE != 0) return null
            val count = packed.size / PACK_STRIDE
            if (count > CAPACITY) return null
            return (0 until count).map { i ->
                val base = i * PACK_STRIDE
                val id = packed[base + 4].toInt()
                val type = LensType.entries.firstOrNull { it.shaderId == id } ?: return null
                Lens(
                    u = packed[base],
                    v = packed[base + 1],
                    radius = packed[base + 2],
                    type = type,
                    strength = packed[base + 3],
                ).sanitized()
            }
        }
    }
}
