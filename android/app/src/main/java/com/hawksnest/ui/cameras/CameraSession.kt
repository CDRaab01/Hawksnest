package com.hawksnest.ui.cameras

import com.hawksnest.core.logic.shouldEnterPip
import com.hawksnest.ui.home.CameraUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one open-camera session, app-scoped — the camera stack's analogue of [com.hawksnest.push.PushNav].
 *
 * Exists because system picture-in-picture forced the lightbox out of HomeScreen's local state:
 * the PiP surface shows only the activity's own window (a Compose `Dialog` is a separate window),
 * so the lightbox renders at the nav-graph root, and *whether* it is open — plus the facts
 * MainActivity needs synchronously in `onUserLeaveHint` (is the playback live? what aspect is the
 * video?) — live here. Writers: HomeScreen opens/closes and refreshes the switcher list,
 * CameraPlayer reports live-vs-recorded and the source video size, MainActivity alone writes
 * [inPip] from `onPictureInPictureModeChanged`.
 */
@Singleton
class CameraSession @Inject constructor() {

    /** An open lightbox. [nonce] bumps on every [open] so the host can reset per-open state
     *  (the switched-to camera, a deep-linked event) even when a doorbell push retargets an
     *  already-open lightbox with the same camera. */
    data class Open(
        val cameras: List<CameraUi>,
        val initial: CameraUi,
        /** Frigate event to open on, from a tapped camera alert. Null = open live. */
        val eventId: String?,
        val nonce: Int,
    )

    private val _open = MutableStateFlow<Open?>(null)
    val open: StateFlow<Open?> = _open.asStateFlow()

    /** Live vs recorded, reported by CameraPlayer (`playhead == null`). True while nothing is
     *  open — a freshly-opened player starts live. */
    private val _isLive = MutableStateFlow(true)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()

    /** The live video's (width, height) post-rotation, from the WebRTC renderers' first frame.
     *  Null until known; the PiP params fall back to 16:9. */
    private val _videoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoSize: StateFlow<Pair<Int, Int>?> = _videoSize.asStateFlow()

    /** Whether the activity is currently in PiP. Written ONLY by MainActivity. */
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    fun open(cameras: List<CameraUi>, initial: CameraUi, eventId: String? = null) {
        _isLive.value = true
        _videoSize.value = null
        _open.value = Open(cameras, initial, eventId, nonce = (_open.value?.nonce ?: 0) + 1)
    }

    /** Refresh the in-player switcher's camera list while Home recomposes underneath. */
    fun updateCameras(cameras: List<CameraUi>) {
        _open.value = _open.value?.copy(cameras = cameras)
    }

    fun close() {
        _open.value = null
        _isLive.value = true
        _videoSize.value = null
    }

    fun reportLive(isLive: Boolean) {
        _isLive.value = isLive
    }

    fun reportVideoSize(width: Int, height: Int) {
        _videoSize.value = width to height
    }

    fun setInPip(inPip: Boolean) {
        _inPip.value = inPip
    }

    /** Synchronous read for `onUserLeaveHint`: minimize into PiP right now? */
    fun wantsPip(): Boolean = shouldEnterPip(_open.value != null, _isLive.value)
}
