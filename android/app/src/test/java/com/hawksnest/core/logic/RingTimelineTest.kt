package com.hawksnest.core.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ports `ringTimeline.ts` behavior. */
class RingTimelineTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val devices = listOf(
        RingDevice(583050895L, "Front Door", "front_door"),
        RingDevice(469488510L, "Front Driveway", "front_driveway"),
        RingDevice(515941450L, "First Floor - Stairway", "first_floor_stairway"),
    )

    @Test
    fun `slugs a Ring device name the way ring-mqtt does`() {
        assertEquals("first_floor_stairway", nameSlug("First Floor - Stairway"))
        assertEquals("back_side_yard", nameSlug("Back Side Yard"))
    }

    // The HA entity ids froze at first discovery and have drifted from the Ring names since
    // (`camera.front_*` is Ring's "Front Driveway"), so the display name is the reliable key.
    @Test
    fun `matches on the camera's display name, not its entity id`() {
        assertEquals(469488510L, matchDevice(devices, "Front Driveway", "front")?.id)
    }

    @Test
    fun `falls back to the entity base when the name does not match`() {
        assertEquals(583050895L, matchDevice(devices, "Renamed In HA", "front_door")?.id)
    }

    @Test
    fun `returns null when the camera is not one of the service's devices`() {
        assertNull(matchDevice(devices, "Garage", "garage"))
    }

    @Test
    fun `parses devices and drops malformed entries`() {
        val body = json.parseToJsonElement(
            """[{"id":1,"name":"A","slug":"a"},{"id":"nope","name":"B","slug":"b"},{"name":"C"}]""",
        ) as JsonArray
        assertEquals(listOf(RingDevice(1L, "A", "a")), parseRingDevices(body))
    }

    private fun timelineJson(vararg events: String, truncated: Boolean = false): JsonObject =
        json.parseToJsonElement(
            """{"truncated":$truncated,"events":[${events.joinToString(",")}]}""",
        ) as JsonObject

    private fun event(id: String, url: String? = "https://ring.test/$id.mp4", expires: Long? = 9000) =
        """{"id":"$id","startMs":1000,"endMs":2000,"durationSec":1,"kind":"motion",
           "person":true,${if (url != null) "\"url\":\"$url\"," else ""}
           ${if (expires != null) "\"urlExpiresAtMs\":$expires," else ""}
           "thumbnailUrl":"https://ring.test/t.jpg"}"""

    @Test
    fun `parses events with their playable URLs and the earliest expiry`() {
        val t = parseRingTimeline(
            timelineJson(event("a", expires = 9000), event("b", expires = 5000)),
            "gate",
        )
        assertEquals(listOf("a", "b"), t.events.map { it.id })
        assertEquals("https://ring.test/a.mp4", t.urls["a"])
        // The player refreshes against the FIRST URL to die, not the last.
        assertEquals(5000L, t.expiresAtMs)
    }

    @Test
    fun `real spans arrive with the event — no learning the duration from the media`() {
        val e = parseRingTimeline(timelineJson(event("a")), "gate").events.single()
        assertEquals(1000L, e.startMs)
        assertEquals(2000L, e.endMs)
        assertEquals("person", e.label)
        assertTrue(e.hasClip)
    }

    @Test
    fun `drops recordings with no URL — every block on the timeline must be watchable`() {
        val t = parseRingTimeline(timelineJson(event("a"), event("b", url = null)), "gate")
        assertEquals(listOf("a"), t.events.map { it.id })
    }

    @Test
    fun `surfaces Ring's truncation instead of looking complete`() {
        assertTrue(parseRingTimeline(timelineJson(event("a"), truncated = true), "gate").truncated)
    }

    @Test
    fun `an empty or malformed payload yields an empty timeline, not an exception`() {
        val empty = parseRingTimeline(json.parseToJsonElement("{}") as JsonObject, "gate")
        assertTrue(empty.events.isEmpty())
        assertNull(empty.expiresAtMs)
    }
}
