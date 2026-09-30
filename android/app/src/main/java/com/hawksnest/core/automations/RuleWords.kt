package com.hawksnest.core.automations

import com.hawksnest.core.ha.domainOf

/**
 * The automation editor's words: what kind of device something is (for the picker's chips), the
 * choices a trigger or condition offers in that device's own language ("Opens" / "Closes" for a
 * door, not "on" / "off"), and the one-sentence summary of the whole rule shown above the form.
 *
 * Only presentation: the values written to Home Assistant are the same raw states as before
 * ([StateOption.value]), so nothing about saving changes.
 */

/** What a device is, for the picker's type chips. [OTHER] is hidden until "Show everything". */
enum class DeviceKind(val label: String) {
    DOORS("Doors & windows"),
    MOTION("Motion"),
    LOCKS("Locks"),
    LIGHTS("Lights"),
    SWITCHES("Switches"),
    ALARM("Alarm"),
    PEOPLE("People"),
    CLIMATE("Climate"),
    OTHER("Other"),
}

private val DOOR_CLASSES = setOf("door", "garage_door", "opening", "window")
private val MOTION_CLASSES = setOf("motion", "occupancy", "presence")

fun deviceKind(domain: String, deviceClass: String?): DeviceKind = when (domain) {
    "binary_sensor" -> when (deviceClass) {
        in DOOR_CLASSES -> DeviceKind.DOORS
        in MOTION_CLASSES -> DeviceKind.MOTION
        else -> DeviceKind.OTHER
    }
    "cover" -> DeviceKind.DOORS
    "lock" -> DeviceKind.LOCKS
    "light" -> DeviceKind.LIGHTS
    "switch", "input_boolean", "fan" -> DeviceKind.SWITCHES
    "alarm_control_panel" -> DeviceKind.ALARM
    "person", "device_tracker" -> DeviceKind.PEOPLE
    "climate" -> DeviceKind.CLIMATE
    "sensor" -> if (deviceClass == "temperature" || deviceClass == "humidity") DeviceKind.CLIMATE else DeviceKind.OTHER
    else -> DeviceKind.OTHER
}

/**
 * What a device can do, as a trigger: "Opens", "Detects motion", "Locks", "Is armed away". Empty
 * for a device with no curated vocabulary; the editor then asks for the state as text.
 */
fun triggerChoices(domain: String, deviceClass: String?): List<StateOption> = when (domain) {
    "binary_sensor" -> when (deviceClass) {
        in DOOR_CLASSES -> listOf(StateOption("on", "Opens"), StateOption("off", "Closes"))
        in MOTION_CLASSES -> listOf(StateOption("on", "Detects motion"), StateOption("off", "Stops detecting motion"))
        "moisture" -> listOf(StateOption("on", "Detects a leak"), StateOption("off", "Dries"))
        "smoke", "carbon_monoxide", "gas" -> listOf(StateOption("on", "Goes off"), StateOption("off", "Clears"))
        else -> listOf(StateOption("on", "Turns on"), StateOption("off", "Turns off"))
    }
    "lock" -> listOf(StateOption("locked", "Locks"), StateOption("unlocked", "Unlocks"), StateOption("jammed", "Jams"))
    "alarm_control_panel" -> listOf(
        StateOption("disarmed", "Is disarmed"),
        StateOption("armed_home", "Is armed home"),
        StateOption("armed_away", "Is armed away"),
        StateOption("armed_night", "Is armed night"),
        StateOption("triggered", "Goes off"),
    )
    "light", "switch", "fan", "input_boolean" -> listOf(StateOption("on", "Turns on"), StateOption("off", "Turns off"))
    "cover" -> listOf(StateOption("open", "Opens"), StateOption("closed", "Closes"))
    "person", "device_tracker" -> listOf(StateOption("home", "Arrives home"), StateOption("not_home", "Leaves home"))
    else -> emptyList()
}

/** The same devices as a condition: "Is open", "Is locked", "Is home". */
fun conditionChoices(domain: String, deviceClass: String?): List<StateOption> = when (domain) {
    "binary_sensor" -> when (deviceClass) {
        in DOOR_CLASSES -> listOf(StateOption("on", "Is open"), StateOption("off", "Is closed"))
        in MOTION_CLASSES -> listOf(StateOption("on", "Sees motion"), StateOption("off", "Sees no motion"))
        else -> listOf(StateOption("on", "Is on"), StateOption("off", "Is off"))
    }
    "lock" -> listOf(StateOption("locked", "Is locked"), StateOption("unlocked", "Is unlocked"))
    "alarm_control_panel" -> listOf(
        StateOption("disarmed", "Is disarmed"),
        StateOption("armed_home", "Is armed home"),
        StateOption("armed_away", "Is armed away"),
        StateOption("armed_night", "Is armed night"),
    )
    "light", "switch", "fan", "input_boolean" -> listOf(StateOption("on", "Is on"), StateOption("off", "Is off"))
    "cover" -> listOf(StateOption("open", "Is open"), StateOption("closed", "Is closed"))
    "person", "device_tracker" -> listOf(StateOption("home", "Is home"), StateOption("not_home", "Is away"))
    else -> emptyList()
}

