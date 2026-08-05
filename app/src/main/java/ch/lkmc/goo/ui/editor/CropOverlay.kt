package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.CropRect
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.ui.components.CandyIconButton
import ch.lkmc.goo.ui.theme.CandyCyan
import ch.lkmc.goo.ui.theme.CandyLime
import ch.lkmc.goo.ui.theme.CandyOrange

/**
 * Full-canvas crop mode: a light rect with candy corner handles over the
 * dimmed photo. Freeform only (KPT had no crop at all; this one stays
 * humble). The rect starts at the full CURRENT frame and only tightens —
 * "back to the full picture" is the separate [onFullFrame] bead, shown
 * when a crop is already active.
 *
 * All geometry runs in the fitted, untransformed pose ([FitTransform]
 * of the image in this box) — the screen resets the view transform to
 * identity before showing this overlay, so view px ≡ canvas px here.
 */
@Composable
fun CropOverlay(
    imageWidth: Int,
    imageHeight: Int,
    showFullFrame: Boolean,
    onApply: (CropRect) -> Unit,
    onFullFrame: () -> Unit,
    onCancel: () -> Unit,
) {
    var rect by remember { mutableStateOf(CropRect.FULL) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(imageWidth, imageHeight) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val fit = FitTransform(
                        viewWidth = size.width.toFloat(),
                        viewHeight = size.height.toFloat(),
                        imageWidth = imageWidth.toFloat(),
                        imageHeight = imageHeight.toFloat(),
                    )
                    val grabRadius = 24.dp.toPx()

                    fun cornerPos(r: CropRect, i: Int): Offset {
                        val u = if (i % 2 == 0) r.left else r.right
                        val v = if (i < 2) r.top else r.bottom
                        val (x, y) = fit.uvToView(u, v)
                        return Offset(x, y)
                    }

                    val start = rect
                    val corner = (0..3).minByOrNull { i ->
                        (cornerPos(start, i) - down.position).getDistance()
                    }!!.takeIf { i ->
                        (cornerPos(start, i) - down.position).getDistance() <= grabRadius
                    }
                    // The finger rarely lands exactly on the corner: keep
                    // the grab offset, or the rect jumps on the first move.
                    val grabOffset = corner?.let { cornerPos(start, it) - down.position }
                    val (du0, dv0) = fit.viewToUv(down.position.x, down.position.y)
                    val inside = corner == null &&
                        du0 in start.left..start.right && dv0 in start.top..start.bottom
                    if (corner == null && !inside) {
                        // Letterbox/dim taps: not a drag, not a dismiss —
                        // Cancel is an explicit bead, never an accident.
                        down.consume()
                        return@awaitEachGesture
                    }
                    down.consume()
                    var previous = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        if (corner != null) {
                            val target = change.position + grabOffset!!
                            val (u, v) = fit.viewToUv(target.x, target.y)
                            rect = rect.dragCorner(corner, u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
                        } else {
                            val delta = change.position - previous
                            rect = rect.dragInside(
                                du = delta.x / fit.fittedWidth,
                                dv = delta.y / fit.fittedHeight,
                            )
                        }
                        previous = change.position
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fit = FitTransform(
                viewWidth = size.width,
                viewHeight = size.height,
                imageWidth = imageWidth.toFloat(),
                imageHeight = imageHeight.toFloat(),
            )
            val (l, t) = fit.uvToView(rect.left, rect.top)
            val (r, b) = fit.uvToView(rect.right, rect.bottom)

            // Dim everything outside the keep-rect (image and letterbox
            // alike) with four full-bleed strips.
            val dim = Color.Black.copy(alpha = 0.55f)
            drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, t))
            drawRect(dim, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
            drawRect(dim, topLeft = Offset(0f, t), size = Size(l, b - t))
            drawRect(dim, topLeft = Offset(r, t), size = Size(size.width - r, b - t))

            // Rule-of-thirds guides, quiet.
            val guide = Color.White.copy(alpha = 0.30f)
            val gw = 1.dp.toPx()
            for (i in 1..2) {
                val gx = l + (r - l) * i / 3f
                val gy = t + (b - t) * i / 3f
                drawLine(guide, Offset(gx, t), Offset(gx, b), strokeWidth = gw)
                drawLine(guide, Offset(l, gy), Offset(r, gy), strokeWidth = gw)
            }

            // Border, cursor-ring style: dark backing under a light line.
            val frame = Size(r - l, b - t)
            drawRect(
                Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(l, t),
                size = frame,
                style = Stroke(width = 3.dp.toPx()),
            )
            drawRect(
                Color.White.copy(alpha = 0.9f),
                topLeft = Offset(l, t),
                size = frame,
                style = Stroke(width = 1.5.dp.toPx()),
            )

            // Candy corner handles.
            val handleR = 8.dp.toPx()
            listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { c ->
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = handleR + 1.5.dp.toPx(), center = c)
                drawCircle(Color.White, radius = handleR, center = c)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CandyIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.crop_cancel),
                color = CandyOrange,
                selected = false,
                haptic = false,
                onClick = onCancel,
            )
            if (showFullFrame) {
                CandyIconButton(
                    icon = Icons.Filled.CropFree,
                    contentDescription = stringResource(R.string.crop_full_frame),
                    color = CandyCyan,
                    selected = false,
                    onClick = onFullFrame,
                )
            }
            CandyIconButton(
                icon = Icons.Filled.Check,
                contentDescription = stringResource(R.string.crop_apply),
                color = CandyLime,
                selected = false,
                onClick = { onApply(rect) },
            )
        }
    }
}
