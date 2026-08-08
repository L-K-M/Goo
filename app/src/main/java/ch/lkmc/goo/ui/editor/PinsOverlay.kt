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
import ch.lkmc.goo.engine.core.MlsControl
import ch.lkmc.goo.engine.core.PinPull
import ch.lkmc.goo.engine.core.ViewTransform
import ch.lkmc.goo.ui.theme.NeonMagenta
import kotlin.math.hypot

/**
 * Taffy Pins on the photo (proposal 0016).
 *
 * Silver hold pins mark what must not move; the magenta puck is the one
 * thing being dragged. That colour split is the whole explanation of the
 * tool, so it is worth more than any label: one kind of mark says
 * "stay", the other says "come here".
 *
 * This overlay owns canvas input while Pins is open, on the Crop and
 * Funhouse precedent. Constraints have to be placed before the edit is
 * made, so the canvas cannot also be painting — every touch here is
 * either a pin or a pull, and there is nothing left for a brush to mean.
 */
@Composable
fun PinsOverlay(
    holds: List<Pair<Float, Float>>,
    puck: MlsControl?,
    imageWidth: Int,
    imageHeight: Int,
    view: ViewTransform,
    /** Tap on empty photo: plant a hold. Tap on a hold: lift it. */
    onTap: (u: Float, v: Float, aspect: Float) -> Unit,
    /** @return true if the drag became a pull. */
    onPullStart: (u: Float, v: Float) -> Boolean,
    onPullMove: (u: Float, v: Float) -> Unit,
    onPullEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (imageWidth <= 0 || imageHeight <= 0) return
    val aspect = imageWidth.toFloat() / imageHeight

    // Live reads through rememberUpdatedState, keyed only on the image —
    // the trap FunhouseOverlay documents at length. As a pointerInput
    // key, `holds` changing mid-gesture would cancel the handler under a
    // finger that is still down.
    val currentHolds by rememberUpdatedState(holds)
    val currentView by rememberUpdatedState(view)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnStart by rememberUpdatedState(onPullStart)
    val currentOnMove by rememberUpdatedState(onPullMove)
    val currentOnEnd by rememberUpdatedState(onPullEnd)

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
                    val pointer = down.id
                    val (u0, v0) = toUv(down.position.x, down.position.y)
                    // A touch that starts on a hold pin is about that
                    // pin, never a pull: dragging from a pin you meant
                    // to remove would otherwise yank the picture.
                    val onHold = PinPull.nearestHold(currentHolds, u0, v0, aspect) != null

                    var pulling = false
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer }
                        if (change == null) {
                            // The tracked pointer left without a final
                            // pressed=false of its own — a cancellation.
                            // Same escape as FunhouseOverlay's, and load
                            // bearing for the same reason: this loop has
                            // no clock to fall back on.
                            if (event.changes.none { it.pressed }) {
                                cancelled = true
                                break
                            }
                            continue
                        }
                        change.consume()
                        if (!change.pressed) break
                        if (onHold) continue
                        val (u, v) = toUv(change.position.x, change.position.y)
                        if (!pulling && hypot((u - u0) * aspect, v - v0) > DRAG_SLOP) {
                            pulling = currentOnStart(u0, v0)
                        }
                        if (pulling) currentOnMove(u, v)
                    }

                    when {
                        cancelled -> if (pulling) currentOnEnd()
                        pulling -> currentOnEnd()
                        // A tap: plant a hold, or lift the one under it.
                        else -> currentOnTap(u0, v0, aspect)
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
        fun toScreen(u: Float, v: Float): Offset {
            val (fx, fy) = fit.uvToView(u, v)
            val (x, y) = view.apply(fx, fy)
            return Offset(x, y)
        }
        for ((u, v) in holds) drawHold(toScreen(u, v))
        puck?.let { p ->
            val from = toScreen(p.su, p.sv)
            val to = toScreen(p.tu, p.tv)
            // The leash: without it the puck is just a dot somewhere,
            // and which feature it grabbed is the thing you need to see.
            drawLine(
                color = Color.Black.copy(alpha = 0.35f),
                start = from,
                end = to,
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                color = NeonMagenta.copy(alpha = 0.8f),
                start = from,
                end = to,
                strokeWidth = 1.5f.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            )
            drawPuck(to)
        }
    }
}

/** Silver, and a cross: this one holds still. */
private fun DrawScope.drawHold(center: Offset) {
    val r = HOLD_RADIUS_DP.dp.toPx()
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = r,
        center = center,
        style = Stroke(width = 3.5.dp.toPx()),
    )
    drawCircle(
        color = SILVER,
        radius = r,
        center = center,
        style = Stroke(width = 1.8.dp.toPx()),
    )
    val tick = r * 0.55f
    for (d in listOf(Offset(tick, 0f), Offset(0f, tick))) {
        drawLine(
            color = SILVER,
            start = center - d,
            end = center + d,
            strokeWidth = 1.8.dp.toPx(),
        )
    }
}

/** Magenta and FILLED: the one thing that moves reads as solid. */
private fun DrawScope.drawPuck(center: Offset) {
    val r = PUCK_RADIUS_DP.dp.toPx()
    drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = r + 1.5f.dp.toPx(), center = center)
    drawCircle(color = NeonMagenta, radius = r, center = center)
}

private val SILVER = Color(0xFFD8DEE6)
private const val HOLD_RADIUS_DP = 11f
private const val PUCK_RADIUS_DP = 9f

/** Aspect-space travel past which a touch is a pull, not a tap. */
private const val DRAG_SLOP = 0.012f
