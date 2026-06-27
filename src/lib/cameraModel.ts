import type { HassEntity } from "./ha";
import { domainOf } from "./ha";
import { resolveName, type OverrideMap } from "./resolve";

/**
 * One logical camera, independent of how the backend models it.
 *
 * **ring-mqtt** splits a single Ring camera across several HA entities —
 * `camera.<base>_live` (stream), `camera.<base>_snapshot` (still),
 * `camera.<base>_event` (recorded-event playback), `select.<base>_event_select`
 * (which of the last ~5 events to play), and `binary_sensor.<base>_motion`/
 * `_ding`. This collapses them into one camera so the wall shows one tile and the
 * player binds the right feed + events. Plain HA / Frigate cameras (a single
 * `camera.<x>`) map to a logical camera with no siblings — fully backward-compatible.
 */
export interface LogicalCamera {
  /** Stable logical id (`camera.<base>`), used as a React key and for routing. */
  id: string;
  name: string;
  /** Entity to stream live from (the `_live` one, or the camera itself). */
  liveEntity: HassEntity;
  /** Entity for the still/snapshot tile (the `_snapshot` one, or the camera itself). */
  snapshotEntity: HassEntity;
  /** ring-mqtt recorded-event playback stream entity (`camera.<base>_event`), if present. */
  eventStreamId: string | null;
  /** ring-mqtt event selector (`select.<base>_event_select`) — picks the event to play. */
  eventSelectId: string | null;
  /** Doorbell press sensor (`binary_sensor.<base>_ding`), if present. */
  dingId: string | null;
  /** Motion sensor (`binary_sensor.<base>_motion`), if present. */
  motionId: string | null;
  /** ring-mqtt siren switch (`switch.<base>_siren`) on siren-capable cameras, else null. */
  sirenSwitchId: string | null;
}

/** The object id (after the domain dot), e.g. `camera.front_door_live` → `front_door_live`. */
function objectIdOf(entityId: string): string {
  const dot = entityId.indexOf(".");
  return dot >= 0 ? entityId.slice(dot + 1) : entityId;
}

type Role = "live" | "snapshot" | "event" | "standalone";

/** Split a camera object id into its ring-mqtt role + base name. */
function classify(objectId: string): { base: string; role: Role } {
  // HA's official Ring integration adds a dedicated live-view entity
  // (`camera.<base>_live_view`, "X Live view") alongside the snapshot camera — treat it as the
  // live feed so it folds into the base camera instead of becoming a second tile.
  if (objectId.endsWith("_live_view")) return { base: objectId.slice(0, -10), role: "live" };
  if (objectId.endsWith("_live")) return { base: objectId.slice(0, -5), role: "live" };
  if (objectId.endsWith("_snapshot")) return { base: objectId.slice(0, -9), role: "snapshot" };
  if (objectId.endsWith("_event")) return { base: objectId.slice(0, -6), role: "event" };
  return { base: objectId, role: "standalone" };
}

/** Strip a trailing role word ring-mqtt / Ring append to friendly names ("Front Door Live view"). */
function cleanName(name: string): string {
  return name.replace(/\s+(Live view|Live|Snapshot|Event)$/i, "");
}

interface Group {
  base: string;
  live?: HassEntity;
  snapshot?: HassEntity;
  event?: HassEntity;
  standalone?: HassEntity;
}

/**
 * Collapse all `camera.*` entities into logical cameras, binding each ring-mqtt
 * camera's sibling entities (event stream/selector, motion/ding) by base name.
 * Sorted by id for a stable wall order.
 */
export function resolveCameras(
  entities: Record<string, HassEntity>,
  overrides: OverrideMap,
): LogicalCamera[] {
  const groups = new Map<string, Group>();

  for (const entity of Object.values(entities)) {
    if (domainOf(entity.entity_id) !== "camera") continue;
    const { base, role } = classify(objectIdOf(entity.entity_id));
    const g = groups.get(base) ?? { base };
    g[role] = entity;
    groups.set(base, g);
  }

  const has = (id: string): string | null => (entities[id] ? id : null);

  const cameras: LogicalCamera[] = [];
  for (const g of groups.values()) {
    const liveEntity = g.live ?? g.standalone ?? g.snapshot;
    const snapshotEntity = g.snapshot ?? g.standalone ?? g.live;
    // A group with only an `_event` playback stream isn't a camera on its own.
    if (!liveEntity || !snapshotEntity) continue;

    cameras.push({
      id: `camera.${g.base}`,
      name: cleanName(resolveName(liveEntity, overrides)),
      liveEntity,
      snapshotEntity,
      eventStreamId: g.event?.entity_id ?? null,
      eventSelectId: has(`select.${g.base}_event_select`),
      dingId: has(`binary_sensor.${g.base}_ding`),
      motionId: has(`binary_sensor.${g.base}_motion`),
      sirenSwitchId: has(`switch.${g.base}_siren`),
    });
  }

  return cameras.sort((a, b) => a.id.localeCompare(b.id));
}
