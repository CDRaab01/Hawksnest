package com.hawksnest.core.ha

import com.hawksnest.core.logic.CameraEvent
import com.hawksnest.core.logic.LogEvent

/** One historical sample for an entity. [t] is epoch milliseconds; [state] is the raw HA state. */
data class HistoryPoint(val t: Long, val state: String)

/** One message from HA's `camera/webrtc/offer` negotiation stream (served by go2rtc/ring-mqtt). */
sealed interface WebRtcSignal {
    /** The session id to tag our outgoing trickle ICE candidates with. */
    data class Session(val sessionId: String) : WebRtcSignal
    /** The remote SDP answer. */
    data class Answer(val sdp: String) : WebRtcSignal
    /** A remote trickle ICE candidate. */
    data class Candidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int) : WebRtcSignal
    /** Negotiation failed — the player steps down to HLS/MJPEG/snapshot. */
    data object Error : WebRtcSignal
}

/** A live WebRTC negotiation handle; [close] unsubscribes the HA stream and frees the session. */
fun interface WebRtcHandle {
    fun close()
}

/** Optional service-call data. `entity_id` targets the entity; the rest is service data. */
data class ServiceData(
    val entityId: String? = null,
    val extra: Map<String, Any?> = emptyMap(),
)

/**
 * A data Source feeds the entity store and (optionally) carries out control actions. The fixture
 * source simulates everything locally; the live HA source (WebSocket) lands behind the same
 * interface, so no screen or card changes when we swap. Ported from `src/store/source.ts`.
 */
interface Source {
    suspend fun start()
    fun stop()

    /**
     * Perform an HA service call (e.g. lock.lock, light.turn_on). The fixture source simulates it
     * locally; the live source forwards it to HA, whose state echo reconciles the store —
     * **non-optimistic**. Throws if the source can't perform writes.
     */
    suspend fun callService(domain: String, service: String, data: ServiceData = ServiceData()) {
        throw UnsupportedOperationException("This source cannot perform writes.")
    }

    /** Fetch recent state history for one entity over the last [hours]. */
    suspend fun fetchHistory(entityId: String, hours: Int): List<HistoryPoint> {
        throw UnsupportedOperationException("This source cannot provide history.")
    }

    /** Fetch the logbook over `[startMs, endMs]`, optionally narrowed to specific entities. */
    suspend fun fetchLogbook(startMs: Long, endMs: Long, entityIds: List<String>? = null): List<LogEvent> {
        throw UnsupportedOperationException("This source cannot provide a logbook.")
    }

    /**
     * The on-demand live-stream URL for a camera (HLS from live HA via `camera/stream`; the bundled
     * demo clip in demo). Null when the source has no stream — the player falls back to MJPEG/snapshot.
     * (WebRTC is the next tier above this, wired once go2rtc is on the cluster.)
     */
    suspend fun streamUrl(entityId: String): String? = null

    /**
     * Recorded motion/object events for a camera over `[startMs, endMs]`, powering the timeline
     * scrubber. The live source reads them from Frigate; the fixture source synthesizes a 24h spread.
     * [camera] is the Frigate camera name. Returned oldest-first.
     */
    suspend fun fetchCameraEvents(camera: String, startMs: Long, endMs: Long): List<CameraEvent> = emptyList()

    /** Recorded-footage URL for [camera] over `[startMs, endMs]` (HLS VOD). Null when unsupported. */
    fun recordingUrlAt(camera: String, startMs: Long, endMs: Long): String? = null

    /** A playable URL for one recorded event's clip. Null when unsupported. */
    fun eventClipUrl(eventId: String): String? = null

    /** True when this source can negotiate WebRTC (the live HA source; not demo). */
    fun supportsWebRtc(): Boolean = false

    /**
     * Begin a WebRTC live session for a camera: send the local SDP [offerSdp] to HA
     * (`camera/webrtc/offer`, a subscribe-style command served by go2rtc) and stream the
     * negotiation back through [onSignal] (session id, answer, trickle ICE, or error). Returns a
     * handle whose `close()` tears the subscription down, or null when unsupported. Only the live
     * HA source implements this; demo returns null so the player falls back.
     */
    suspend fun webrtcOffer(
        entityId: String,
        offerSdp: String,
        onSignal: (WebRtcSignal) -> Unit,
    ): WebRtcHandle? = null

    /** Push a local trickle ICE candidate up to HA for an in-flight WebRTC session. */
    suspend fun webrtcCandidate(
        sessionId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int,
    ) {
    }
}
