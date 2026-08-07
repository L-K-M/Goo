package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.ViewTransform

/**
 * Where Echo is cloning FROM.
 *
 * A clone brush whose source is invisible is a brush you have to
 * remember the state of: plant an anchor, paint somewhere else, and the
 * only feedback that the plant happened at all is that the next drag
 * grafts from *somewhere*. A stray tap silently re-aims it.
 *
 * So the anchor gets a ring, drawn only while Echo is the live tool.
 * Unlike [BrushPreviewOverlay], which deliberately sits at the
 * viewport's own middle so panning can never carry it off-screen, this
 * one is pinned to a point in the PHOTO — it has to ride the view, or
 * it would stop pointing at the thing it names.
 */
@Composable
fun EchoAnchorOverlay(
    anchor: Pair<Float, Float>?,
    imageWidth: Int,
    imageHeight: Int,
    view: ViewTransform,
    modifier: Modifier = Modifier,
) {
    if (anchor == null || imageWidth <= 0 || imageHeight <= 0) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val fit = FitTransform(
            size.width, size.height,
            imageWidth.toFloat(), imageHeight.toFloat(),
        )
        // UV → fitted canvas pixels → view pixels. The same two steps the
        // touch path runs in reverse (FitTransform.viewToUv after
        // ViewTransform.invert), so the ring lands exactly where a touch
        // at this UV would have.
        val (cx, cy) = fit.uvToView(anchor.first, anchor.second)
        val (x, y) = view.apply(cx, cy)
        val center = Offset(x, y)
        val r = ANCHOR_RADIUS_DP.dp.toPx()

        // Dark backing first so the mark survives a bright photo — the
        // same trick the brush ring uses.
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = r,
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = r,
            center = center,
            style = Stroke(width = 1.5f.dp.toPx()),
        )
        // Crosshair: a ring alone reads as another brush cursor, and the
        // one thing this must not be mistaken for is where you are
        // painting.
        val tick = r * 0.6f
        for (d in listOf(Offset(tick, 0f), Offset(0f, tick))) {
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = center - d,
                end = center + d,
                strokeWidth = 1.5f.dp.toPx(),
            )
        }
    }
}

private const val ANCHOR_RADIUS_DP = 11f
