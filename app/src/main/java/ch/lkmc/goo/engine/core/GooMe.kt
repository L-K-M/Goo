package ch.lkmc.goo.engine.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * One dealt batch of goo (proposal 0007): ordinary strokes plus where the
 * levers end up.
 *
 * Nothing here is special-cased downstream. The strokes are the same
 * `Stroke`s a finger produces — same tools, same resampler, same pump
 * schedule — so a deal logs, undoes, replays at export resolution,
 * persists, and tweens in a GOOvie with no code that knows it was dealt.
 * That is the point of the button as much as the joke is: every deal is
 * a worked example of the palette, made of things the user could have
 * painted.
 */
data class Deal(
    val strokes: List<Stroke>,
    val globals: GlobalParams,
)

/**
 * The slot machine behind the Goo Me dome.
 *
 * A deal is drawn from a small hand-authored deck rather than sampled
 * uniformly over tools and parameters: the taste *is* the feature. Random
 * picks over the whole palette land on "everything smeared slightly" far
 * more often than on anything funny, because the funny region is narrow
 * and correlated — a bulge on each eye is a joke, a bulge in each of two
 * random places is a smudge.
 *
 * Determinism is by seed, and the seed is what a caller should record if
 * it wants the same accident back: re-running the generator with a fresh
 * seed produces a different face, so "reload the project and get the deal
 * I fell in love with" is a property of the *recorded strokes*, not of
 * this object. (Strokes are recorded, so that holds automatically. The
 * seed only needs keeping if a deal should be re-derivable.)
 */
object GooMe {

    /**
     * The subject's rough position: recipes place their moves relative to
     * this, not to the frame. Slightly above center because that is where
     * heads sit in photographs of people.
     */
    const val FACE_U = 0.5f
    const val FACE_V = 0.44f

    /** How far a jittered landmark can wander, in aspect-space units. */
    const val JITTER = 0.025f

    /**
     * Landmarks are laid out in *aspect space* (fractions of image
     * height, x scaled by aspect) so the arrangement stays a face-shaped
     * arrangement on a portrait, a square and a panorama alike. In plain
     * UV, the eyes of a 16:9 photo would drift onto the ears.
     */
    enum class Landmark(val ax: Float, val ay: Float) {
        BROW(0.00f, -0.17f),
        EYE_L(-0.10f, -0.06f),
        EYE_R(0.10f, -0.06f),
        NOSE(0.00f, 0.04f),
        MOUTH(0.00f, 0.16f),
        CHIN(0.00f, 0.28f),
        CHEEK_L(-0.16f, 0.10f),
        CHEEK_R(0.16f, 0.10f),
    }

    /** Which lever a recipe pulls. */
    enum class Lever { BULGE, TWIRL, SQUEEZE, STRETCH, SPIKE, STATIC }

    /** How many recipes the deck holds; the UI may want to say "1 of N". */
    val deckSize: Int get() = DECK.size

    /**
     * Deal recipe [seed] onto an image of the given [aspect], starting
     * from the levers currently pulled ([from] — a recipe only sets the
     * one lever it names, so the rest of the table survives a deal).
     */
    fun deal(seed: Long, aspect: Float, from: GlobalParams = GlobalParams()): Deal {
        require(aspect > 0f) { "aspect must be positive: $aspect" }
        val dice = Dice(seed)
        val recipe = DECK[dice.pick(DECK.size)]
        val strokes = recipe.moves.mapNotNull { move -> strokeFor(move, aspect, dice) }
        val globals = recipe.lever?.let { pull ->
            val magnitude = dice.inRange(pull.value)
            from.with(pull.lever, if (pull.eitherWay) magnitude * dice.sign() else magnitude)
        } ?: from
        return Deal(strokes = strokes, globals = globals)
    }

    // ---- Turning one move into one stroke -------------------------------

    /** Null when the gesture produced no stamps (a drag too short to bite). */
    private fun strokeFor(move: Move, aspect: Float, dice: Dice): Stroke? {
        val radius = dice.inRange(move.radius)
        val strength = dice.inRange(move.strength) * move.tool.strengthScale
        val (u, v) = place(move.at, aspect, dice)
        val stamps = if (move.hold.last > 0) {
            holdStamps(move.tool, u, v, dice.pickIn(move.hold))
        } else {
            dragStamps(
                tool = move.tool,
                startU = u,
                startV = v,
                heading = dice.inRange(move.heading).toRadians(),
                length = dice.inRange(move.length),
                sweep = dice.inRange(move.sweep).toRadians(),
                radius = radius,
                aspect = aspect,
            )
        }
        if (stamps.isEmpty()) return null
        return Stroke(tool = move.tool, radius = radius, strength = strength, stamps = stamps)
    }

