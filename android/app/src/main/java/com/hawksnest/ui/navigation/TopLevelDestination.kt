package com.hawksnest.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The five bottom-bar destinations — security-forward: Home (status at a glance) · Cameras · Rooms ·
 * History (activity timeline) · Settings. Material 3 caps the bar at 3–5.
 */
enum class TopLevelDestination(
    val route: String,
    val navRoute: String,
    val label: String,
    val icon: ImageVector,
    val iconOutlined: ImageVector,
) {
    HOME(
        route = Screen.Home.route,
        navRoute = Screen.Home.route,
        label = "Home",
        icon = Icons.Filled.Home,
        iconOutlined = Icons.Outlined.Home,
    ),
    CAMERAS(
        route = Screen.Cameras.route,
        navRoute = Screen.Cameras.route,
        label = "Cameras",
        icon = Icons.Filled.Videocam,
        iconOutlined = Icons.Outlined.Videocam,
    ),
    ROOMS(
        route = Screen.Rooms.route,
        navRoute = Screen.Rooms.route,
        label = "Rooms",
        icon = Icons.Filled.GridView,
        iconOutlined = Icons.Outlined.GridView,
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
