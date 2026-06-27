package com.hawksnest.core.automations

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the Kotlin port of `src/lib/automations.ts` — Rule ⇄ HA config mapping must match the web's
 * byte-for-byte, since both write the same Config API. Mirrors `automations.test.ts`.
 */
class RuleConfigTest {

    private val lockAllWhenArmed = Rule(
        id = "1000",
        alias = "Lock all doors when armed home",
        trigger = RuleTrigger.State(entityId = "alarm_control_panel.home", to = "armed_home"),
        conditions = emptyList(),
        actions = listOf(
            RuleAction(
                domain = "lock",
                verb = "lock",
                targetEntityIds = listOf("lock.front_door_lock", "lock.back_door_lock", "lock.garage_lock"),
            ),
        ),
        mode = "single",
    )

    private val lightOnMotion = Rule(
        id = "2000",
        alias = "Hall light on motion after dark",
        trigger = RuleTrigger.State(entityId = "binary_sensor.hall_motion", to = "on"),
        conditions = listOf(RuleCondition.TimeWindow(after = "20:00", before = "06:00")),
        actions = listOf(RuleAction(domain = "light", verb = "turn_on", targetEntityIds = listOf("light.hall"))),
        mode = "single",
    )

    private val porchLightAtSunset = Rule(
        id = "7000",
        alias = "Porch light 15m before sunset",
        trigger = RuleTrigger.Sun(event = SunEvent.SUNSET, offsetMinutes = -15),
        actions = listOf(RuleAction(domain = "light", verb = "turn_on", targetEntityIds = listOf("light.porch"))),
    )

    private val lockAtNight = Rule(
        id = "8000",
        alias = "Lock up at 11pm",
        trigger = RuleTrigger.Time(at = "23:00"),
        actions = listOf(RuleAction(domain = "lock", verb = "lock", targetEntityIds = listOf("lock.front_door_lock"))),
    )

    private val unlockWhenHome = Rule(
        id = "9000",
        alias = "Unlock when Alex arrives",
        trigger = RuleTrigger.Presence(personEntityId = "person.alex", event = PresenceEvent.ENTER, zone = "home"),
        actions = listOf(RuleAction(domain = "lock", verb = "unlock", targetEntityIds = listOf("lock.front_door_lock"))),
    )

    // --- ruleToConfig ------------------------------------------------------

    @Test
    fun `maps a state trigger and a multi-target lock action`() {
        val config = ruleToConfig(lockAllWhenArmed)
        assertEquals(
            buildJsonArray {
                add(buildJsonObject {
                    put("platform", "state")
                    put("entity_id", "alarm_control_panel.home")
                    put("to", "armed_home")
                })
            },
            config["trigger"],
        )
        assertEquals(
            buildJsonArray {
                add(buildJsonObject {
                    put("service", "lock.lock")
                    putJsonObject("target") {
                        putJsonArray("entity_id") {
                            add("lock.front_door_lock"); add("lock.back_door_lock"); add("lock.garage_lock")
                        }
                    }
                })
            },
            config["action"],
        )
    }

    @Test
    fun `maps a time-window condition`() {
        assertEquals(
            buildJsonArray { add(buildJsonObject { put("condition", "time"); put("after", "20:00"); put("before", "06:00") }) },
            ruleToConfig(lightOnMotion)["condition"],
        )
    }

    @Test
    fun `maps a sun trigger with a negative offset`() {
        assertEquals(
            buildJsonArray { add(buildJsonObject { put("platform", "sun"); put("event", "sunset"); put("offset", "-00:15:00") }) },
            ruleToConfig(porchLightAtSunset)["trigger"],
        )
    }

    @Test
    fun `omits the offset key when a sun offset is zero`() {
        val config = ruleToConfig(porchLightAtSunset.copy(trigger = RuleTrigger.Sun(SunEvent.SUNRISE, 0)))
        assertEquals(
            buildJsonArray { add(buildJsonObject { put("platform", "sun"); put("event", "sunrise") }) },
            config["trigger"],
        )
    }

    @Test
    fun `maps a time trigger, padding seconds`() {
        assertEquals(
            buildJsonArray { add(buildJsonObject { put("platform", "time"); put("at", "23:00:00") }) },
            ruleToConfig(lockAtNight)["trigger"],
        )
    }

    @Test
    fun `maps a presence trigger to an HA zone trigger`() {
        assertEquals(
            buildJsonArray {
                add(buildJsonObject {
                    put("platform", "zone"); put("entity_id", "person.alex"); put("zone", "zone.home"); put("event", "enter")
                })
            },
            ruleToConfig(unlockWhenHome)["trigger"],
        )
    }

