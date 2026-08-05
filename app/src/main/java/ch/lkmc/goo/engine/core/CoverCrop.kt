package ch.lkmc.goo.engine.core

/**
 * Centered cover-crop math for Fusion (PLAN.md §3): photo B is cropped
 * to photo A's aspect so the two share one UV space — the warp then
 * moves both in lockstep and the painted mask reveals aligned content.
 * Pure and unit-tested; ImageLoader applies the rect.
 */
object CoverCrop {

    /**
     * The centered crop of a [sourceWidth]×[sourceHeight] image with
     * aspect [targetAspect] (w/h), as [x, y, w, h] in source pixels.
     * Never empty for positive inputs.
     */
    fun rect(sourceWidth: Int, sourceHeight: Int, targetAspect: Float): IntArray {
        require(sourceWidth > 0 && sourceHeight > 0 && targetAspect > 0f) { "degenerate crop" }
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        return if (sourceAspect > targetAspect) {
            // Source is wider: full height, trim the sides.
            val w = (sourceHeight * targetAspect).toInt().coerceIn(1, sourceWidth)
            intArrayOf((sourceWidth - w) / 2, 0, w, sourceHeight)
        } else {
            // Source is taller: full width, trim top and bottom.
            val h = (sourceWidth / targetAspect).toInt().coerceIn(1, sourceHeight)
            intArrayOf(0, (sourceHeight - h) / 2, sourceWidth, h)
        }
    }
}
