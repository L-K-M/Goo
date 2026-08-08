package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.Lens
import ch.lkmc.goo.engine.core.LensType
import ch.lkmc.goo.engine.core.ViewTransform
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

/**
 * The Funhouse apparatus, drawn on the photo (proposal 0006).
 *
 * Each lens gets a slim chrome ring at its true radius, so what you drag
 * is what warps — the ring is the same circle the shader's window uses,
 * not an icon standing in for one. The selected lens gets a second inner
 * ring and a type mark, because the rack is meant to be read at a glance:
 * three rings on a face should say "bulge, pinch, swirl" without a tap.
 *
 * This overlay owns canvas input while Funhouse mode is open, exactly as
 * the crop overlay does. Painting and lens-fiddling are different verbs
 * and sharing a canvas between them would make both worse: every tap
 * would have to guess.
 */
@Composable
fun FunhouseOverlay(
    lenses: List<Lens>,
    selected: Int,
    imageWidth: Int,
    imageHeight: Int,
    view: ViewTransform,
    /** Tap on empty photo: place one here (no-op when the rack is full). */
    onPlace: (u: Float, v: Float) -> Unit,
    /** Tap on a lens. */
    onSelect: (Int) -> Unit,
    /** Tap on the already-selected lens: change what it does. */
    onCycle: (Int) -> Unit,
    onMove: (index: Int, u: Float, v: Float) -> Unit,
    /** Long-press: fling it away. */
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspect = imageWidth.toFloat() / imageHeight

    // Everything the gesture reads that can change WHILE a finger is
    // down has to arrive through rememberUpdatedState rather than
    // through a pointerInput key.
    //
    // As a key it would be a bug: the first touch of an unselected lens
    // calls onSelect, `selected` changes, Compose cancels and relaunches
    // the handler — and the gesture dies under a finger that is still
    // down. Every onMove would do the same to `lenses` mid-drag.
    //
    // As a plain capture it would be a different bug: pointerInput's
    // block runs once and freezes whatever it closed over, which is the
    // trap ChromeLever documents a few files away. Hence both halves.
    val currentLenses by rememberUpdatedState(lenses)
    val currentSelected by rememberUpdatedState(selected)
    val currentView by rememberUpdatedState(view)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight) {
                awaitEachGesture {
                    val fit = FitTransform(
                        viewWidth = size.width.toFloat(),
                        viewHeight = size.height.toFloat(),
                        imageWidth = imageWidth.toFloat(),
                        imageHeight = imageHeight.toFloat(),
                    )
                    fun toUv(x: Float, y: Float): Pair<Float, Float> {
                        val (vx, vy) = currentView.invert(x, y)
                        return fit.viewToUv(vx, vy)
                    }

                    val down = awaitFirstDown()
                    down.consume()
                    // Follow the finger that started this, not whichever
                    // change happens to be first in the event. The overlay
                    // owns canvas input so there is no pinch to collide
                    // with, but a stray second touch would otherwise hand
                    // the dragged lens to the wrong finger, or end the
                    // gesture on the wrong pointer's lift.
                    val pointer = down.id
                    val (u0, v0) = toUv(down.position.x, down.position.y)
                    val hit = hitTest(currentLenses, u0, v0, aspect)
                    // Snapshot the selection for the whole gesture. It has
                    // to be live to START one (the previous line's fix)
                    // and frozen to CLASSIFY one: onSelect below changes
                    // it while this coroutine is suspended awaiting the
                    // lift, so a live read at the end would find that
                    // every tap landed on "the already-selected lens" and
                    // cycle the type of the lens it had only just picked.
                    val selectedAtDown = currentSelected
                    // Grabbing an unselected lens selects it, so the first
                    // drag of a lens you were not holding still moves the
                    // one you touched rather than the one you last used.
                    if (hit != null && hit != selectedAtDown) onSelect(hit)

                    fun travelled(u: Float, v: Float): Float =
                        hypot((u - u0) * aspect, v - v0)

                    // Where inside the ring the finger landed. Without
                    // this the lens centre snaps to the touch point on the
                    // first move past slop — imperceptible on a small
                    // lens, a jump of most of a radius on a large one.
                    val grabbed = hit?.let { currentLenses.getOrNull(it) }
                    val grabU = grabbed?.let { u0 - it.u } ?: 0f
                    val grabV = grabbed?.let { v0 - it.v } ?: 0f

                    var moved = false
                    var lifted = false
                    var removed = false

                    // The long press has to race a CLOCK, not the pointer
                    // stream. A finger held perfectly still delivers no
                    // move events on most devices, so a deadline checked
                    // only when an event arrives would fire for people
                    // whose hands shake and never for anyone else.
                    if (hit != null) {
                        val settled = withTimeoutOrNull(LONG_PRESS_MS) {
                            while (true) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == pointer }
                                    ?: continue
                                if (!change.pressed) {
                                    change.consume()
                                    return@withTimeoutOrNull Settle.LIFTED
                                }
                                val (u, v) = toUv(change.position.x, change.position.y)
                                change.consume()
                                if (travelled(u, v) > DRAG_SLOP) {
                                    onMove(hit, u - grabU, v - grabV)
                                    return@withTimeoutOrNull Settle.MOVED
                                }
                            }
                            // Unreachable: the loop only leaves by
                            // returning. Kept because Kotlin infers a
                            // lambda's type from its final EXPRESSION and
                            // does not treat `while (true)` as Nothing —
                            // an explicit type argument on
                            // withTimeoutOrNull does not help either, it
                            // just moves the same mismatch to the call.
                            @Suppress("UNREACHABLE_CODE")
                            Settle.LIFTED
                        }
                        when (settled) {
                            // The clock won: still down, still where it
                            // started. Throw the lens away now rather than
                            // on the lift, which is what makes it feel
                            // like a press and not a slow tap.
                            null -> {
                                removed = true
                                onRemove(hit)
                            }
                            Settle.MOVED -> moved = true
                            Settle.LIFTED -> lifted = true
                        }
                    }

                    // Past the long-press window: an ordinary drag. Keep
                    // draining events even after a removal — the finger is
                    // still down, and anything left unconsumed reaches the
                    // pan/zoom handler underneath as a stray gesture.
                    while (!lifted) {
                        val change = awaitPointerEvent().changes
                            .firstOrNull { it.id == pointer }
                            ?: continue
                        change.consume()
                        if (!change.pressed) break
                        if (removed) continue
                        val (u, v) = toUv(change.position.x, change.position.y)
                        if (travelled(u, v) > DRAG_SLOP) moved = true
                        if (moved && hit != null) onMove(hit, u - grabU, v - grabV)
                    }

                    if (!moved && !removed) {
                        when (hit) {
                            // A tap on the lens you are already holding is
                            // the "what else can you be" verb.
                            selectedAtDown -> onCycle(selectedAtDown)
                            null -> onPlace(u0, v0)
                            else -> Unit // selection already happened
                        }
                    }
                }
            },
    ) {
        val fit = FitTransform(
            viewWidth = size.width,
            viewHeight = size.height,
            imageWidth = imageWidth.toFloat(),
            imageHeight = imageHeight.toFloat(),
        )
        lenses.forEachIndexed { index, lens ->
            val (fx, fy) = fit.uvToView(lens.u, lens.v)
            val (x, y) = view.apply(fx, fy)
            // Aspect-space radius is a fraction of image height, so the
            // pixel radius rides the fit AND the zoom — the ring covers
            // exactly the pixels the lens warps, at any zoom.
            val radiusPx = lens.radius * fit.fittedHeight * view.scale
            drawLens(
                center = Offset(x, y),
                radiusPx = radiusPx,
                type = lens.type,
                strength = lens.strength,
                selected = index == selected,
            )
        }
    }
}

