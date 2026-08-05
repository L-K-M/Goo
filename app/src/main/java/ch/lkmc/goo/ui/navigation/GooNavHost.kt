package ch.lkmc.goo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ch.lkmc.goo.ui.editor.EditorScreen
import ch.lkmc.goo.ui.home.HomeScreen
import kotlinx.serialization.Serializable

// Type-safe routes. The rooms metaphor (PLAN.md §6): Home is the In room,
// Editor is the Goo room; Out lives inside the editor as a sheet.
@Serializable
object HomeRoute

@Serializable
data class EditorRoute(val imageUri: String)

@Composable
fun GooNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenImage = { uri -> navController.navigate(EditorRoute(uri.toString())) },
            )
        }
        composable<EditorRoute> {
            // navigateUp, not popBackStack: a second tap during the pop
            // transition would otherwise pop the start destination too and
            // leave an empty NavHost; navigateUp no-ops at the root.
            EditorScreen(onBack = { navController.navigateUp() })
        }
    }
}
