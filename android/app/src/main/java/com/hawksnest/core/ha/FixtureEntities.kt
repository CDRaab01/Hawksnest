package com.hawksnest.core.ha

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    ent("alarm_control_panel.home", "disarmed") { put("friendly_name", "Alarm") },
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
)

/** Area assignment — a registry concern, kept separate from state. */
val fixtureAreaRegistry: AreaRegistry = mapOf(
    "camera.front_door" to "Front Door",
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
