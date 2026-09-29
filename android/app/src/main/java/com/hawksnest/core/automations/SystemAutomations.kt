package com.hawksnest.core.automations

/**
 * Whether an automation is part of the system rather than the household: the ones the
 * hawksnest-automation repo deploys to keep cameras, push and watchdogs working ("Hawksnest: park
 * the Home Hub battery cameras in Frigate", "Hawksnest push: doorbell ding"). They carry that prefix
 * by convention in that repo.
 *
 * The list offers them no Run button. Running one by hand does something nobody asked for: parks a
 * camera, fires a push to every phone, or trips a watchdog. They still show, so their state and
 * last run stay visible, and can still be switched off.
 */
fun isSystemAutomation(alias: String): Boolean {
    val a = alias.trim()
    return SYSTEM_PREFIXES.any { a.startsWith(it, ignoreCase = true) }
}

private val SYSTEM_PREFIXES = listOf("Hawksnest:", "Hawksnest push:")
