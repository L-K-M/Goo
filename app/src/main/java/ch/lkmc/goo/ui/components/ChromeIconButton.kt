package ch.lkmc.goo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.ui.theme.MeltPanel
import ch.lkmc.goo.ui.theme.MeltVoid
import ch.lkmc.goo.ui.theme.chromeSweep

/**
 * A chrome-rimmed bead with an icon — the editor's working control.
 *
 * Three visual states: enabled+selected burns in its neon [color] and
 * spills glow onto the deck, enabled+unselected sits as a dark bead
 * behind the same rim, disabled lets the rim go dull and recedes into the
 * panel. Press squishes with the same spring as [ChromeButton]; selecting
 * ticks the haptics, because a console this shiny should also click.
 */
@Composable
fun ChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    enabled: Boolean = true,
    haptic: Boolean = true,
    /** Expose [selected] to accessibility (tools, toggles — not plain buttons). */
    selectable: Boolean = false,
    size: Dp = 46.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val squish by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "chromeIconSquish",
    )
    // Selection pops the whole bead slightly proud of its neighbors.
    val pop by animateFloatAsState(
        targetValue = if (selected && enabled) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "chromeIconPop",
    )

    val body = when {
        !enabled -> MeltPanel
        selected -> color
        else -> color.copy(alpha = 0.30f).compositeOverPanel()
    }
    val iconTint = when {
        !enabled -> Color.White.copy(alpha = 0.22f)
        selected -> MeltVoid
        else -> Color.White.copy(alpha = 0.75f)
    }

    Box(
        modifier = modifier
            // 48dp hit area even when the bead draws smaller — the stock
            // components enforced this; custom clickables must ask for it.
            .minimumInteractiveComponentSize()
            .size(size)
            .graphicsLayer {
                scaleX = squish * pop
                scaleY = squish * pop
            }
            .semantics { if (selectable) this.selected = selected }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = {
                    if (haptic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val outer = this.size.minDimension / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            // A selected tool lights its own patch of the deck.
            if (enabled && selected) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.40f), Color.Transparent),
                        center = c,
                        radius = outer,
                    ),
                    radius = outer,
                    center = c,
                )
            }
            // Milled rim: thin, so the bead still reads as the subject.
            val rimWidth = outer * 0.12f
            val rimRadius = outer * 0.92f - rimWidth / 2f
            drawCircle(
                brush = chromeSweep(),
                radius = rimRadius,
                center = c,
                style = Stroke(width = rimWidth),
                alpha = if (enabled) 1f else 0.35f,
            )
            val r = rimRadius - rimWidth / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(body.lighten(0.30f), body, body.darken(0.40f)),
                    center = c + Offset(-r * 0.3f, -r * 0.35f),
                    radius = r * 1.6f,
                ),
                radius = r,
                center = c,
            )
            if (enabled) {
                val gloss = c + Offset(-r * 0.30f, -r * 0.35f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (selected) 0.55f else 0.25f),
                            Color.Transparent,
                        ),
                        center = gloss,
                        radius = r * 0.5f,
                    ),
                    radius = r * 0.5f,
                    center = gloss,
                )
            }
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Flatten a translucent neon onto the panel color for the bead look. */
private fun Color.compositeOverPanel(): Color {
    val bg = MeltPanel
    val a = alpha
    return Color(
        red = red * a + bg.red * (1 - a),
        green = green * a + bg.green * (1 - a),
        blue = blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}

internal fun Color.lighten(amount: Float): Color = Color(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
    alpha = alpha,
)

internal fun Color.darken(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha,
)
