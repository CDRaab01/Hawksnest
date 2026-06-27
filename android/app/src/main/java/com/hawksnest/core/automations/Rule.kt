package com.hawksnest.core.automations

import com.hawksnest.core.ha.domainOf
import kotlinx.serialization.json.JsonObject

/**
 * Automations — Hawksnest's "linkages": user-built *if this, then that* rules. The Kotlin port of
 * `src/lib/automations.ts`.
 *
 * Hawksnest does NOT run these — a [Rule] maps to a real Home Assistant automation config
 * ([ruleToConfig]) written via HA's Config API, so HA's own engine evaluates and runs it 24/7. The
 * [Rule] model is a deliberately small subset (one trigger, optional conditions, one+ service
 * actions); anything outside it round-trips back as `null` from [configToRule] so the editor falls
 * back to "open in Home Assistant" rather than corrupting a hand-written automation.
 */

/** A raw HA state string, e.g. "armed_home", "on", "locked". */
typealias StateValue = String

/** The single trigger that fires the rule (IFTTT-style "if this"). */
sealed interface RuleTrigger {
    /** A device/state trigger: fire when an entity reaches a state. */
    data class State(
        val entityId: String,
        val to: StateValue,
        val from: StateValue? = null,
        val forSeconds: Int? = null,
    ) : RuleTrigger

    /** A time-of-day trigger: fire at a wall-clock time (`HH:MM`, 24h). */
    data class Time(val at: String) : RuleTrigger

    /** A sun trigger: fire at sunrise/sunset, optionally offset by minutes (±). */
    data class Sun(val event: SunEvent, val offsetMinutes: Int = 0) : RuleTrigger

    /** A presence trigger: fire when a person enters or leaves the home zone. */
    data class Presence(
        val personEntityId: String,
        val event: PresenceEvent,
        val zone: String = "home",
    ) : RuleTrigger
}

enum class SunEvent(val value: String) { SUNRISE("sunrise"), SUNSET("sunset") }

enum class PresenceEvent(val value: String) { ENTER("enter"), LEAVE("leave") }

/** The trigger discriminant, e.g. for the builder's type picker. */
enum class TriggerKind(val id: String) { STATE("state"), TIME("time"), SUN("sun"), PRESENCE("presence") }

/** The kind discriminant for a trigger. */
val RuleTrigger.kind: TriggerKind
    get() = when (this) {
        is RuleTrigger.State -> TriggerKind.STATE
        is RuleTrigger.Time -> TriggerKind.TIME
        is RuleTrigger.Sun -> TriggerKind.SUN
        is RuleTrigger.Presence -> TriggerKind.PRESENCE
    }

/** An optional guard: an entity-state check or a time-of-day window. */
sealed interface RuleCondition {
    data class StateIs(val entityId: String, val state: StateValue) : RuleCondition
    data class TimeWindow(val after: String? = null, val before: String? = null) : RuleCondition
}

/** A friendly verb applied to one or more target entities of a domain. */
data class RuleAction(
    val domain: String,
    val verb: String,
    val targetEntityIds: List<String>,
    /** Optional extra service data, e.g. `{ "brightness_pct": 60 }`; round-tripped verbatim. */
    val data: JsonObject? = null,
)

data class Rule(
    /** HA automation id (generated for new rules; preserved on edit). */
    val id: String,
    val alias: String,
    val trigger: RuleTrigger,
    val conditions: List<RuleCondition> = emptyList(),
    val actions: List<RuleAction>,
    val mode: String = "single",
)

// --- factories --------------------------------------------------------------

/** A fresh id in HA's own scheme (epoch ms + jitter to avoid collisions). */
fun newRuleId(): String {
    val jitter = (0..999).random().toString().padStart(3, '0')
    return "${System.currentTimeMillis()}$jitter"
}

/** `automation.<slug>` source for demo-mode synthetic entities. */
fun slugify(text: String): String {
    val s = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return s.ifEmpty { "automation" }
}

/** A blank draft for the "create" flow. */
fun newRule(): Rule = Rule(
    id = newRuleId(),
    alias = "",
    trigger = RuleTrigger.State(entityId = "", to = ""),
    conditions = emptyList(),
    actions = listOf(RuleAction(domain = "lock", verb = "lock", targetEntityIds = emptyList())),
    mode = "single",
)

/** A blank trigger of a given kind, used when the builder switches trigger type. */
fun newTriggerOfKind(kind: TriggerKind): RuleTrigger = when (kind) {
    TriggerKind.STATE -> RuleTrigger.State(entityId = "", to = "")
    TriggerKind.TIME -> RuleTrigger.Time(at = "")
    TriggerKind.SUN -> RuleTrigger.Sun(event = SunEvent.SUNSET, offsetMinutes = 0)
    TriggerKind.PRESENCE -> RuleTrigger.Presence(personEntityId = "", event = PresenceEvent.ENTER, zone = "home")
}

/** Domain of a state trigger's entity (for choosing its state options); "" otherwise. */
fun triggerDomain(rule: Rule): String {
    val t = rule.trigger
    return if (t is RuleTrigger.State && t.entityId.isNotEmpty()) domainOf(t.entityId) else ""
}

/** A one-line, human-readable summary of a rule's trigger, for the list. */
fun describeTrigger(rule: Rule): String = when (val t = rule.trigger) {
    is RuleTrigger.State ->
        if (t.entityId.isNotEmpty()) "When ${t.entityId} → ${t.to}" else "When a device changes"
    is RuleTrigger.Time ->
        if (t.at.isNotEmpty()) "At ${t.at}" else "At a time of day"
    is RuleTrigger.Sun -> {
        val base = if (t.event == SunEvent.SUNRISE) "At sunrise" else "At sunset"
        val off = t.offsetMinutes
        if (off == 0) base else {
            val mins = kotlin.math.abs(off)
            "$base (${if (off < 0) "${mins}m before" else "${mins}m after"})"
        }
    }
    is RuleTrigger.Presence ->
        if (t.personEntityId.isNotEmpty()) {
            "When ${t.personEntityId} ${if (t.event == PresenceEvent.ENTER) "arrives home" else "leaves home"}"
        } else {
            "When someone arrives or leaves"
        }
}
