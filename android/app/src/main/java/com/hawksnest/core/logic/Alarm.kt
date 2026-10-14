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

/**
 * Whether one Off / Home / Away button takes a tap right now. Home's hero and the Devices segment
 * control both ask this, so they cannot disagree again.
 *
 * - While this app's own command is in flight ([inFlight]), none do: one command at a time, and
 *   the button that sent it holds a spinner until HA answers.
 * - HA's own transitions never lock the buttons. `pending` is the entry countdown after a door
 *   opens on an armed house and `arming` is the exit delay, and both are exactly when you reach
 *   for Off. Home used to treat them as busy, so the countdown ran with Off refusing taps.
 * - The mode the panel is already in is not sent again.
 */
fun armButtonEnabled(button: ArmButton, rawState: String?, inFlight: Boolean): Boolean =
    !inFlight && rawState != button.state
