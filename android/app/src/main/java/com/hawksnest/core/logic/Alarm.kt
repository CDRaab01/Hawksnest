package com.hawksnest.core.logic

/** Which shield glyph the UI should draw; kept pure (mapped to a Material icon in the UI). */
enum class AlarmGlyph { SHIELD, SHIELD_ALERT, SHIELD_CHECK }

private val LABEL = mapOf(
    "disarmed" to "Disarmed",
    "armed_home" to "Armed — Home",
    "armed_away" to "Armed — Away",
    "armed_night" to "Armed — Night",
    "armed_vacation" to "Armed — Vacation",
    "arming" to "Arming…",
    "pending" to "Pending…",
    "disarming" to "Disarming…",
    "triggered" to "Triggered",
)

private val SHORT = mapOf(
    "disarmed" to "Disarmed",
    "armed_home" to "Home",
    "armed_away" to "Away",
    "armed_night" to "Night",
    "armed_vacation" to "Vacation",
    "triggered" to "Triggered",
)

/**
 * Pure view-model for an `alarm_control_panel` state. Shared by the alarm card and the security
 * hero/status pill so the read-out is identical everywhere. Ported from `src/lib/alarm.ts`.
 */
data class AlarmView(
    /** Full human label, e.g. "Armed — Away". */
    val label: String,
    /** Short label for the nav pill, e.g. "Away". */
    val short: String,
    val glyph: AlarmGlyph,
    /** PULSE channel: green when settled, blue when armed, orange when triggered. */
    val channel: Channel,
    val armed: Boolean,
    val triggered: Boolean,
    val transitioning: Boolean,
)

fun alarmView(state: String): AlarmView {
    val triggered = state == "triggered"
    val armed = state.startsWith("armed")
    val transitioning = state == "arming" || state == "pending" || state == "disarming"
    val glyph = when {
        triggered -> AlarmGlyph.SHIELD_ALERT
        armed -> AlarmGlyph.SHIELD
        else -> AlarmGlyph.SHIELD_CHECK
    }
    val channel = when {
        triggered -> Channel.STREAK
        armed -> Channel.EFFORT
        else -> Channel.RECOVERY
    }
    return AlarmView(
        label = LABEL[state] ?: state,
        short = SHORT[state] ?: LABEL[state] ?: state,
        glyph = glyph,
        channel = channel,
        armed = armed,
        triggered = triggered,
        transitioning = transitioning,
    )
}

/** HA alarm-panel states where a command is still settling (exit delays, entry countdowns). */
val ALARM_TRANSITIONAL = setOf("arming", "disarming", "pending")

/** One segment of the Off / Home / Away control. [service] is the HA `alarm_control_panel.*` call. */
data class ArmButton(val label: String, val service: String, val state: String)

/** The Off / Home / Away segmented control, in display order. */
val ARM_BUTTONS: List<ArmButton> = listOf(
    ArmButton("Off", "alarm_disarm", "disarmed"),
    ArmButton("Home", "alarm_arm_home", "armed_home"),
    ArmButton("Away", "alarm_arm_away", "armed_away"),
)
