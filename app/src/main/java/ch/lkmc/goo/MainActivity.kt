package ch.lkmc.goo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.lkmc.goo.ui.navigation.GooNavHost
import ch.lkmc.goo.ui.theme.GooTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-bleed like the original: the goo table owns the whole screen.
        // Forced-dark bar styles, not the no-arg auto(): the theme is always
        // dark, but auto() keys icon contrast on the SYSTEM night mode and
        // would draw dark icons over the near-black table on light-mode
        // devices (the family's documented edge-to-edge bug).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            GooTheme {
                GooNavHost()
            }
        }
    }
}