    /** A landmark's UV, jittered, kept clear of the frame edge. */
    private fun place(at: Landmark, aspect: Float, dice: Dice): Pair<Float, Float> {
        val ax = at.ax + dice.signed() * JITTER
        val ay = at.ay + dice.signed() * JITTER
        return Pair(
            (FACE_U + ax / aspect).coerceIn(EDGE, 1f - EDGE),
            (FACE_V + ay).coerceIn(EDGE, 1f - EDGE),
        )
    }

    /**
     * A held finger, straight through [PumpStamps] — which is what makes
     * a dealt Melt accelerate exactly like a painted one instead of
     * approximating the ramp with a second copy of the schedule.
     */
    private fun holdStamps(tool: BrushTool, u: Float, v: Float, ticks: Int): List<Stamp> =
        (1..ticks).map { tick -> PumpStamps.at(tool, u, v, tick) }

    /**
     * A dragged finger, straight through [StrokeResampler] — same reason:
     * the stamp spacing and the per-stamp delta of a dealt smear are
     * produced by the code that produces them for a real one, so a deal
     * cannot drift away from what the palette actually does.
     *
     * The path is walked in aspect space with the heading turning
     * linearly over [sweep], so a recipe asking for a curve gets an arc
     * of even curvature rather than a polyline with a corner in it.
     * Points are clamped inside the frame, which is no more than a real
     * finger reaching the edge of the screen does.
     */
    private fun dragStamps(
        tool: BrushTool,
        startU: Float,
        startV: Float,
        heading: Float,
        length: Float,
        sweep: Float,
        radius: Float,
        aspect: Float,
    ): List<Stamp> {
        val resampler = StrokeResampler(radius = radius, aspect = aspect)
        resampler.begin(startU, startV)
        val out = mutableListOf<Stamp>()
        if (tool.stampsOnDown) out.add(PumpStamps.at(tool, startU, startV, tick = 1))
        var ax = startU * aspect
        var ay = startV
        val step = length / PATH_STEPS
        for (i in 1..PATH_STEPS) {
            val turn = heading + sweep * ((i - 0.5f) / PATH_STEPS - 0.5f)
            ax += cos(turn) * step
            ay += sin(turn) * step
            val u = (ax / aspect).coerceIn(EDGE, 1f - EDGE)
            val v = ay.coerceIn(EDGE, 1f - EDGE)
            resampler.extend(u, v, out)
        }
        return out
    }

    private fun Float.toRadians(): Float = this * (kotlin.math.PI.toFloat() / 180f)

    /** How far a dealt drag stays from the frame edge, in UV. */
    private const val EDGE = 0.03f

    /**
     * Segments a dealt drag is walked in. High enough that an arc reads
     * as a curve and not as a fan of chords; the resampler decides how
     * many stamps actually land.
     */
    private const val PATH_STEPS = 32

    // ---- The deck --------------------------------------------------------

    private class Move(
        val tool: BrushTool,
        val at: Landmark,
        val radius: ClosedFloatingPointRange<Float>,
        val strength: ClosedFloatingPointRange<Float>,
        /** Ticks to hold, for tools that pump. Zero means "drag instead". */
        val hold: IntRange = 0..0,
        /** Drag heading, degrees; 0 = right, 90 = down (UV is y-down). */
        val heading: ClosedFloatingPointRange<Float> = 0f..0f,
        /** Drag length in aspect-space units (fractions of image height). */
        val length: ClosedFloatingPointRange<Float> = 0f..0f,
        /**
         * Curvature over the whole drag, degrees — which way it bends is
         * part of the joke, so the sign lives in the range and is never
         * diced.
         */
        val sweep: ClosedFloatingPointRange<Float> = 0f..0f,
    )

    private class LeverPull(
        val lever: Lever,
        /** Magnitude band; direction comes from the sign of these bounds. */
        val value: ClosedFloatingPointRange<Float>,
        /**
         * True for levers where both directions are equally funny (a
         * twirl turns either way). False keeps the authored direction: a
         * wind tunnel that squashed instead of stretching would be a
         * different recipe wearing this one's name.
         */
        val eitherWay: Boolean = false,
    )

