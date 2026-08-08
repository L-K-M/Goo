package ch.lkmc.goo.engine.core

/**
 * One GOOvie keyframe (PLAN.md §4.1): a pin into the document, not a
 * bitmap — an immutable stroke revision plus the lever values at capture.
 * The GPU materializes fields on demand by replay; reordering keyframes
 * reorders playback, the pins stay put.
 *
 * **Each keyframe is its own thing.** It pins the exact [StrokeRevision]
 * it was punched from, so the editor's undo cursor can move anywhere
 * without disturbing it. That is the whole reason this is a revision and
 * not the prefix COUNT it originally was: counts index into
 * `StrokeLog.strokes`, which shrinks on undo, so undoing back toward the
 * original photo silently collapsed every keyframe along with it.
 * Punching the original photo as the closing frame — undo to it, punch,
 * redo your goo — was therefore impossible.
 *
 * The revision is a pointer, not a copy: revisions are structurally
 * shared nodes, so 64 keyframes cost 64 references. A revision the log's
 * history has since truncated (the redo branch after a new stroke) stays
 * alive and replayable here, which is exactly what independence means —
 * and its [StrokeRevisionId] is never reused, so renderer caches keyed by
 * it can never show a stale field for a fresh pin.
 *
 * A pin is still a bookmark, not a canvas: there is no "editing
 * keyframe 2" in place. You goo the photo until it looks right, then
 * re-punch the keyframe that should hold it
 * (EditorViewModel.repunchSelectedKeyframe).
 */
data class Keyframe(
    /** The immutable document revision this pin replays. */
    val revision: StrokeRevision,
    val globals: GlobalParams,
    /**
     * How the segment LEAVING this pin moves (proposal 0011). Belongs to
     * the keyframe rather than to a separate segment list because a
     * segment has no identity of its own — reordering the strip reorders
     * the pins, and the curve should travel with the pose it leaves.
     * The last keyframe's easing is unused, since nothing leaves it.
     */
    val easing: Easing = Easing.DEFAULT,
) {
    val revisionId: StrokeRevisionId
        get() = revision.id
}

/**
 * What the strip should say out loud under the beads.
 *
 * A GOOvie only moves when consecutive keyframes pin DIFFERENT document
 * states, and a pin is taken at punch time — the two facts every
 * first-time user has to discover, and the two that make "I punched two
 * keyframes and nothing happens" the classic first bug report. The strip
 * names the current situation instead of leaving them to guess.
 */
enum class GoovieHint {
    /** No keyframes yet. */
    EMPTY,

    /** Gooing live with a keyframe selected: Punch adds, Update re-pins. */
    LIVE_PINNED,

    /** Gooing live with nothing selected: Punch pins what you see. */
    LIVE,

    /** One keyframe — a strip needs two to tween. */
    NEEDS_SECOND,

    /** Two or more, all pinning the same state: a movie that can't move. */
    ALL_SAME,

    /** The selected pin is behind the live document. */
    STALE,

    /** Nothing to say; scrub or play. */
    READY,
}

/**
 * Pick the strip's nudge. Pure so the wording logic is unit-testable and
 * stays out of the composable (AGENTS.md).
 */
fun goovieHint(
    keyframes: List<Keyframe>,
    selected: Int,
    selectedStale: Boolean,
    live: Boolean,
): GoovieHint = when {
    keyframes.isEmpty() -> GoovieHint.EMPTY
    live && selected in keyframes.indices -> GoovieHint.LIVE_PINNED
    live -> GoovieHint.LIVE
    keyframes.size == 1 -> GoovieHint.NEEDS_SECOND
    // The user's punch-punch-nothing-moves case: identical pins tween to
    // a still frame. Say so rather than exporting a dead loop.
    keyframes.all { it == keyframes[0] } -> GoovieHint.ALL_SAME
    selectedStale -> GoovieHint.STALE
    else -> GoovieHint.READY
}

/**
 * Pure timeline math for scrubbing and playback over a keyframe list.
 * Position `p` is continuous in [0, size-1]: integer values sit on
 * keyframes, fractions tween between neighbors.
 */
