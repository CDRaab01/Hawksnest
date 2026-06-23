import { entities, areaRegistry } from "../fixtures/entities";
import { useEntityStore } from "./entityStore";
import type { ServiceData, Source } from "./source";
import type { HassEntity } from "../lib/ha";

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
  };
}