    private class Recipe(val moves: List<Move>, val lever: LeverPull? = null)

    /**
     * Ten hand-tuned combos. Strength bands sit on the funny side of
     * horrifying deliberately: a deal the user immediately undoes teaches
     * nothing, and one they can't tell happened is worse.
     */
    private val DECK = listOf(
        // Bug eyes — the joke everyone tries first, done properly.
        Recipe(
            listOf(
                Move(BrushTool.GROW, Landmark.EYE_L, 0.07f..0.10f, 0.6f..0.9f, hold = 22..34),
                Move(BrushTool.GROW, Landmark.EYE_R, 0.07f..0.10f, 0.6f..0.9f, hold = 22..34),
                Move(
                    BrushTool.NUDGE, Landmark.MOUTH, 0.10f..0.14f, 0.7f..1f,
                    heading = 250f..290f, length = 0.10f..0.16f,
                ),
            ),
        ),
        // Wind tunnel — both cheeks blown outward, and the frame stretched.
        Recipe(
            listOf(
                Move(
                    BrushTool.SMEAR, Landmark.CHEEK_L, 0.10f..0.15f, 0.5f..0.8f,
                    heading = 160f..200f, length = 0.16f..0.26f, sweep = 10f..40f,
                ),
                Move(
                    BrushTool.SMEAR, Landmark.CHEEK_R, 0.10f..0.15f, 0.5f..0.8f,
                    heading = -20f..20f, length = 0.16f..0.26f, sweep = 10f..40f,
                ),
            ),
            LeverPull(Lever.STRETCH, 0.25f..0.5f),
        ),
        // Whirlpool cheek — one swirl, one bulge, a whisper of twirl.
        Recipe(
            listOf(
                Move(BrushTool.VORTEX, Landmark.CHEEK_L, 0.10f..0.15f, 0.6f..0.9f, hold = 26..40),
                Move(BrushTool.GROW, Landmark.NOSE, 0.08f..0.12f, 0.5f..0.7f, hold = 14..22),
            ),
            LeverPull(Lever.TWIRL, 0.12f..0.28f, eitherWay = true),
        ),
        // Candle — wax off the chin and the mouth, smoothed at the brow.
        Recipe(
            listOf(
                Move(BrushTool.MELT, Landmark.CHIN, 0.10f..0.16f, 0.7f..1f, hold = 45..75),
                Move(BrushTool.MELT, Landmark.MOUTH, 0.08f..0.12f, 0.6f..0.9f, hold = 30..55),
                Move(BrushTool.SMOOTH, Landmark.BROW, 0.10f..0.14f, 0.5f..0.8f, hold = 5..9),
            ),
        ),
        // Pinhead — small skull, big jaw, squeezed frame.
        Recipe(
            listOf(
                Move(BrushTool.SHRINK, Landmark.BROW, 0.12f..0.18f, 0.6f..0.9f, hold = 24..38),
                Move(BrushTool.GROW, Landmark.CHIN, 0.10f..0.15f, 0.5f..0.8f, hold = 18..30),
            ),
            LeverPull(Lever.SQUEEZE, 0.2f..0.4f),
        ),
        // Fright wig — strands combed up off the brow, and a spiky frame.
        Recipe(
            listOf(
                Move(
                    BrushTool.COMB, Landmark.BROW, 0.08f..0.12f, 0.7f..1f,
                    heading = 250f..270f, length = 0.14f..0.22f, sweep = 0f..25f,
                ),
                Move(
                    BrushTool.COMB, Landmark.BROW, 0.08f..0.12f, 0.7f..1f,
                    heading = 270f..290f, length = 0.14f..0.22f, sweep = 0f..25f,
                ),
            ),
            LeverPull(Lever.SPIKE, 0.3f..0.6f),
        ),
        // Pond face — two taps and their rings, over a gentle bulge.
        Recipe(
            listOf(
                Move(BrushTool.POND, Landmark.NOSE, 0.12f..0.18f, 0.6f..0.9f, hold = 1..1),
                Move(BrushTool.POND, Landmark.CHEEK_R, 0.09f..0.14f, 0.5f..0.8f, hold = 1..1),
            ),
            LeverPull(Lever.BULGE, 0.15f..0.3f),
        ),
        // Fault line — a seam across the mouth, then a smudge to sell it.
        Recipe(
            listOf(
                Move(
                    BrushTool.FAULT, Landmark.MOUTH, 0.08f..0.12f, 0.7f..1f,
                    heading = -15f..15f, length = 0.24f..0.36f,
                ),
                Move(
                    BrushTool.SMUDGE, Landmark.CHIN, 0.10f..0.16f, 0.6f..0.9f,
                    heading = 60f..120f, length = 0.08f..0.14f,
                ),
            ),
        ),
        // Grin — both mouth corners hauled up an arc, then inflated.
        Recipe(
            listOf(
                Move(
                    BrushTool.MOVE, Landmark.CHEEK_L, 0.08f..0.12f, 0.5f..0.8f,
                    heading = 200f..240f, length = 0.10f..0.16f, sweep = 20f..50f,
                ),
                Move(
                    BrushTool.MOVE, Landmark.CHEEK_R, 0.08f..0.12f, 0.5f..0.8f,
                    heading = -60f..-20f, length = 0.10f..0.16f, sweep = 20f..50f,
                ),
                Move(BrushTool.GROW, Landmark.MOUTH, 0.09f..0.13f, 0.4f..0.7f, hold = 12..20),
            ),
        ),
        // Cyclone — counter-rotating swirls, and the whole frame turning.
        Recipe(
            listOf(
                Move(BrushTool.UNWIND, Landmark.NOSE, 0.12f..0.18f, 0.7f..1f, hold = 30..46),
                Move(BrushTool.VORTEX, Landmark.BROW, 0.09f..0.14f, 0.5f..0.8f, hold = 20..32),
            ),
            LeverPull(Lever.TWIRL, 0.2f..0.4f, eitherWay = true),
        ),
    )

