package ch.lkmc.goo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.goo.ui.theme.MeltVoid
import ch.lkmc.goo.ui.theme.NeonCyan
import ch.lkmc.goo.ui.theme.NeonMagenta
import ch.lkmc.goo.ui.theme.chromePlate

/**
 * The product wordmark: chrome-plated type with a neon shadow burning
 * behind it, and the model number boxed off beneath like a spec plate.
 *
 * Chrome is a vertical gradient painted straight into the glyphs
 * ([TextStyle.brush]) rather than an image — it scales with the user's
 * font size and ships no assets. The neon copy underneath is offset by a
 * couple of dp: a poor man's glow that works on every API level, unlike
 * `Modifier.blur` (RenderEffect, API 31+, silently a no-op below).
 *
 * The type is measured against the width it actually gets and stepped
 * down until it fits: nine wide-tracked caps overflow a 360dp phone at
 * full size, and overflow much sooner at a 200% font scale. Both copies
 * render at the one [fitted] size, so the shadow can never drift out of
 * register with the plate.
 *
 * The whole lockup reads as one label to TalkBack — "Meltorama 2000" —
 * because letter-spaced chrome type is exactly the kind of thing screen
 * readers otherwise spell out.
 */
@Composable
fun Wordmark(
    name: String,
    model: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.displayMedium
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$name $model" },
        contentAlignment = Alignment.Center,
    ) {
        val available = constraints.maxWidth
        val fitted = remember(available, name, style) {
            var candidate = style
            // Shrink type and tracking together — dropping only the size
            // would leave the letters swimming in their original spacing.
            while (
                candidate.fontSize > MIN_SIZE &&
                measurer.measure(AnnotatedString(name), candidate).size.width > available
            ) {
                candidate = candidate.copy(
                    fontSize = candidate.fontSize * SHRINK,
                    letterSpacing = candidate.letterSpacing * SHRINK,
                )
            }
            candidate
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name,
                    style = fitted,
                    color = NeonMagenta.copy(alpha = 0.55f),
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = 3.dp, start = 2.dp)
                        .clearAndSetSemantics { },
                )
                Text(
                    text = name,
                    style = fitted.copy(brush = chromePlate()),
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text = model,
                style = MaterialTheme.typography.labelLarge,
                color = MeltVoid,
                maxLines = 1,
                modifier = Modifier
                    .background(NeonCyan, RoundedCornerShape(4.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** Floor for the shrink loop: below this the mark stops being a mark. */
private val MIN_SIZE = 18.sp

private const val SHRINK = 0.92f
