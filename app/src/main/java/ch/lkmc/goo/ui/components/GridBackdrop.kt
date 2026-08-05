package ch.lkmc.goo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.ui.theme.MeltDeck
import ch.lkmc.goo.ui.theme.MeltGrid
import ch.lkmc.goo.ui.theme.MeltVoid
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonMagenta

/**
 * The In room's horizon: a perspective grid running to a vanishing point,
 * with a neon glow where the ground meets the sky. Every 1990s box cover
 * that promised the future had this exact backdrop, and it costs two
 * loops of [drawLine].
 *
 * Pure geometry, no randomness: the same picture every launch, which also
 * keeps it out of the recomposition budget (one Canvas, no state).
 */
@Composable
fun GridBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Horizon sits low: most of the frame is "sky" for the wordmark
        // and the hero button to live in.
        val horizon = h * 0.62f

        drawRect(
            brush = Brush.verticalGradient(
                0f to MeltVoid,
                0.55f to MeltDeck,
                1f to MeltVoid,
            ),
        )
        // Sky glow pooling on the horizon line.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(NeonMagenta.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w / 2f, horizon),
                radius = w * 0.85f,
            ),
            radius = w * 0.85f,
            center = Offset(w / 2f, horizon),
        )

        val vanishX = w / 2f
        val line = 1.dp.toPx()

        // Rails converging on the vanishing point. Spread beyond the frame
        // so the outermost ones still cross the bottom edge.
        val rails = 15
        for (i in 0..rails) {
            val t = i / rails.toFloat()
            val x = -w + t * (3f * w)
            drawLine(
                color = MeltGrid.copy(alpha = 0.55f),
                start = Offset(vanishX, horizon),
                end = Offset(x, h),
                strokeWidth = line,
            )
        }
        // Ties receding toward the horizon: quadratic spacing reads as
        // even ground under a camera, which linear spacing never does.
        val ties = 12
        for (i in 1..ties) {
            val t = i / ties.toFloat()
            val y = horizon + (h - horizon) * t * t
            drawLine(
                color = MeltGrid.copy(alpha = 0.20f + 0.45f * t),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = line,
            )
        }
        // The horizon itself: a lit neon tube, brightest at the center.
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to NeonCyan.copy(alpha = 0.75f),
                1f to Color.Transparent,
            ),
            start = Offset(0f, horizon),
            end = Offset(w, horizon),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