/** How a touch on a lens resolved before the long-press deadline. */
private enum class Settle { MOVED, LIFTED }

/** Nearest center wins, so overlapping lenses grab predictably. */
private fun hitTest(lenses: List<Lens>, u: Float, v: Float, aspect: Float): Int? {
    var best = -1
    var bestDistance = Float.MAX_VALUE
    lenses.forEachIndexed { i, lens ->
        val dx = (u - lens.u) * aspect
        val dy = v - lens.v
        val d = hypot(dx, dy)
        if (d <= lens.radius && d < bestDistance) {
            best = i
            bestDistance = d
        }
    }
    return best.takeIf { it >= 0 }
}

private fun DrawScope.drawLens(
    center: Offset,
    radiusPx: Float,
    type: LensType,
    strength: Float,
    selected: Boolean,
) {
    if (radiusPx <= 0f) return
    // Dark backing under every line: chrome on a bright photo is
    // invisible without it.
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = radiusPx,
        center = center,
        style = Stroke(width = 3.5.dp.toPx()),
    )
    drawCircle(
        color = Color.White.copy(alpha = if (selected) 0.95f else 0.6f),
        radius = radiusPx,
        center = center,
        style = Stroke(width = if (selected) 2.dp.toPx() else 1.2.dp.toPx()),
    )
    if (selected) {
        // A dashed inner ring reads as "this one is in hand" without
        // another colour to learn.
        drawCircle(
            color = Color.White.copy(alpha = 0.5f),
            radius = radiusPx * 0.78f,
            center = center,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                ),
            ),
        )
    }
    // The type mark: a small figure at the center saying what this piece
    // of glass does. Negative strength runs the effect backwards, so the
    // mark is drawn for what the lens is ACTUALLY doing right now.
    val mark = radiusPx * 0.22f
    val inward = when (type) {
        LensType.PINCH -> strength >= 0f
        LensType.BULGE, LensType.FISHEYE -> strength < 0f
        LensType.VORTEX -> false
    }
    val ink = Color.White.copy(alpha = 0.9f)
    val width = 2.dp.toPx()
    when (type) {
        LensType.VORTEX -> {
            // Two opposed strokes — straight, not arcs: at ring sizes
            // this reads as a turning mark, and the sign mirrors it so a
            // counter-wound lens reads counter-wound.
            val turn = if (strength >= 0f) 1f else -1f
            for (side in listOf(1f, -1f)) {
                drawLine(
                    color = ink,
                    start = Offset(center.x + side * mark, center.y),
                    end = Offset(center.x + side * mark * 0.2f, center.y + turn * side * mark),
                    strokeWidth = width,
                )
            }
        }

        else -> {
            // Four arrows in or out — magnify or shrink, at a glance.
            //
            // With heads, because a bare segment has no direction: a line
            // from far to near draws exactly the pixels a line from near
            // to far does, so reversing the endpoints — which is what
            // this used to do — left bulge and pinch identical on screen.
            val head = mark * 0.3f
            for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                val near = Offset(center.x + dx * mark * 0.35f, center.y + dy * mark * 0.35f)
                val far = Offset(center.x + dx * mark, center.y + dy * mark)
                val tip = if (inward) near else far
                drawLine(color = ink, start = near, end = far, strokeWidth = width)
                // Barbs sit BEHIND the tip, along the arrow's own axis,
                // and splay across it — so the V opens away from travel.
                val backX = tip.x - if (inward) -dx * head else dx * head
                val backY = tip.y - if (inward) -dy * head else dy * head
                val splay = head * 0.55f
                drawLine(
                    color = ink,
                    start = tip,
                    end = Offset(backX - dy * splay, backY + dx * splay),
                    strokeWidth = width,
                )
                drawLine(
                    color = ink,
                    start = tip,
                    end = Offset(backX + dy * splay, backY - dx * splay),
                    strokeWidth = width,
                )
            }
            if (type == LensType.FISHEYE) {
                // The flat core, drawn as the core: a filled dot where a
                // fisheye's magnification stops ramping.
                drawCircle(
                    color = ink,
                    radius = mark * 0.28f,
                    center = center,
                )
            }
        }
    }
}

/** Aspect-space travel past which a touch is a drag, not a tap. */
private const val DRAG_SLOP = 0.012f

/** Hold this long on a lens to throw it away. */
private const val LONG_PRESS_MS = 450L
