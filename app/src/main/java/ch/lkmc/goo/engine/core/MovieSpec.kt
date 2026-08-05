package ch.lkmc.goo.engine.core

/**
 * Pure sizing/timing math for GOOvie movie export (PLAN.md §5, §10 #8).
 * The GL/MediaCodec plumbing consumes these numbers; keeping them here
 * makes the contract unit-testable without a device.
 */
object MovieSpec {

    const val FPS = 30
    const val BIT_RATE = 8_000_000
    const val I_FRAME_INTERVAL_SECONDS = 1

    /** Longest edge cap — 1080p-class output regardless of photo size. */
    const val MAX_DIM = 1920
    const val MIN_DIM = 2

    /**
     * Video dimensions for an image: fit within [MAX_DIM] on the long
     * edge (never upscale) and round to even — H.264 encoders reject odd
     * sizes.
     */
    fun videoSize(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        require(imageWidth > 0 && imageHeight > 0) { "empty image" }
        val scale = minOf(
            1f,
            MAX_DIM.toFloat() / maxOf(imageWidth, imageHeight),
        )
        val w = (imageWidth * scale).toInt().coerceAtLeast(MIN_DIM)
        val h = (imageHeight * scale).toInt().coerceAtLeast(MIN_DIM)
        return Pair(w - w % 2, h - h % 2)
    }

    /**
     * Total frames for one full pass over the strip: segment count ×
     * seconds-per-segment × fps, plus the closing frame so the movie
     * lands exactly on the last keyframe.
     */
    fun totalFrames(keyframeCount: Int): Int {
        if (keyframeCount < 2) return 0
        val segments = keyframeCount - 1
        return (segments * GoovieTimeline.SECONDS_PER_SEGMENT * FPS).toInt() + 1
    }

    /** Strip position for frame [frame] of [totalFrames] over [keyframeCount] keyframes. */
    fun positionAt(frame: Int, totalFrames: Int, keyframeCount: Int): Float {
        if (keyframeCount < 2 || totalFrames < 2) return 0f
        val span = (keyframeCount - 1).toFloat()
        return span * frame / (totalFrames - 1)
    }

    /** Presentation timestamp for frame [frame], nanoseconds. */
    fun ptsNanos(frame: Int): Long = frame * 1_000_000_000L / FPS
}