object GoovieTimeline {

    /** Seconds per segment during playback — KPT-ish steady amble. */
    const val SECONDS_PER_SEGMENT = 1.2f

    /** Segment start index for position [p] in a [size]-keyframe strip. */
    fun segment(p: Float, size: Int): Int =
        if (size < 2) 0 else p.toInt().coerceIn(0, size - 2)

    /** Tween fraction within [p]'s segment. */
    fun fraction(p: Float, size: Int): Float {
        if (size < 2) return 0f
        val s = segment(p, size)
        return (p - s).coerceIn(0f, 1f)
    }

    /** Clamp a position to the strip. */
    fun clamp(p: Float, size: Int): Float =
        if (size == 0) 0f else p.coerceIn(0f, (size - 1).toFloat())

    /**
     * Advance playback by [dtSeconds], looping to the start past the end.
     * A strip needs two keyframes to move; otherwise stays at 0.
     */
    fun advance(p: Float, dtSeconds: Float, size: Int): Float {
        if (size < 2) return 0f
        val span = (size - 1).toFloat()
        val next = p + dtSeconds / SECONDS_PER_SEGMENT
        // mod, not a single subtraction: the frame clock stops while the
        // app is backgrounded, so a resume can deliver a dt spanning many
        // loops — one subtraction would leave p far past the end and the
        // playback pinned there for hundreds of frames.
        return if (next >= span) next.mod(span) else next
    }

    // No clampCount here any more: a keyframe pins its own immutable
    // revision, so there is nothing left to clamp against a log that has
    // since moved. Clamping was the mechanism by which undo flattened a
    // whole strip.
}

/** Linear lever interpolation for tween scrubbing. */
fun GlobalParams.lerp(other: GlobalParams, t: Float): GlobalParams = GlobalParams(
    bulge = bulge + (other.bulge - bulge) * t,
    twirl = twirl + (other.twirl - twirl) * t,
    squeeze = squeeze + (other.squeeze - squeeze) * t,
    stretch = stretch + (other.stretch - stretch) * t,
    spike = spike + (other.spike - spike) * t,
    static = static + (other.static - static) * t,
    lenses = lerpLenses(lenses, other.lenses, t),
)

/**
 * Interpolate two lens racks by slot — where the traveling bulge comes
 * from: a lens that sits on the nose in one pin and on the ear in the
 * next crosses the face over the segment, an animation no brush can
 * record because strokes are instant and lenses are positions.
 *
 * A slot present on only one side fades through its own strength rather
 * than popping, so adding or removing a lens between pins is a dissolve.
 * Type cannot be interpolated — there is no half-way between a pinch and
 * a swirl — so it switches at the midpoint. That is invisible for a slot
 * appearing or disappearing, which is passing through zero strength
 * there anyway, and it is NOT invisible when both pins carry a lens in
 * the same slot at similar strength: that case pops, once, at the middle
 * of the segment. Hiding it would take rendering both types through a
 * blend window, which costs a second lens slot for the duration; the pop
 * is the cheaper honest answer until someone asks for the other one.
 */
private fun lerpLenses(a: List<Lens>, b: List<Lens>, t: Float): List<Lens> {
    if (a.isEmpty() && b.isEmpty()) return emptyList()
    return (0 until maxOf(a.size, b.size)).mapNotNull { i ->
        val from = a.getOrNull(i)
        val to = b.getOrNull(i)
        when {
            from != null && to != null -> Lens(
                u = from.u + (to.u - from.u) * t,
                v = from.v + (to.v - from.v) * t,
                radius = from.radius + (to.radius - from.radius) * t,
                type = if (t < 0.5f) from.type else to.type,
                strength = from.strength + (to.strength - from.strength) * t,
            )
            // Fading out: hold the position, run the strength to zero.
            from != null -> from.copy(strength = from.strength * (1f - t))
            to != null -> to.copy(strength = to.strength * t)
            else -> null
        }
    }
}
