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
 * Domains that aren't physical "devices" and shouldn't appear in the Devices
 * hub — automations/scripts/scenes have their own surfaces, and people/zones/the
 * sun are infrastructure entities the automation builder consumes. Shared so web
 * and Android filter the device list the same way.
 */
export const NON_DEVICE_DOMAINS = new Set<string>([
  "automation",
  "script",
  "scene",
  "person",
  "zone",
  "sun",
]);

/**
 * Area assignment is a runtime concern in real HA — it comes from the area +
 * entity registries (WS), never from the state stream. In Phase 0 we model it as
 * a separate map (see fixtures) to keep that seam honest for Phase 1.
 */
export type AreaRegistry = Record<string, string>;

/**
 * True when a light actually supports brightness levels. HA marks on/off-only
 * lights (relay/switch-type, e.g. Ring smart lighting) with
 * `supported_color_modes: ["onoff"]` — those must not get a dimmer slider or a
 * "% brightness" readout. When color modes are absent (older integrations),
 * fall back to whether a `brightness` attribute exists — unreliable while the
 * light is OFF (HA drops `brightness` then), which is why color modes win.
 */
export function isDimmableLight(entity: HassEntity): boolean {
  const modes = entity.attributes.supported_color_modes;
  if (Array.isArray(modes)) {
    return modes.some((m) => m !== "onoff");
  }
  return typeof entity.attributes.brightness === "number";
}
