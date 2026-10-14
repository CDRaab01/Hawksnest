package com.hawksnest.core.automations

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the Automations screen organises a house's automations: into groups by room, each with a
 * short readable title, so 36 near-identical truncated rows ("Master Bedroom LE…" five times)
 * become a handful of rooms you open.
 *
 * Nothing is renamed. The names already say where and what ("Nursery LED effect - garage door
 * opened"); this only reads that structure back out. A room the owner assigns to the automation in
 * Home Assistant wins over anything read from the name.
 */

/** Where a name's parts landed. [subtitle] is the "what it does" half, when the name has one. */
data class AutomationTitle(
    val group: String,
    val title: String,
    val subtitle: String?,
    /** Background plumbing (phone alerts, camera housekeeping, watchdogs): no Run, listed last. */
    val system: Boolean,
)

/** The section for automations whose names name no room. */
const val WHOLE_HOUSE_GROUP = "Whole house"

/** The section for the automations hawksnest-automation deploys to keep the house running. */
const val SYSTEM_GROUP = "Behind the scenes"

/**
 * Read a group and title out of an automation's [alias].
 *
 * First match wins:
 *  1. [assignedArea], the room the automation is given in Home Assistant, if any;
 *  2. the hawksnest-automation prefixes ("Hawksnest:", "Hawksnest push:") → [SYSTEM_GROUP];
 *  3. a leading room name from [areaNames] ("Master Bedroom LED bar - …"), longest first;
 *  4. text before a colon ("Garage: bay open too long");
 *  5. text before " - " ("Kid's ZEN32 - big button");
 *  6. otherwise [WHOLE_HOUSE_GROUP] and the whole name.
 *
 * What remains splits on the first " - " into title and subtitle ("LED bar" / "Alarm colour +
 * dim at night"), or on a trailing parenthetical ("Person at Front" / "hub PIR, instant").
 */
fun titleAutomation(alias: String, areaNames: Collection<String>, assignedArea: String? = null): AutomationTitle {
    val name = alias.trim()
    val systemPrefix = SYSTEM_PREFIXES.firstOrNull { name.startsWith(it, ignoreCase = true) }
    val area = assignedArea?.takeIf { it.isNotBlank() }

    // What's left of the name once the group is read off it.
    val (group, rest, system) = when {
        area != null -> Triple(area, stripLeading(name, listOfNotNull(systemPrefix) + area), false)
        systemPrefix != null -> Triple(SYSTEM_GROUP, name.substring(systemPrefix.length), true)
        else -> {
            val room = areaNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
                .firstOrNull { startsWithWord(name, it) }
            val colon = name.indexOf(':')
            val dash = name.indexOf(" - ")
            when {
                // The room is only taken off the title when the name is shaped "<room>: …",
                // "<room> - …" or "<room> <device> - <what>"; "Garage Door Unarm" keeps its name.
                room != null -> Triple(room, if (roomIsAPrefix(name, room)) name.substring(room.length) else name, false)
                colon > 0 -> Triple(name.substring(0, colon).trim(), name.substring(colon + 1), false)
                dash > 0 -> Triple(name.substring(0, dash).trim(), name.substring(dash + 3), false)
                else -> Triple(WHOLE_HOUSE_GROUP, name, false)
            }
        }
    }
    val (title, subtitle) = splitTitle(rest.trim().trimStart(':', '-', ' ').trim().ifEmpty { name })
    return AutomationTitle(group, title, subtitle, system)
}

private val SYSTEM_PREFIXES = listOf("Hawksnest push:", "Hawksnest:")

private fun roomIsAPrefix(name: String, room: String): Boolean {
    val after = name.substring(room.length).trimStart()
    return after.startsWith(":") || after.startsWith("- ") || " - " in after
}

private fun startsWithWord(name: String, prefix: String): Boolean =
    name.length > prefix.length && name.startsWith(prefix, ignoreCase = true) &&
        !name[prefix.length].isLetterOrDigit()

private fun stripLeading(name: String, prefixes: List<String>): String {
    var s = name
    prefixes.forEach { p -> if (startsWithWord(s, p)) s = s.substring(p.length).trimStart(':', ' ') }
    return s
}

private fun splitTitle(rest: String): Pair<String, String?> {
    val dash = rest.indexOf(" - ")
    if (dash > 0) return cap(rest.substring(0, dash).trim()) to cap(rest.substring(dash + 3).trim()).ifEmpty { null }
    val paren = Regex("""^(.*\S)\s*\(([^()]+)\)$""").find(rest)
    if (paren != null) return cap(paren.groupValues[1]) to paren.groupValues[2].trim()
    return cap(rest) to null
}

private fun cap(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

/**
 * The order groups are shown in: rooms alphabetically, then [WHOLE_HOUSE_GROUP], then
 * [SYSTEM_GROUP] last, since those are the ones the owner rarely touches.
 */
fun groupOrder(group: String): Pair<Int, String> = when (group) {
    SYSTEM_GROUP -> 2 to group
    WHOLE_HOUSE_GROUP -> 1 to group
    else -> 0 to group.lowercase()
}

/**
 * A row's "last ran", short enough to sit beside a switch: "9:37 PM" today, "Yesterday", a weekday
 * within the week, else "Sep 26". "Never" when it hasn't run.
 */
fun shortLastRan(lastMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastMs == null) return "Never"
    val then = Instant.ofEpochMilli(lastMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val day = then.toLocalDate()
    return when {
        day == today -> then.format(TIME)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> then.format(WEEKDAY)
        else -> then.format(DATE)
    }
}

/** Whether an automation last ran on [nowMs]'s calendar day. */
fun ranToday(lastMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    lastMs != null && Instant.ofEpochMilli(lastMs).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US)
private val DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)
