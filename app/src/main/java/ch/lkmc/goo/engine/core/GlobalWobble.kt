package ch.lkmc.goo.engine.core

import kotlinx.serialization.Serializable
import kotlin.math.sin

/**
 * One lever's modulation (proposal 0009).
 *
 * [rate] is in cycles per GOOvie loop and is an integer on purpose: a
 * loop containing a whole number of cycles is seamless by construction,
 * so a wobbled movie loops without a stitch in both MP4 and GIF, with no
 * cross-fade and nothing to tune. [depth] is the excursion either side
 * of the position the user set the lever to.
 */
@Serializable
data class LeverWobble(
    val rate: Int = 0,
    val depth: Float = 0f,
) {
    /** A lever with no depth, or no rate, is simply still. */
    val isStill: Boolean get() = rate == 0 || depth == 0f

    fun sanitized(): LeverWobble = copy(
        rate = rate.coerceIn(0, MAX_RATE),
        depth = depth.coerceIn(0f, 1f),
    )

    companion object {
        /**
         * The highest cycle count the dial offers before the per-document
         * frequency cap is applied. Not itself a safety limit — see
         * [GlobalWobble.maxSafeRate], which is.
         */
        const val MAX_RATE = 8
    }
}

/**
 * The modulation rig: one [LeverWobble] per lever, in the same order as
 * [GlobalParams.toArray] — the `u_g[6]` contract, which is the order the
 * whole engine already indexes levers by.
 *
 * A `List` rather than an `IntArray`/`FloatArray` pair, deliberately: a
 * data class holding arrays gets `equals` by REFERENCE, so two identical
 * rigs would not compare equal. That is not academic — this is document
 * state, and "unsaved" in this app means the saved signature differs
 * from disk, so a rig that never equals itself would report unwritten
 * changes forever and defeat the autosave checkpoint policy.
 */
@Serializable
data class GlobalWobble(
    val levers: List<LeverWobble> = List(SIZE) { LeverWobble() },
) {
    val isStill: Boolean get() = levers.all { it.isStill }

    /** Padded/truncated to [SIZE] and clamped — a file is not a promise. */
    fun sanitized(): GlobalWobble = GlobalWobble(
        List(SIZE) { i -> levers.getOrNull(i)?.sanitized() ?: LeverWobble() },
    )

    /**
     * This rig with every rate clamped to what [loopSeconds] can carry
     * safely.
     *
     * Applied at the point a FILE is produced, not only where the dial is
     * drawn. The dial is computed for the document as it stands, but the
     * export speed control can shorten the loop by 4× afterwards — so
     * without this, a rig dialled at 1× would exceed the cap the moment
     * someone chose a faster export. Clamping here makes "no exported
     * file flashes above [MAX_HZ]" a property of the encoder rather than
     * of the user remembering to re-check a panel.
     */
    fun cappedFor(loopSeconds: Float): GlobalWobble {
        val ceiling = maxSafeRate(loopSeconds)
        if (levers.all { it.rate <= ceiling }) return this
        return GlobalWobble(levers.map { it.copy(rate = it.rate.coerceAtMost(ceiling)) })
    }

    /**
     * Flat pack for `SavedStateHandle`, which takes primitive arrays but
     * not lists of data classes: rate then depth, per lever.
     */
    fun pack(): FloatArray {
        val out = FloatArray(SIZE * 2)
        for (i in 0 until SIZE) {
            val lever = levers.getOrNull(i) ?: LeverWobble()
            out[i * 2] = lever.rate.toFloat()
            out[i * 2 + 1] = lever.depth
        }
        return out
    }

    fun with(index: Int, wobble: LeverWobble): GlobalWobble =
        if (index !in 0 until SIZE) {
            this
        } else {
            GlobalWobble(levers.toMutableList().also { it[index] = wobble.sanitized() })
        }

    companion object {
        /** One per lever, in [GlobalParams.toArray] order. */
        const val SIZE = 6

        /**
         * The flash-safety ceiling, in hertz: WCAG 2.3.1's "three flashes
         * or below threshold" line.
         *
         * This exists because cycles-per-loop hides a frequency, and the
         * frequency is the safety problem. A segment is
         * [GoovieTimeline.SECONDS_PER_SEGMENT] = 1.2 s, the shortest strip
         * that can move is two keyframes, and the export speed control
         * goes to 4× — so the shortest loop this app can produce is 0.3 s.
         * Eight cycles in 0.3 s is 26.7 Hz, the middle of the
         * photosensitive-seizure band, and it would be baked into a file
         * the author then shares with people who never chose it.
         *
         * So the cap is on the FREQUENCY, not on the cycle count, and it
         * is evaluated against the loop the document will actually
         * export. Shortening the strip or raising the export speed takes
         * the high rates away again.
         */
        const val MAX_HZ = 3f

        /**
         * The highest rate that stays under [MAX_HZ] for a loop of
         * [loopSeconds]. Zero when the document cannot loop at all
         * (fewer than two keyframes), because there is nothing for a
         * cycle count to be measured against.
         */
        fun maxSafeRate(loopSeconds: Float): Int {
            if (loopSeconds <= 0f) return 0
            return (MAX_HZ * loopSeconds).toInt().coerceIn(0, LeverWobble.MAX_RATE)
        }

        /**
         * The rates a document of this shape may offer, always including
         * 0 (still). Detented and integral; see [LeverWobble.rate].
         */
        fun ratesFor(loopSeconds: Float): List<Int> = (0..maxSafeRate(loopSeconds)).toList()

        /** Inverse of [pack]; null when the array is not a rig. */
        fun unpack(packed: FloatArray): GlobalWobble? {
            if (packed.size != SIZE * 2) return null
            return GlobalWobble(
                List(SIZE) { i ->
                    LeverWobble(packed[i * 2].toInt(), packed[i * 2 + 1])
                },
            ).sanitized()
        }

        /**
         * The loop a document of [keyframeCount] pins previews against.
         *
         * Floored at two pins: a strip too short to export is still a
         * document you can dial a wobble into and watch, and pinning the
         * preview to the shortest exportable loop keeps what you dial,
         * what you see, and what you get the same thing.
         */
        fun previewLoopSeconds(keyframeCount: Int): Float =
            MovieSpec.durationSeconds(keyframeCount.coerceAtLeast(2))
    }
}

/**
 * The levers as they stand at [phase] of the loop — the whole feature,
 * as one pure function.
 *
 * `phase` runs [0, 1) over one loop and is derived from the frame index
 * during export and from a preview clock in the editor. Never from a
 * wall clock in the document: the phase comes from the frame number, so
 * an export reproduces the preview exactly and two renders of the same
 * document are identical. Same discipline as [GlobalField]'s
 * integer-hash noise.
 *
 * The wobble is applied ON TOP of [base], which during a GOOvie export
 * is already the interpolation between two pins. A strip and a wobble
 * together therefore give authored motion with a shimmer on it, rather
 * than one overriding the other: the lever's set value is what the user
 * aimed at, and the wobble is what happens around it.
 */
fun leversAt(base: GlobalParams, wobble: GlobalWobble, phase: Float): GlobalParams {
    if (wobble.isStill) return base
    val values = base.toArray()
    for (i in values.indices) {
        val lever = wobble.levers.getOrNull(i) ?: continue
        if (lever.isStill) continue
        val swing = lever.depth * sin(TAU * lever.rate * phase)
        values[i] = (values[i] + swing).coerceIn(-1f, 1f)
    }
    // fromArray only rejects a wrong-sized pack; toArray always makes six.
    return GlobalParams.fromArray(values) ?: base
}
