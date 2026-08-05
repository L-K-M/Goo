package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.GlobalParams

/**
 * The global-effects rig (PLAN.md §4.1): six bipolar levers, live on the
 * whole image, composed over the painted goo. Center = identity; the
 * candy-lever look arrives with the UI-polish roadmap step.
 */
@Composable
fun LeversPanel(
    globals: GlobalParams,
    onChange: (GlobalParams) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Lever(R.string.lever_bulge, globals.bulge) { onChange(globals.copy(bulge = it)) }
        Lever(R.string.lever_twirl, globals.twirl) { onChange(globals.copy(twirl = it)) }
        Lever(R.string.lever_squeeze, globals.squeeze) { onChange(globals.copy(squeeze = it)) }
        Lever(R.string.lever_stretch, globals.stretch) { onChange(globals.copy(stretch = it)) }
        Lever(R.string.lever_spike, globals.spike) { onChange(globals.copy(spike = it)) }
        Lever(R.string.lever_static, globals.static) { onChange(globals.copy(static = it)) }
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
private fun Lever(labelRes: Int, value: Float, onChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            modifier = Modifier.weight(1f),
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
        )
    }
}
