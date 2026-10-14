package com.hawksnest.push

import com.hawksnest.core.logic.CameraStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a tapped notification or button wants opened: a camera, optionally the exact moment that
 * triggered the alert, and how the player should start.
 *
 * [eventId] is a Frigate event id carried in the notification's `click` URL. Null for a
 * doorbell/alarm tap, which has no single moment to land on.
 */
data class CameraTarget(
    val cameraId: String,
    val eventId: String? = null,
    val start: CameraStart = CameraStart.LIVE,
    /** When the tap asked for it (epoch ms). See [isFresh]. */
    val atMs: Long = System.currentTimeMillis(),
) {
    /**
     * Whether the request is still worth acting on. Home opens the camera once its camera list has
     * loaded; with HA unreachable that can be minutes later, and a camera popping open long after
     * the tap reads as the app doing something on its own. Past [CAMERA_TARGET_TTL_MS] the request
     * is dropped and the owner stays where they are.
     */
    fun isFresh(nowMs: Long): Boolean = nowMs - atMs <= CAMERA_TARGET_TTL_MS
}

/** A minute: a cold start plus a slow first connect fits well inside it. */
const val CAMERA_TARGET_TTL_MS = 60_000L

/** A notification's own words, pinned over Home when an alarm that went off is tapped. */
data class AlertBanner(val title: String, val body: String)

/**
 * The app-scoped bus for everything that opens the app somewhere specific: notification taps and
 * buttons, widget taps, the widgets' "fix it in Settings" errors.
 *
 * None of these can simply be a start destination. A camera opens in the lightbox overlay, not a
 * route; a device screen belongs on top of Home so Back returns into the app; and all of them can
 * arrive while the app is already open on some other screen. So MainActivity turns an intent into
 * a target here, from onCreate or onNewIntent alike, and the nav shell ([AppNavGraph]) acts on it
 * from whatever screen is showing, then the consumer clears it so it fires once.
 */
@Singleton
class PushNav @Inject constructor() {
    private val _cameraTarget = MutableStateFlow<CameraTarget?>(null)
    /** The camera (and optional moment) a tap wants opened, or null. */
    val cameraTarget: StateFlow<CameraTarget?> = _cameraTarget.asStateFlow()

    fun openCamera(cameraId: String, eventId: String? = null, start: CameraStart = CameraStart.LIVE) {
        _cameraTarget.value = CameraTarget(cameraId, eventId, start)
    }

    fun consume() {
        _cameraTarget.value = null
    }

    private val _entityTarget = MutableStateFlow<String?>(null)
    /** An entity whose screen a tap wants opened, or null. */
    val entityTarget: StateFlow<String?> = _entityTarget.asStateFlow()

    fun openEntity(entityId: String) {
        _entityTarget.value = entityId
    }

    fun consumeEntity() {
        _entityTarget.value = null
    }

    private val _routeTarget = MutableStateFlow<String?>(null)
    /** A plain route a tap wants opened (Settings, from a widget that is signed out), or null. */
    val routeTarget: StateFlow<String?> = _routeTarget.asStateFlow()

    fun openRoute(route: String) {
        _routeTarget.value = route
    }

    fun consumeRoute() {
        _routeTarget.value = null
    }

    private val _alertBanner = MutableStateFlow<AlertBanner?>(null)
    /** The alert pinned over Home until dismissed. Setting it also brings Home forward. */
    val alertBanner: StateFlow<AlertBanner?> = _alertBanner.asStateFlow()

    fun showAlert(title: String, body: String) {
        _alertBanner.value = AlertBanner(title, body)
    }

    fun dismissAlert() {
        _alertBanner.value = null
    }
}
