package ch.lkmc.goo.engine.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * The editor's view transform (user feedback, v1.1): a similarity —
 * uniform [scale], [rotation] (radians), then translation ([tx], [ty])
 * in view pixels. Applied to the PREVIEW only, in pixel space (the
 * warp vertex shader); export and movie always render the document at
 * identity, so the view is pure navigation and never leaks into output.
 *
 * `T(p) = s·R(θ)·p + t`. Similarities are closed under composition, so
 * two-finger gesture steps accumulate exactly. Ephemeral state — not
 * serialized; a reset button returns it to identity.
 */
data class ViewTransform(
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    val isIdentity: Boolean
        get() = scale == 1f && rotation == 0f && tx == 0f && ty == 0f

    /** a = s·cosθ, b = s·sinθ — the four uniform floats the shader wants. */
    val a: Float get() = scale * cos(rotation)
    val b: Float get() = scale * sin(rotation)

    /** Map an untransformed canvas point to where the view shows it. */
    fun apply(x: Float, y: Float): Pair<Float, Float> =
        Pair(a * x - b * y + tx, b * x + a * y + ty)

    /** Inverse of [apply]: a touch in view pixels → canvas pixels. */
    fun invert(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - tx
        val dy = y - ty
        val c = cos(rotation)
        val s = sin(rotation)
        // R(-θ)·d / scale
        return Pair((c * dx + s * dy) / scale, (-s * dx + c * dy) / scale)
    }

    /**
     * One two-finger gesture step: pan by ([panX], [panY]), zoom by
     * [zoom] and rotate by [rotationDelta] about the view-space
     * [centroidX], [centroidY] — the standard formulation where the
     * point under the fingers stays under the fingers. Scale clamps to
     * [MIN_SCALE]..[MAX_SCALE]; the effective zoom is adjusted so the
     * centroid anchor stays exact at the clamp boundary.
     */
    fun gesture(
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        rotationDelta: Float,
    ): ViewTransform {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val effectiveZoom = newScale / scale
        val c = cos(rotationDelta)
        val s = sin(rotationDelta)
        val relX = tx - centroidX
        val relY = ty - centroidY
        return ViewTransform(
            scale = newScale,
            // Wrapped to (-π, π]: keeps cos/sin arguments small over long
            // sessions AND makes the reset lerp shortest-path by
            // construction — a view rotated "350°" stores as -10°, so
            // Reset springs 10° forward, never the long way around.
            rotation = normalizeAngle(rotation + rotationDelta),
            tx = centroidX + panX + effectiveZoom * (c * relX - s * relY),
            ty = centroidY + panY + effectiveZoom * (s * relX + c * relY),
        )
    }

    /**
     * Rebase this navigation from [oldFit] to [newFit]. The source UV shown
     * at the old viewport center remains at the new viewport center, while
     * zoom and rotation stay unchanged. This handles rotation, multi-window,
     * fold posture, and editor-panel height changes without stale pixel pan.
     */
    fun rebase(oldFit: FitTransform, newFit: FitTransform): ViewTransform {
        if (isIdentity || !scale.isFinite() || scale == 0f || !rotation.isFinite()) return this
        val oldCenterX = oldFit.viewWidth / 2f
        val oldCenterY = oldFit.viewHeight / 2f
        val (oldCanvasX, oldCanvasY) = invert(oldCenterX, oldCenterY)
        val (centerU, centerV) = oldFit.viewToUv(oldCanvasX, oldCanvasY)
        val (newCanvasX, newCanvasY) = newFit.uvToView(centerU, centerV)
        val newCenterX = newFit.viewWidth / 2f
        val newCenterY = newFit.viewHeight / 2f
        return copy(
            tx = newCenterX - (a * newCanvasX - b * newCanvasY),
            ty = newCenterY - (b * newCanvasX + a * newCanvasY),
        )
    }

    /** Component-wise interpolation toward [other] — the reset spring. */
    fun lerp(other: ViewTransform, t: Float): ViewTransform = ViewTransform(
        scale = scale + (other.scale - scale) * t,
        rotation = rotation + (other.rotation - rotation) * t,
        tx = tx + (other.tx - tx) * t,
        ty = ty + (other.ty - ty) * t,
    )

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 8f

        private const val TWO_PI = (2.0 * kotlin.math.PI).toFloat()

        /** Wrap an angle to (-π, π]. Exact zero stays exact zero. */
        fun normalizeAngle(a: Float): Float {
            if (a == 0f) return 0f
            var r = a % TWO_PI
            if (r > kotlin.math.PI.toFloat()) r -= TWO_PI
            if (r <= -kotlin.math.PI.toFloat()) r += TWO_PI
            return r
        }
    }
}
