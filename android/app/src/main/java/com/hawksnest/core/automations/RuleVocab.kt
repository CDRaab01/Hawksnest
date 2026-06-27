package com.hawksnest.core.automations

/**
 * Curated domain/verb/state lookup tables for the automation builder — the Kotlin port of the
 * tables in `src/lib/automations.ts`. They mirror exactly what the device cards already invoke
 * (lock.lock/unlock, light.turn_on/off, alarm arm/disarm…), so an automation can only do what the
 * app can already do by hand.
 */

data class StateOption(val value: String, val label: String)

data class VerbDef(val verb: String, val label: String, val service: String)

data class TriggerTypeOption(val kind: TriggerKind, val label: String, val hint: String)

/**
 * Curated "to state" options per domain for the trigger/condition pickers. Domains absent here fall
 * back to a free-text state input. Mirrors the state vocabulary the cards use.
 */
private val STATE_OPTIONS: Map<String, List<StateOption>> = mapOf(
    "alarm_control_panel" to listOf(
        StateOption("disarmed", "Disarmed"),
        StateOption("armed_home", "Armed — Home"),
        StateOption("armed_away", "Armed — Away"),
        StateOption("armed_night", "Armed — Night"),
        StateOption("triggered", "Triggered"),
    ),
    "lock" to listOf(
        StateOption("locked", "Locked"),
        StateOption("unlocked", "Unlocked"),
    ),
    "binary_sensor" to listOf(
        StateOption("on", "Detected / On"),
        StateOption("off", "Clear / Off"),
    ),
    "light" to listOf(StateOption("on", "On"), StateOption("off", "Off")),
    "switch" to listOf(StateOption("on", "On"), StateOption("off", "Off")),
    "fan" to listOf(StateOption("on", "On"), StateOption("off", "Off")),
    "cover" to listOf(StateOption("open", "Open"), StateOption("closed", "Closed")),
    "person" to listOf(StateOption("home", "Home"), StateOption("not_home", "Away")),
    "device_tracker" to listOf(StateOption("home", "Home"), StateOption("not_home", "Away")),
)

/** Action verbs per domain → the HA service they call. */
private val ACTION_VERBS: Map<String, List<VerbDef>> = mapOf(
    "lock" to listOf(
        VerbDef("lock", "Lock", "lock.lock"),
        VerbDef("unlock", "Unlock", "lock.unlock"),
    ),
    "light" to listOf(
        VerbDef("turn_on", "Turn on", "light.turn_on"),
        VerbDef("turn_off", "Turn off", "light.turn_off"),
    ),
    "switch" to listOf(
        VerbDef("turn_on", "Turn on", "switch.turn_on"),
        VerbDef("turn_off", "Turn off", "switch.turn_off"),
    ),
    "fan" to listOf(
        VerbDef("turn_on", "Turn on", "fan.turn_on"),
        VerbDef("turn_off", "Turn off", "fan.turn_off"),
    ),
    "cover" to listOf(
        VerbDef("open", "Open", "cover.open_cover"),
        VerbDef("close", "Close", "cover.close_cover"),
    ),
    "alarm_control_panel" to listOf(
        VerbDef("arm_home", "Arm — Home", "alarm_control_panel.alarm_arm_home"),
        VerbDef("arm_away", "Arm — Away", "alarm_control_panel.alarm_arm_away"),
        VerbDef("disarm", "Disarm", "alarm_control_panel.alarm_disarm"),
    ),
    "scene" to listOf(VerbDef("activate", "Activate", "scene.turn_on")),
    "script" to listOf(VerbDef("run", "Run", "script.turn_on")),
)

/** Friendly labels for the action-domain picker. */
val DOMAIN_LABEL: Map<String, String> = mapOf(
    "lock" to "Locks",
    "light" to "Lights",
    "switch" to "Switches",
    "fan" to "Fans",
    "cover" to "Covers / Blinds",
    "alarm_control_panel" to "Alarm",
    "scene" to "Scenes",
    "script" to "Scripts",
)

/** Domains that can be targeted by an action, in picker order. */
val ACTION_DOMAINS: List<String> = ACTION_VERBS.keys.toList()

fun stateOptionsFor(domain: String): List<StateOption> = STATE_OPTIONS[domain] ?: emptyList()

fun verbsFor(domain: String): List<VerbDef> = ACTION_VERBS[domain] ?: emptyList()

fun serviceForVerb(domain: String, verb: String): String =
    verbsFor(domain).find { it.verb == verb }?.service
        ?: throw IllegalArgumentException("Unknown action $domain.$verb")

/** Reverse of [serviceForVerb]: full service string → {domain, verb}, or null. */
fun verbForService(service: String): Pair<String, String>? {
    for ((domain, verbs) in ACTION_VERBS) {
        val match = verbs.find { it.service == service }
        if (match != null) return domain to match.verb
    }
    return null
}

// --- trigger vocab ----------------------------------------------------------

/** The trigger types offered by the builder, in picker order. */
val TRIGGER_TYPES: List<TriggerTypeOption> = listOf(
    TriggerTypeOption(TriggerKind.STATE, "A device changes", "When a device reaches a state"),
    TriggerTypeOption(TriggerKind.TIME, "At a time", "At a specific time of day"),
    TriggerTypeOption(TriggerKind.SUN, "Sun", "At sunrise or sunset, with an offset"),
    TriggerTypeOption(TriggerKind.PRESENCE, "Someone comes/goes", "When a person arrives or leaves"),
)

/** Sun events for the sun-trigger picker. */
val SUN_EVENTS: List<StateOption> = listOf(
    StateOption("sunrise", "Sunrise"),
    StateOption("sunset", "Sunset"),
)

/** Enter/leave options for the presence-trigger picker. */
val PRESENCE_EVENTS: List<StateOption> = listOf(
    StateOption("enter", "Arrives home"),
    StateOption("leave", "Leaves home"),
)

/** Domains whose entities can drive a presence trigger, best first. */
val PRESENCE_DOMAINS: List<String> = listOf("person", "device_tracker")