/** A device's state right now, for the picker's rows: "Closed", "Locked", "21.5 °C". */
fun currentStateLabel(domain: String, deviceClass: String?, state: String, unit: String? = null): String {
    if (state == "unavailable") return "Offline"
    if (state == "unknown") return "Unknown"
    conditionChoices(domain, deviceClass).firstOrNull { it.value == state }?.let { choice ->
        return choice.label.removePrefix("Is ").removePrefix("Sees ").replaceFirstChar { it.uppercase() }
    }
    if (!unit.isNullOrBlank()) return "$state $unit"
    return state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * The rule in one sentence, as the editor builds it: "When Back Door opens, after 20:00, turn on
 * String Lights." Parts not yet chosen read as what's missing ("When a device changes…"), so the
 * sentence is always a sentence. Also the automation's name when the owner leaves the name blank.
 */
fun ruleSentence(
    rule: Rule,
    nameOf: (String) -> String,
    deviceClassOf: (String) -> String? = { null },
): String {
    val whenPart = when (val t = rule.trigger) {
        is RuleTrigger.State -> if (t.entityId.isEmpty()) {
            "When a device changes"
        } else {
            val verb = triggerChoices(domainOf(t.entityId), deviceClassOf(t.entityId))
                .firstOrNull { it.value == t.to }?.label?.lowercase()
                ?: t.to.takeIf { it.isNotEmpty() }?.let { "becomes ${it.replace('_', ' ')}" }
                ?: "changes"
            "When ${nameOf(t.entityId)} $verb"
        }
        is RuleTrigger.Time -> if (t.at.isEmpty()) "At a time" else "At ${t.at}"
        is RuleTrigger.Sun -> {
            val event = if (t.event == SunEvent.SUNRISE) "sunrise" else "sunset"
            when {
                t.offsetMinutes < 0 -> "${-t.offsetMinutes} minutes before $event"
                t.offsetMinutes > 0 -> "${t.offsetMinutes} minutes after $event"
                else -> "At $event"
            }.replaceFirstChar { it.uppercase() }
        }
        is RuleTrigger.Presence -> {
            val who = t.personEntityId.takeIf { it.isNotEmpty() }?.let(nameOf) ?: "someone"
            "When $who ${if (t.event == PresenceEvent.ENTER) "arrives home" else "leaves home"}"
        }
    }
    val onlyIf = rule.conditions.mapNotNull { c ->
        when (c) {
            is RuleCondition.TimeWindow -> when {
                c.after != null && c.before != null -> "between ${c.after} and ${c.before}"
                c.after != null -> "after ${c.after}"
                c.before != null -> "before ${c.before}"
                else -> null
            }
            is RuleCondition.StateIs -> if (c.entityId.isEmpty()) {
                null
            } else {
                val state = conditionChoices(domainOf(c.entityId), deviceClassOf(c.entityId))
                    .firstOrNull { it.value == c.state }?.label?.lowercase()
                    ?: c.state.takeIf { it.isNotEmpty() }?.let { "is ${it.replace('_', ' ')}" }
                state?.let { "if ${nameOf(c.entityId)} $it" }
            }
        }
    }
    val doParts = rule.actions.map { a ->
        val verb = verbsFor(a.domain).firstOrNull { it.verb == a.verb }?.label
            ?.replace(" — ", " ")?.lowercase() ?: "do something to"
        "$verb ${targetsPhrase(a.targetEntityIds.map(nameOf), a.domain)}"
    }
    return buildString {
        append(whenPart)
        onlyIf.forEach { append(", ").append(it) }
        append(", ")
        append(if (doParts.isEmpty()) "do something" else doParts.joinToString(", then "))
        // "turn on …" is waiting for its targets; a full stop after it reads as a typo.
        if (!endsWith("…")) append('.')
    }
}

private fun targetsPhrase(names: List<String>, domain: String): String = when (names.size) {
    0 -> "…"
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> "${names[0]} and ${names.size - 1} other ${(DOMAIN_LABEL[domain] ?: "devices").lowercase()}"
}
