package com.hawksnest.core.logic

import com.hawksnest.core.ha.domainOf

/**
 * The first-class card kinds. Anything unmapped falls back to [GENERIC] so the UI degrades instead
 * of crashing. Ported from `src/lib/cards.ts` (the React component map becomes an enum the UI
 * switches on).
 */
enum class CardType {
    LOCK, CAMERA, BINARY_SENSOR, LIGHT, ALARM, COVER, CLIMATE, MEDIA_PLAYER, FAN, GENERIC,
}

private val CARD_BY_DOMAIN: Map<String, CardType> = mapOf(
    "lock" to CardType.LOCK,
    "camera" to CardType.CAMERA,
    "image" to CardType.CAMERA,
    "binary_sensor" to CardType.BINARY_SENSOR,
    "light" to CardType.LIGHT,
    "alarm_control_panel" to CardType.ALARM,
    "cover" to CardType.COVER,
    "climate" to CardType.CLIMATE,
    "media_player" to CardType.MEDIA_PLAYER,
    "fan" to CardType.FAN,
)

/**
 * Resolve the card kind for an entity_id. Never throws on an unknown domain — returns
 * [CardType.GENERIC].
 */
fun domainToCard(entityId: String): CardType =
    CARD_BY_DOMAIN[domainOf(entityId)] ?: CardType.GENERIC
