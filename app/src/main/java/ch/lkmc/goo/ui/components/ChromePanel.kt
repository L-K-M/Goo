package ch.lkmc.goo.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.ui.theme.ChromeHi
import ch.lkmc.goo.ui.theme.MeltPanel
import ch.lkmc.goo.ui.theme.MeltPanelHi
import ch.lkmc.goo.ui.theme.MeltPanelLo

/**
 * Mounts content on a milled console panel: a top-lit gunmetal gradient
 * with a bright hairline along its upper lip and a dark one below, so the
 * rails read as plates bolted onto the deck rather than floating text.
 *
 * One light source for the whole app (above, slightly left) — every bevel
 * in the design system agrees on it, which is what makes flat gradients
 * read as physical hardware.
 */
fun Modifier.chromePanel(shape: Shape = RoundedCornerShape(14.dp)): Modifier = this
    // Clip first: everything the later modifiers draw — plate, hairlines,
    // content — is bounded by the plate's own silhouette.
    .clip(shape)
    .drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                0f to MeltPanelHi,
                0.18f to MeltPanel,
                1f to MeltPanelLo,
            ),
        )
        val hairline = 1.dp.toPx()
        drawLine(
            color = ChromeHi.copy(alpha = 0.45f),
            start = Offset(0f, hairline / 2f),
            end = Offset(size.width, hairline / 2f),
            strokeWidth = hairline,
        )
        drawLine(
            color = MeltPanelLo,
            start = Offset(0f, size.height - hairline / 2f),
            end = Offset(size.width, size.height - hairline / 2f),
            strokeWidth = hairline,
        )
    }
