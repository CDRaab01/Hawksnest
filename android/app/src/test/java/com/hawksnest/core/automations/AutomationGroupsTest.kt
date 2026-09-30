package com.hawksnest.core.automations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationGroupsTest {

    // Generic names in the shapes the real ones take; no household's own names in a public repo.
    private val areas = listOf("Office", "Guest Room", "Garage", "Guest")

    @Test
    fun `a leading room name becomes the group and the rest splits into title and subtitle`() {
        val t = titleAutomation("Guest Room LED bar - alarm colour + dim at night", areas)
        assertEquals("Guest Room", t.group)
        assertEquals("LED bar", t.title)
        assertEquals("Alarm colour + dim at night", t.subtitle)
        assertFalse(t.system)
    }

    @Test
    fun `the longest room name wins, and a room must end at a word boundary`() {
        val fan = titleAutomation("Guest Room fan follows temperature", areas)
        assertEquals("Guest Room", fan.group)
        // No separator after the room: the name stays whole ("Garage Door Unarm" keeps "Garage").
        assertEquals("Guest Room fan follows temperature", fan.title)
        assertEquals("Fan follows temperature", titleAutomation("Guest Room: fan follows temperature", areas).title)
        // "Officer" is not the Office room.
        assertEquals(WHOLE_HOUSE_GROUP, titleAutomation("Officer rota", areas).group)
    }

    @Test
    fun `a colon or a dash names the group when no room does`() {
        val colon = titleAutomation("Garage: bay open too long", listOf("Kitchen"))
        assertEquals("Garage", colon.group)
        assertEquals("Bay open too long", colon.title)
        assertNull(colon.subtitle)
        val dash = titleAutomation("Kid's ZEN32 - big button", areas)
        assertEquals("Kid's ZEN32", dash.group)
        assertEquals("Big button", dash.title)
    }

    @Test
    fun `hawksnest-automation's own automations go behind the scenes, parenthetical as subtitle`() {
        val push = titleAutomation("Hawksnest push: person at Front (hub PIR, instant)", areas)
        assertEquals(SYSTEM_GROUP, push.group)
        assertTrue(push.system)
        assertEquals("Person at Front", push.title)
        assertEquals("hub PIR, instant", push.subtitle)
        assertEquals("Z-Wave battery watchdog", titleAutomation("Hawksnest: Z-Wave battery watchdog", areas).title)
    }

    @Test
    fun `a room assigned in Home Assistant wins, and takes an automation out of behind the scenes`() {
        val t = titleAutomation("Hawksnest: backyard string lights - dusk on, curfew off", areas, assignedArea = "Backyard")
        assertEquals("Backyard", t.group)
        assertFalse(t.system)
        // The room is the group now, so the title doesn't repeat it.
        assertEquals("String lights", t.title)
        assertEquals("Dusk on, curfew off", t.subtitle)
    }

    @Test
    fun `a name that names nothing is whole house, and groups sort rooms then whole house then system`() {
        assertEquals(WHOLE_HOUSE_GROUP, titleAutomation("All Lock", areas).group)
        val sorted = listOf(SYSTEM_GROUP, "Nursery", WHOLE_HOUSE_GROUP, "garage").sortedWith(compareBy({ groupOrder(it).first }, { groupOrder(it).second }))
        assertEquals(listOf("garage", "Nursery", WHOLE_HOUSE_GROUP, SYSTEM_GROUP), sorted)
    }

    private val zone = ZoneId.of("America/New_York")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `last ran reads as a time today, then yesterday, a weekday, a date`() {
        val now = ms(2026, 9, 29, 22, 0)
        assertEquals("9:37 PM", shortLastRan(ms(2026, 9, 29, 21, 37), now, zone))
        assertEquals("Yesterday", shortLastRan(ms(2026, 9, 28, 9, 41), now, zone))
        assertEquals("Sat", shortLastRan(ms(2026, 9, 26, 19, 46), now, zone))
        assertEquals("Sep 19", shortLastRan(ms(2026, 9, 19, 20, 53), now, zone))
        assertEquals("Never", shortLastRan(null, now, zone))
        assertTrue(ranToday(ms(2026, 9, 29, 3, 23), now, zone))
        assertFalse(ranToday(ms(2026, 9, 28, 23, 59), now, zone))
    }

    // --- trigger wording ------------------------------------------------------------------------

    private fun cfg(s: String) = Json.parseToJsonElement(s) as JsonObject
    private val names = mapOf(
        "binary_sensor.garage_door" to "Garage Door",
        "alarm_control_panel.home" to "Alarm",
        "sensor.nursery_temp" to "Nursery Temperature",
    )
    private fun nameOf(id: String) = names[id] ?: id
    private fun dc(id: String) = if (id == "binary_sensor.garage_door") "garage_door" else null

    @Test
    fun `state triggers read in the device's own words`() {
        assertEquals(
            "Garage Door: opened",
            describeTriggers(cfg("""{"triggers":[{"trigger":"state","entity_id":"binary_sensor.garage_door","from":"off","to":"on"}]}"""), ::nameOf, ::dc),
        )
        assertEquals(
            "Alarm: armed home",
            describeTriggers(cfg("""{"trigger":[{"platform":"state","entity_id":"alarm_control_panel.home","to":"armed_home"}]}"""), ::nameOf),
        )
    }

    @Test
    fun `time, sun, numeric and unknown triggers, and several at once`() {
        assertEquals("At 22:30", describeTriggers(cfg("""{"triggers":[{"trigger":"time","at":"22:30:00"}]}"""), ::nameOf))
        assertEquals("At sunset", describeTriggers(cfg("""{"triggers":[{"trigger":"sun","event":"sunset"}]}"""), ::nameOf))
        assertEquals(
            "Nursery Temperature goes above 75",
            describeTriggers(cfg("""{"triggers":[{"trigger":"numeric_state","entity_id":"sensor.nursery_temp","above":75}]}"""), ::nameOf),
        )
        assertEquals(
            "A device event, At sunset or 1 more",
            describeTriggers(cfg("""{"triggers":[{"trigger":"event"},{"trigger":"sun","event":"sunset"},{"trigger":"template"}]}"""), ::nameOf),
        )
        assertNull(describeTriggers(cfg("""{"alias":"x"}"""), ::nameOf))
        assertEquals(
            "Garage Door comes back online",
            describeTriggers(cfg("""{"triggers":[{"trigger":"state","entity_id":"binary_sensor.garage_door","from":"unavailable"}]}"""), ::nameOf, ::dc),
        )
    }

    @Test
    fun `the description is the author's own words, whitespace tidied`() {
        assertEquals("Pulses the bar green for 5s.", automationDescription(cfg("""{"description":"Pulses the bar\n  green for 5s."}""")))
        assertNull(automationDescription(cfg("""{"description":"  "}""")))
    }
}
