package ch.lkmc.goo.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.engine.core.BrushFalloff
import ch.lkmc.goo.engine.core.FalloffProfile
import ch.lkmc.goo.engine.core.FitTransform
import ch.lkmc.goo.engine.core.ViewTransform

/**
 * Live brush preview while a Size/Strength slider is in hand: a ring at
 * the exact aspect-space radius over the fitted photo, filled with a
 * radial gradient sampled from the ACTUAL falloff curve of the current
 * tool at the current strength — Plateau reads flat-topped, Feather
 * reads soft, and strength reads as opacity.
 *
 * It sits in the middle of the VIEWPORT, not the middle of the photo.
 * Those coincide at rest (FitTransform centers the photo in the canvas),
 * but zoom in and the photo's center walks off-screen, taking the
 * preview with it — user-reported, and a preview you cannot see is no
 * preview. Only the radius rides the view transform, which is what keeps
 * "this big, against what you are looking at" true at any zoom.
 *
 * Strength shown is the user-facing slider value, not the per-tool
 * scaled value — Nudge at full strength should read "full", even though
 * its effective displacement is deliberately tiny.
 */
@Composable
fun BrushPreviewOverlay(
    visible: Boolean,
    imageWidth: Int,
    imageHeight: Int,
    radius: Float,
    strength: Float,
    profile: FalloffProfile,
    view: ViewTransform,
    modifier: Modifier = Modifier,
) {
    // Quick in, gentle out — the fade-out is the "letting go" feel.
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 120 else 350),
        label = "brushPreviewAlpha",
    )
    if (alpha < 0.01f) return

    Canvas(modifier = modifier.fillMaxSize()) {
        val fit = FitTransform(
            size.width, size.height,
            imageWidth.toFloat(), imageHeight.toFloat(),
        )
        // Aspect-space radius is a fraction of image height (see
        // UiState.brushRadius) — px radius scales with the fitted photo
        // AND the view zoom, so a brush drawn here covers exactly the
        // pixels it would cover if stamped here. Rotation is irrelevant
        // to a circle, and the center does not ride the view at all: it
        // is the viewport's own middle, so panning and zooming can never
        // carry the preview off-screen.
        val radiusPx = radius * fit.fittedHeight * view.scale
        if (radiusPx <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)

        // Fill: the tool's real falloff, strength as peak opacity.
        val peak = 0.45f * strength.coerceIn(0f, 1f) * alpha
        val stops = Array(FALLOFF_STOPS + 1) { i ->
            val d = i.toFloat() / FALLOFF_STOPS
            d to Color.White.copy(alpha = peak * BrushFalloff.weight(d, profile))
        }
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = stops,
                center = center,
                radius = radiusPx,
            ),
            radius = radiusPx,
            center = center,
        )
        // The ring: a crisp edge at the true radius, dark backing first
        // so it stays visible over bright photos.
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f * alpha),
            radius = radiusPx,
            center = center,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f * alpha),
            radius = radiusPx,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

private const val FALLOFF_STOPS = 8
