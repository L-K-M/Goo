package ch.lkmc.goo.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.data.MovieFormat
import ch.lkmc.goo.engine.core.MovieSpec
import ch.lkmc.goo.engine.core.MovieSpeed
import ch.lkmc.goo.ui.theme.MeltVoid
import ch.lkmc.goo.ui.theme.NeonLime

/**
 * The GOOvie out-tray: format, speed, looping, then Save (gallery) or
 * Share (chooser) — the movie twin of [ExportSheet], and deliberately the
 * same shape so "how do I get this out of the app" has one answer.
 *
 * The length line is the point of the speed control: it turns "2×" into
 * "1.8 s", which is the number a user actually has an opinion about. It
 * is computed from the same [MovieSpec] math the renderer walks, per
 * format — a GIF of a long strip drops to a slower frame rate, so its
 * length is honest but its smoothness is not the MP4's.
 */
@Composable
fun MovieExportSheet(
    keyframeCount: Int,
    exporting: Boolean,
    progress: Float,
    onSave: (MovieFormat, MovieSpeed, Boolean) -> Unit,
    onShare: (MovieFormat, MovieSpeed, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(MovieFormat.MP4) }
    var speed by rememberSaveable { mutableStateOf(MovieSpeed.DEFAULT) }
    var loop by rememberSaveable { mutableStateOf(true) }

    // Same dismissal veto as ExportSheet: an onDismissRequest-only guard
    // lets M3 animate the sheet to Hidden first and leaves an invisible
    // modal window eating every touch for the length of the render.
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
                text = stringResource(R.string.movie_export_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MovieFormat.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = format == entry,
                        onClick = { format = entry },
                        enabled = !exporting,
                        shape = SegmentedButtonDefaults.itemShape(index, MovieFormat.entries.size),
                    ) {
                        Text(entry.name)
                    }
                }
            }

            Text(
                text = stringResource(R.string.movie_speed),
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MovieSpeed.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = speed == entry,
                        onClick = { speed = entry },
                        enabled = !exporting,
                        shape = SegmentedButtonDefaults.itemShape(index, MovieSpeed.entries.size),
                    ) {
                        Text(stringResource(entry.labelRes()))
                    }
                }
            }

            if (format == MovieFormat.GIF) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = stringResource(R.string.movie_loop),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = loop, onCheckedChange = { loop = it }, enabled = !exporting)
                }
            }

            Text(
                text = stringResource(R.string.movie_length, lengthLabel(keyframeCount, format, speed)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (exporting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = NeonLime,
                    trackColor = MeltVoid,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = !exporting,
                    onClick = { onShare(format, speed, loop) },
                ) { Text(stringResource(R.string.export_share)) }
                TextButton(
                    enabled = !exporting,
                    onClick = { onSave(format, speed, loop) },
                ) { Text(stringResource(R.string.export_save)) }
            }
        }
    }
}

/** One decimal, e.g. "3.6" — the seconds the export will run for. */
private fun lengthLabel(keyframeCount: Int, format: MovieFormat, speed: MovieSpeed): String {
    val fps = when (format) {
        MovieFormat.MP4 -> MovieSpec.FPS
        MovieFormat.GIF -> MovieSpec.gifFps(keyframeCount, speed.multiplier)
    }
    val seconds = MovieSpec.durationSeconds(keyframeCount, speed.multiplier, fps)
    // The user's locale, not US: this is a number they read, and the
    // decimal separator belongs to them.
    return String.format(java.util.Locale.getDefault(), "%.1f", seconds)
}

/**
 * Speed labels live in strings.xml, not in the enum: [MovieSpeed] is pure
 * engine code and must not reach for R (AGENTS.md).
 */
private fun MovieSpeed.labelRes(): Int = when (this) {
    MovieSpeed.HALF -> R.string.movie_speed_half
    MovieSpeed.NORMAL -> R.string.movie_speed_normal
    MovieSpeed.DOUBLE -> R.string.movie_speed_double
    MovieSpeed.QUADRUPLE -> R.string.movie_speed_quadruple
}
