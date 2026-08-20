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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    startDestination: String = Screen.Home.route,
    /**
     * An entity to open on top of the start destination — a temperature widget tap, which lands on
     * that sensor's history chart.
     *
     * Deliberately NOT passed as [startDestination]. `Screen.Entity.route` carries an argument, and
     * NavHost resolves a start destination by exact route match, so a filled-in `entity/sensor.foo`
     * would not find the `entity/{entityId}` node. Navigating instead is both correct and better:
     * Home stays underneath, so back returns to the app rather than straight out to the launcher.
     */
    openEntityId: String? = null,
    pushNav: com.hawksnest.push.PushNav? = null,
    /** The app-scoped open-camera session; the lightbox renders here at the nav-graph root (in
     *  the activity's own window, over the bottom bar) so system PiP can show it. Null only in
     *  previews/tests that don't exercise cameras. */
    cameraSession: com.hawksnest.ui.cameras.CameraSession? = null,
    feedback: ControlFeedbackViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()

    // One-shot: `rememberSaveable` survives the recomposition and configuration changes that would
    // otherwise re-run this and stack a second copy of the same screen.
    var deepLinkConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openEntityId) {
        if (openEntityId != null && !deepLinkConsumed) {
            deepLinkConsumed = true
            navController.navigate(Screen.Entity.createRoute(openEntityId))
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomBarRoutes

    // Doorbell notification tap: a specific camera opens in HomeScreen's lightbox overlay,
    // so bring Home forward (from any tab) when a deep-link arrives — HomeScreen then opens
    // the camera and consumes the target. Only navigates if we're not already on Home.
    val pushCameraFlow = remember(pushNav) {
        pushNav?.cameraTarget
            ?: kotlinx.coroutines.flow.MutableStateFlow<com.hawksnest.push.CameraTarget?>(null)
    }
    val pushCamera by pushCameraFlow.collectAsState()
    LaunchedEffect(pushCamera) {
        if (pushCamera != null && currentRoute != Screen.Home.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
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
                    startDestination = startDestination,
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
                    SettingsScreen()
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
                    inPip = inPip,
                    onDismiss = { cameraSession.close() },
                )
            }
        }
    }
}
