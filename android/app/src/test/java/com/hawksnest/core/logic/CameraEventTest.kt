package com.hawksnest.core.logic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

    /** As [raw], plus Frigate's nested `data.description`. `description = null`
     *  still emits the `data` object, to cover the explicit-JSON-null case. */
    private fun rawWithData(id: String = "e1", description: String?): JsonObject = buildJsonObject {
        put("id", id)
        put("camera", "kitchen")
        put("label", "person")
        put("start_time", 1_700_000_000.0)
        put("has_clip", false)
        put("has_snapshot", false)
        put(
            "data",
            buildJsonObject { if (description != null) put("description", description) },
        )
    }

    @Test
    fun `maps Frigate's nested data description`() {
        val ev = normalizeFrigateEvents(listOf(rawWithData(description = "A person walks to the counter.")))[0]
        assertEquals("A person walks to the counter.", ev.description)
    }

    // Null is the NORMAL case, not an error: genai runs for person only, and only
    // after the event ends. One "absent" representation keeps the UI simple.
    @Test
    fun `description is null when absent, empty, whitespace, or data missing`() {
        assertNull(normalizeFrigateEvents(listOf(raw(id = "no-data")))[0].description)
        assertNull(normalizeFrigateEvents(listOf(rawWithData(description = null)))[0].description)
        assertNull(normalizeFrigateEvents(listOf(rawWithData(description = "")))[0].description)
        assertNull(normalizeFrigateEvents(listOf(rawWithData(description = "   \n ")))[0].description)
    }

    // Real text off the phone: descriptions generated before the Frigate prompt was
    // fixed are multi-paragraph raw markdown, and nothing here renders markdown, so
    // the asterisks and headings showed up literally on screen.
    @Test
    fun `a legacy markdown essay is flattened into one line of prose`() {
        val essay = listOf(
            "Based on the sequence of images, the person's behavior shows a clear",
            "progression related to **cleaning or general household maintenance**.",
            "",
            "**Analysis of Actions and Movement:**",
            "",
            "1.  **Initial/Mid-Sequence (Frames 1-3):** The person is moving toward the",
            "kitchen.",
            "2.  **Late Sequence:** The movement slows slightly.",
            "",
            "## Conclusion on Intent",
            "> The primary intent is to *complete a physical task*.",
        ).joinToString("\n")
        val ev = normalizeFrigateEvents(listOf(rawWithData(description = essay)))[0]
        val d = ev.description!!

        assertFalse(d.contains("*"))
        assertFalse(d.contains("#"))
        assertFalse(d.contains(">"))
        assertFalse(d.contains("\n"))
        assertTrue(d.contains("cleaning or general household maintenance"))
        assertTrue(d.contains("Initial/Mid-Sequence (Frames 1-3):"))
    }

    @Test
    fun `an already-plain sentence is left untouched`() {
        val plain = "A person walks through the kitchen carrying towels."
        assertEquals(
            plain,
            normalizeFrigateEvents(listOf(rawWithData(description = plain)))[0].description,
        )
    }

    // Markup that is ALL syntax must collapse to absent, not to an empty string.
    @Test
    fun `description is null when it is nothing but markup`() {
        assertNull(normalizeFrigateEvents(listOf(rawWithData(description = "**  **")))[0].description)
    }

    @Test
    fun `description is trimmed`() {
        assertEquals(
            "hello",
            normalizeFrigateEvents(listOf(rawWithData(description = "  hello  ")))[0].description,
        )
    }

    // The WS result arrives as a JSON *string*; the description must survive that hop.
    @Test
    fun `description survives the JSON-string-wrapped websocket result`() {
        val wire = JsonPrimitive(
            JsonArray(listOf(rawWithData(description = "Carrying a box."))).toString(),
        )
        val ev = normalizeFrigateEvents(parseFrigateWsEvents(wire))[0]
        assertEquals("Carrying a box.", ev.description)
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

    // The transport matters here: `GET /api/frigate/events` DOES NOT EXIST — the integration's
    // query API is websocket-only, and the old REST fetch 404'd on every call while its catch
    // returned []. These pin the websocket result shapes (mirrors the web parseFrigateWsEvents
    // tests) so a stubbed-wrong transport can't pass again.
    @Test
    fun `unwraps the JSON-string result the integration actually sends`() {
        val result = JsonPrimitive("""[{"id":"1","camera":"kitchen","start_time":1,"has_clip":true}]""")
        val events = parseFrigateWsEvents(result)
        assertEquals(1, events.size)
        assertEquals("kitchen", (events[0]["camera"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `accepts an already-decoded array, in case a future version decodes server-side`() {
        val arr = JsonArray(listOf(buildJsonObject { put("id", "1"); put("start_time", 1) }))
        assertEquals(1, parseFrigateWsEvents(arr).size)
    }

    @Test
    fun `returns empty for junk rather than throwing`() {
        assertEquals(emptyList(), parseFrigateWsEvents(JsonPrimitive("not json")))
        assertEquals(emptyList(), parseFrigateWsEvents(null))
        assertEquals(emptyList(), parseFrigateWsEvents(buildJsonObject { put("events", "x") }))
        assertEquals(emptyList(), parseFrigateWsEvents(JsonPrimitive("""{"an":"object"}""")))
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
