package ch.lkmc.goo.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.data.ExportFormat
import kotlin.math.roundToInt

/**
 * The Out room: format + quality, then Save (gallery) or Share (chooser).
 * Kept deliberately small: the console's out-tray, not a room of its own.
 */
@Composable
fun ExportSheet(
    exporting: Boolean,
    onSave: (ExportFormat, Int) -> Unit,
    onShare: (ExportFormat, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(ExportFormat.JPEG) }
    var quality by rememberSaveable { mutableFloatStateOf(DEFAULT_JPEG_QUALITY) }

    // Veto dismissal at the sheet-state level while an export runs. An
    // onDismissRequest-only guard can't do this: M3 animates the sheet to
    // Hidden first and only then asks, which would leave an invisible
    // modal window eating every touch.
    val exportingNow by rememberUpdatedState(exporting)
    val sheetState = rememberModalBottomSheetState(
        confirmValueChange = { target -> !(exportingNow && target == SheetValue.Hidden) },
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.export_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ExportFormat.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = format == entry,
                        onClick = { format = entry },
                        shape = SegmentedButtonDefaults.itemShape(index, ExportFormat.entries.size),
                    ) {
                        Text(entry.name)
                    }
                }
            }

            if (format.supportsQuality) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.export_quality, quality.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        modifier = Modifier.weight(1f),
                        value = quality,
                        onValueChange = { quality = it },
                        valueRange = 50f..100f,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 16.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(
                    enabled = !exporting,
                    onClick = { onShare(format, quality.roundToInt()) },
                ) { Text(stringResource(R.string.export_share)) }
                TextButton(
                    enabled = !exporting,
                    onClick = { onSave(format, quality.roundToInt()) },
                ) { Text(stringResource(R.string.export_save)) }
            }
        }
    }
}

private const val DEFAULT_JPEG_QUALITY = 95f
