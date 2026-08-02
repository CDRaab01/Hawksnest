package com.hawksnest.ui.navigation

import android.net.Uri

/**
 * Navigation routes. The five bottom-bar destinations (Devices · Rooms · Home · History ·
 * Automations) plus Settings (reached from the Home gear) and the drill-in screens (Area, Entity,
 * AutomationEdit). Mirrors the web app's react-router paths.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Devices : Screen("devices")
    // No Cameras route: cameras live on Home (the 2-up grid and the lightbox) rather than
    // getting a tab of their own. A `Cameras` route and a placeholder CamerasScreen existed
    // here from Phase 0, registered in no NavHost and referenced by nothing — removed 2026-08-02.
    data object Rooms : Screen("rooms")
    data object History : Screen("history")
    data object Settings : Screen("settings")
    data object Automations : Screen("automations")

    data object AutomationEdit : Screen("automations/{id}") {
        /** Pass "new" to start a fresh automation, or an existing HA config id to edit. */
        fun createRoute(id: String) = "automations/${Uri.encode(id)}"
    }

    data object Area : Screen("area/{area}") {
        // Area names contain spaces ("Front Door") — encode for the path segment.
        fun createRoute(area: String) = "area/${Uri.encode(area)}"
    }
    data object Entity : Screen("entity/{entityId}") {
        fun createRoute(entityId: String) = "entity/${Uri.encode(entityId)}"
    }
}
