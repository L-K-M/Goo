package ch.lkmc.goo.engine.core

import kotlin.math.roundToInt

/**
 * How fast an exported GOOvie runs, as a multiplier on the strip's
 * playback rate ([GoovieTimeline.SECONDS_PER_SEGMENT] per segment).
 *
 * Speed changes the FRAME COUNT, never the frame rate: a 2× movie covers
 * the same strip in half the frames at the same fps, so the encoder, the
 * pts ladder and the GIF delay all stay on their nominal clock. Export
 * only — the strip keeps ambling at 1× so the preview stays the thing you
 * scrub, not a thing that also has a gear lever.
 */
enum class MovieSpeed(val multiplier: Float) {
    HALF(0.5f),
    NORMAL(1f),
    DOUBLE(2f),
    QUADRUPLE(4f),
    ;

    companion object {
        val DEFAULT = NORMAL
    }
}

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
     * GIF is a share-anywhere format, not an archival one: a palette frame
     * costs roughly a whole raw image, so the long edge caps far below the
     * MP4's and the frame rate is a fraction of it.
     */
    const val GIF_MAX_DIM = 480

    /**
     * GIF frame delays are whole CENTIseconds, so only rates that divide
     * 100 keep an honest duration. The ladder is walked top-down until the
     * movie fits [GIF_MAX_FRAMES]; 2 fps is the floor, which is a
     * slideshow — but a 64-keyframe strip at ½× is one anyway.
     */
    val GIF_FPS_LADDER = intArrayOf(20, 10, 5, 4, 2)

    /**
     * Frame budget for one GIF. 200 frames of 480×360 indices is ~35 MB
     * before LZW — past that a "shareable" GIF stops being shareable.
     */
    const val GIF_MAX_FRAMES = 200

    /**
     * Video dimensions for an image: fit within [maxDim] on the long
     * edge (never upscale) and round to even — H.264 encoders reject odd
     * sizes.
     */
    fun videoSize(imageWidth: Int, imageHeight: Int, maxDim: Int = MAX_DIM): Pair<Int, Int> {
        require(imageWidth > 0 && imageHeight > 0) { "empty image" }
        val scale = minOf(
            1f,
            maxDim.toFloat() / maxOf(imageWidth, imageHeight),
        )
        val w = (imageWidth * scale).toInt().coerceAtLeast(MIN_DIM)
        val h = (imageHeight * scale).toInt().coerceAtLeast(MIN_DIM)
        return Pair(w - w % 2, h - h % 2)
    }

    /** GIF frame dimensions — the same fit against the smaller cap. */
    fun gifSize(imageWidth: Int, imageHeight: Int): Pair<Int, Int> =
        videoSize(imageWidth, imageHeight, GIF_MAX_DIM)

    /**
     * Total frames for one full pass over the strip at [speed]: segment
     * count × seconds-per-segment ÷ speed × [fps], plus the closing frame
     * so the movie lands exactly on the last keyframe.
     */
    fun totalFrames(keyframeCount: Int, speed: Float = 1f, fps: Int = FPS): Int {
        require(speed > 0f) { "speed must be positive" }
        require(fps > 0) { "fps must be positive" }
        if (keyframeCount < 2) return 0
        val segments = keyframeCount - 1
        // roundToInt, not toInt: the seconds product is a float, and a
        // 35.999996 would silently drop a frame off every segment.
        val frames = (segments * GoovieTimeline.SECONDS_PER_SEGMENT / speed * fps).roundToInt()
        return frames.coerceAtLeast(1) + 1
    }

    /** Wall-clock length of the exported movie, seconds. */
    fun durationSeconds(keyframeCount: Int, speed: Float = 1f, fps: Int = FPS): Float {
        val total = totalFrames(keyframeCount, speed, fps)
        return if (total < 2) 0f else (total - 1).toFloat() / fps
    }

    /**
     * The fastest ladder rate whose frame count fits [GIF_MAX_FRAMES].
     * Dropping the rate (rather than truncating the strip) keeps the GIF
     * the same length as the MP4 would be — it just gets choppier.
     */
    fun gifFps(keyframeCount: Int, speed: Float = 1f): Int =
        GIF_FPS_LADDER.firstOrNull { totalFrames(keyframeCount, speed, it) <= GIF_MAX_FRAMES }
            ?: GIF_FPS_LADDER.last()

    /** Per-frame GIF delay in centiseconds — GIF's only time unit. */
    fun gifDelayCentis(fps: Int): Int {
        require(fps > 0) { "fps must be positive" }
        // ≥2: most viewers silently promote 0/1cs delays to 10cs, which
        // would stretch a fast GOOvie to a crawl on exactly those viewers.
        return (100 / fps).coerceAtLeast(2)
    }

    /** Strip position for frame [frame] of [totalFrames] over [keyframeCount] keyframes. */
    fun positionAt(frame: Int, totalFrames: Int, keyframeCount: Int): Float {
        if (keyframeCount < 2 || totalFrames < 2) return 0f
        val span = (keyframeCount - 1).toFloat()
        return span * frame / (totalFrames - 1)
    }

    /** Presentation timestamp for frame [frame], nanoseconds. */
    fun ptsNanos(frame: Int, fps: Int = FPS): Long = frame * 1_000_000_000L / fps
}
