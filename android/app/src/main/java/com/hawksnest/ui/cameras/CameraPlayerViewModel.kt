package com.hawksnest.ui.cameras

import androidx.lifecycle.ViewModel
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.logic.CameraEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Proxies the active [ConnectionManager] for the Ring-style camera player — on-demand live stream
 * URLs, recorded events for the timeline, and recorded-footage URLs. Demo synthesizes everything;
 * live HA reads go2rtc/Frigate. Mirrors the web `CameraPlayer`'s use of the connection seam.
 */
@HiltViewModel
class CameraPlayerViewModel @Inject constructor(
    private val connection: ConnectionManager,
) : ViewModel() {

    suspend fun liveStreamUrl(entityId: String): String? = connection.streamUrl(entityId)

    suspend fun events(camera: String, startMs: Long, endMs: Long): List<CameraEvent> =
        connection.fetchCameraEvents(camera, startMs, endMs)

    fun recordingUrl(camera: String, startMs: Long, endMs: Long): String? =
        connection.recordingUrlAt(camera, startMs, endMs)

    /** Read a (live) entity from the store — used to pull a ring-mqtt event selector's options. */
    fun entity(id: String): HassEntity? = connection.state.entities.value[id]

    /** Select which ring-mqtt event plays, then resolve the `_event` stream URL. */
    suspend fun playRingEvent(eventSelectId: String, option: String, eventStreamId: String): String? {
        connection.callService(
            "select",
            "select_option",
            ServiceData(entityId = eventSelectId, extra = mapOf("option" to option)),
        )
        return connection.streamUrl(eventStreamId)
    }
}
