import type { HassEntity } from "./ha";

/** HA integration platform ids for the two Ring paths. */
export const RING_PLATFORM = "ring";
export const MQTT_PLATFORM = "mqtt";

/**
 * Collapse the Ring-vs-ring-mqtt double exposure.
 *
 * The household runs **both** the official Ring integration and ring-mqtt, so
 * every Ring light (and potentially other domains) appears twice with the same
 * friendly name. ring-mqtt is this app's documented backend (cameras, events,
 * go2rtc), so when a `ring`-platform entity has an `mqtt`-platform twin —
 * same domain, same normalized name — the `ring` one is dropped.
 *
 * Deliberately narrow: only the {ring, mqtt} pair dedupes, and only on an
 * exact (domain, normalized-name) collision. Anything else HA exposes twice is
 * a configuration question for HA, not something to guess at client-side.
 * Mirrors Android `core/logic/Dedupe.kt`.
 */
export function dedupeRingMqtt(
  entities: Record<string, HassEntity>,
  platforms: Record<string, string>,
): Record<string, HassEntity> {
  if (Object.keys(platforms).length === 0) return entities;

  const mqttIdentities = new Set<string>();
  for (const e of Object.values(entities)) {
    if (platforms[e.entity_id] === MQTT_PLATFORM) mqttIdentities.add(identityOf(e));
  }

  const out: Record<string, HassEntity> = {};
  for (const [id, e] of Object.entries(entities)) {
    const isShadowedRing =
      platforms[id] === RING_PLATFORM && mqttIdentities.has(identityOf(e));
    if (!isShadowedRing) out[id] = e;
  }
  return out;
}

function identityOf(e: HassEntity): string {
  const domain = e.entity_id.split(".")[0];
  const raw =
    typeof e.attributes.friendly_name === "string"
      ? e.attributes.friendly_name
      : e.entity_id.split(".")[1] ?? "";
  const name = raw.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  return `${domain}|${name}`;
}
