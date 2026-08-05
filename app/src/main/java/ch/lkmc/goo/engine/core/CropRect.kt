package ch.lkmc.goo.engine.core

import kotlin.math.roundToInt

/**
 * A crop selection in normalized coordinates of the EXIF-upright image:
 * fractions in [0,1], left < right, top < bottom.
 *
 * Crop is a DOCUMENT-SPACE change, not an edit (PLAN.md "Crop"): strokes
 * and keyframes record UVs of the frame they were painted on, so applying
 * a crop restarts the document ([StrokeLog.clearHistory]) instead of
 * remapping history into a new aspect. The stored rect is always relative
 * to the ORIGINAL image — re-crops [compose] into it — so decode applies
 * exactly one subrect no matter how many times the user reframed.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** True when this crop keeps (effectively) the whole image. */
    val isFullFrame: Boolean
        get() = left <= EPSILON && top <= EPSILON &&
            right >= 1f - EPSILON && bottom >= 1f - EPSILON

    /**
     * [inner], drawn on the image THIS rect produced, mapped into the
     * same space this rect lives in (the original image).
     */
    fun compose(inner: CropRect): CropRect = CropRect(
        left = left + inner.left * width,
        top = top + inner.top * height,
        right = left + inner.right * width,
        bottom = top + inner.bottom * height,
    )

    /**
     * Move one corner to (u, v): clamped into the image and away from
     * the opposite edges so both sides keep [MIN_FRACTION]. Corners:
     * 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right.
     */
    fun dragCorner(corner: Int, u: Float, v: Float): CropRect {
        val movesLeft = corner % 2 == 0
        val movesTop = corner < 2
        return CropRect(
            left = if (movesLeft) u.coerceIn(0f, right - MIN_FRACTION) else left,
            top = if (movesTop) v.coerceIn(0f, bottom - MIN_FRACTION) else top,
            right = if (!movesLeft) u.coerceIn(left + MIN_FRACTION, 1f) else right,
            bottom = if (!movesTop) v.coerceIn(top + MIN_FRACTION, 1f) else bottom,
        )
    }

    /** Translate by (du, dv), clamped so the rect never leaves the image. */
    fun dragInside(du: Float, dv: Float): CropRect {
        val dx = du.coerceIn(-left, 1f - right)
        val dy = dv.coerceIn(-top, 1f - bottom)
        return CropRect(left + dx, top + dy, right + dx, bottom + dy)
    }

    /**
     * The subrect in pixels of a w × h bitmap: always in bounds and at
     * least 1 px on each side, whatever float rounding does. Returns
     * [x, y, width, height] (the createBitmap argument order).
     */
    fun pixelRect(w: Int, h: Int): IntArray {
        val x = (left * w).roundToInt().coerceIn(0, w - 1)
        val y = (top * h).roundToInt().coerceIn(0, h - 1)
        val pw = (width * w).roundToInt().coerceIn(1, w - x)
        val ph = (height * h).roundToInt().coerceIn(1, h - y)
        return intArrayOf(x, y, pw, ph)
    }

    /** For SavedStateHandle; inverse of [fromArray]. */
    fun toArray(): FloatArray = floatArrayOf(left, top, right, bottom)

    companion object {
        /** Smallest croppable side, as a fraction of the image. */
        const val MIN_FRACTION = 0.10f

        /**
         * Slack for "is this edge at the border": a hair under half of
         * MIN_FRACTION's granularity, generous against handle jitter
         * but far too small to eat a deliberate crop.
         */
        private const val EPSILON = 0.005f

        val FULL = CropRect(0f, 0f, 1f, 1f)

        /**
         * Restore from [toArray] output. Null on anything malformed
         * (wrong size, out of range, inverted or empty) — a corrupt
         * restore must fall back to uncropped, never crash or produce
         * a degenerate decode. Deliberately structural-only: composed
         * re-crops legitimately store sides below [MIN_FRACTION]
         * (that's a per-gesture drag floor, not a document invariant),
         * and rejecting them here would silently uncrop on restore.
         */
        fun fromArray(a: FloatArray?): CropRect? {
            if (a == null || a.size != 4) return null
            val r = CropRect(a[0], a[1], a[2], a[3])
            val valid = r.left in 0f..1f && r.top in 0f..1f &&
                r.right in 0f..1f && r.bottom in 0f..1f &&
                r.width > 0f && r.height > 0f
            return if (valid) r else null
        }
    }
}
