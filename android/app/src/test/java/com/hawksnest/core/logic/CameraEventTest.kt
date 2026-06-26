package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `cameraEvents.test.ts` 1:1. */
class CameraEventTest {

    private fun raw(
        id: String? = "a",
        camera: String = "front",
        label: String? = "person",
        start: Double? = 1_700_000_000.0,
        end: Double? = null,
        hasClip: Boolean = false,
        hasSnapshot: Boolean = false,
    ): JsonObject = buildJsonObject {
        if (id != null) put("id", id)
        put("camera", camera)
        if (label != null) put("label", label)
        if (start != null) put("start_time", start)
        if (end != null) put("end_time", end)
        put("has_clip", hasClip)
        put("has_snapshot", hasSnapshot)
    }

    @Test
    fun `normalizes Frigate events seconds to ms, oldest-first, defensive defaults`() {
        val out = normalizeFrigateEvents(
            listOf(
                raw(id = "b", label = "person", start = 1_700_000_100.0, end = 1_700_000_130.0, hasClip = true, hasSnapshot = true),
                raw(id = "a", label = "car", start = 1_700_000_000.0, end = 1_700_000_050.0),
            ),
        )
        assertEquals(listOf("a", "b"), out.map { it.id })
        val first = out[0]
        assertEquals("front", first.camera)
        assertEquals("car", first.label)
        assertEquals(1_700_000_000_000, first.startMs)
        assertEquals(1_700_000_050_000, first.endMs)
        assertFalse(first.hasClip)
        assertFalse(first.hasSnapshot)
        assertNull(first.thumbnailUrl)
        assertEquals(eventSnapshotUrl("b"), out[1].thumbnailUrl)
    }

    @Test
    fun `treats a missing end_time as ongoing (endMs null)`() {
        val ev = normalizeFrigateEvents(listOf(raw(id = "x", label = "motion", end = null)))[0]
        assertNull(ev.endMs)
    }

    @Test
    fun `drops entries with no id or no usable start time`() {
        val out = normalizeFrigateEvents(
            listOf(
                raw(id = null),                 // no id
                raw(id = "y", start = null),    // no start_time
                raw(id = "z"),
            ),
        )
        assertEquals(listOf("z"), out.map { it.id })
    }

    @Test
    fun `defaults a missing label to motion`() {
        val ev = normalizeFrigateEvents(listOf(raw(id = "n", label = null)))[0]
        assertEquals("motion", ev.label)
    }

    @Test
    fun `builds VOD, clip and snapshot URLs against the default and a custom base`() {
        assertEquals(
            "$FRIGATE_BASE/vod/front/start/1700000000/end/1700000600/master.m3u8",
            recordingUrlAt("front", 1_700_000_000_000, 1_700_000_600_000),
        )
        assertEquals("$FRIGATE_BASE/notifications/evt-1/clip.mp4", eventClipUrl("evt-1"))
        assertEquals("$FRIGATE_BASE/notifications/evt-1/snapshot.jpg", eventSnapshotUrl("evt-1"))

        val base = "http://ha.local:8123/api/frigate"
        assertEquals(
            "$base/vod/front/start/1700000000/end/1700000600/master.m3u8",
            recordingUrlAt("front", 1_700_000_000_000, 1_700_000_600_000, base),
        )
    }

    @Test
    fun `marks snapshot URLs only when has_snapshot is set`() {
        val withSnap = normalizeFrigateEvents(listOf(raw(id = "s", hasSnapshot = true)))[0]
        assertTrue(withSnap.snapshotUrl != null)
        val without = normalizeFrigateEvents(listOf(raw(id = "p", hasSnapshot = false)))[0]
        assertNull(without.snapshotUrl)
    }
}
