package com.hawksnest.core.logic

/**
 * Plain-English wording for History rows.
 *
 * HA's `logbook/get_events` sends a state change as a bare `{entity_id, state, when}`: no name and
 * no message (HA's own frontend fills both in from its state cache). The feed therefore read
 * "binary_sensor.kitchen_motion_3 · changed to off", which cannot answer "what happened last
 * night?". The screen now names the device the way the rest of the app does and phrases the change
 * for what the device is.
 */

/**
 * What a state change means, in words, for an entity of [domain] with HA's [deviceClass]. Null when
 * there is nothing better than showing the value itself (the caller then says "Changed to …").
 */
fun describeStateChange(domain: String?, deviceClass: String?, state: String?, unit: String? = null): String? {
    if (state == null) return null
    when (state) {
        "unavailable" -> return "Went offline"
        "unknown" -> return "State unknown"
    }
    return when (domain) {
        "binary_sensor" -> binarySensorText(deviceClass, state == "on")
        "lock" -> when (state) {
            "locked" -> "Locked"
            "unlocked" -> "Unlocked"
            "locking" -> "Locking"
            "unlocking" -> "Unlocking"
            "jammed" -> "Jammed"
            "open" -> "Opened"
            else -> null
        }
        "alarm_control_panel" -> when (state) {
            "disarmed" -> "Disarmed"
            "armed_home" -> "Armed home"
            "armed_away" -> "Armed away"
            "armed_night" -> "Armed night"
            "armed_vacation" -> "Armed vacation"
            "arming" -> "Arming"
            "pending" -> "Entry delay started"
            "triggered" -> "Alarm triggered"
            else -> null
        }
        "cover" -> when (state) {
            "open" -> "Opened"
            "closed" -> "Closed"
            "opening" -> "Opening"
            "closing" -> "Closing"
            else -> null
        }
        "light", "switch", "fan", "input_boolean", "siren", "automation" -> when (state) {
            "on" -> "Turned on"
            "off" -> "Turned off"
            else -> null
        }
        "person", "device_tracker" -> when (state) {
            "home" -> "Arrived home"
            "not_home" -> "Left home"
            else -> "At ${state.replace('_', ' ')}"
        }
        "sensor" -> if (unit.isNullOrBlank()) null else "$state $unit"
        else -> null
    }
}

private fun binarySensorText(deviceClass: String?, on: Boolean): String = when (deviceClass) {
    "motion", "occupancy", "presence" -> if (on) "Motion detected" else "Motion cleared"
    "door", "garage_door", "opening" -> if (on) "Opened" else "Closed"
    "window" -> if (on) "Window opened" else "Window closed"
    // HA's lock device class is inverted: on means unlocked.
    "lock" -> if (on) "Unlocked" else "Locked"
    "moisture" -> if (on) "Leak detected" else "Dry"
    "smoke" -> if (on) "Smoke detected" else "Smoke cleared"
    "carbon_monoxide", "gas" -> if (on) "Gas detected" else "Gas cleared"
    "battery" -> if (on) "Battery low" else "Battery OK"
    "connectivity" -> if (on) "Connected" else "Disconnected"
    "problem" -> if (on) "Problem" else "OK"
    "tamper" -> if (on) "Tampered" else "Tamper cleared"
    "vibration" -> if (on) "Vibration detected" else "Vibration stopped"
    "sound" -> if (on) "Sound detected" else "Sound stopped"
    "running", "power", "plug" -> if (on) "On" else "Off"
    else -> if (on) "Turned on" else "Turned off"
}

/**
 * History's category chips, named for the house rather than HA's domains ("Binary Sensor" read as
 * jargon). Unknown domains fall back to their title-cased id.
 */
fun historyCategoryLabel(domain: String): String = when (domain) {
    "binary_sensor" -> "Sensors"
    "sensor" -> "Readings"
    "lock" -> "Locks"
    "light" -> "Lights"
    "switch" -> "Switches"
    "alarm_control_panel" -> "Alarm"
    "cover" -> "Doors"
    "automation" -> "Automations"
    "script" -> "Scripts"
    "scene" -> "Scenes"
    "person", "device_tracker" -> "People"
    "camera" -> "Cameras"
    "climate" -> "Climate"
    "fan" -> "Fans"
    "media_player" -> "Media"
    "event" -> "Events"
    "input_boolean" -> "Toggles"
    "siren" -> "Sirens"
    else -> domain.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
}
