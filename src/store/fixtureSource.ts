import { entities, areaRegistry } from "../fixtures/entities";
import { useEntityStore } from "./entityStore";
import { domainOf } from "../lib/ha";
import { slugify, type AutomationConfig } from "../lib/automations";
import { resolveName } from "../lib/resolve";
import { overrides } from "../config/overrides";
import type { HistoryPoint, ServiceData, Source } from "./source";
import type { LogEvent } from "../lib/logbook";
import type { HassEntity } from "../lib/ha";
import {
  DEMO_CLIP_URL,
  DEMO_POSTER_URL,
  type CameraEvent,
} from "../lib/cameraEvents";

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

// A plausible message per domain for the synthesized demo logbook.
function demoMessage(entity: HassEntity): string {
  switch (domainOf(entity.entity_id)) {
    case "lock":
      return entity.state === "locked" ? "was locked" : "was unlocked";
    case "binary_sensor":
      return entity.attributes.device_class === "motion"
        ? "detected motion"
        : entity.state === "on"
          ? "was opened"
          : "was closed";
    case "alarm_control_panel":
      return entity.state === "disarmed" ? "was disarmed" : "was armed";
    case "camera":
      return "recorded a clip";
    case "light":
      return entity.state === "on" ? "turned on" : "turned off";
    default:
      return `changed to ${entity.state}`;
  }
}

/**
 * Synthesize a believable home logbook from the current fixtures so demo mode's
 * History hub isn't empty. Events are spread back from `endMs` at a steady
 * cadence and clipped to the requested window.
 */
function synthLogbook(startMs: number, endMs: number): LogEvent[] {
  const store = useEntityStore.getState();
  const all = Object.values(store.entities).filter((e) =>
    ["lock", "binary_sensor", "alarm_control_panel", "camera", "light"].includes(
      domainOf(e.entity_id),
    ),
  );
  const stepMs = 23 * 60_000; // ~one event every 23 minutes
  return all
    .map((entity, i) => ({
      when: endMs - i * stepMs,
      name: resolveName(entity, overrides),
      message: demoMessage(entity),
      entityId: entity.entity_id,
      domain: domainOf(entity.entity_id),
      state: entity.state,
    }))
    .filter((e) => e.when >= startMs && e.when <= endMs)
    .sort((a, b) => b.when - a.when);
}

// Object labels the synthetic camera events cycle through, so the demo timeline
// shows a believable mix of motion/person/vehicle markers.
const DEMO_EVENT_LABELS = ["person", "motion", "car", "motion", "dog", "person"];

/**
 * Synthesize a believable 24h spread of recorded camera events for `camera` over
 * `[startMs, endMs]`, so demo mode's timeline scrubber is populated without
 * Frigate. Events land on a steady cadence, vary their label/duration, and point
 * their thumbnail at the bundled demo poster. Returned oldest-first (timeline
 * order). Deterministic per (camera, slot) so re-fetches are stable.
 */
function synthCameraEvents(
  camera: string,
  startMs: number,
  endMs: number,
): CameraEvent[] {
  const stepMs = 37 * 60_000; // ~one event every 37 minutes
  const out: CameraEvent[] = [];
  let slot = 0;
  for (let t = startMs; t <= endMs; t += stepMs, slot++) {
    const label = DEMO_EVENT_LABELS[slot % DEMO_EVENT_LABELS.length];
    const durationMs = 20_000 + (slot % 5) * 15_000; // 20s–80s
    out.push({
      id: `demo-${camera}-${slot}`,
      camera,
      label,
      startMs: t,
      endMs: Math.min(endMs, t + durationMs),
      hasClip: true,
      hasSnapshot: true,
      thumbnailUrl: DEMO_POSTER_URL,
      snapshotUrl: DEMO_POSTER_URL,
    });
  }
  return out;
}

/** Drop one synthetic entity from the store (filtered rebuild of the map). */
function removeEntity(entityId: string): void {
  const store = useEntityStore.getState();
  const next = { ...store.entities };
  delete next[entityId];
  store.setEntities(next);
}

/** Loads the invented fixtures into the store and flags the app as demo data. */
export function createFixtureSource(): Source {
  // In-memory automation store for demo mode: configs by id, plus the synthetic
  // `automation.*` entity id we surfaced for each (so the list, toggle, and
  // "run now" behave like live HA without a real backend).
  const configs = new Map<string, AutomationConfig>();
  const entityIdFor = new Map<string, string>();

  return {
    start() {
      const map = Object.fromEntries(entities.map((e) => [e.entity_id, e]));
      const store = useEntityStore.getState();
      store.setSnapshot(map, areaRegistry);
      store.setBaseUrl(""); // demo: entity_picture paths stay page-relative
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
    async fetchLogbook(startMs, endMs, opts) {
      const events = synthLogbook(startMs, endMs);
      if (opts?.entityIds && opts.entityIds.length > 0) {
        const want = new Set(opts.entityIds);
        return events.filter((e) => e.entityId && want.has(e.entityId));
      }
      return events;
    },
    async streamUrl(entityId) {
      // Demo "live" feed: loop the bundled clip for any camera entity.
      return domainOf(entityId) === "camera" ? DEMO_CLIP_URL : null;
    },
    async fetchCameraEvents(camera, startMs, endMs) {
      return synthCameraEvents(camera, startMs, endMs);
    },
    recordingUrlAt() {
      // Demo: every seek plays the same bundled clip (no real recordings).
      return DEMO_CLIP_URL;
    },
    eventClipUrl() {
      return DEMO_CLIP_URL;
    },
    async getAutomationConfig(id) {
      return configs.get(id) ?? null;
    },
    async saveAutomationConfig(config) {
      configs.set(config.id, config);
      // Re-surface the synthetic entity (alias may have changed → new id).
      const prev = entityIdFor.get(config.id);
      if (prev) removeEntity(prev);
      const friendlyName = String(config.alias ?? config.id);
      const entityId = `automation.${slugify(friendlyName)}`;
      entityIdFor.set(config.id, entityId);
      useEntityStore.getState().upsertEntities([
        {
          entity_id: entityId,
          state: "on",
          attributes: { friendly_name: friendlyName, id: config.id },
        },
      ]);
    },
    async deleteAutomationConfig(id) {
      configs.delete(id);
      const entityId = entityIdFor.get(id);
      if (entityId) {
        removeEntity(entityId);
        entityIdFor.delete(id);
      }
    },
  };
}
