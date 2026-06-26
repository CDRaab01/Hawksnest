package com.hawksnest.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hawksnest.ui.area.AreaDetailScreen
import com.hawksnest.ui.history.HistoryScreen
import com.hawksnest.ui.home.HomeScreen
import com.hawksnest.ui.cameras.CamerasScreen
import com.hawksnest.ui.rooms.RoomsScreen
import com.hawksnest.ui.settings.SettingsScreen

private val bottomBarRoutes = TopLevelDestination.entries.map { it.route }.toSet()

/**
 * The single-Scaffold navigation shell: a NavHost wrapped by the bottom bar. Tab switches use
 * saveState/restoreState so each tab keeps its own back stack and scroll position.
 */
@Composable
fun AppNavGraph(startDestination: String = Screen.Home.route) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomBarRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                PulseBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { dest ->
                        navController.navigate(dest.navRoute) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onOpenRooms = {
                        navController.navigate(Screen.Rooms.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Screen.Cameras.route) { CamerasScreen() }
            composable(Screen.Rooms.route) {
                RoomsScreen(onOpenArea = { area -> navController.navigate(Screen.Area.createRoute(area)) })
            }
            composable(Screen.History.route) { HistoryScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(
                route = Screen.Area.route,
                arguments = listOf(navArgument("area") { type = NavType.StringType }),
            ) {
                AreaDetailScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
