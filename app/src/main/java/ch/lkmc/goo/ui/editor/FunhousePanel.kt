package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.Lens
import ch.lkmc.goo.engine.core.LensType
import ch.lkmc.goo.ui.components.ChromeIconButton
import ch.lkmc.goo.ui.components.ChromeLever
import ch.lkmc.goo.ui.components.chromePanel
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonMagenta
import ch.lkmc.goo.ui.theme.NeonTangerine

/**
 * The Funhouse bench (proposal 0006): what the selected lens is, how big
 * it is, and how hard it works.
 *
 * Placement and removal happen on the photo, where the apparatus is —
 * this panel is for the two continuous values a finger cannot express by
 * dragging a ring around, plus a readout that says which of four things
 * the ring in hand currently is.
 */
@Composable
fun FunhousePanel(
    lens: Lens?,
    placed: Int,
    onRadius: (Float) -> Unit,
    onStrength: (Float) -> Unit,
    onCycleType: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .chromePanel(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    if (lens == null) {
                        // The whole instruction, in the place the answer
                        // will appear: an empty bench that says nothing
                        // is how a mode gets abandoned.
                        if (placed >= Lens.CAPACITY) {
                            R.string.funhouse_full
                        } else {
                            R.string.funhouse_empty
                        }
                    } else {
                        lens.type.labelRes()
                    },
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$placed / ${Lens.CAPACITY}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChromeIconButton(
                icon = Icons.Filled.Cached,
                contentDescription = stringResource(R.string.funhouse_cycle),
                color = NeonCyan,
                selected = false,
                enabled = lens != null,
                onClick = onCycleType,
                size = 38.dp,
            )
            ChromeIconButton(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.funhouse_remove),
                color = NeonTangerine,
                selected = false,
                enabled = lens != null,
                onClick = onRemove,
                size = 38.dp,
            )
        }

        // Strength is bipolar and gets the chrome lever, because it is a
        // lever: centre is identity and the far side runs the effect
        // backwards, which is what lets a lens tween through zero.
        Labelled(R.string.funhouse_strength) {
            ChromeLever(
                modifier = Modifier.weight(1f),
                value = lens?.strength ?: 0f,
                color = NeonMagenta,
                contentDescription = stringResource(R.string.funhouse_strength),
                enabled = lens != null,
                onChange = onStrength,
            )
        }
        // Size is not bipolar — a negative radius is nothing — so it is a
        // plain slider, same as the brush's own Size.
        Labelled(R.string.funhouse_size) {
            Slider(
                modifier = Modifier.weight(1f),
                value = lens?.radius ?: Lens.DEFAULT_RADIUS,
                onValueChange = onRadius,
                valueRange = Lens.MIN_RADIUS..Lens.MAX_RADIUS,
                enabled = lens != null,
                colors = SliderDefaults.colors(
                    thumbColor = NeonCyan,
                    activeTrackColor = NeonCyan.copy(alpha = 0.6f),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(R.string.funhouse_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Labelled(labelRes: Int, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            // Same column width and same one-line rule as the lever
            // rack next door: these two benches sit in the same place on
            // screen, so a label that wrapped in one and not the other
            // would make the panels look like different furniture.
            modifier = Modifier.width(84.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

/** The name the bench prints for each kind of glass. */
private fun LensType.labelRes(): Int = when (this) {
    LensType.BULGE -> R.string.funhouse_bulge
    LensType.PINCH -> R.string.funhouse_pinch
    LensType.FISHEYE -> R.string.funhouse_fisheye
    LensType.VORTEX -> R.string.funhouse_vortex
}
