package ch.lkmc.goo.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A big, juicy, round candy button — the signature Goo control.
 *
 * Rendered as a glossy sphere: radial base gradient, a soft top-left
 * highlight, and a bottom shadow rim so it reads as raised off the table.
 * Pressing squishes it (spring scale), because squishing is the brand.
 */
@Composable
fun CandyButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    breathe: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val squish by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "candySquish",
    )
    // Idle breathing for hero buttons: alive, but slow enough not to nag.
    // Kept as State and read only inside graphicsLayer — reading .value
    // here would recompose the whole button every frame, forever.
    val breatheAnim: State<Float>? = if (breathe) {
        rememberInfiniteTransition(label = "candyBreathe").animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "candyBreatheScale",
        )
    } else {
        null
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val b = breatheAnim?.value ?: 1f
                scaleX = squish * b
                scaleY = squish * b
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            // Body: lit from the upper left.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.lighten(0.35f), color, color.darken(0.35f)),
                    center = c + Offset(-r * 0.3f, -r * 0.35f),
                    radius = r * 1.6f,
                ),
                radius = r,
                center = c,
            )
            // Gloss highlight. Offset + radius stay inside the body circle
            // (0.46r + 0.5r < r) so no haze bleeds past the silhouette.
            val gloss = c + Offset(-r * 0.30f, -r * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = gloss,
                    radius = r * 0.5f,
                ),
                radius = r * 0.5f,
                center = gloss,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.Black.copy(alpha = 0.72f),
        )
    }
}

// lighten/darken shared with CandyIconButton (internal, CandyIconButton.kt).
