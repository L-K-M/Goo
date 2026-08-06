package ch.lkmc.goo.engine.media

/**
 * A 256-colour palette for one GIF frame, built by median cut, plus the
 * colour→index lookup that maps the frame onto it.
 *
 * Pure JVM (no Android types) on purpose: this is the half of GIF export
 * that can actually be wrong, so it lives where `testDebugUnitTest` can
 * reach it (AGENTS.md — decision logic out of the GL renderer).
 *
 * Colours are bucketed to 5 bits per channel before anything else. That
 * bounds every loop below by the 32 768-entry histogram instead of by the
 * pixel count, and it costs nothing visible: GIF's own palette is coarser
 * still. Median cut then splits the busiest box along its longest axis
 * until 256 boxes remain, so gradients get the entries they need and a
 * flat background gets exactly one.
 */
class GifPalette private constructor(
    /** Packed 0xRRGGBB, one per index; size ≤ [MAX_COLORS]. */
    val colors: IntArray,
    /** Lazily filled key15 → palette index; [UNSET] means "not yet resolved". */
    private val lookup: ShortArray,
) {

    val size: Int get() = colors.size

    /** Nearest palette index for an ARGB pixel (alpha ignored). */
    fun indexOf(argb: Int): Int {
        val key = key15(argb)
        val cached = lookup[key]
        if (cached != UNSET) return cached.toInt()
        val index = nearest(key)
        lookup[key] = index.toShort()
        return index
    }

    /** Map a whole frame; one byte per pixel, row-major like the input. */
    fun index(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size)
        for (i in pixels.indices) out[i] = indexOf(pixels[i]).toByte()
        return out
    }

    /** GIF colour table bytes: r,g,b per entry, padded to a power of two. */
    fun tableBytes(): ByteArray {
        val entries = 1 shl bits()
        val bytes = ByteArray(entries * 3)
        for (i in colors.indices) {
            bytes[i * 3] = (colors[i] ushr 16 and 0xFF).toByte()
            bytes[i * 3 + 1] = (colors[i] ushr 8 and 0xFF).toByte()
            bytes[i * 3 + 2] = (colors[i] and 0xFF).toByte()
        }
        return bytes
    }

    /** Bits per pixel for the padded table — GIF tables are powers of two. */
    fun bits(): Int {
        var bits = 1
        while ((1 shl bits) < size) bits++
        return bits
    }

    private fun nearest(key: Int): Int {
        // Bucket centre, not the bucket floor: the pixels that map here are
        // spread across the whole 8-value bucket.
        val r = expand(key ushr 10 and MASK)
        val g = expand(key ushr 5 and MASK)
        val b = expand(key and MASK)
        var best = 0
        var bestDistance = Int.MAX_VALUE
        for (i in colors.indices) {
            val c = colors[i]
            val dr = r - (c ushr 16 and 0xFF)
            val dg = g - (c ushr 8 and 0xFF)
            val db = b - (c and 0xFF)
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
                if (distance == 0) break
            }
        }
        return best
    }

    companion object {
        const val MAX_COLORS = 256

        private const val BITS = 5
        private const val LEVELS = 1 shl BITS
        private const val MASK = LEVELS - 1
        private const val HISTOGRAM = LEVELS * LEVELS * LEVELS
        private const val UNSET: Short = -1

        /** 5-bit-per-channel histogram key for an ARGB pixel. */
        fun key15(argb: Int): Int =
            ((argb ushr 19 and MASK) shl 10) or
                ((argb ushr 11 and MASK) shl 5) or
                (argb ushr 3 and MASK)

        /** Build a palette covering [pixels] with at most [maxColors] entries. */
        fun from(pixels: IntArray, maxColors: Int = MAX_COLORS): GifPalette {
            require(maxColors in 2..MAX_COLORS) { "maxColors out of range" }
            val counts = IntArray(HISTOGRAM)
            var distinct = 0
            for (p in pixels) {
                val key = key15(p)
                if (counts[key] == 0) distinct++
                counts[key]++
            }
            val lookup = ShortArray(HISTOGRAM) { UNSET }
            if (distinct == 0) {
                // An empty frame still needs a legal (2-entry) table.
                return GifPalette(intArrayOf(0, 0), lookup)
            }

            val keys = IntArray(distinct)
            var at = 0
            for (key in 0 until HISTOGRAM) if (counts[key] != 0) keys[at++] = key

            val colors = medianCut(keys, counts, maxColors)
            return GifPalette(colors, lookup)
        }

        /**
         * Split the colour space into ≤ [maxColors] boxes, busiest first,
         * and average each box down to one palette entry.
         */
        private fun medianCut(keys: IntArray, counts: IntArray, maxColors: Int): IntArray {
            val starts = IntArray(maxColors)
            val ends = IntArray(maxColors)
            val weights = IntArray(maxColors)
            starts[0] = 0
            ends[0] = keys.size
            weights[0] = keys.sumOf { counts[it] }
            var boxes = 1

            while (boxes < maxColors) {
                // Busiest splittable box: spending entries where the pixels
                // are is the whole point of median cut.
                var target = -1
                var targetWeight = 0
                for (i in 0 until boxes) {
                    if (ends[i] - starts[i] > 1 && weights[i] > targetWeight) {
                        target = i
                        targetWeight = weights[i]
                    }
                }
                if (target < 0) break

                val start = starts[target]
                val end = ends[target]
                sortByLongestAxis(keys, start, end)
                // Median by PIXEL weight, not by bucket count: one bucket
                // holding half the frame must not be lumped in with the
                // long tail of near-empty ones.
                var half = weights[target] / 2
                var mid = start
                while (mid < end - 1) {
                    half -= counts[keys[mid]]
                    mid++
                    if (half <= 0) break
                }
                val leftWeight = (start until mid).sumOf { counts[keys[it]] }
                starts[boxes] = mid
                ends[boxes] = end
                weights[boxes] = weights[target] - leftWeight
                ends[target] = mid
                weights[target] = leftWeight
                boxes++
            }

            return IntArray(boxes) { average(keys, counts, starts[it], ends[it]) }
        }

        /** Order a box's buckets along whichever channel spans widest. */
        private fun sortByLongestAxis(keys: IntArray, start: Int, end: Int) {
            var minR = MASK; var maxR = 0
            var minG = MASK; var maxG = 0
            var minB = MASK; var maxB = 0
            for (i in start until end) {
                val key = keys[i]
                val r = key ushr 10 and MASK
                val g = key ushr 5 and MASK
                val b = key and MASK
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }
            val shift = when (maxOf(maxR - minR, maxG - minG, maxB - minB)) {
                maxR - minR -> 10
                maxG - minG -> 5
                else -> 0
            }
            // Sort on (channel, key) packed into a long: keys are already
            // ordered by (r,g,b), so this is a stable ordering on the axis
            // without a comparator (and without boxing 32k Integers).
            val slice = LongArray(end - start) {
                val key = keys[start + it]
                ((key ushr shift and MASK).toLong() shl 32) or key.toLong()
            }
            slice.sort()
            for (i in slice.indices) keys[start + i] = (slice[i] and 0xFFFFFFFFL).toInt()
        }

        /** Pixel-weighted mean colour of a box, as packed 0xRRGGBB. */
        private fun average(keys: IntArray, counts: IntArray, start: Int, end: Int): Int {
            var total = 0L
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in start until end) {
                val key = keys[i]
                val n = counts[key].toLong()
                total += n
                r += (key ushr 10 and MASK) * n
                g += (key ushr 5 and MASK) * n
                b += (key and MASK) * n
            }
            if (total == 0L) return 0
            return (expand((r / total).toInt()) shl 16) or
                (expand((g / total).toInt()) shl 8) or
                expand((b / total).toInt())
        }

        /** 5-bit level → 8-bit channel, spanning the full 0..255 range. */
        private fun expand(level: Int): Int = (level shl 3) or (level ushr 2)
    }
}
