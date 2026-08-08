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
import kotlin.math.sqrt

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

                    fun travelled(u: Float, v: Float): Float = sqrt(
                        ((u - u0) * aspect) * ((u - u0) * aspect) + (v - v0) * (v - v0),
                    )

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
                                val change = awaitPointerEvent().changes.first()
                                if (!change.pressed) {
                                    change.consume()
                                    return@withTimeoutOrNull Settle.LIFTED
                                }
                                val (u, v) = toUv(change.position.x, change.position.y)
                                change.consume()
                                if (travelled(u, v) > DRAG_SLOP) {
                                    onMove(hit, u, v)
                                    return@withTimeoutOrNull Settle.MOVED
                                }
                            }
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
                        val change = awaitPointerEvent().changes.first()
                        change.consume()
                        if (!change.pressed) break
                        if (removed) continue
                        val (u, v) = toUv(change.position.x, change.position.y)
                        if (travelled(u, v) > DRAG_SLOP) moved = true
                        if (moved && hit != null) onMove(hit, u, v)
                    }

                    if (!moved && !removed) {
                        when (hit) {
                            // A tap on the lens you are already holding is
                            // the "what else can you be" verb.
                            selectedAtDown -> if (hit != null) onCycle(hit)
                            null -> onPlace(u0, v0)
                            else -> Unit // selection already happened
                        }
                    }
                }
            },
    ) {
        val fit = FitTransform(
            size.width, size.height,
            imageWidth.toFloat(), imageHeight.toFloat(),
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
        val d = sqrt(dx * dx + dy * dy)
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
            // Two opposed arcs: a turning mark, mirrored by the sign so
            // a counter-wound lens reads counter-wound.
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
            for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                val near = Offset(center.x + dx * mark * 0.35f, center.y + dy * mark * 0.35f)
                val far = Offset(center.x + dx * mark, center.y + dy * mark)
                val (start, end) = if (inward) far to near else near to far
                drawLine(color = ink, start = start, end = end, strokeWidth = width)
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
