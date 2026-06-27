package com.hawksnest.ui.cameras

import androidx.lifecycle.ViewModel
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.WebRtcHandle
import com.hawksnest.core.ha.WebRtcSignal
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.CameraEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    /** True when [entityId] advertises go2rtc WebRTC and the source can negotiate it. */
    fun canWebRtc(entityId: String): Boolean =
        connection.supportsWebRtc() &&
            entity(entityId)?.stringAttr("frontend_stream_type") == "web_rtc"

    /** Begin a WebRTC negotiation; returns a handle to tear it down (null when unsupported). */
    suspend fun webrtcOffer(
        entityId: String,
        offerSdp: String,
        onSignal: (WebRtcSignal) -> Unit,
    ): WebRtcHandle? = connection.webrtcOffer(entityId, offerSdp, onSignal)

    /** Push a local trickle ICE candidate for an in-flight session. */
    suspend fun webrtcCandidate(
        entityId: String,
        sessionId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) = connection.webrtcCandidate(entityId, sessionId, candidate, sdpMid, sdpMLineIndex)

    suspend fun events(camera: String, startMs: Long, endMs: Long): List<CameraEvent> =
        connection.fetchCameraEvents(camera, startMs, endMs)

    fun recordingUrl(camera: String, startMs: Long, endMs: Long): String? =
        connection.recordingUrlAt(camera, startMs, endMs)

    /** Read a (live) entity from the store — used to pull a ring-mqtt event selector's options. */
    fun entity(id: String): HassEntity? = connection.state.entities.value[id]

    /** The connected origin (HA / Hawksnest nginx) — the talk feature derives the go2rtc WS URL from it. */
    fun baseUrl(): String = connection.state.baseUrl.value

    /** Reactive on/off for a ring-mqtt siren switch (false when absent/off). */
    fun sirenOn(entityId: String): Flow<Boolean> =
        connection.state.entities.map { it[entityId]?.state == "on" }

    /** Sound or silence a camera's siren (ring-mqtt `switch.<base>_siren`). */
    suspend fun setSiren(entityId: String, on: Boolean) {
        connection.callService(
            "switch",
            if (on) "turn_on" else "turn_off",
            ServiceData(entityId = entityId),
        )
    }

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
