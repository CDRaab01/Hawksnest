package com.hawksnest.core.logic

/**
 * Appearance preference. Mirrors the web `ThemePref` in `src/store/theme.ts`.
 *
 * **The default differs from web on purpose.** Web defaults to `dark` — it was designed as a
 * wall-mounted OLED dashboard and has never had another default. Android has always followed the
 * system setting, so defaulting to `dark` here would silently change what the phone looks like
 * for anyone who already had it. Preserving today's behaviour beats matching a constant, and the
 * picker makes either reachable in one tap.
 */
enum class ThemePref {
    Dark,
    Light,
    System;

    companion object {
        val DEFAULT = System

        /** Tolerant of anything unrecognised — a bad stored value must not brick the UI. */
        fun parse(raw: String?): ThemePref =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Resolve a preference to the boolean the theme actually needs.
 *
 * [systemIsDark] is passed rather than read, so this stays pure and testable — resolving inside
 * a `@Composable` would make the one branch worth checking (that System defers, and the others
 * override) reachable only from an instrumented test.
 */
fun resolveDarkTheme(pref: ThemePref, systemIsDark: Boolean): Boolean = when (pref) {
    ThemePref.Dark -> true
    ThemePref.Light -> false
    ThemePref.System -> systemIsDark
}
