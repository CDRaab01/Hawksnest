package com.hawksnest.ui.navigation

/**
 * Navigation routes. The four bottom-bar destinations (Home · Cameras · History · Settings) plus
 * the drill-in screens (Area, Entity) added in Phase 1/2. Mirrors the web app's react-router paths.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Cameras : Screen("cameras")
    data object History : Screen("history")
    data object Settings : Screen("settings")

    data object Area : Screen("area/{area}") {
        fun createRoute(area: String) = "area/$area"
    }
    data object Entity : Screen("entity/{entityId}") {
        fun createRoute(entityId: String) = "entity/$entityId"
    }
}
