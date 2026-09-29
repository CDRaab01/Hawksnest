package com.hawksnest.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hawksnest.ui.components.ControlFeedbackViewModel
import com.hawksnest.ui.components.ZWaveStatusBanner
import com.hawksnest.ui.components.rememberHaptics
import com.hawksnest.ui.area.AreaDetailScreen
import com.hawksnest.ui.cameras.CameraLightbox
import com.hawksnest.ui.automations.AutomationEditScreen
import com.hawksnest.ui.automations.AutomationsScreen
import com.hawksnest.ui.devices.DevicesScreen
import com.hawksnest.ui.entity.EntityDetailScreen
import com.hawksnest.ui.history.HistoryScreen
import com.hawksnest.ui.home.HomeScreen
import com.hawksnest.ui.rooms.RoomsScreen
import com.hawksnest.ui.settings.SettingsScreen

private val bottomBarRoutes = TopLevelDestination.entries.map { it.route }.toSet()

/**
 * The single-Scaffold navigation shell: a NavHost wrapped by the bottom bar. Tab switches use
 * saveState/restoreState so each tab keeps its own back stack and scroll position.
 */
@Composable
fun AppNavGraph(
    /** Deep-link targets from notifications, widgets and shortcuts (see [com.hawksnest.push.PushNav]).
     *  Null only in previews/tests. */
    pushNav: com.hawksnest.push.PushNav? = null,
    /** The app-scoped open-camera session; the lightbox renders here at the nav-graph root (in
     *  the activity's own window, over the bottom bar) so system PiP can show it. Null only in
     *  previews/tests that don't exercise cameras. */
    cameraSession: com.hawksnest.ui.cameras.CameraSession? = null,
    feedback: ControlFeedbackViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomBarRoutes

    // Back to Home for a deep link, from anywhere. NOT the bottom bar's navigate-with-restoreState:
    // that pattern saves the stack it pops and restores the saved stack of the destination, so from
    // Settings (pushed on top of Home) it popped Settings, saved it under Home, and put it straight
    // back. A doorbell tap on the Settings screen did nothing until the next visit to Home.
    fun goHome() {
        if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id)
                launchSingleTop = true
            }
        }
    }

    // A camera opens in HomeScreen's lightbox overlay, so bring Home forward (from any screen) —
    // HomeScreen then opens the camera once its list is loaded and consumes the target.
    val pushCameraFlow = remember(pushNav) {
        pushNav?.cameraTarget
            ?: kotlinx.coroutines.flow.MutableStateFlow<com.hawksnest.push.CameraTarget?>(null)
    }
    val pushCamera by pushCameraFlow.collectAsState()
    LaunchedEffect(pushCamera) {
        if (pushCamera != null && currentRoute != Screen.Home.route) goHome()
    }

    // A device's screen always opens on top of Home, so Back returns into the app rather than out
    // to the launcher, whichever screen the app was on and whether or not it was already running.
    val entityFlow = remember(pushNav) {
        pushNav?.entityTarget ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
    val entityTarget by entityFlow.collectAsState()
    LaunchedEffect(entityTarget) {
        val id = entityTarget ?: return@LaunchedEffect
        goHome()
        navController.navigate(Screen.Entity.createRoute(id))
        pushNav?.consumeEntity()
    }

    // A plain route (Settings, from a widget that is signed out), also on top of Home.
    val routeFlow = remember(pushNav) {
        pushNav?.routeTarget ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }
    val routeTarget by routeFlow.collectAsState()
    LaunchedEffect(routeTarget) {
        val route = routeTarget ?: return@LaunchedEffect
        goHome()
        if (route != Screen.Home.route) navController.navigate(route) { launchSingleTop = true }
        pushNav?.consumeRoute()
    }

    // A triggered-alarm alert pins its text over Home; Home is where Off is.
    val alertFlow = remember(pushNav) {
        pushNav?.alertBanner ?: kotlinx.coroutines.flow.MutableStateFlow<com.hawksnest.push.AlertBanner?>(null)
    }
    val alert by alertFlow.collectAsState()
    LaunchedEffect(alert) {
        if (alert != null && currentRoute != Screen.Home.route) goHome()
    }

    // The one snackbar for control failures — the control gate makes every failed tap/slide land
    // here instead of crashing the coroutine, with a reject buzz so it's felt, not just seen.
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    LaunchedEffect(feedback) {
        feedback.errors.collect { message ->
            haptics.reject()
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize(),
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
                        onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    )
                }
                composable(Screen.Devices.route) {
                    DevicesScreen(onOpenEntity = { id -> navController.navigate(Screen.Entity.createRoute(id)) })
                }
                composable(Screen.Rooms.route) {
                    RoomsScreen(onOpenArea = { area -> navController.navigate(Screen.Area.createRoute(area)) })
                }
                composable(Screen.History.route) {
                    HistoryScreen(onOpenEntity = { id -> navController.navigate(Screen.Entity.createRoute(id)) })
                }
                composable(Screen.Settings.route) {
                    // Settings can be the only thing on the stack above Home (a widget sends the
                    // owner here to fix a token), so Back always has somewhere to go.
                    SettingsScreen(onBack = { if (!navController.popBackStack()) goHome() })
                }
                composable(Screen.Automations.route) {
                    AutomationsScreen(
                        onNew = { navController.navigate(Screen.AutomationEdit.createRoute("new")) },
                        onEdit = { id -> navController.navigate(Screen.AutomationEdit.createRoute(id)) },
                    )
                }
                composable(
                    route = Screen.AutomationEdit.route,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) {
                    AutomationEditScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Screen.Area.route,
                    arguments = listOf(navArgument("area") { type = NavType.StringType }),
                ) {
                    AreaDetailScreen(
                        onBack = { navController.popBackStack() },
                        onOpenEntity = { id -> navController.navigate(Screen.Entity.createRoute(id)) },
                    )
                }
                composable(
                    route = Screen.Entity.route,
                    arguments = listOf(navArgument("entityId") { type = NavType.StringType }),
                ) {
                    EntityDetailScreen(onBack = { navController.popBackStack() })
                }
                }

                ZWaveStatusBanner(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                )
            }
        }

        // The open-camera lightbox, hosted HERE — a sibling of the Scaffold in the activity's own
        // window — rather than in a Dialog or a nav destination. Full-bleed over the bottom bar,
        // and (the reason it moved) visible to the system PiP surface, which renders only the
        // activity window. HomeScreen still decides WHAT opens (it owns the camera list and the
        // push deep-link); this only renders whatever session is open.
        if (cameraSession != null) {
            val session by cameraSession.open.collectAsState()
            val inPip by cameraSession.inPip.collectAsState()
            session?.let { s ->
                CameraLightbox(
                    cameras = s.cameras,
                    initial = s.initial,
                    nonce = s.nonce,
                    initialEventId = s.eventId,
                    initialStart = s.start,
                    inPip = inPip,
                    onDismiss = { cameraSession.close() },
                )
            }
        }
    }
}
