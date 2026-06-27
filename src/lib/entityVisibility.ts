import type { HassEntity } from "./ha";

/**
 * Object-id suffixes for ring-mqtt / Ring "housekeeping" entities that aren't real controls and
 * just clutter the device list, area views, history feed, and the automation picker. Home
 * Assistant's `entity_category` (config/diagnostic) already demotes most secondary entities, but
 * ring-mqtt frequently leaves these **untagged**, so they leak through a category-only filter.
 *
 * Deliberately NOT listed (kept visible — they're real signals/controls or already category-tagged):
 * `_battery`, `_volume`, `_tamper`, `_motion`, `_ding`, `_siren`, `_live`. Camera feed siblings
 * (`_snapshot`/`_live_view`) are folded into one logical camera by `cameraModel`, but we also
 * suppress them here so they don't show up as separate picker options.
 */
export const NOISE_SUFFIXES = [
  "_last_activity",
  "_info",
  "_event_stream",
  "_live_stream",
  "_event_select",
  "_bypass_mode",
  "_chirp_tone",
  "_snapshot",
  "_live_view",
] as const;

/** The object id (after the domain dot), e.g. `sensor.back_last_activity` → `back_last_activity`. */
function objectIdOf(entityId: string): string {
  const dot = entityId.indexOf(".");
  return dot >= 0 ? entityId.slice(dot + 1) : entityId;
}

/** A noise (housekeeping) entity by object-id suffix — independent of HA's `entity_category`. */
export function isNoiseEntity(entityId: string): boolean {
  const obj = objectIdOf(entityId);
  return NOISE_SUFFIXES.some((s) => obj.endsWith(s));
}

/**
 * A "primary" entity: a real control/signal worth surfacing in the main UI. False for anything HA
 * marks config/diagnostic (`categories`) or matching the ring-mqtt noise denylist. Non-primary
 * entities aren't deleted — they stay reachable under each device's Diagnostics section.
 */
export function isPrimaryEntity(
  entityId: string,
  categories: Record<string, string>,
): boolean {
  if (entityId in categories) return false;
  return !isNoiseEntity(entityId);
}

/** Filter a list of entities down to the primary ones. */
export function primaryEntities(
  entities: HassEntity[],
  categories: Record<string, string>,
): HassEntity[] {
  return entities.filter((e) => isPrimaryEntity(e.entity_id, categories));
}
