package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ports `ringEvents.ts` behavior. */
class RingEventsTest {

    private val NOW = 1_700_000_000_000L

    private fun select(vararg options: String, state: String? = null): HassEntity = HassEntity(
        entityId = "select.front_event_select",
        state = state ?: options.firstOrNull() ?: "",
        attributes = buildJsonObject {
            putJsonArray("options") { options.forEach { add(it) } }
        },
    )

    private fun iso(h: Int, m: Int) = "2026-09-23T%02d:%02d:00.000Z".format(h, m)

    @Test
    fun `parses options into labelled events, oldest-first`() {
        val events = ringEventsFromSelect(
            select("Motion ${iso(10, 12)}", "Ding ${iso(10, 6)}", "On-Demand ${iso(10, 0)}"),
            "front",
        )
        assertEquals(3, events.size)
        assertEquals(
            listOf("On-Demand ${iso(10, 0)}", "Ding ${iso(10, 6)}", "Motion ${iso(10, 12)}"),
            events.map { it.id },
        )
        assertEquals(setOf("motion", "ding", "event"), events.map { it.label }.toSet())
        events.forEach { assertEquals("front", it.camera) }
    }

    @Test
    fun `returns empty when there are no options`() {
        assertTrue(ringEventsFromSelect(null, "front").isEmpty())
        val noOptions = HassEntity("select.x", "", JsonObject(emptyMap()))
        assertTrue(ringEventsFromSelect(noOptions, "front").isEmpty())
    }

    // HA restores a retired ring-mqtt entity with its last `options` intact, so a dead selector
    // still looks like it holds playable events long after its camera was deleted.
    @Test
    fun `a selector that is not reporting has no options`() {
        assertTrue(ringEventOptions(select("Motion 1", "Motion 2", state = "unavailable")).isEmpty())
        assertTrue(ringEventOptions(select("Motion 1", state = "unknown")).isEmpty())
        assertEquals(listOf("Motion 1", "Motion 2"), ringEventOptions(select("Motion 1", "Motion 2")))
    }

    @Test
    fun `recovers a time only from a full ISO instant in the option`() {
        assertEquals(null, ringOptionTimeMs("Motion 1"))
        assertEquals(null, ringOptionTimeMs("Motion 1 (Transcoded)"))
        // A zone designator is required so both platforms land on the same millisecond.
        assertEquals(null, ringOptionTimeMs("Motion 2026-09-23T10:00:00"))
        assertEquals(
            java.time.Instant.parse(iso(10, 0)).toEpochMilli(),
            ringOptionTimeMs("Motion ${iso(10, 0)}"),
        )
    }

    @Test
    fun `decodes a ring snowflake event id back to its time`() {
        val t = 1_782_925_000_000L
        // Construct an id that encodes t, then confirm the decode round-trips (guards the offset).
        val eid = ((t + 42_790_053_458L) shl 22).toString()
        assertEquals(t, ringEventIdToMs(eid))
        assertEquals(null, ringEventIdToMs(null))
        assertEquals(null, ringEventIdToMs("not-a-number"))
    }

    @Test
    fun `pairs options with real decoded times by recency, oldest-first`() {
        // Motion 1 is newest → takes the newest time; Motion 2 the next.
        val events = ringEventsFromOptions(
            options = listOf("Motion 1", "Motion 2"),
            timesDesc = listOf(5_000L, 1_000L),
            cameraName = "back",
        )
        assertEquals(listOf("Motion 2", "Motion 1"), events.map { it.id })
        assertEquals(listOf(1_000L, 5_000L), events.map { it.startMs })
    }

    // The regression that produced this whole change: `camera.front` inherited 30 frozen options
    // from a retired Ring camera and drew them as a comb of evenly spaced "recordings" — each
    // claiming hasClip — that had never happened. An untimed option is now dropped, not placed.
    @Test
    fun `drops an option whose time cannot be recovered rather than inventing one`() {
        val events = ringEventsFromOptions(
            options = listOf("Motion 1", "Motion 2"),
            timesDesc = emptyList(),
            cameraName = "back",
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `keeps the options it can time and drops the rest`() {
        val events = ringEventsFromOptions(
            options = listOf("Motion 1", "Motion 2"),
            timesDesc = listOf(5_000L),
            cameraName = "back",
        )
        assertEquals(1, events.size)
        assertEquals("Motion 1", events[0].id)
        assertEquals(5_000L, events[0].startMs)
    }

    /** The selector as ring-mqtt 5.x publishes it: the chosen event's recording URL as an attribute. */
    private fun selectWithRecording(recordingUrl: String?): HassEntity = HassEntity(
        entityId = "select.front_event_select",
        state = "Motion 1",
        attributes = buildJsonObject {
            putJsonArray("options") { add("Motion 1") }
            if (recordingUrl != null) put("recordingUrl", recordingUrl)
        },
    )

    @Test
    fun `reads the published recording URL`() {
        assertEquals(
            "https://ring.example/clip.mp4",
            ringRecordingUrl(selectWithRecording("https://ring.example/clip.mp4")),
        )
    }

    @Test
    fun `sentinels are not playable URLs`() {
        assertEquals(null, ringRecordingUrl(selectWithRecording("<Recording Not Found>")))
        assertEquals(null, ringRecordingUrl(selectWithRecording("<Transcoding in Progress>")))
        assertEquals(null, ringRecordingUrl(selectWithRecording(null)))
        assertEquals(null, ringRecordingUrl(null))
    }

    @Test
    fun `only a missing recording is terminal — a transcode is still coming`() {
        assertTrue(ringRecordingMissing(selectWithRecording("<Recording Not Found>")))
        assertFalse(ringRecordingMissing(selectWithRecording("<Transcoding in Progress>")))
        assertFalse(ringRecordingMissing(selectWithRecording("https://ring.example/clip.mp4")))
        assertFalse(ringRecordingMissing(null))
    }
}
