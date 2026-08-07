package ch.lkmc.goo.engine.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * A half-open texel rectangle `[x0, x1) × [y0, y1)` in displacement-field
 * space, used to scissor the stamp pass (REVIEW.md G-3).
 *
 * Coordinates are framebuffer texels with the origin at (0, 0) and y
 * increasing the same way image UV v does. That is not an accident of
 * convention but a property of `GlShaders.QUAD_VERT`, which sets
 * `v_uv = a_pos * 0.5 + 0.5` against `gl_Position = a_pos`: the fragment
 * at NDC y = −1 has both v_uv.y = 0 and framebuffer y = 0. So a rect
 * derived from UV needs no flip on its way to `glScissor`.
 */
data class TexelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {

    val width: Int get() = (x1 - x0).coerceAtLeast(0)
    val height: Int get() = (y1 - y0).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 || height == 0

    /** Texels covered — the quantity the scissor optimization is about. */
    val area: Long get() = width.toLong() * height.toLong()

    /**
     * True when this rect covers all of [other] — so a draw clipped to
     * this rect will overwrite every texel of it, and any repair of
     * [other] beforehand would be wasted.
     */
    fun contains(other: TexelRect): Boolean =
        other.isEmpty || (!isEmpty && x0 <= other.x0 && y0 <= other.y0 && x1 >= other.x1 && y1 >= other.y1)

    /** The smallest rect containing both; an empty operand contributes nothing. */
    fun union(other: TexelRect): TexelRect = when {
        isEmpty -> other
        other.isEmpty -> this
        else -> TexelRect(
            minOf(x0, other.x0),
            minOf(y0, other.y0),
            maxOf(x1, other.x1),
            maxOf(y1, other.y1),
        )
    }

    companion object {
        val EMPTY = TexelRect(0, 0, 0, 0)

        /** The whole field — what a full-surface pass writes. */
        fun full(width: Int, height: Int) = TexelRect(0, 0, width, height)
    }
}

/**
 * Which field texels one stamp can actually change.
 *
 * The stamp shader weights every kernel by `falloff(distA / u_radius)`,
 * and every profile in [BrushFalloff] is exactly 0 at and beyond the rim.
 * Outside that disc every branch of `STAMP_FRAG` reduces to an identity
 * copy — the warp modes get `b = 0` and so read back the fragment's own
 * texel, RELAX mixes by 0, ERASE fades by 0, FUSE adds 0 — which is why
 * the pass could afford to be fullscreen in the first place, and equally
 * why those fragments can be skipped.
 *
 * Skipping them means the destination of a ping-pong pass no longer
 * receives the untouched majority of the field, so [PingPongField] pairs
 * this rect with a blit of the previous stamp's rect. This object owns
 * only the geometry, because the geometry is the part a JVM test can pin.
 *
 * Distances are aspect-space (`Stroke.radius` units — see the coordinate
 * block in `Stroke.kt`): the disc spans `radius / aspect` in u and
 * `radius` in v, which is what keeps a brush circle round in pixels.
 */
object StampBounds {

    /**
     * Texels of slack around the analytic disc. The falloff reaches zero
     * exactly at the rim, so one texel would do for the mathematics; two
     * absorbs float rounding in the UV → texel conversion and costs a
     * border whose area is negligible against any usable brush.
     */
    const val MARGIN = 2

    /**
     * The rect a stamp centered at ([cx], [cy]) with aspect-space
     * [radius] can write in a [width] × [height] field, clamped to the
     * field and widened by [margin].
     *
     * Returns an empty rect when the disc misses the field entirely,
     * which callers may use to skip the pass outright — a mirrored or
     * kaleidoscoped twin can legitimately land off-canvas.
     */
    fun of(
        cx: Float,
        cy: Float,
        radius: Float,
        aspect: Float,
        width: Int,
        height: Int,
        margin: Int = MARGIN,
    ): TexelRect {
        require(width > 0 && height > 0) { "field must be non-empty: ${width}x$height" }
        require(aspect > 0f) { "aspect must be positive: $aspect" }
        if (radius <= 0f || !radius.isFinite()) return TexelRect.EMPTY
        if (!cx.isFinite() || !cy.isFinite()) return TexelRect.EMPTY

        // Aspect space → UV: the u half-extent divides the aspect out, the
        // v half-extent is already in UV units.
        val halfU = radius / aspect
        val halfV = radius

        // Widen by the margin in Long space and clamp to the field ONCE,
        // at the end. Clamping the raw edges first would collapse a disc
        // that misses the field entirely onto the near edge, and the
        // margin would then reopen it into a bogus sliver.
        val x0 = (saturate((cx - halfU) * width, roundDown = true) - margin).clampTo(width)
        val x1 = (saturate((cx + halfU) * width, roundDown = false) + margin).clampTo(width)
        val y0 = (saturate((cy - halfV) * height, roundDown = true) - margin).clampTo(height)
        val y1 = (saturate((cy + halfV) * height, roundDown = false) + margin).clampTo(height)

        return if (x1 <= x0 || y1 <= y0) TexelRect.EMPTY else TexelRect(x0, y0, x1, y1)
    }

    /**
     * Float texel edge → Long, saturating instead of wrapping. Nothing
     * clamps `Stroke.radius` to the frame, so a large enough radius
     * reaches values `toInt()` would wrap — landing the rect on the wrong
     * side of the field, which is a blank-frame bug rather than a slow
     * one. Long also leaves headroom for the margin arithmetic below.
     */
    private fun saturate(edge: Float, roundDown: Boolean): Long {
        if (edge.isNaN()) return 0
        val rounded = if (roundDown) floor(edge) else ceil(edge)
        return when {
            rounded <= LIMIT_LOW -> LIMIT_LOW.toLong()
            rounded >= LIMIT_HIGH -> LIMIT_HIGH.toLong()
            else -> rounded.toLong()
        }
    }

    private fun Long.clampTo(limit: Int): Int = coerceIn(0L, limit.toLong()).toInt()

    private const val LIMIT_LOW = -1e9f
    private const val LIMIT_HIGH = 1e9f
}
