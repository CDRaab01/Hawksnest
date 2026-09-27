package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Recording a **live view** so that watching a camera leaves a mark on its own timeline — the
 * behaviour Ring has, where opening a live stream produces an entry you can scrub back to.
 *
 * The footage is not the hard part: nine of the eleven Frigate cameras already record 24/7, so a
 * live view is over video that exists regardless. What was missing is a *marker*. Rather than
 * invent one (an HA helper's history was the obvious alternative), this opens a real Frigate
 * **manual event** for the duration of the view: `frigate.create_event` on start,
 * `frigate.end_event` on stop. That buys three things a helper could not:
 *
 *  - the block is genuinely playable — Frigate attaches a clip, so the timeline's rule that every
 *    block is watchable still holds;
 *  - retention is Frigate's, so the marker expires with the footage it points at. HA's recorder
 *    keeps history far longer than Frigate keeps video, so a history-derived marker would outlive
 *    its own recording and show blocks that 404 — exactly the bug this timeline just shed;
 *  - it needs no HA configuration at all. The Frigate integration already ships `create_event` /
 *    `end_event` with `SupportsResponse.OPTIONAL`, and the response carries the event id.
 *
 * **Ring cameras are out of scope.** Ring records live views server-side and `ring-timeline`
 * already carries a `kind` per event, so surfacing those is a different (and blocked) job.
 */

/** The Frigate label manual live-view events are created with. Also the timeline's colour key. */
const val LIVE_VIEW_LABEL = "live_view"

/**
 * Bound on a single recorded live view, in seconds.
 *
 * `duration: 0` would mean "until explicitly ended", which is correct right up until the app dies
 * mid-view — then Frigate records that camera forever. A bounded event self-heals: the worst case
 * is a recording this long rather than an unbounded one. The client renews while the view is still
 * open, so a long watch is a chain of events rather than one. 300 s is the service's own maximum,
 * and it sits under the Home Hub's 5-minute force-sleep for the battery cameras.
 */
const val LIVE_VIEW_SEGMENT_SEC = 300

/** Renew this long before [LIVE_VIEW_SEGMENT_SEC] expires, so the chain has no visible gap. */
const val LIVE_VIEW_RENEW_BEFORE_SEC = 20

/** Whether [event] is a recorded live view rather than something the camera detected. */
fun isLiveViewEvent(event: CameraEvent): Boolean = event.label == LIVE_VIEW_LABEL

/**
 * Whether watching this camera should record.
 *
 * Only Frigate cameras: it is Frigate's event API doing the recording, and a camera it does not
 * record has nothing to attach a clip to. Only while genuinely live — scrubbing back through
 * recorded footage is not a live view and must not mint an event.
 */
fun shouldRecordLiveView(backend: RecordedBackend, isLive: Boolean): Boolean =
    backend == RecordedBackend.FRIGATE && isLive

/**
 * The event id HA hands back from `frigate.create_event`, or null if the call did not produce one.
 *
 * HA wraps a service response keyed by the entity it targeted, so the shape is
 * `{"response": {"camera.front": {"success": true, "event_id": "…"}}}`. Both the wrapped and the
 * bare form are accepted because the wrapping is HA's business, not Frigate's, and has changed
 * shape before. A response without an id is a failure the caller must not paper over: without it
 * there is no handle to end the event, and an unended event keeps recording.
 */
fun eventIdFromCreateResponse(result: JsonObject?): String? {
    val response = result?.get("response")?.let { it as? JsonObject } ?: result ?: return null
    directEventId(response)?.let { return it }
    // Keyed by entity id — take the first entry that carries one.
    for ((_, value) in response) {
        (value as? JsonObject)?.let { directEventId(it) }?.let { return it }
    }
    return null
}

private fun directEventId(obj: JsonObject): String? =
    (obj["event_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/**
 * Whether this camera has to be woken in Frigate before it can record.
 *
 * The two Argus 4 Pros behind the Reolink Home Hub are parked (`enabled: false`) until the hub's
 * PIR fires, so live-viewing one produces no footage at all unless Frigate is turned on for the
 * duration. Everything else records continuously and needs nothing. Same `isBattery` tell the live
 * tiers already use — see [LogicalCamera.isBattery].
 */
fun needsFrigateWake(isBattery: Boolean): Boolean = isBattery

/** MQTT topic that enables/disables a camera's Frigate pipeline (`ON` / `OFF`). */
fun frigateEnabledTopic(cameraName: String): String = "frigate/$cameraName/enabled/set"

/**
 * The helper that tells the HA parking automation "someone is watching, do not park this camera".
 *
 * `Hawksnest: Frigate on demand — <cam>` publishes `OFF` two minutes after the PIR clears. Without
 * this guard that lands mid-view and kills the recording — and the odds are not small, because the
 * reason someone opens the front camera is usually that it just saw something.
 */
fun liveViewGuardEntity(cameraName: String): String = "input_boolean.hawksnest_live_view_$cameraName"
