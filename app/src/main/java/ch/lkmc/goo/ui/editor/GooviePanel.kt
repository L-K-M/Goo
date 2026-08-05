package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cached
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
import ch.lkmc.goo.engine.core.GoovieHint
import ch.lkmc.goo.engine.core.Keyframe
import ch.lkmc.goo.engine.core.goovieHint
import ch.lkmc.goo.ui.components.chromePanel
import ch.lkmc.goo.ui.components.ChromeIconButton
import ch.lkmc.goo.ui.components.darken
import ch.lkmc.goo.ui.components.lighten
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonViolet
import ch.lkmc.goo.ui.theme.NeonAmber
import ch.lkmc.goo.ui.theme.NeonLime
import ch.lkmc.goo.ui.theme.NeonTangerine
import ch.lkmc.goo.ui.theme.NeonMagenta
import ch.lkmc.goo.ui.theme.MeltPanel
import ch.lkmc.goo.ui.theme.MeltVoid

/**
 * The GOOvie strip (PLAN.md §10 step 7): punch keyframes as you goo,
 * scrub the tweens, play the loop. Numbered neon beads stand in for
 * thumbnails until the polish pass (#10) — the numbers are the KPT way
 * anyway.
 *
 * Gooing works while the strip is open: touching the canvas drops the
 * preview from the tween to the live document ([live]) so the strokes are
 * visible where they land. Punch pins that as a new keyframe; Update
 * re-pins the selected one. The hint line names whichever of those the
 * strip's current state calls for.
 */
@Composable
fun GooviePanel(
    keyframes: List<Keyframe>,
    scrubPos: Float,
    playing: Boolean,
    selected: Int,
    /** The canvas is showing live goo instead of the scrub tween. */
    live: Boolean,
    /** The selected pin is behind the live document (Update would move it). */
    stale: Boolean,
    canCapture: Boolean,
    exporting: Boolean,
    exportProgress: Float,
    onCapture: () -> Unit,
    onRepunch: () -> Unit,
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
            ChromeIconButton(
                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (playing) R.string.goovie_pause else R.string.goovie_play,
                ),
                color = NeonLime,
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
            ChromeIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.goovie_capture),
                color = NeonMagenta,
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
                    color = NeonLime,
                    trackColor = MeltVoid,
                )
            }
        } else {
            if (keyframes.isNotEmpty()) {
                // The scrub gets its own row: the control beads below claim
                // 48dp of layout each (minimumInteractiveComponentSize), so
                // sharing a row with six of them left the slider a stub on
                // a 360dp screen.
                Slider(
                    modifier = Modifier.fillMaxWidth(),
                    value = scrubPos,
                    onValueChange = onScrub,
                    valueRange = 0f..(keyframes.size - 1).coerceAtLeast(1).toFloat(),
                    enabled = keyframes.size >= 2,
                    colors = SliderDefaults.colors(
                        // Live goo is off the timeline: the thumb still
                        // marks where a scrub would land (and scrubbing is
                        // how you get back), but it is NOT what's on
                        // screen — so dim it instead of implying it is.
                        thumbColor = if (live) NeonAmber.copy(alpha = 0.45f) else NeonAmber,
                        activeTrackColor = NeonAmber.copy(alpha = if (live) 0.25f else 0.6f),
                        inactiveTrackColor = MeltVoid,
                    ),
                )
                // Scrollable for the same reason the top rail is (K3-1):
                // six 48dp targets plus gaps overflow a 360dp screen.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ChromeIconButton(
                        icon = Icons.Filled.ChevronLeft,
                        contentDescription = stringResource(R.string.goovie_move_earlier),
                        color = NeonCyan,
                        selected = false,
                        enabled = selected > 0,
                        onClick = { onMove(-1) },
                        size = 38.dp,
                    )
                    ChromeIconButton(
                        icon = Icons.Filled.ChevronRight,
                        contentDescription = stringResource(R.string.goovie_move_later),
                        color = NeonCyan,
                        selected = false,
                        enabled = selected in 0 until keyframes.size - 1,
                        onClick = { onMove(1) },
                        size = 38.dp,
                    )
                    // Re-punch: the "edit this step" verb. Lit while the
                    // pin lags the live goo, so it reads as the answer to
                    // "my edits didn't land in the keyframe".
                    ChromeIconButton(
                        icon = Icons.Filled.Cached,
                        contentDescription = stringResource(R.string.goovie_update),
                        color = NeonAmber,
                        selected = stale,
                        enabled = selected >= 0,
                        onClick = onRepunch,
                        size = 38.dp,
                    )
                    ChromeIconButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.goovie_delete),
                        color = NeonTangerine,
                        selected = false,
                        enabled = selected >= 0,
                        onClick = onDelete,
                        size = 38.dp,
                    )
                    ChromeIconButton(
                        icon = Icons.Filled.Download,
                        contentDescription = stringResource(R.string.goovie_save_movie),
                        color = NeonViolet,
                        selected = false,
                        enabled = keyframes.size >= 2,
                        haptic = false,
                        onClick = { onExport(false) },
                        size = 38.dp,
                    )
                    ChromeIconButton(
                        icon = Icons.Filled.IosShare,
                        contentDescription = stringResource(R.string.goovie_share_movie),
                        color = NeonViolet,
                        selected = false,
                        enabled = keyframes.size >= 2,
                        haptic = false,
                        onClick = { onExport(true) },
                        size = 38.dp,
                    )
                }
            }
            StripHint(keyframes = keyframes, selected = selected, stale = stale, live = live)
        }
    }
}

/**
 * The line under the strip that says what to do next.
 *
 * `minLines = 2` is a floor, not a cap: it stops the panel bouncing when
 * a one-line hint follows a two-line one, which is the swap that actually
 * happens at default text size (every hint string is written to fit two
 * lines at `bodySmall` on a 360dp screen). At large accessibility font
 * scales a hint can still take a third line and nudge the canvas — that's
 * the right trade, because the alternative is a fixed height that clips
 * the text for exactly the users who most need to read it.
 */
@Composable
private fun StripHint(
    keyframes: List<Keyframe>,
    selected: Int,
    stale: Boolean,
    live: Boolean,
) {
    // Remembered: List<Keyframe> is an unstable parameter type, so this
    // composable re-runs on every GooviePanel recomposition — i.e. every
    // scrub tick and every playback frame — while the hint itself can only
    // change when one of these four does. (GLM on PR #26.)
    val hint = remember(keyframes, selected, stale, live) {
        goovieHint(keyframes, selected, stale, live)
    }
    val text = when (hint) {
        GoovieHint.EMPTY -> stringResource(R.string.goovie_empty_hint)
        GoovieHint.LIVE_PINNED -> stringResource(R.string.goovie_hint_live_pinned, selected + 1)
        GoovieHint.LIVE -> stringResource(R.string.goovie_hint_live)
        GoovieHint.NEEDS_SECOND -> stringResource(R.string.goovie_hint_needs_second)
        GoovieHint.ALL_SAME -> stringResource(R.string.goovie_hint_all_same)
        GoovieHint.STALE -> stringResource(R.string.goovie_hint_stale, selected + 1)
        GoovieHint.READY -> stringResource(R.string.goovie_hint_ready)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        minLines = 2,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** A numbered neon bead — the strip's stand-in for a thumbnail. */
@Composable
private fun KeyframeBead(
    number: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val body = if (selected) NeonAmber else MeltPanel
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
            color = if (selected) MeltVoid else Color.White.copy(alpha = 0.75f),
        )
    }
}
