import { entities, areaRegistry } from "../fixtures/entities";
import { useEntityStore } from "./entityStore";
import { domainOf } from "../lib/ha";
import type { HistoryPoint, ServiceData, Source } from "./source";
import type { HassEntity } from "../lib/ha";

// Discrete (on/off-ish) domains step between two states in the synthetic series;
// everything else is treated as a numeric sensor and jittered around its value.
const DISCRETE_DOMAINS = new Set([
  "lock",
  "binary_sensor",
  "switch",
  "light",
  "fan",
  "cover",
  "alarm_control_panel",
  "media_player",
]);

/**
 * Synthesize a plausible history series ending at the entity's current state, so
 * demo mode (no live HA) still renders a chart. Deterministic-ish per entity:
 * numeric sensors wander around their current value; discrete entities flip a
 * few times and settle on the live state.
 */
function synthHistory(entity: HassEntity, hours: number): HistoryPoint[] {
  const now = Date.now();
  const points = Math.min(48, Math.max(12, hours * 2));
  const stepMs = (hours * 3600_000) / points;
  const discrete = DISCRETE_DOMAINS.has(domainOf(entity.entity_id));
  const out: HistoryPoint[] = [];

  if (discrete) {
    const other =
      entity.state === "on"
        ? "off"
        : entity.state === "off"
          ? "on"
          : entity.state === "locked"
            ? "unlocked"
            : "off";
    for (let i = 0; i < points; i++) {
      // Flip on a simple cadence, then guarantee the latest sample is current.
      const flipped = i % 5 === 0 && i !== points - 1;
      out.push({
        t: now - (points - 1 - i) * stepMs,
        state: i === points - 1 ? entity.state : flipped ? other : entity.state,
      });
    }
    return out;
  }

  const base = Number(entity.state);
  const center = Number.isFinite(base) ? base : 50;
  const amp = Math.max(1, Math.abs(center) * 0.08);
  for (let i = 0; i < points; i++) {
    const wobble = Math.sin(i / 3) * amp + (Math.random() - 0.5) * amp * 0.5;
    const value = i === points - 1 ? center : center + wobble;
    out.push({
      t: now - (points - 1 - i) * stepMs,
      state: (Math.round(value * 10) / 10).toString(),
    });
  }
  return out;
}

/** Apply a control action to a fixture entity (simulates HA's state echo). */
function simulate(
  entity: HassEntity,
  domain: string,
  service: string,
  data: ServiceData,
): HassEntity {
  const next: HassEntity = {
    ...entity,
    attributes: { ...entity.attributes },
  };
  if (domain === "lock") {
    next.state = service === "lock" ? "locked" : "unlocked";
  } else if (domain === "light") {
    if (service === "turn_off") {
      next.state = "off";
    } else {
      next.state = "on";
      if (typeof data.brightness_pct === "number") {
        next.attributes.brightness = Math.round((data.brightness_pct / 100) * 255);
      }
    }
  } else if (domain === "switch") {
    next.state = service === "turn_on" ? "on" : "off";
  } else if (domain === "alarm_control_panel") {
    next.state =
      service === "alarm_disarm"
        ? "disarmed"
        : service === "alarm_arm_home"
          ? "armed_home"
          : service === "alarm_arm_away"
            ? "armed_away"
            : entity.state;
  } else if (domain === "cover") {
    if (service === "open_cover") {
      next.state = "open";
      next.attributes.current_position = 100;
    } else if (service === "close_cover") {
      next.state = "closed";
      next.attributes.current_position = 0;
    }
    // stop_cover leaves the resting state as-is (demo has no transit phase).
  } else if (domain === "climate") {
    if (service === "set_temperature" && typeof data.temperature === "number") {
      next.attributes.temperature = data.temperature;
    }
  } else if (domain === "media_player") {
    if (service === "media_play_pause") {
      next.state = entity.state === "playing" ? "paused" : "playing";
    }
    // next/previous track: title is static in demo, state unchanged.
  } else if (domain === "fan") {
    if (service === "turn_off") {
      next.state = "off";
    } else if (service === "turn_on") {
      next.state = "on";
    } else if (service === "set_percentage" && typeof data.percentage === "number") {
      next.state = data.percentage > 0 ? "on" : "off";
      next.attributes.percentage = data.percentage;
    }
  }
  return next;
}

/** Loads the invented fixtures into the store and flags the app as demo data. */
export function createFixtureSource(): Source {
  return {
    start() {
      const map = Object.fromEntries(entities.map((e) => [e.entity_id, e]));
      const store = useEntityStore.getState();
      store.setSnapshot(map, areaRegistry);
      store.setStatus("demo");
    },
    stop() {},
    async callService(domain, service, data = {}) {
      const store = useEntityStore.getState();
      const entity = data.entity_id ? store.entities[data.entity_id] : undefined;
      if (!entity) return;
      store.upsertEntities([simulate(entity, domain, service, data)]);
    },
    async fetchHistory(entityId, hours) {
      const entity = useEntityStore.getState().entities[entityId];
      if (!entity) return [];
      return synthHistory(entity, hours);
    },
  };
}
