package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.ui.components.chromePanel
import ch.lkmc.goo.ui.components.ChromeLever
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonViolet
import ch.lkmc.goo.ui.theme.NeonAmber
import ch.lkmc.goo.ui.theme.NeonLime
import ch.lkmc.goo.ui.theme.NeonTangerine
import ch.lkmc.goo.ui.theme.NeonMagenta

/**
 * The global-effects rig (PLAN.md §4.1): six bipolar levers, live
 * on the whole image, composed over the painted goo. Center = identity;
 * each lever wears its own neon and snaps to the center detent.
 */
@Composable
fun LeversPanel(
    globals: GlobalParams,
    onChange: (GlobalParams) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .chromePanel(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Lever(R.string.lever_bulge, globals.bulge, NeonMagenta) {
            onChange(globals.copy(bulge = it))
        }
        Lever(R.string.lever_twirl, globals.twirl, NeonCyan) {
            onChange(globals.copy(twirl = it))
        }
        Lever(R.string.lever_squeeze, globals.squeeze, NeonTangerine) {
            onChange(globals.copy(squeeze = it))
        }
        Lever(R.string.lever_stretch, globals.stretch, NeonLime) {
            onChange(globals.copy(stretch = it))
        }
        Lever(R.string.lever_spike, globals.spike, NeonAmber) {
            onChange(globals.copy(spike = it))
        }
        Lever(R.string.lever_static, globals.static, NeonViolet) {
            onChange(globals.copy(static = it))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                enabled = !globals.isIdentity,
                onClick = { onChange(GlobalParams()) },
            ) { Text(stringResource(R.string.lever_zero_all)) }
        }
    }
}

@Composable
private fun Lever(labelRes: Int, value: Float, color: Color, onChange: (Float) -> Unit) {
    val label = stringResource(labelRes)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChromeLever(
            modifier = Modifier.weight(1f),
            value = value,
            color = color,
            contentDescription = label,
            onChange = onChange,
        )
    }
}
