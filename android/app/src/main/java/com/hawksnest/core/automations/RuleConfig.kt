package com.hawksnest.core.automations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Rule ⇄ Home Assistant automation-config mapping — the Kotlin port of `ruleToConfig`/`configToRule`
 * in `src/lib/automations.ts`. The config JSON is exchanged with HA's Config API verbatim, so the
 * mappings must match the web's byte-for-byte.
 */

// --- Rule -> HA config ------------------------------------------------------

/** Map a [Rule] to a Home Assistant automation config (Config API body). */
fun ruleToConfig(rule: Rule): JsonObject = buildJsonObject {
    put("id", rule.id)
    put("alias", rule.alias)
    put("mode", rule.mode)
    putJsonArray("trigger") { add(triggerToHa(rule.trigger)) }
    putJsonArray("condition") { rule.conditions.forEach { add(conditionToHa(it)) } }
    putJsonArray("action") { rule.actions.forEach { add(actionToHa(it)) } }
}

private fun triggerToHa(t: RuleTrigger): JsonObject = buildJsonObject {
    when (t) {
        is RuleTrigger.State -> {
            put("platform", "state")
            put("entity_id", t.entityId)
            put("to", t.to)
            if (!t.from.isNullOrEmpty()) put("from", t.from)
            if (t.forSeconds != null && t.forSeconds > 0) putJsonObject("for") { put("seconds", t.forSeconds) }
        }
        is RuleTrigger.Time -> {
            put("platform", "time")
            put("at", padTime(t.at))
        }
        is RuleTrigger.Sun -> {
            put("platform", "sun")
            put("event", t.event.value)
            if (t.offsetMinutes != 0) put("offset", minutesToOffset(t.offsetMinutes))
        }
        is RuleTrigger.Presence -> {
            put("platform", "zone")
            put("entity_id", t.personEntityId)
            put("zone", "zone.${t.zone}")
            put("event", t.event.value)
        }
    }
}

private fun conditionToHa(c: RuleCondition): JsonObject = buildJsonObject {
    when (c) {
        is RuleCondition.TimeWindow -> {
            put("condition", "time")
            if (!c.after.isNullOrEmpty()) put("after", c.after)
            if (!c.before.isNullOrEmpty()) put("before", c.before)
        }
        is RuleCondition.StateIs -> {
            put("condition", "state")
            put("entity_id", c.entityId)
            put("state", c.state)
        }
    }
}

private fun actionToHa(a: RuleAction): JsonObject = buildJsonObject {
    put("service", serviceForVerb(a.domain, a.verb))
    putJsonObject("target") { putJsonArray("entity_id") { a.targetEntityIds.forEach { add(it) } } }
    if (a.data != null && a.data.isNotEmpty()) put("data", a.data)
}

// --- HA config -> Rule (null when outside the V1 subset) --------------------

/**
 * Parse an HA automation config into a [Rule], or `null` if it falls outside the V1 subset (multiple
 * triggers, unsupported platforms/services, templates, …). Callers treat `null` as "show read-only /
 * edit in HA".
 */
fun configToRule(config: JsonObject): Rule? {
    val triggers = asArray(config["trigger"] ?: config["triggers"])
    if (triggers.size != 1) return null
    val trigger = parseTrigger(triggers[0]) ?: return null

    val conditions = mutableListOf<RuleCondition>()
    for (raw in asArray(config["condition"] ?: config["conditions"])) {
        val parsed = parseCondition(raw) ?: return null
        conditions.add(parsed)
    }

    val rawActions = asArray(config["action"] ?: config["actions"])
    if (rawActions.isEmpty()) return null
    val actions = mutableListOf<RuleAction>()
    for (raw in rawActions) {
        val parsed = parseAction(raw) ?: return null
        actions.add(parsed)
    }

    val id = str(config["id"]) ?: ""
    val alias = str(config["alias"]) ?: id
    val mode = if (str(config["mode"]) == "restart") "restart" else "single"
    return Rule(id = id, alias = alias, trigger = trigger, conditions = conditions, actions = actions, mode = mode)
}

