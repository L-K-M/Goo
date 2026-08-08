package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.ui.components.CandyIconButton
import ch.lkmc.goo.ui.components.darken
import ch.lkmc.goo.ui.components.lighten
import ch.lkmc.goo.ui.theme.CandyCyan
import ch.lkmc.goo.ui.theme.CandyGrape
import ch.lkmc.goo.ui.theme.CandyLemon
import ch.lkmc.goo.ui.theme.CandyLime
import ch.lkmc.goo.ui.theme.CandyOrange
import ch.lkmc.goo.ui.theme.CandyPink
import ch.lkmc.goo.ui.theme.GooTableRaised
import ch.lkmc.goo.ui.theme.GooTableShadow

/**
 * The GOOvie strip (PLAN.md §10 step 7): punch keyframes as you goo,
 * scrub the tweens, play the loop. Numbered candy beads stand in for
 * thumbnails until the polish pass (#10) — the numbers are the KPT way
 * anyway.
 */
@Composable
fun GooviePanel(
    keyframes: List<Keyframe>,
    scrubPos: Float,
    playing: Boolean,
    selected: Int,
    canCapture: Boolean,
    exporting: Boolean,
    exportProgress: Float,
    onCapture: () -> Unit,
    onSelect: (Int) -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onScrub: (Float) -> Unit,
    onPlayToggle: () -> Unit,
    onExport: (share: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CandyIconButton(
                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.goovie_pause else R.string.goovie_play,
                ),
                color = CandyLime,
                selected = playing,
                // Playing during an export would advance a preview the
                // busy GL thread can't draw — a frozen image and a queue
                // of no-op tweens.
                enabled = keyframes.size >= 2 && !exporting,
                onClick = onPlayToggle,
            )
            val stripState = rememberLazyListState()
            // Keep the SELECTED bead in view — capture selects the new
            // bead, so fresh punches scroll into view past the fold.
            // Deliberately selection-only: scrubbing clears selection
            // (-1), and yanking the strip around during a scrub or
            // playback would fight the user.
            LaunchedEffect(selected) {
                if (selected >= 0) stripState.animateScrollToItem(selected)
            }
            LazyRow(
                state = stripState,
                modifier = Modifier.weight(1f),
                // minimumInteractiveComponentSize grows each item's layout
                // node to 48dp, so targets can never overlap; the 8dp gap
                // is purely visual rhythm.
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(keyframes) { index, _ ->
                    KeyframeBead(
                        number = index + 1,
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
                }
            }
            CandyIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.goovie_capture),
                color = CandyPink,
                selected = false,
                enabled = canCapture && !exporting,
                onClick = onCapture,
            )
        }

        if (exporting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.goovie_exporting),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { exportProgress },
                    modifier = Modifier.weight(1f),
                    color = CandyLime,
                    trackColor = GooTableShadow,
                )
            }
        } else if (keyframes.isEmpty()) {
            Text(
                text = stringResource(R.string.goovie_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CandyIconButton(
                    icon = Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(R.string.goovie_move_earlier),
                    color = CandyCyan,
                    selected = false,
                    enabled = selected > 0,
                    onClick = { onMove(-1) },
                    size = 38.dp,
                )
                CandyIconButton(
                    icon = Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.goovie_move_later),
                    color = CandyCyan,
                    selected = false,
                    enabled = selected in 0 until keyframes.size - 1,
                    onClick = { onMove(1) },
                    size = 38.dp,
                )
                Slider(
                    modifier = Modifier.weight(1f),
                    value = scrubPos,
                    onValueChange = onScrub,
                    valueRange = 0f..(keyframes.size - 1).coerceAtLeast(1).toFloat(),
                    enabled = keyframes.size >= 2,
                    colors = SliderDefaults.colors(
                        thumbColor = CandyLemon,
                        activeTrackColor = CandyLemon.copy(alpha = 0.6f),
                        inactiveTrackColor = GooTableShadow,
                    ),
                )
                CandyIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.goovie_delete),
                    color = CandyOrange,
                    selected = false,
                    enabled = selected >= 0,
                    onClick = onDelete,
                    size = 38.dp,
                )
                CandyIconButton(
                    icon = Icons.Filled.Download,
                    contentDescription = stringResource(R.string.goovie_save_movie),
                    color = CandyGrape,
                    selected = false,
                    enabled = keyframes.size >= 2,
                    haptic = false,
                    onClick = { onExport(false) },
                    size = 38.dp,
                )
                CandyIconButton(
                    icon = Icons.Filled.IosShare,
                    contentDescription = stringResource(R.string.goovie_share_movie),
                    color = CandyGrape,
                    selected = false,
                    enabled = keyframes.size >= 2,
                    haptic = false,
                    onClick = { onExport(true) },
                    size = 38.dp,
                )
            }
        }
    }
}

/** A numbered candy bead — the strip's stand-in for a thumbnail. */
@Composable
private fun KeyframeBead(
    number: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val body = if (selected) CandyLemon else GooTableRaised
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .semantics { this.selected = selected }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(40.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(body.lighten(0.30f), body, body.darken(0.35f)),
                    center = c + Offset(-r * 0.3f, -r * 0.35f),
                    radius = r * 1.6f,
                ),
                radius = r,
                center = c,
            )
        }
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) GooTableShadow else Color.White.copy(alpha = 0.75f),
        )
    }
}
