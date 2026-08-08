package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import ch.lkmc.goo.engine.core.GlobalWobble
import ch.lkmc.goo.engine.core.LeverWobble
import ch.lkmc.goo.ui.components.chromePanel
import ch.lkmc.goo.ui.components.ChromeLever
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonViolet
import ch.lkmc.goo.ui.theme.NeonAmber
import ch.lkmc.goo.ui.theme.NeonLime
import ch.lkmc.goo.ui.theme.NeonTangerine
import ch.lkmc.goo.ui.theme.NeonMagenta
import ch.lkmc.goo.ui.theme.MeltVoid

/**
 * The global-effects rig (PLAN.md §4.1): six bipolar levers, live
 * on the whole image, composed over the painted goo. Center = identity;
 * each lever wears its own neon and snaps to the center detent.
 */
@Composable
fun LeversPanel(
    globals: GlobalParams,
    onChange: (GlobalParams) -> Unit,
    /** The modulation rig (proposal 0009), one entry per lever. */
    wobble: GlobalWobble,
    /**
     * The cycle counts this document may offer. Computed from the loop
     * it would export, so the high rates simply are not present on a
     * short strip — see [GlobalWobble.MAX_HZ].
     */
    rates: List<Int>,
    onWobble: (index: Int, LeverWobble) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .chromePanel(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Lever(R.string.lever_bulge, globals.bulge, 0, wobble, rates, onWobble, NeonMagenta) {
            onChange(globals.copy(bulge = it))
        }
        Lever(R.string.lever_twirl, globals.twirl, 1, wobble, rates, onWobble, NeonCyan) {
            onChange(globals.copy(twirl = it))
        }
        Lever(R.string.lever_squeeze, globals.squeeze, 2, wobble, rates, onWobble, NeonTangerine) {
            onChange(globals.copy(squeeze = it))
        }
        Lever(R.string.lever_stretch, globals.stretch, 3, wobble, rates, onWobble, NeonLime) {
            onChange(globals.copy(stretch = it))
        }
        Lever(R.string.lever_spike, globals.spike, 4, wobble, rates, onWobble, NeonAmber) {
            onChange(globals.copy(spike = it))
        }
        Lever(R.string.lever_static, globals.static, 5, wobble, rates, onWobble, NeonViolet) {
            onChange(globals.copy(static = it))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                enabled = !globals.isIdentity || !wobble.isStill,
                onClick = {
                    onChange(GlobalParams())
                    // Zero all means still, too: a panel that reads all
                    // centred while the picture keeps breathing is a lie.
                    for (i in 0 until GlobalWobble.SIZE) onWobble(i, LeverWobble())
                },
            ) { Text(stringResource(R.string.lever_zero_all)) }
        }
    }
}

@Composable
private fun Lever(
    labelRes: Int,
    value: Float,
    index: Int,
    wobble: GlobalWobble,
    rates: List<Int>,
    onWobble: (Int, LeverWobble) -> Unit,
    color: Color,
    onChange: (Float) -> Unit,
) {
    val label = stringResource(labelRes)
    val lever = wobble.levers.getOrNull(index) ?: LeverWobble()
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
        // The wobble knob: tap to step the cycle count, which also sets a
        // usable depth the first time so one tap makes the picture move.
        // Deliberately a stepper and not a slider — the rates are
        // integers because a whole number of cycles is what makes the
        // loop seamless, and a continuous control would imply otherwise.
        WobbleKnob(
            label = label,
            lever = lever,
            rates = rates,
            color = color,
            onChange = { onWobble(index, it) },
        )
    }
}

/** How deep a wobble goes the first time it is switched on. */
private const val FIRST_DEPTH = 0.35f

@Composable
private fun WobbleKnob(
    label: String,
    lever: LeverWobble,
    rates: List<Int>,
    color: Color,
    onChange: (LeverWobble) -> Unit,
) {
    // A document too short to loop offers nothing: there is no length for
    // a cycle count to be measured against, so the knob is simply absent
    // rather than present and inert.
    val offered = rates.filter { it > 0 }
    // Show what the encoder will actually use. Shortening the strip
    // lowers the cap under a rate that was legal when it was dialled,
    // and a knob reading 4 while the movie renders 2 is a knob lying
    // about the document.
    val shown = lever.rate.coerceAtMost(offered.lastOrNull() ?: 0)
    val on = shown > 0 && lever.depth > 0f
    val description = stringResource(R.string.lever_wobble, label)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(34.dp)
            .clip(CircleShape)
            .background(if (on) color.copy(alpha = 0.35f) else MeltVoid)
            .border(1.dp, if (on) color else color.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = offered.isNotEmpty()) {
                val next = offered.getOrNull(offered.indexOf(shown) + 1) ?: 0
                onChange(
                    LeverWobble(
                        rate = next,
                        // One tap has to make the picture move, or the
                        // knob reads as broken the first time it is used.
                        depth = if (next == 0) {
                            lever.depth
                        } else {
                            lever.depth.takeIf { it > 0f } ?: FIRST_DEPTH
                        },
                    ),
                )
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (shown == 0) "~" else "$shown",
            style = MaterialTheme.typography.labelMedium,
            color = if (offered.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
