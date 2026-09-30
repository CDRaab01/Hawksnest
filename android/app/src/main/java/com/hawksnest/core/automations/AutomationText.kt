package com.hawksnest.core.automations

import com.hawksnest.core.ha.domainOf
import com.hawksnest.core.logic.describeStateChange
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * "When" for an automation's detail sheet, in words, from its Home Assistant config: "Garage Door:
 * opened", "At 22:30", "At sunset". Read-only and best effort: any trigger it doesn't recognise is
 * "Something else happens" rather than raw YAML, and the automation's own description (shown
 * beneath) carries the detail.
 *
 * [nameOf] turns an entity id into the name the rest of the app uses; [deviceClassOf] gives HA's
 * device class so a door reads "opened" rather than "turned on".
 */
fun describeTriggers(
    config: JsonObject,
    nameOf: (String) -> String,
    deviceClassOf: (String) -> String? = { null },
): String? {
    val triggers = asList(config["triggers"] ?: config["trigger"])
    if (triggers.isEmpty()) return null
    val phrases = triggers.mapNotNull { it as? JsonObject }.map { describeTrigger(it, nameOf, deviceClassOf) }.distinct()
    return when {
        phrases.isEmpty() -> null
        phrases.size <= 2 -> phrases.joinToString(" or ")
        else -> "${phrases.take(2).joinToString(", ")} or ${phrases.size - 2} more"
    }
}

private fun describeTrigger(t: JsonObject, nameOf: (String) -> String, deviceClassOf: (String) -> String?): String {
    val kind = str(t["trigger"]) ?: str(t["platform"])
    val entities = asList(t["entity_id"]).mapNotNull { str(it) }
    val who = when (entities.size) {
        0 -> null
        1 -> nameOf(entities[0])
        else -> "${nameOf(entities[0])} or ${entities.size - 1} more"
    }
    return when (kind) {
        "state" -> {
            val to = str(t["to"])
            val from = str(t["from"])
            val first = entities.firstOrNull()
            val phrase = if (first != null && to != null) {
                describeStateChange(domainOf(first), deviceClassOf(first), to)
            } else {
                null
            }
            when {
                who == null -> "A device changes"
                to == "unavailable" -> "$who goes offline"
                to == null && from == "unavailable" -> "$who comes back online"
                phrase != null -> "$who: ${phrase.replaceFirstChar { it.lowercase() }}"
                to != null -> "$who becomes $to"
                else -> "$who changes"
            }
        }
        "numeric_state" -> {
            val above = str(t["above"])
            val below = str(t["below"])
            when {
                who == null -> "A reading crosses a limit"
                above != null && below != null -> "$who goes between $above and $below"
                above != null -> "$who goes above $above"
                below != null -> "$who goes below $below"
                else -> "$who changes"
            }
        }
        "time" -> str(t["at"])?.let { at ->
            when {
                // "22:30:00" reads as "22:30".
                Regex("""^\d{1,2}:\d{2}:00$""").matches(at) -> "At ${at.dropLast(3)}"
                // A time held in a helper: name it.
                '.' in at -> "At the time in ${nameOf(at)}"
                else -> "At $at"
            }
        } ?: "At a set time"
        "time_pattern" -> "On a repeating schedule"
        "sun" -> "At ${str(t["event"]) ?: "sunrise or sunset"}"
        "zone" -> {
            val event = if (str(t["event"]) == "leave") "leaves" else "arrives at"
            val zone = str(t["zone"])?.substringAfter("zone.")?.replace('_', ' ') ?: "a place"
            "${who ?: "Someone"} $event $zone"
        }
        "homeassistant" -> "Home Assistant starts"
        "event" -> "A device event"
        "template" -> "A condition becomes true"
        "webhook" -> "A webhook is called"
        "device" -> "A device does something"
        else -> "Something else happens"
    }
}

private fun asList(e: JsonElement?): List<JsonElement> = when (e) {
    null -> emptyList()
    is JsonArray -> e
    else -> listOf(e)
}

private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

/** The automation's own description, trimmed, or null. What it does, in its author's words. */
fun automationDescription(config: JsonObject): String? =
    str(config["description"])?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }
