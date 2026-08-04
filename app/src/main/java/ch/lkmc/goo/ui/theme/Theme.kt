package ch.lkmc.goo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always dark: the editor is a lit table in a dark room, in both system
// themes — photos and candy buttons pop against it, and there is no "light
// mode" of a 90s funware table. (KPT Goo had exactly one look, too.)
private val GooColorScheme = darkColorScheme(
    primary = CandyPink,
    onPrimary = GooTableShadow,
    secondary = CandyCyan,
    onSecondary = GooTableShadow,
    tertiary = CandyLime,
    onTertiary = GooTableShadow,
    background = GooTable,
    onBackground = GooOnDark,
    surface = GooTableRaised,
    onSurface = GooOnDark,
    surfaceVariant = GooTableRaised,
    onSurfaceVariant = GooOnDarkDim,
    error = CandyOrange,
    onError = GooTableShadow,
)

@Composable
fun GooTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GooColorScheme,
        typography = GooTypography,
        content = content,
    )
}