    /**
     * Deterministic xorshift64. Deliberately not `java.util.Random`: a
     * dealt batch is baked into the document, so the generator that made
     * it has to be a fixed, readable definition rather than whatever the
     * platform's RNG does this release.
     */
    private class Dice(seed: Long) {
        /**
         * The seed is scrambled before it becomes state, and that is not
         * decoration. Xorshift64 only ever mixes bits *upward* by
         * shifting and xoring; a small seed therefore has almost nothing
         * in its top bits after one step, and [next] reads exactly those
         * top bits. Raw sequential-ish seeds all landed on recipe 0 —
         * a Goo Me button that always dealt the same joke.
         */
        private var state: Long = scramble(seed).let { if (it == 0L) GOLDEN else it }

        private fun bits(): Long {
            var x = state
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            state = x
            return x
        }

        /** Uniform in [0, 1). */
        fun next(): Float = (bits() ushr 40).toFloat() / (1 shl 24).toFloat()

        /** Uniform in [-1, 1). */
        fun signed(): Float = next() * 2f - 1f

        /** −1 or +1. */
        fun sign(): Float = if (next() < 0.5f) -1f else 1f

        fun pick(count: Int): Int = (next() * count).toInt().coerceIn(0, count - 1)

        fun pickIn(range: IntRange): Int =
            range.first + pick(range.last - range.first + 1)

        fun inRange(range: ClosedFloatingPointRange<Float>): Float =
            range.start + next() * (range.endInclusive - range.start)

        private companion object {
            /** 2⁶⁴/φ — any nonzero constant works; xorshift dies on zero. */
            val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()

            /** SplitMix64's finalizer: avalanches every input bit. */
            fun scramble(seed: Long): Long {
                var z = seed + GOLDEN
                z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
                z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
                return z xor (z ushr 31)
            }
        }
    }
}

/** [lever] set to [value], leaving every other lever alone. */
fun GlobalParams.with(lever: GooMe.Lever, value: Float): GlobalParams = when (lever) {
    GooMe.Lever.BULGE -> copy(bulge = value.coerceIn(-1f, 1f))
    GooMe.Lever.TWIRL -> copy(twirl = value.coerceIn(-1f, 1f))
    GooMe.Lever.SQUEEZE -> copy(squeeze = value.coerceIn(-1f, 1f))
    GooMe.Lever.STRETCH -> copy(stretch = value.coerceIn(-1f, 1f))
    GooMe.Lever.SPIKE -> copy(spike = value.coerceIn(-1f, 1f))
    GooMe.Lever.STATIC -> copy(static = value.coerceIn(-1f, 1f))
}
