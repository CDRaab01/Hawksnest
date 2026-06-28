package com.hawksnest.core.logic

/**
 * Human-readable label for a Home Assistant lock state. A jam is a terminal
 * *failure* (the bolt couldn't throw) — surface it clearly with a retry hint and
 * never let it read as "Unlocked". Mirrors the web LockCard wording so both
 * clients speak about the lock identically.
 */
fun lockStateLabel(state: String): String = when (state) {
    "locked" -> "Locked"
    "unlocked" -> "Unlocked"
    "locking" -> "Locking…"
    "unlocking" -> "Unlocking…"
    "jammed" -> "Jammed — try again"
    "unavailable" -> "Unavailable"
    else -> state.replaceFirstChar { it.uppercaseChar() }
}
