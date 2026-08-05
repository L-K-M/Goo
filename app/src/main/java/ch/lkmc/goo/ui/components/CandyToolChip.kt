package ch.lkmc.goo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.ui.theme.GooOnDarkDim

/**
 * A palette entry: candy bead with the tool's icon, name underneath.
 * Selection lights the bead in the tool's own candy color and pops it
 * proud of the row (the spring lives in [CandyIconButton]).
 */
@Composable
fun CandyToolChip(
    icon: ImageVector,
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CandyIconButton(
            icon = icon,
            contentDescription = label,
            color = color,
            selected = selected,
            selectable = true,
            enabled = enabled,
            onClick = onClick,
            size = 42.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> GooOnDarkDim.copy(alpha = 0.4f)
                selected -> color
                else -> GooOnDarkDim
            },
            // The bead already announces the label; a second TalkBack stop
            // saying the same word would just be noise.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
