import type { HassEntity } from "./ha";

/** Battery at or below this (%) is flagged as needing attention. */
export const LOW_BATTERY_PCT = 20;

// States that mean "we have no live reading" rather than a real value.
const OFFLINE_STATES = new Set(["unavailable", "unknown", "none", ""]);

export interface EntityHealth {
  online: boolean;
  /** Battery percent if this entity reports one, else null. */
  battery: number | null;
  lowBattery: boolean;
  /** Offline or low battery — surfaced in the Devices "Needs attention" rail. */
  needsAttention: boolean;
  /** Epoch ms of the last state change, when HA provides it. */
  lastChangedMs: number | null;
}

function batteryOf(entity: HassEntity): number | null {
  if (entity.attributes.device_class === "battery") {
    const n = Number(entity.state);
    return Number.isFinite(n) ? n : null;
  }
  const level = entity.attributes.battery_level;
  if (typeof level === "number" && Number.isFinite(level)) return level;
  return null;
}

/**
 * HA's **binary** battery signal: `binary_sensor` + `device_class: battery`, where `on` means
 * LOW, not "present".
 *
 * It reads backwards and it is easy to miss, which is exactly what happened: `batteryOf` above
 * matches on `device_class === "battery"` and then parses the state as a number, so `"on"` became
 * `NaN` → null → not low. A device whose only battery signal is a binary sensor — which is how
 * plenty of Z-Wave and Zigbee devices report it — could therefore never reach "Needs attention",
 * no matter how flat its cell was. Kept separate from `batteryOf` because there is no percentage
 * to return: the sensor knows one bit, and pretending it knows a number would be worse.
 */
export function isLowBatteryBinary(entity: HassEntity): boolean {
  return (
    entity.entity_id.startsWith("binary_sensor.") &&
    entity.attributes.device_class === "battery" &&
    entity.state.toLowerCase() === "on"
  );
}

function lastChangedMs(entity: HassEntity): number | null {
  const raw = entity.last_changed ?? entity.last_updated;
  if (!raw) return null;
  const t = new Date(raw).getTime();
  return Number.isFinite(t) ? t : null;
}

/** Derive a device-health read-out from a single entity's live state. */
export function entityHealth(entity: HassEntity): EntityHealth {
  const online = !OFFLINE_STATES.has(entity.state.toLowerCase());
  const battery = batteryOf(entity);
  // Two ways a device says "my battery is flat", and both have to count: a percentage at or under
  // the threshold, or HA's binary battery sensor reading `on`.
  const lowBattery =
    (battery !== null && battery <= LOW_BATTERY_PCT) || isLowBatteryBinary(entity);
  return {
    online,
    battery,
    lowBattery,
    // ONLY "unavailable" flags attention — measured 2026-08-08: "unknown" is the
    // RESTING state for sirens, PTZ-preset selects and other stateless entities
    // (13 healthy devices would sit pinned to the rail forever). `online` keeps the
    // broader definition for the entity-detail readout, where "no live reading" is
    // the honest thing to display. Android's deck goes further (whole-device offline
    // + a pre-filter device-level battery scan, core/logic/ControlDeck.kt) — this
    // per-entity rule is the floor both platforms share.
    needsAttention: entity.state.toLowerCase() === "unavailable" || lowBattery,
    lastChangedMs: lastChangedMs(entity),
  };
}

// HA's Z-Wave JS integration reports node lifecycle as one of these; "dead"
// means the controller can no longer reach the node (e.g. a lock dropped off
// the mesh) — the one state that's actionable.
const DEAD_NODE_STATUS = "dead";

export interface ZWaveHealth {
  /** Node lifecycle ("alive" | "awake" | "asleep" | "dead"), or null if not a Z-Wave node. */
  nodeStatus: string | null;
  /** Controller can't reach the node — surfaced as a warning on the device. */
  dead: boolean;
  /** Epoch ms the controller last heard from the node, when reported. */
  lastSeenMs: number | null;
  /** Round-trip time to the node in ms (lower is better); null unless the stat is enabled. */
  rttMs: number | null;
}

const NODE_STATUS_SUFFIX = "_node_status";
const LAST_SEEN_SUFFIX = "_last_seen";
const RTT_SUFFIX = "_round_trip_time";

/** True if `entityId` is one of the Z-Wave diagnostic entities `zwaveHealth` reads. */
export function isZWaveDiagnostic(entityId: string): boolean {
  return (
    entityId.endsWith(NODE_STATUS_SUFFIX) ||
    entityId.endsWith(LAST_SEEN_SUFFIX) ||
    entityId.endsWith(RTT_SUFFIX)
  );
}

/**
 * Pull Z-Wave node diagnostics from a device's sibling entities. HA's Z-Wave JS
 * integration exposes these as per-device diagnostic entities: node status
 * (`*_node_status`), last-seen (`*_last_seen`), and — when network statistics are
 * enabled — round-trip time (`*_round_trip_time`). `siblings` are the other
 * entities on the same device (see `useDeviceDiagnostics`). Returns all-null for
 * a non-Z-Wave device, so callers can render the panel only when there's data.
 */
export function zwaveHealth(siblings: HassEntity[]): ZWaveHealth {
  let nodeStatus: string | null = null;
  let lastSeenMs: number | null = null;
  let rttMs: number | null = null;
  for (const s of siblings) {
    const id = s.entity_id;
    if (id.endsWith(NODE_STATUS_SUFFIX)) {
      const v = s.state.toLowerCase();
      if (!OFFLINE_STATES.has(v)) nodeStatus = v;
    } else if (id.endsWith(LAST_SEEN_SUFFIX)) {
      const t = Date.parse(s.state);
      if (Number.isFinite(t)) lastSeenMs = t;
    } else if (id.endsWith(RTT_SUFFIX)) {
      const n = Number(s.state);
      if (Number.isFinite(n)) rttMs = n;
    }
  }
  return { nodeStatus, dead: nodeStatus === DEAD_NODE_STATUS, lastSeenMs, rttMs };
}

/**
 * True when the Z-Wave controller/radio looks offline: there are Z-Wave entities
 * and *every one* reports `unavailable`/`unknown`. A single dead node leaves the
 * rest reporting, so this only fires when the whole network drops at once — the
 * symptom of the stick or zwave-js-ui going down (the spec's "fragile link").
 */
export function zwaveControllerOffline(zwaveEntities: HassEntity[]): boolean {
  if (zwaveEntities.length === 0) return false;
  return zwaveEntities.every((e) => OFFLINE_STATES.has(e.state.toLowerCase()));
}

export interface HealthSummary {
  total: number;
  online: number;
  offline: number;
  lowBattery: number;
}

/** Aggregate health counts across a set of entities (for the status row). */
export function summarizeHealth(entities: HassEntity[]): HealthSummary {
  let online = 0;
  let offline = 0;
  let lowBattery = 0;
  for (const e of entities) {
    const h = entityHealth(e);
    if (h.online) online += 1;
    else offline += 1;
    if (h.lowBattery) lowBattery += 1;
  }
  return { total: entities.length, online, offline, lowBattery };
}
