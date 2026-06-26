package com.hawksnest.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The bottom-bar destinations, in on-screen order: Devices · Rooms · HOME (large center circle) ·
 * History · Settings. Cameras fold into Home, so there's no Cameras tab. [center] marks the
 * prominent raised Home circle.
 */
enum class TopLevelDestination(
    val route: String,
    val navRoute: String,
    val label: String,
    val icon: ImageVector,
    val iconOutlined: ImageVector,
    val center: Boolean = false,
) {
    DEVICES(
        route = Screen.Devices.route,
        navRoute = Screen.Devices.route,
        label = "Devices",
        icon = Icons.Filled.ToggleOn,
        iconOutlined = Icons.Outlined.ToggleOn,
    ),
    ROOMS(
        route = Screen.Rooms.route,
        navRoute = Screen.Rooms.route,
        label = "Rooms",
        icon = Icons.Filled.GridView,
        iconOutlined = Icons.Outlined.GridView,
    ),
    HOME(
        route = Screen.Home.route,
        navRoute = Screen.Home.route,
        label = "Home",
        icon = Icons.Filled.Home,
        iconOutlined = Icons.Outlined.Home,
        center = true,
    ),
    HISTORY(
        route = Screen.History.route,
        navRoute = Screen.History.route,
        label = "History",
        icon = Icons.Filled.History,
        iconOutlined = Icons.Outlined.History,
    ),
    SETTINGS(
        route = Screen.Settings.route,
        navRoute = Screen.Settings.route,
        label = "Settings",
        icon = Icons.Filled.Settings,
        iconOutlined = Icons.Outlined.Settings,
    ),
}
