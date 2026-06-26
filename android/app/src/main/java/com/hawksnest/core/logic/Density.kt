package com.hawksnest.core.logic

import com.hawksnest.core.ha.domainOf

/** Card density: controls + feature tiles are comfortable; read-only sensors are compact. */
enum class Density { COMFORTABLE, COMPACT }

// Interactive control domains get the comfortable, one-primary-action card.
private val CONTROL_DOMAINS = setOf(
    "lock", "light", "switch", "alarm_control_panel", "cover", "climate", "media_player", "fan",
    "scene",
)

// Camera/image render as a full-width "feature" tile.
private val FEATURE_DOMAINS = setOf("camera", "image")

/** Controls + feature tiles are comfortable; everything else (read-only) is compact. */
fun cardDensityFor(entityId: String): Density {
    val domain = domainOf(entityId)
    return if (domain in FEATURE_DOMAINS || domain in CONTROL_DOMAINS) {
        Density.COMFORTABLE
    } else {
        Density.COMPACT
    }
}

/** Feature tiles span the full grid width (e.g. a camera). */
fun isFeature(entityId: String): Boolean = domainOf(entityId) in FEATURE_DOMAINS
