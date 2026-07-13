package com.hawksnest.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tiny app-scoped bus for "a tapped notification wants to open camera X".
 *
 * A notification tap can't just carry a nav route because a specific camera opens
 * in the CameraLightbox overlay on Home, not via a NavHost destination. So the
 * tap sets [cameraTarget] here (from MainActivity, cold start via the launch intent
 * or warm via onNewIntent); the nav shell reacts by bringing Home forward, and
 * HomeScreen opens the lightbox for that camera once the camera list is loaded,
 * then [consume]s it so it fires once.
 */
@Singleton
class PushNav @Inject constructor() {
    private val _cameraTarget = MutableStateFlow<String?>(null)
    /** Logical camera id (`camera.<base>`) a tap wants opened, or null. */
    val cameraTarget: StateFlow<String?> = _cameraTarget.asStateFlow()

    fun openCamera(cameraId: String) {
        _cameraTarget.value = cameraId
    }

    fun consume() {
        _cameraTarget.value = null
    }
}
