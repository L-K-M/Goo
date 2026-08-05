package ch.lkmc.goo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark: the editor is a lit console in a dark room, in both system
// themes — photos and neon controls pop against gunmetal, and there is no
// "light mode" of a 90s funware deck. (KPT Goo had exactly one look, too.)
private val MeltoramaColorScheme = darkColorScheme(
    primary = NeonMagenta,
    onPrimary = MeltVoid,
    secondary = NeonCyan,
    onSecondary = MeltVoid,
    tertiary = NeonLime,
    onTertiary = MeltVoid,
    background = MeltDeck,
    onBackground = MeltOnDark,
    surface = MeltPanel,
    onSurface = MeltOnDark,
    surfaceVariant = MeltPanel,
    onSurfaceVariant = MeltOnDarkDim,
    error = NeonTangerine,
    onError = MeltVoid,
)

@Composable
fun MeltoramaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeltoramaColorScheme,
        typography = MeltoramaTypography,
        content = content,
    )
}
