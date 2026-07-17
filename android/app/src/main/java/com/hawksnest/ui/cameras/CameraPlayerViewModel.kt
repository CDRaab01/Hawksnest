package com.hawksnest.ui.cameras

import androidx.lifecycle.ViewModel
import com.hawksnest.core.ha.ConnectionManager
import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.ServiceData
import com.hawksnest.core.ha.WebRtcHandle
import com.hawksnest.core.ha.WebRtcSignal
import com.hawksnest.core.ha.stringAttr
import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.ringEventIdToMs
import com.hawksnest.core.logic.ringEventOptions
import com.hawksnest.core.logic.ringEventsFromOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Where the player is with a selected ring clip's stream URL. [Failed] is a terminal, *visible*
 * state (HA timed out / errored / the event rotated out of ring-mqtt's ~5-slot selector) — never
 * left looking like it's still loading. Mirrors the web `RingClipState`.
 */
sealed interface RingClipState {
    data object Idle : RingClipState
    data class Resolving(val clipId: String) : RingClipState
    data class Ready(val clipId: String, val url: String) : RingClipState
    data class Failed(val clipId: String) : RingClipState
}

/** Home Assistant `CameraEntityFeature.STREAM` bit (camera supports a live video stream). */
private const val CAMERA_FEATURE_STREAM = 2

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

    /**
     * True when the app should attempt a WebRTC live session for [entityId].
     *
     * Modern HA (2024+) dropped the `frontend_stream_type` state attribute and serves WebRTC via its
     * bundled go2rtc for any STREAM-capable camera — verified live: these ring cameras report
     * `frontend_stream_types: ["web_rtc"]` over `camera/capabilities`. So gate on STREAM support, not
     * the now-absent attribute.
     *
     * Crucially we attempt WebRTC when `supported_features` is *present-with-STREAM* OR *absent*, and
     * only bail on a definite `0` (image-only). A battery camera's live entity churns and momentarily
     * publishes without attributes; the old `?: 0` treated that as "not streamable", so during a live
     * view the transport flickered off WebRTC after ~40ms and settled on the stale snapshot (observed
     * on-device: renderer created then released before it could negotiate). Treating "absent" as
     * "worth a try" keeps the WebRTC player stable so the negotiation completes. The player still
     * steps down to HLS/snapshot if a negotiation genuinely fails (`webRtcFailed`).
     */
    fun canWebRtc(entityId: String): Boolean {
        if (!connection.supportsWebRtc()) return false
        val features = entity(entityId)?.stringAttr("supported_features") ?: return true
        return (features.toIntOrNull() ?: CAMERA_FEATURE_STREAM) and CAMERA_FEATURE_STREAM != 0
    }

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

    /**
     * Ring timeline events at their REAL times: the selector's current options (`Motion N`, kept as
     * the playable handles) paired with real event times decoded from the `eventId` attribute history
     * (Ring Snowflake). Falls back to even spacing per-option when a time can't be recovered, so the
     * timeline degrades gracefully rather than emptying.
     */
    suspend fun ringEvents(eventSelectId: String, cameraName: String, startMs: Long, endMs: Long): List<CameraEvent> {
        val options = ringEventOptions(entity(eventSelectId))
        if (options.isEmpty()) return emptyList()
        val timesDesc = runCatching { connection.fetchAttributeHistory(eventSelectId, startMs, endMs, "eventId") }
            .getOrDefault(emptyList())
            .map { it.second }.distinct()
            .mapNotNull { ringEventIdToMs(it) }
            .filter { it in startMs..endMs }
            .sortedDescending()
        return ringEventsFromOptions(options, timesDesc, cameraName, endMs)
    }

    fun recordingUrl(camera: String, startMs: Long, endMs: Long): String? =
        connection.recordingUrlAt(camera, startMs, endMs)

    /** Read a (live) entity from the store — used to pull a ring-mqtt event selector's options. */
    fun entity(id: String): HassEntity? = connection.state.entities.value[id]

    /** The connected origin (HA / Hawksnest nginx) — the talk feature derives the go2rtc WS URL from it. */
    fun baseUrl(): String = connection.state.baseUrl.value

    /** Reactive on/off for a ring-mqtt siren switch (false when absent/off). */
    fun sirenOn(entityId: String): Flow<Boolean> =
        connection.state.entities.map { it[entityId]?.state == "on" }

    /** Sound or silence a camera's siren (ring-mqtt `switch.<base>_siren`), crash-safe via the
     *  control gate — a failed siren call must never crash the player. */
    fun setSiren(entityId: String, on: Boolean) {
        connection.control(entityId, if (on) "turn_on" else "turn_off", label = "Siren")
    }

    /**
     * Select which ring-mqtt event plays, then resolve the `_event` stream URL. The select is
     * best-effort (a rotated-out option fails, but the event stream may still hold footage); a null
     * or thrown stream resolution lands in [RingClipState.Failed] — an honest, retryable error
     * instead of a stuck loader. Never throws (this runs in produceState's coroutine; an uncaught
     * throw would crash the whole player).
     */
    suspend fun resolveRingClip(
        eventSelectId: String,
        option: String,
        eventStreamId: String,
    ): RingClipState = runCatching {
        runCatching {
            connection.callService(
                "select",
                "select_option",
                ServiceData(entityId = eventSelectId, extra = mapOf("option" to option)),
            )
        }
        val url = connection.streamUrl(eventStreamId)
        if (url != null) RingClipState.Ready(option, url) else RingClipState.Failed(option)
    }.getOrElse { e ->
        // Cancellation must propagate (produceState relaunching on a key change),
        // not masquerade as a failed clip.
        if (e is kotlinx.coroutines.CancellationException) throw e
        RingClipState.Failed(option)
    }
}
