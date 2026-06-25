/**
 * Minimal Home Assistant entity shape, compatible with the `HassEntity` from
 * `home-assistant-js-websocket` so Phase 1 can swap fixtures for the live store
 * without changing consumers. Phase 0 uses fixtures only.
 */
export interface HassEntityAttributes {
  friendly_name?: string;
  icon?: string;
  // Domain-specific extras (brightness, device_class, etc.) live here too.
  [key: string]: unknown;
}

export interface HassEntity {
  entity_id: string;
  state: string;
  attributes: HassEntityAttributes;
  /** ISO timestamp of the last state change (when HA provides it). */
  last_changed?: string;
  /** ISO timestamp of the last update, incl. attribute-only changes. */
  last_updated?: string;
}

/** The domain is the prefix before the dot, e.g. `lock.front_door` -> `lock`. */
export function domainOf(entityId: string): string {
  return entityId.split(".", 1)[0];
}

/**
 * Area assignment is a runtime concern in real HA — it comes from the area +
 * entity registries (WS), never from the state stream. In Phase 0 we model it as
 * a separate map (see fixtures) to keep that seam honest for Phase 1.
 */
export type AreaRegistry = Record<string, string>;
