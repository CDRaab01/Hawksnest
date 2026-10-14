package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the pure half of live-view recording (see LiveViewRecording.kt). */
class LiveViewRecordingTest {

    private fun event(label: String) = CameraEvent(
        id = "e1", camera = "front", label = label, startMs = 1_000L, endMs = 2_000L,
        hasClip = true, hasSnapshot = false, thumbnailUrl = null, snapshotUrl = null,
    )

    @Test
    fun `a live view is told apart from something the camera detected`() {
        assertTrue(isLiveViewEvent(event(LIVE_VIEW_LABEL)))
        assertFalse(isLiveViewEvent(event("person")))
        assertFalse(isLiveViewEvent(event("car")))
    }

    // Only Frigate records these, and only a genuinely live view is one: scrubbing back through
    // recorded footage must never mint an event.
    @Test
    fun `only a live Frigate camera records a live view`() {
        assertTrue(shouldRecordLiveView(RecordedBackend.FRIGATE, isLive = true))
        assertFalse(shouldRecordLiveView(RecordedBackend.FRIGATE, isLive = false))
        assertFalse(shouldRecordLiveView(RecordedBackend.RING, isLive = true))
        assertFalse(shouldRecordLiveView(RecordedBackend.NONE, isLive = true))
    }

    // HA wraps a service response keyed by the entity it targeted.
    @Test
    fun `reads the event id out of HA's entity-keyed response`() {
        val wrapped = buildJsonObject {
            putJsonObject("response") {
                putJsonObject("camera.front") {
                    put("success", true)
                    put("event_id", "1790516562.220557-fm04pz")
                }
            }
        }
        assertEquals("1790516562.220557-fm04pz", eventIdFromCreateResponse(wrapped))
    }

    @Test
    fun `accepts a bare response too, since the wrapping is HA's and has moved before`() {
        val bare = buildJsonObject { put("event_id", "abc-123") }
        assertEquals("abc-123", eventIdFromCreateResponse(bare))
        val wrappedBare = buildJsonObject { putJsonObject("response") { put("event_id", "abc-123") } }
        assertEquals("abc-123", eventIdFromCreateResponse(wrappedBare))
    }

    // Without an id there is no handle to end the event, and an unended event keeps the camera
    // recording — so this must read as failure, never as an empty string the caller might send.
    @Test
    fun `a response with no usable id is null, not an empty handle`() {
        assertNull(eventIdFromCreateResponse(null))
        assertNull(eventIdFromCreateResponse(JsonObject(emptyMap())))
        assertNull(eventIdFromCreateResponse(buildJsonObject { put("success", false) }))
        assertNull(eventIdFromCreateResponse(buildJsonObject { put("event_id", "") }))
        assertNull(
            eventIdFromCreateResponse(
                buildJsonObject { putJsonObject("response") { putJsonObject("camera.front") { put("success", false) } } },
            ),
        )
    }

    // The two Home Hub cameras are parked in Frigate until their PIR fires, so live-viewing one
    // records nothing unless Frigate is woken for the duration.
    @Test
    fun `only a battery camera needs Frigate woken`() {
        assertTrue(needsFrigateWake(isBattery = true))
        assertFalse(needsFrigateWake(isBattery = false))
    }

    @Test
    fun `topic and guard entity are derived from the camera name`() {
        assertEquals("frigate/front/enabled/set", frigateEnabledTopic("front"))
        assertEquals("input_boolean.hawksnest_live_view_front", liveViewGuardEntity("front"))
        assertEquals("frigate/backyard_patio/enabled/set", frigateEnabledTopic("backyard_patio"))
    }

    // A segment must be short enough that a crashed app leaks one bounded recording, and the
    // renewal must land before it expires or the timeline shows a gap.
    @Test
    fun `the renewal lands inside the segment it renews`() {
        assertTrue(LIVE_VIEW_RENEW_BEFORE_SEC > 0)
        assertTrue(LIVE_VIEW_RENEW_BEFORE_SEC < LIVE_VIEW_SEGMENT_SEC)
        // 300 s is the create_event service's own maximum; going over it would be rejected.
        assertTrue(LIVE_VIEW_SEGMENT_SEC <= 300)
    }
}
