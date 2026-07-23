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

/** The lock's physical situation, as HA reports it. */
enum class LockPhase { LOCKED, UNLOCKED, LOCKING, UNLOCKING, JAMMED, UNAVAILABLE, UNKNOWN }

fun lockPhase(rawState: String): LockPhase = when (rawState) {
    "locked" -> LockPhase.LOCKED
    "unlocked" -> LockPhase.UNLOCKED
    "locking" -> LockPhase.LOCKING
    "unlocking" -> LockPhase.UNLOCKING
    "jammed" -> LockPhase.JAMMED
    "unavailable" -> LockPhase.UNAVAILABLE
    else -> LockPhase.UNKNOWN
}

/**
 * Pure view-model for the lock vault card. Two channels because the card speaks twice: the
 * slide track wears the *action's* color (unlocking is the cautionary streak, locking the
 * securing recovery — unchanged from the original SlideToAct wiring), while the frame wears
 * the *state's* color (recovery glow when secure, streak border when jammed, null otherwise).
 *
 * [boltThrown] is true only for an echoed `locked` — the bolt glyph animates on HA's word,
 * never on the user's gesture. That keeps the vault presentation inside the non-optimistic
 * lock invariant.
 */
data class LockVaultView(
    val phase: LockPhase,
    val actionChannel: Channel,
    val stateChannel: Channel?,
    val label: String,
    val actionLabel: String,
    val pendingLabel: String,
    val service: String,
    val boltThrown: Boolean,
    val transitional: Boolean,
    val enabled: Boolean,
)

fun lockVaultView(rawState: String): LockVaultView {
    val phase = lockPhase(rawState)
    val locked = phase == LockPhase.LOCKED
    return LockVaultView(
        phase = phase,
        actionChannel = if (locked) Channel.STREAK else Channel.RECOVERY,
        stateChannel = when (phase) {
            LockPhase.LOCKED -> Channel.RECOVERY
            LockPhase.JAMMED -> Channel.STREAK
            else -> null
        },
        label = lockStateLabel(rawState),
        actionLabel = when (phase) {
            LockPhase.LOCKED -> "Slide to unlock"
            LockPhase.JAMMED -> "Jammed — slide to retry"
            else -> "Slide to lock"
        },
        // Gate-pending before the first echo reads as the opposite action in flight.
        pendingLabel = when (phase) {
            LockPhase.LOCKING -> "Locking…"
            LockPhase.UNLOCKING -> "Unlocking…"
            LockPhase.LOCKED -> "Unlocking…"
            else -> "Locking…"
        },
        service = if (locked) "unlock" else "lock",
        boltThrown = locked,
        transitional = phase == LockPhase.LOCKING || phase == LockPhase.UNLOCKING,
        enabled = phase != LockPhase.UNAVAILABLE,
    )
}