private fun parseTrigger(raw: JsonElement): RuleTrigger? {
    if (raw !is JsonObject) return null
    // Accept classic `platform` and modern `trigger` keys.
    return when (str(raw["platform"]) ?: str(raw["trigger"])) {
        "state" -> {
            val entityId = singleEntityId(raw["entity_id"]) ?: return null
            if (entityId.isEmpty()) return null
            val to = str(raw["to"]) ?: return null
            if (to.isEmpty()) return null
            val from = str(raw["from"])?.takeIf { it.isNotEmpty() }
            val forSeconds = (raw["for"] as? JsonObject)?.let { (it["seconds"] as? JsonPrimitive)?.intOrNull }
            RuleTrigger.State(entityId = entityId, to = to, from = from, forSeconds = forSeconds)
        }
        "time" -> {
            // Only a single wall-clock time; an array of times or an input_datetime entity is unsupported.
            val at = str(raw["at"]) ?: return null
            if (at.isEmpty()) return null
            RuleTrigger.Time(at = stripTimeSeconds(at))
        }
        "sun" -> {
            val event = when (str(raw["event"])) {
                "sunrise" -> SunEvent.SUNRISE
                "sunset" -> SunEvent.SUNSET
                else -> return null
            }
            RuleTrigger.Sun(event = event, offsetMinutes = offsetElementToMinutes(raw["offset"]))
        }
        "zone" -> {
            val personEntityId = singleEntityId(raw["entity_id"]) ?: return null
            if (personEntityId.isEmpty()) return null
            val event = when (str(raw["event"])) {
                "enter" -> PresenceEvent.ENTER
                "leave" -> PresenceEvent.LEAVE
                else -> return null
            }
            val zone = str(raw["zone"])
            // V1 only models the built-in home zone.
            if (zone != "zone.home" && zone != "home") return null
            RuleTrigger.Presence(personEntityId = personEntityId, event = event, zone = "home")
        }
        else -> null
    }
}

private fun parseCondition(raw: JsonElement): RuleCondition? {
    if (raw !is JsonObject) return null
    return when (str(raw["condition"])) {
        "state" -> {
            val entityId = str(raw["entity_id"]) ?: return null
            if (entityId.isEmpty()) return null
            val state = str(raw["state"]) ?: return null
            RuleCondition.StateIs(entityId = entityId, state = state)
        }
        "time" -> {
            val after = str(raw["after"])?.takeIf { it.isNotEmpty() }
            val before = str(raw["before"])?.takeIf { it.isNotEmpty() }
            if (after == null && before == null) return null
            RuleCondition.TimeWindow(after = after, before = before)
        }
        else -> null
    }
}

private fun parseAction(raw: JsonElement): RuleAction? {
    if (raw !is JsonObject) return null
    // Accept classic `service` and modern `action` keys.
    val service = str(raw["service"]) ?: str(raw["action"]) ?: return null
    val (domain, verb) = verbForService(service) ?: return null
    val targets = collectTargets(raw)
    if (targets.isEmpty()) return null
    val data = (raw["data"] as? JsonObject)?.takeIf { it.isNotEmpty() }
    return RuleAction(domain = domain, verb = verb, targetEntityIds = targets, data = data)
}

/** Target entity ids from `target.entity_id` or a bare `entity_id`. */
private fun collectTargets(raw: JsonObject): List<String> {
    val ids = (raw["target"] as? JsonObject)?.get("entity_id") ?: raw["entity_id"]
    return when (ids) {
        is JsonPrimitive -> if (ids.isString) listOf(ids.content) else emptyList()
        is JsonArray -> ids.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }
}

// --- sun offset helpers -----------------------------------------------------

/** Signed minutes → HA offset string `±HH:MM:SS`. */
fun minutesToOffset(minutes: Int): String {
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "%s%02d:%02d:00".format(sign, abs / 60, abs % 60)
}

/** HA offset string (`±HH:MM:SS`) → signed minutes; 0 for null/blank/malformed. */
fun offsetToMinutes(offset: String?): Int {
    val s = offset?.trim().orEmpty()
    if (s.isEmpty()) return 0
    val m = Regex("^([+-]?)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(s) ?: return 0
    val sign = if (m.groupValues[1] == "-") -1 else 1
    return sign * (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt())
}

/** A sun `offset` value (a `±HH:MM:SS` string, or bare integer seconds) → signed minutes. */
private fun offsetElementToMinutes(offset: JsonElement?): Int {
    val p = offset as? JsonPrimitive ?: return 0
    if (p.isString) return offsetToMinutes(p.content)
    val seconds = p.intOrNull ?: return 0
    return Math.round(seconds / 60.0).toInt()
}

/** Pad a wall-clock `HH:MM` to HA's `HH:MM:SS`. */
private fun padTime(at: String): String = if (at.count { it == ':' } == 1) "$at:00" else at

/** Strip a trailing `:SS` so `HH:MM:SS` round-trips back to the `HH:MM` input. */
private fun stripTimeSeconds(at: String): String =
    Regex("^(\\d{1,2}:\\d{2})(?::\\d{2})?$").find(at)?.groupValues?.get(1) ?: at

// --- small JSON helpers -----------------------------------------------------

private fun asArray(v: JsonElement?): List<JsonElement> = when (v) {
    is JsonArray -> v
    null, JsonNull -> emptyList()
    else -> listOf(v)
}

private fun str(v: JsonElement?): String? {
    val p = v as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

/** A single entity_id from a string or single-element array; else null. */
private fun singleEntityId(v: JsonElement?): String? {
    if (v is JsonPrimitive && v.isString) return v.content
    if (v is JsonArray && v.size == 1) {
        val first = v[0]
        if (first is JsonPrimitive && first.isString) return first.content
    }
    return null
}
