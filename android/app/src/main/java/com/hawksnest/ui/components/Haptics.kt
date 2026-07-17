package com.hawksnest.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Semantic haptics for the control surfaces. Every actuation gets a tick, every confirmed echo a
 * confirm, every failure a reject — the app should feel like it has switches, not glass. One
 * vocabulary here so all controls buzz alike; don't call [HapticFeedback] directly in components.
 */
class Haptics(private val hf: HapticFeedback) {
    /** A toggle flipped on (switch thumbs, segment selection). */
    fun toggleOn() = hf.performHapticFeedback(HapticFeedbackType.ToggleOn)

    /** A toggle flipped off. */
    fun toggleOff() = hf.performHapticFeedback(HapticFeedbackType.ToggleOff)

    /** An action committed and confirmed (slide reached the end, HA echoed the new state). */
    fun confirm() = hf.performHapticFeedback(HapticFeedbackType.Confirm)

    /** An action failed or was rejected. */
    fun reject() = hf.performHapticFeedback(HapticFeedbackType.Reject)

    /** Crossing an actionable threshold mid-gesture (the slide arming its commit point). */
    fun threshold() = hf.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
}

/** The [Haptics] vocabulary bound to the current composition's [LocalHapticFeedback]. */
@Composable
fun rememberHaptics(): Haptics {
    val hf = LocalHapticFeedback.current
    return remember(hf) { Haptics(hf) }
}
