package ch.lkmc.goo.ui.components

/**
 * Detent logic for [CandyLever], kept as pure math so it's unit-testable
 * without Compose. Levers live in [-1, 1] with a magnetic center: values
 * released inside the detent snap to exact 0 (identity), and dragging
 * across the center ticks the haptics like a real switch.
 */
object LeverDetent {

    /** Half-width of the magnetic zone around center. */
    const val SNAP = 0.06f

    /** Where a released thumb comes to rest. */
    fun settle(value: Float): Float =
        if (value > -SNAP && value < SNAP) 0f else value.coerceIn(-1f, 1f)

    /**
     * True when a drag from [previous] to [current] crossed or landed on
     * the center — the moment that deserves a haptic tick. Exact zero
     * counts once (crossing onto zero ticks; sitting at zero doesn't).
     */
    fun crossedCenter(previous: Float, current: Float): Boolean {
        if (previous == current) return false
        return (previous < 0f && current >= 0f) || (previous > 0f && current <= 0f)
    }

    /** Map a horizontal drag delta in pixels to lever units. */
    fun dragToDelta(dragPx: Float, trackWidthPx: Float): Float =
        if (trackWidthPx <= 0f) 0f else dragPx / (trackWidthPx / 2f)
}
