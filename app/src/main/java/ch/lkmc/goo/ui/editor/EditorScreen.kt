package ch.lkmc.goo.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.goo.R
import ch.lkmc.goo.ui.components.CandyButton
import ch.lkmc.goo.ui.theme.CandyCyan

/**
 * The Goo room. An empty table until the warp engine lands (PLAN.md roadmap
 * #2): this placeholder proves navigation, theme, and back handling so the
 * scaffold PR reviews the real app shell.
 */
@Composable
fun EditorScreen(onBack: () -> Unit) {
    // No BackHandler: the NavHost already pops this destination on system
    // back, and an unconditional handler would defeat predictive back.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.editor_placeholder_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.editor_placeholder_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(48.dp))
            CandyButton(
                label = stringResource(R.string.editor_back),
                color = CandyCyan,
                onClick = onBack,
            )
        }
    }
}
