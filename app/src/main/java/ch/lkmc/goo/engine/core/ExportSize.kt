package ch.lkmc.goo.engine.core

import kotlin.math.roundToInt

/**
 * Sizing rules shared by the preview and export paths — one implementation
 * so the GL renderer and the ViewModel can never disagree (same principle
 * as FitTransform).
 */
object ExportSize {

    /**
     * Long-side cap for the export decode: the GL max texture size AND the
     * memory budget, whichever is tighter.
     *
     * The budget exists because five export-sized allocations coexist at
     * the readback peak: decoded source bitmap, GL source texture, GL
     * output texture, the direct readback buffer, and the result bitmap —
     * ~320 MB at a square 4096² worst case (~250 MB at 4:3), almost all
     * native/GPU rather than Java heap; with a Fusion photo B loaded add
     * two more export-sized allocations (B bitmap + B texture) — ~450 MB
     * worst case, still inside the budget this cap was sized for.
     * Uncapped 48 MP would quadruple that and OOM mid-range devices.
     * 4096 covers native 12 MP output;
     * larger sources downscale. (The separate readback buffer is
     * unavoidable without NDK pixel locking.) True full-resolution tiled
     * export is tracked in REVIEW.md.
     */
    fun exportLongSideCap(maxTextureSize: Int): Int {
        require(maxTextureSize > 0) { "invalid max texture size: $maxTextureSize" }
        return minOf(maxTextureSize, EXPORT_MAX_DIM)
    }

    /**
     * Displacement-field texture size for an image: the field is smooth by
     * nature, so it stays at or below [fieldCap] on the long side no
     * matter how large the image is (PLAN.md §4.1). Used by both the
     * preview field and the export replay field.
     */
    fun fieldDimensions(width: Int, height: Int, fieldCap: Int = FIELD_MAX_DIM): Pair<Int, Int> {
        require(width > 0 && height > 0 && fieldCap > 0) {
            "invalid field input: ${width}x$height cap $fieldCap"
        }
        val long = maxOf(width, height).toFloat()
        val scale = (fieldCap / long).coerceAtMost(1f)
        return Pair(
            (width * scale).roundToInt().coerceAtLeast(2),
            (height * scale).roundToInt().coerceAtLeast(2),
        )
    }

    /** Export bitmap budget cap; see [exportLongSideCap]. */
    const val EXPORT_MAX_DIM = 4096

    /** Field long-side cap; see [fieldDimensions]. */
    const val FIELD_MAX_DIM = 1024
}
