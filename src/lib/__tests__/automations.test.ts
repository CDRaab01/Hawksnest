import { describe, it, expect } from "vitest";
import {
  configToRule,
  ruleToConfig,
  type AutomationConfig,
  type Rule,
} from "../automations";

// The two scenarios the user asked for, plus the safety paths.
const lockAllWhenArmed: Rule = {
  id: "1000",
  alias: "Lock all doors when armed home",
  trigger: { entityId: "alarm_control_panel.home", to: "armed_home" },
  conditions: [],
  actions: [
    {
      domain: "lock",
      verb: "lock",
      targetEntityIds: [
        "lock.front_door_lock",
        "lock.back_door_lock",
        "lock.garage_lock",
      ],
    },
  ],
  mode: "single",
};

const lightOnMotion: Rule = {
  id: "2000",
  alias: "Hall light on motion after dark",
  trigger: { entityId: "binary_sensor.hall_motion", to: "on" },
  conditions: [{ kind: "timeWindow", after: "20:00", before: "06:00" }],
  actions: [{ domain: "light", verb: "turn_on", targetEntityIds: ["light.hall"] }],
  mode: "single",
};

describe("ruleToConfig", () => {
  it("maps a state trigger and a multi-target lock action to HA config", () => {
    const config = ruleToConfig(lockAllWhenArmed);
    expect(config.trigger).toEqual([
      { platform: "state", entity_id: "alarm_control_panel.home", to: "armed_home" },
    ]);
    expect(config.action).toEqual([
      {
        service: "lock.lock",
        target: {
          entity_id: [
            "lock.front_door_lock",
            "lock.back_door_lock",
            "lock.garage_lock",
          ],
        },
      },
    ]);
    expect(config.condition).toEqual([]);
    expect(config.mode).toBe("single");
  });

  it("maps a time-window condition", () => {
    const config = ruleToConfig(lightOnMotion);
    expect(config.condition).toEqual([
      { condition: "time", after: "20:00", before: "06:00" },
    ]);
  });
});

describe("configToRule round-trips", () => {
  it("survives a round trip for the lock-all rule", () => {
    expect(configToRule(ruleToConfig(lockAllWhenArmed))).toEqual(lockAllWhenArmed);
  });

  it("survives a round trip for the motion rule with a condition", () => {
    expect(configToRule(ruleToConfig(lightOnMotion))).toEqual(lightOnMotion);
  });
});

describe("configToRule (parsing real HA configs)", () => {
  it("accepts the modern triggers/actions keys (forward-compatible)", () => {
    const modern: AutomationConfig = {
      id: "3000",
      alias: "Motion light",
      triggers: [{ trigger: "state", entity_id: "binary_sensor.m", to: "on" }],
      actions: [{ action: "light.turn_on", target: { entity_id: "light.hall" } }],
    };
    const rule = configToRule(modern);
    expect(rule?.trigger.entityId).toBe("binary_sensor.m");
    expect(rule?.actions[0]).toEqual({
      domain: "light",
      verb: "turn_on",
      targetEntityIds: ["light.hall"],
    });
  });

  it("returns null for an unsupported trigger platform (edit-in-HA fallback)", () => {
    const config: AutomationConfig = {
      id: "4000",
      trigger: [{ platform: "sun", event: "sunset" }],
      action: [{ service: "light.turn_on", target: { entity_id: ["light.hall"] } }],
    };
    expect(configToRule(config)).toBeNull();
  });

  it("returns null when there are multiple triggers", () => {
    const config: AutomationConfig = {
      id: "5000",
      trigger: [
        { platform: "state", entity_id: "a.b", to: "on" },
        { platform: "state", entity_id: "c.d", to: "on" },
      ],
      action: [{ service: "light.turn_on", target: { entity_id: ["light.hall"] } }],
    };
    expect(configToRule(config)).toBeNull();
  });

  it("returns null when an action service isn't one Hawksnest models", () => {
    const config: AutomationConfig = {
      id: "6000",
      trigger: [{ platform: "state", entity_id: "binary_sensor.m", to: "on" }],
      action: [{ service: "notify.mobile_app", data: { message: "hi" } }],
    };
    expect(configToRule(config)).toBeNull();
  });
});
