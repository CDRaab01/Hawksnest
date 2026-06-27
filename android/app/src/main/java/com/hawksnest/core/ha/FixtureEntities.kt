package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun ent(id: String, state: String, attrs: JsonObjectBuilder.() -> Unit = {}): HassEntity =
    HassEntity(entityId = id, state = state, attributes = buildJsonObject(attrs))

/**
 * Demo fixtures — no live HA. Friendly names are intentionally left as the ugly, attribute-derived
 * strings stock HA actually shows (e.g. "Lock Current status"), so the label-resolution layer +
 * overrides have something real to fix. Ported from `src/fixtures/entities.ts`.
 */
val fixtureEntities: List<HassEntity> = listOf(
    ent("camera.front_door", "streaming") {
        put("friendly_name", "Front Door Camera"); put("icon", "mdi:cctv")
        put("entity_picture", "/demo-cam-3.svg")
    },
    ent("camera.backyard", "streaming") {
        put("friendly_name", "Backyard Camera"); put("icon", "mdi:cctv")
        put("entity_picture", "/demo-cam-1.svg")
    },
    ent("camera.living_room", "streaming") {
        put("friendly_name", "Living Room Camera"); put("icon", "mdi:cctv")
        put("entity_picture", "/demo-cam-2.svg")
    },
    ent("camera.basement", "idle") {
        put("friendly_name", "Basement Camera"); put("icon", "mdi:cctv")
        put("entity_picture", "/demo-cam-2.svg")
    },
    // Doorbell ring sensor for the front-door camera (ring-mqtt names it
    // `binary_sensor.<base>_ding`). Idle in demo; flipping it on raises the banner.
    ent("binary_sensor.front_door_ding", "off") {
        put("friendly_name", "Front Door Ding"); put("device_class", "occupancy")
    },
    ent("lock.front_door_lock", "locked") { put("friendly_name", "Lock") },
    ent("binary_sensor.front_door_current_status", "on") {
        put("friendly_name", "Lock Current status"); put("device_class", "door")
    },
    ent("binary_sensor.front_door_intrusion", "off") {
        put("friendly_name", "Lock Intrusion"); put("device_class", "safety")
    },
    ent("sensor.front_door_battery", "100") {
        put("friendly_name", "Front Door Battery"); put("device_class", "battery")
        put("unit_of_measurement", "%")
    },
    ent("lock.back_door_lock", "locked") { put("friendly_name", "Lock") },
    ent("light.basement", "on") { put("friendly_name", "Basement"); put("brightness", 153) },
    ent("alarm_control_panel.home", "disarmed") {
        put("friendly_name", "Alarm"); put("code_format", "number")
    },
    ent("binary_sensor.kitchen_smoke", "off") {
        put("friendly_name", "Kitchen Smoke"); put("device_class", "smoke")
    },
    ent("binary_sensor.basement_leak", "off") {
        put("friendly_name", "Basement Leak"); put("device_class", "moisture")
    },
    ent("cover.living_room_blinds", "closed") {
        put("friendly_name", "Living Room Blinds"); put("device_class", "blind")
        put("current_position", 0)
    },
    ent("climate.living_room", "heat") {
        put("friendly_name", "Living Room Thermostat"); put("current_temperature", 69)
        put("temperature", 71); put("unit_of_measurement", "°F")
    },
    ent("media_player.living_room", "playing") {
        put("friendly_name", "Living Room Speaker"); put("media_title", "Nightcall")
        put("media_artist", "Kavinsky")
    },
    ent("fan.bedroom", "on") { put("friendly_name", "Bedroom Fan"); put("percentage", 66) },
    ent("binary_sensor.backyard_motion", "off") {
        put("friendly_name", "Backyard Motion"); put("device_class", "motion")
    },
    ent("sensor.garage_door_battery", "14") {
        put("friendly_name", "Garage Door Battery"); put("device_class", "battery")
        put("unit_of_measurement", "%")
    },
    ent("light.garage", "unavailable") { put("friendly_name", "Garage Light") },
    ent("automation.arm_away_lock_doors", "on") {
        put("friendly_name", "When armed away, lock all doors"); put("id", "1700000001")
        put("last_triggered", "2026-06-25T22:14:00+00:00")
    },
    ent("automation.motion_porch_light", "off") {
        put("friendly_name", "Porch light on motion after sunset"); put("id", "1700000002")
    },
    // People + the sun: not "devices" (filtered from the Devices hub), but they populate the
    // automation builder's presence and sunrise/sunset pickers.
    ent("person.alex", "home") { put("friendly_name", "Alex") },
    ent("person.sam", "not_home") { put("friendly_name", "Sam") },
    ent("sun.sun", "above_horizon") {
        put("friendly_name", "Sun")
        put("next_rising", "2026-06-28T09:41:00+00:00")
        put("next_setting", "2026-06-28T00:18:00+00:00")
    },
)

/**
 * The editable configs behind the two demo `automation.*` entities, so the builder can open and
 * round-trip them in demo mode (the live source reads these from HA's Config API instead). Mirrors
 * the web fixture source's in-memory automation map.
 */
val fixtureAutomationConfigs: Map<String, JsonObject> = mapOf(
    "1700000001" to buildJsonObject {
        put("id", "1700000001")
        put("alias", "When armed away, lock all doors")
        put("mode", "single")
        putJsonArray("trigger") {
            add(buildJsonObject {
                put("platform", "state")
                put("entity_id", "alarm_control_panel.home")
                put("to", "armed_away")
            })
        }
        putJsonArray("condition") {}
        putJsonArray("action") {
            add(buildJsonObject {
                put("service", "lock.lock")
                putJsonObject("target") {
                    putJsonArray("entity_id") { add("lock.front_door_lock"); add("lock.back_door_lock") }
                }
            })
        }
    },
    "1700000002" to buildJsonObject {
        put("id", "1700000002")
        put("alias", "Porch light on motion after sunset")
        put("mode", "single")
        putJsonArray("trigger") {
            add(buildJsonObject {
                put("platform", "state")
                put("entity_id", "binary_sensor.backyard_motion")
                put("to", "on")
            })
        }
        putJsonArray("condition") {}
        putJsonArray("action") {
            add(buildJsonObject {
                put("service", "light.turn_on")
                putJsonObject("target") { putJsonArray("entity_id") { add("light.basement") } }
            })
        }
    },
)

/** Area assignment — a registry concern, kept separate from state. */
val fixtureAreaRegistry: AreaRegistry = mapOf(
    "camera.front_door" to "Front Door",
    "binary_sensor.front_door_ding" to "Front Door",
    "lock.front_door_lock" to "Front Door",
    "binary_sensor.front_door_current_status" to "Front Door",
    "binary_sensor.front_door_intrusion" to "Front Door",
    "sensor.front_door_battery" to "Front Door",
    "lock.back_door_lock" to "Back Door",
    "camera.backyard" to "Backyard",
    "binary_sensor.backyard_motion" to "Backyard",
    "camera.basement" to "Basement",
    "light.basement" to "Basement",
    "alarm_control_panel.home" to "Security",
    "camera.living_room" to "Living Room",
    "cover.living_room_blinds" to "Living Room",
    "climate.living_room" to "Living Room",
    "media_player.living_room" to "Living Room",
    "fan.bedroom" to "Bedroom",
    "sensor.garage_door_battery" to "Garage",
    "light.garage" to "Garage",
)
