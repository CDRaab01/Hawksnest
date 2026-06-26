package com.hawksnest.security

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Actions that make the home *less* secure require the biometric gate; arming/locking never do.
 * Keeping the rule in one predicate makes the gated set auditable in isolation.
 */
fun isSensitiveAction(domain: String, service: String): Boolean =
    (domain == "lock" && service == "unlock") ||
        (domain == "alarm_control_panel" && service == "alarm_disarm")

/**
 * A gate invoked from the UI layer (it needs the Activity to show a `BiometricPrompt`). The default
 * is a pass-through — used by previews/tests and any host that doesn't provide the real one;
 * `MainActivity` provides the biometric-backed implementation over the whole nav graph.
 */
typealias Gate = (reason: String, action: () -> Unit) -> Unit

val LocalBiometricGate = staticCompositionLocalOf<Gate> { { _, action -> action() } }
