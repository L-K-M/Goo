package ch.lkmc.goo.data

/**
 * BitmapFactory inSampleSize for decoding near a target size without
 * blowing the heap: the largest power of two that keeps the LONG side of
 * the decoded image at or above [maxDimension] — the next halving would
 * undershoot the preview target. The long side (not both sides) is what
 * matters: a 20000×4000 panorama must subsample aggressively even though
 * its short side is already near target, or the full decode OOMs. Powers
 * of two because decoders only honor those exactly.
 */
object SampleSizeCalculator {

    fun calculate(width: Int, height: Int, maxDimension: Int): Int {
        require(width > 0 && height > 0 && maxDimension > 0) {
            "invalid dimensions: ${width}x$height target $maxDimension"
        }
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
