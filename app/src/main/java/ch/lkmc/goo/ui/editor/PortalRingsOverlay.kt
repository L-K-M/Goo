package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.ViewTransform
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonMagenta

/**
 * The two linked rings (proposal 0012).
 *
 * Each ring is drawn at the *brush's* radius, not at a fixed icon size,
 * because that circle is literally the hit target: a stroke starting
 * inside it is twinned and one starting outside it is ordinary. Drawing a
 * decorative dot instead would leave the one thing the user has to aim at
 * invisible, and "why did that stroke not copy" is exactly the confusion
 * this tool cannot afford.
 *
 * Cyan for A and magenta for B, per the proposal, and a dashed tie-line
 * between them: two circles of different colours read as two unrelated
 * marks, and the whole idea is that they belong together.
 *
 * Like [EchoAnchorOverlay] and unlike [BrushPreviewOverlay], the rings are
 * pinned to points in the PHOTO and ride the view transform — a link that
 * drifted when you panned would be pointing at the wrong pixels.
 */
@Composable
fun PortalRingsOverlay(
    a: Pair<Float, Float>?,
    b: Pair<Float, Float>?,
    /** Aspect-space brush radius; the rings are the live brush. */
    radius: Float,
    imageWidth: Int,
    imageHeight: Int,
    view: ViewTransform,
    modifier: Modifier = Modifier,
) {
    if (a == null && b == null) return
    if (imageWidth <= 0 || imageHeight <= 0) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val fit = FitTransform(
            size.width, size.height,
            imageWidth.toFloat(), imageHeight.toFloat(),
        )
        fun toScreen(p: Pair<Float, Float>): Offset {
            val (fx, fy) = fit.uvToView(p.first, p.second)
            val (x, y) = view.apply(fx, fy)
            return Offset(x, y)
        }
        // Aspect-space radius is a fraction of image height, so the pixel
        // radius rides the fit AND the zoom — the ring covers exactly the
        // texels the brush would, at any zoom.
        val radiusPx = radius * fit.fittedHeight * view.scale
        val centerA = a?.let(::toScreen)
        val centerB = b?.let(::toScreen)

        // Tie-line first, so the rings sit on top of it rather than being
        // cut by it.
        if (centerA != null && centerB != null) {
            drawLine(
                color = Color.Black.copy(alpha = 0.35f),
                start = centerA,
                end = centerB,
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = centerA,
                end = centerB,
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(7.dp.toPx(), 7.dp.toPx()),
                ),
            )
        }
        centerA?.let { drawRing(it, radiusPx, NeonCyan) }
        centerB?.let { drawRing(it, radiusPx, NeonMagenta) }
    }
}

private fun DrawScope.drawRing(center: Offset, radiusPx: Float, tint: Color) {
    if (radiusPx <= 0f) return
    // Dark backing under every line: chrome on a bright photo is
    // invisible without it. Same trick as the brush ring.
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = radiusPx,
        center = center,
        style = Stroke(width = 3.5.dp.toPx()),
    )
    drawCircle(
        color = tint,
        radius = radiusPx,
        center = center,
        style = Stroke(width = 1.8.dp.toPx()),
    )
    // A centre cross, on EchoAnchorOverlay's reasoning: a bare ring at
    // brush size reads as another brush cursor, and the one thing a
    // portal must not be mistaken for is where you are painting.
    val tick = (radiusPx * 0.28f).coerceAtMost(14.dp.toPx())
    for (d in listOf(Offset(tick, 0f), Offset(0f, tick))) {
        drawLine(
            color = tint,
            start = center - d,
            end = center + d,
            strokeWidth = 1.8.dp.toPx(),
        )
    }
}