    // --- offset helpers ----------------------------------------------------

    @Test
    fun `encodes signed minutes to offset strings`() {
        assertEquals("-00:15:00", minutesToOffset(-15))
        assertEquals("+01:30:00", minutesToOffset(90))
        assertEquals("+00:00:00", minutesToOffset(0))
    }

    @Test
    fun `decodes offset strings back to minutes`() {
        assertEquals(-15, offsetToMinutes("-00:15:00"))
        assertEquals(90, offsetToMinutes("+01:30:00"))
        assertEquals(0, offsetToMinutes(null))
        assertEquals(0, offsetToMinutes("garbage"))
    }

    // --- round trips -------------------------------------------------------

    @Test
    fun `round-trips every supported trigger kind`() {
        for (rule in listOf(lockAllWhenArmed, lightOnMotion, porchLightAtSunset, lockAtNight, unlockWhenHome)) {
            assertEquals(rule, configToRule(ruleToConfig(rule)))
        }
    }

    // --- configToRule parsing real configs ---------------------------------

    @Test
    fun `accepts modern triggers and actions keys`() {
        val modern = buildJsonObject {
            put("id", "3000")
            put("alias", "Motion light")
            putJsonArray("triggers") {
                add(buildJsonObject { put("trigger", "state"); put("entity_id", "binary_sensor.m"); put("to", "on") })
            }
            putJsonArray("actions") {
                add(buildJsonObject { put("action", "light.turn_on"); putJsonObject("target") { put("entity_id", "light.hall") } })
            }
        }
        val rule = configToRule(modern)
        assertEquals(RuleTrigger.State(entityId = "binary_sensor.m", to = "on"), rule?.trigger)
        assertEquals(RuleAction(domain = "light", verb = "turn_on", targetEntityIds = listOf("light.hall")), rule?.actions?.first())
    }

    @Test
    fun `parses a sun offset given as bare integer seconds`() {
        val config = buildJsonObject {
            put("id", "3100")
            putJsonArray("trigger") { add(buildJsonObject { put("platform", "sun"); put("event", "sunset"); put("offset", -900) }) }
            putJsonArray("action") {
                add(buildJsonObject { put("service", "light.turn_on"); putJsonObject("target") { putJsonArray("entity_id") { add("light.hall") } } })
            }
        }
        assertEquals(RuleTrigger.Sun(event = SunEvent.SUNSET, offsetMinutes = -15), configToRule(config)?.trigger)
    }

    @Test
    fun `returns null for an unsupported trigger platform`() {
        val config = buildJsonObject {
            put("id", "4000")
            putJsonArray("trigger") { add(buildJsonObject { put("platform", "template"); put("value_template", "{{ true }}") }) }
            putJsonArray("action") { add(buildJsonObject { put("service", "light.turn_on"); putJsonObject("target") { putJsonArray("entity_id") { add("light.hall") } } }) }
        }
        assertNull(configToRule(config))
    }

    @Test
    fun `returns null for a zone trigger that isn't the home zone`() {
        val config = buildJsonObject {
            put("id", "4100")
            putJsonArray("trigger") {
                add(buildJsonObject { put("platform", "zone"); put("entity_id", "person.alex"); put("zone", "zone.office"); put("event", "enter") })
            }
            putJsonArray("action") { add(buildJsonObject { put("service", "light.turn_on"); putJsonObject("target") { putJsonArray("entity_id") { add("light.hall") } } }) }
        }
        assertNull(configToRule(config))
    }

    @Test
    fun `returns null when there are multiple triggers`() {
        val config = buildJsonObject {
            put("id", "5000")
            putJsonArray("trigger") {
                add(buildJsonObject { put("platform", "state"); put("entity_id", "a.b"); put("to", "on") })
                add(buildJsonObject { put("platform", "state"); put("entity_id", "c.d"); put("to", "on") })
            }
            putJsonArray("action") { add(buildJsonObject { put("service", "light.turn_on"); putJsonObject("target") { putJsonArray("entity_id") { add("light.hall") } } }) }
        }
        assertNull(configToRule(config))
    }

    @Test
    fun `returns null when an action service isn't modeled`() {
        val config = buildJsonObject {
            put("id", "6000")
            putJsonArray("trigger") { add(buildJsonObject { put("platform", "state"); put("entity_id", "binary_sensor.m"); put("to", "on") }) }
            putJsonArray("action") { add(buildJsonObject { put("service", "notify.mobile_app"); putJsonObject("data") { put("message", "hi") } }) }
        }
        assertNull(configToRule(config))
    }
}
