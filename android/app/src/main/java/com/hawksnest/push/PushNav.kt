package com.hawksnest.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a tapped notification wants opened: a camera, and optionally the exact
 * moment that triggered the alert.
 *
 * [eventId] is a Frigate event id carried in the notification's `click` URL. Null
 * for a doorbell/alarm tap, which has no single moment to land on.
 */
data class CameraTarget(val cameraId: String, val eventId: String? = null)

/**
 * A tiny app-scoped bus for "a tapped notification wants to open camera X".
 *
 * A notification tap can't just carry a nav route because a specific camera opens
 * in the CameraLightbox overlay, not via a NavHost destination. So the tap sets
 * [cameraTarget] here (from MainActivity, cold start via the launch intent or warm
 * via onNewIntent); the nav shell reacts by bringing Home forward, and HomeScreen
 * opens the lightbox for that camera once the camera list is loaded — via
 * CameraSession, which renders it at the nav-graph root — then [consume]s it so it
 * fires once.
 */
@Singleton
class PushNav @Inject constructor() {
    private val _cameraTarget = MutableStateFlow<CameraTarget?>(null)
    /** The camera (and optional moment) a tap wants opened, or null. */
    val cameraTarget: StateFlow<CameraTarget?> = _cameraTarget.asStateFlow()

    fun openCamera(cameraId: String, eventId: String? = null) {
        _cameraTarget.value = CameraTarget(cameraId, eventId)
    }

    fun consume() {
        _cameraTarget.value = null
    }
}
