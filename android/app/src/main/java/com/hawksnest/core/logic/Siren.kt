package com.hawksnest.core.logic

import com.hawksnest.core.ha.domainOf

/**
 * A `switch` that sounds a siren: ring-mqtt's `switch.<camera>_siren` and the alarm panel's
 * `switch.mfa_alarm_siren`. These are switches only in HA's plumbing. Drawn as one, a siren sat in
 * the Devices "Lights & switches" grid as a one-tap toggle, truncated to "Basement Stora…" so
 * nothing on the tile said siren, and a stray tap sounded it (2026-09-27).
 *
 * The suffix is deliberate: Reolink's `switch.<camera>_siren_on_event` is a config toggle ("sound
 * the siren on a detection"), not a siren, and stays an ordinary switch. The `siren` DOMAIN is a
 * different entity with different services and is not matched here either.
 */
fun isSirenSwitch(entityId: String): Boolean =
    domainOf(entityId) == "switch" && entityId.endsWith("_siren")

/** What one tap on a siren control does. */
enum class SirenTap {
    /** First tap on a quiet siren: ask for the second one. */
    ARM,

    /** Second tap inside [SIREN_CONFIRM_WINDOW_MS]: sound it. */
    SOUND,

    /** Any tap on a sounding siren: silence it at once. */
    SILENCE,
}

/**
 * Two taps to sound, one to silence. The same asymmetry the doorbell panel's siren and the web
 * `SirenButton` use: a siren is loud, so firing it takes deliberate intent, while stopping it is
 * always the fast path.
 */
fun sirenTap(on: Boolean, armed: Boolean): SirenTap = when {
    on -> SirenTap.SILENCE
    armed -> SirenTap.SOUND
    else -> SirenTap.ARM
}

/** How long an armed siren control waits for the confirming second tap. */
const val SIREN_CONFIRM_WINDOW_MS = 3_000L
