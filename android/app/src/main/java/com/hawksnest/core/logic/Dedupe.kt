package com.hawksnest.core.logic

import com.hawksnest.core.ha.HassEntity
import com.hawksnest.core.ha.friendlyName

/** HA integration platform ids for the two Ring paths. */
const val RING_PLATFORM = "ring"
const val MQTT_PLATFORM = "mqtt"

/**
 * Collapse the Ring-vs-ring-mqtt double exposure.
 *
 * The household runs **both** the official Ring integration and ring-mqtt, so
 * every Ring light (and potentially other domains) appears twice with the same
 * friendly name — the Devices screen showed "Front Light" twice, once per
 * integration. ring-mqtt is this app's documented backend (cameras, events,
 * go2rtc), so when a `ring`-platform entity has an `mqtt`-platform twin —
 * same domain, same normalized name — the `ring` one is dropped.
 *
 * Deliberately narrow: only the {ring, mqtt} pair dedupes, and only on an
 * exact (domain, normalized-name) collision. Anything else HA exposes twice is
 * a configuration question for HA, not something to guess at client-side.
 * (The real hygiene fix is disabling the duplicate entities in one of the two
 * integrations in HA — this keeps the app honest until/despite that.)
 */
fun dedupeRingMqtt(
    entities: Map<String, HassEntity>,
    platforms: Map<String, String>,
): Map<String, HassEntity> {
    if (platforms.isEmpty()) return entities

    // (domain, normalized name) → the platforms present for that identity.
    val platformsByIdentity = HashMap<Pair<String, String>, MutableSet<String>>()
    for (e in entities.values) {
        val platform = platforms[e.entityId] ?: continue
        if (platform != RING_PLATFORM && platform != MQTT_PLATFORM) continue
        platformsByIdentity
            .getOrPut(identityOf(e)) { mutableSetOf() }
            .add(platform)
    }

    return entities.filter { (id, e) ->
        val platform = platforms[id] ?: return@filter true
        if (platform != RING_PLATFORM) return@filter true
        // A ring entity survives only when no mqtt twin claims the same identity.
        MQTT_PLATFORM !in (platformsByIdentity[identityOf(e)] ?: emptySet())
    }
}

private fun identityOf(e: HassEntity): Pair<String, String> {
    val domain = e.entityId.substringBefore('.')
    val name = (e.friendlyName() ?: e.entityId.substringAfter('.'))
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    return domain to name
}
