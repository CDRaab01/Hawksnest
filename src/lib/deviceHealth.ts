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
  const lowBattery = battery !== null && battery <= LOW_BATTERY_PCT;
  return {
    online,
    battery,
    lowBattery,
    needsAttention: !online || lowBattery,
    lastChangedMs: lastChangedMs(entity),
  };
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
