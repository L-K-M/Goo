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
object EditorRoute

@Composable
fun GooNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(onEnterEditor = { navController.navigate(EditorRoute) })
        }
        composable<EditorRoute> {
            EditorScreen(onBack = { navController.popBackStack() })
        }
    }
}
