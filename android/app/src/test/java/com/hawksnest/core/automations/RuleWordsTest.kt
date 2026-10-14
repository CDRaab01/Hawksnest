package com.hawksnest.core.automations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleWordsTest {

    private val names = mapOf(
        "binary_sensor.back_door" to "Back Door",
        "binary_sensor.hall_motion" to "Hall Motion",
        "lock.front_door" to "Front Door",
        "light.string_lights" to "String Lights",
        "light.porch" to "Porch",
        "light.hall" to "Hall",
        "person.sam" to "Sam",
    )
    private fun nameOf(id: String) = names[id] ?: id
    private fun dc(id: String) = when (id) {
        "binary_sensor.back_door" -> "door"
        "binary_sensor.hall_motion" -> "motion"
        else -> null
    }

    @Test
    fun `devices sort into the picker's kinds`() {
        assertEquals(DeviceKind.DOORS, deviceKind("binary_sensor", "garage_door"))
        assertEquals(DeviceKind.MOTION, deviceKind("binary_sensor", "occupancy"))
        assertEquals(DeviceKind.LOCKS, deviceKind("lock", null))
        assertEquals(DeviceKind.PEOPLE, deviceKind("device_tracker", null))
        assertEquals(DeviceKind.CLIMATE, deviceKind("sensor", "temperature"))
        // What the old dropdown was full of: backups, battery levels, lock sub-sensors.
        assertEquals(DeviceKind.OTHER, deviceKind("sensor", "timestamp"))
        assertEquals(DeviceKind.OTHER, deviceKind("binary_sensor", "problem"))
    }

    @Test
    fun `choices speak the device's language, and write the same raw states`() {
        assertEquals(listOf("Opens", "Closes"), triggerChoices("binary_sensor", "door").map { it.label })
        assertEquals(listOf("on", "off"), triggerChoices("binary_sensor", "door").map { it.value })
        assertEquals("Detects motion", triggerChoices("binary_sensor", "motion")[0].label)
        assertEquals("Is armed away", triggerChoices("alarm_control_panel", null).first { it.value == "armed_away" }.label)
        assertEquals(listOf("Is open", "Is closed"), conditionChoices("binary_sensor", "window").map { it.label })
        assertTrue(triggerChoices("sensor", "temperature").isEmpty())
    }

    @Test
    fun `a device's state right now reads plainly`() {
        assertEquals("Closed", currentStateLabel("binary_sensor", "door", "off"))
        assertEquals("Locked", currentStateLabel("lock", null, "locked"))
        assertEquals("Motion", currentStateLabel("binary_sensor", "motion", "on"))
        assertEquals("21.5 °C", currentStateLabel("sensor", "temperature", "21.5", "°C"))
        assertEquals("Offline", currentStateLabel("light", null, "unavailable"))
        assertEquals("Armed away", currentStateLabel("alarm_control_panel", null, "armed_away"))
    }

    private fun rule(
        trigger: RuleTrigger,
        conditions: List<RuleCondition> = emptyList(),
        actions: List<RuleAction> = listOf(RuleAction("light", "turn_on", listOf("light.string_lights"))),
    ) = Rule(id = "1", alias = "", trigger = trigger, conditions = conditions, actions = actions)

    @Test
    fun `the rule reads as one sentence`() {
        assertEquals(
            "When Back Door opens, after 20:00, turn on String Lights.",
            ruleSentence(rule(RuleTrigger.State("binary_sensor.back_door", "on"), listOf(RuleCondition.TimeWindow(after = "20:00"))), ::nameOf, ::dc),
        )
        assertEquals(
            "At 22:30, lock Front Door.",
            ruleSentence(rule(RuleTrigger.Time("22:30"), actions = listOf(RuleAction("lock", "lock", listOf("lock.front_door")))), ::nameOf, ::dc),
        )
        assertEquals(
            "30 minutes before sunset, turn on String Lights and Porch.",
            ruleSentence(
                rule(RuleTrigger.Sun(SunEvent.SUNSET, -30), actions = listOf(RuleAction("light", "turn_on", listOf("light.string_lights", "light.porch")))),
                ::nameOf,
                ::dc,
            ),
        )
    }

    @Test
    fun `conditions, several targets, presence, and several actions`() {
        assertEquals(
            "When Sam arrives home, if Front Door is locked, between 22:00 and 06:00, turn on String Lights and 2 other lights, then unlock Front Door.",
            ruleSentence(
                rule(
                    RuleTrigger.Presence("person.sam", PresenceEvent.ENTER),
                    listOf(RuleCondition.StateIs("lock.front_door", "locked"), RuleCondition.TimeWindow("22:00", "06:00")),
                    listOf(
                        RuleAction("light", "turn_on", listOf("light.string_lights", "light.porch", "light.hall")),
                        RuleAction("lock", "unlock", listOf("lock.front_door")),
                    ),
                ),
                ::nameOf,
                ::dc,
            ),
        )
    }

    @Test
    fun `an unfinished rule still reads as a sentence`() {
        assertEquals(
            "When a device changes, turn on …",
            ruleSentence(rule(RuleTrigger.State("", ""), actions = listOf(RuleAction("light", "turn_on", emptyList()))), ::nameOf, ::dc),
        )
        assertEquals(
            "When Hall Motion changes, arm away …",
            ruleSentence(
                rule(RuleTrigger.State("binary_sensor.hall_motion", ""), actions = listOf(RuleAction("alarm_control_panel", "arm_away", emptyList()))),
                ::nameOf,
                ::dc,
            ),
        )
    }
}
