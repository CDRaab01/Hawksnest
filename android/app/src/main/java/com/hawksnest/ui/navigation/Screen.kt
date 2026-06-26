package com.hawksnest.ui.navigation

import android.net.Uri

/**
 * Navigation routes. The five bottom-bar destinations (Home · Cameras · Rooms · History · Settings)
 * plus the drill-in screens (Area, Entity). Mirrors the web app's react-router paths.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Devices : Screen("devices")
    data object Cameras : Screen("cameras")
    data object Rooms : Screen("rooms")
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object Automations : Screen("automations")

    data object Area : Screen("area/{area}") {
        // Area names contain spaces ("Front Door") — encode for the path segment.
        fun createRoute(area: String) = "area/${Uri.encode(area)}"
    }
    data object Entity : Screen("entity/{entityId}") {
        fun createRoute(entityId: String) = "entity/${Uri.encode(entityId)}"
    }
}
