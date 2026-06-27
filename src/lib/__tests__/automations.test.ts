import { describe, it, expect } from "vitest";
import {
  configToRule,
  minutesToOffset,
  offsetToMinutes,
  ruleToConfig,
  type AutomationConfig,
  type Rule,
} from "../automations";

// The two scenarios the user asked for, plus the safety paths.
const lockAllWhenArmed: Rule = {
  id: "1000",
  alias: "Lock all doors when armed home",
  trigger: { kind: "state", entityId: "alarm_control_panel.home", to: "armed_home" },
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
  trigger: { kind: "state", entityId: "binary_sensor.hall_motion", to: "on" },
  conditions: [{ kind: "timeWindow", after: "20:00", before: "06:00" }],
  actions: [{ domain: "light", verb: "turn_on", targetEntityIds: ["light.hall"] }],
  mode: "single",
};

// IFTTT-style triggers beyond plain device state.
const porchLightAtSunset: Rule = {
  id: "7000",
  alias: "Porch light 15m before sunset",
  trigger: { kind: "sun", event: "sunset", offsetMinutes: -15 },
  conditions: [],
  actions: [{ domain: "light", verb: "turn_on", targetEntityIds: ["light.porch"] }],
  mode: "single",
};

const lockAtNight: Rule = {
  id: "8000",
  alias: "Lock up at 11pm",
  trigger: { kind: "time", at: "23:00" },
  conditions: [],
  actions: [{ domain: "lock", verb: "lock", targetEntityIds: ["lock.front_door_lock"] }],
  mode: "single",
};

const unlockWhenHome: Rule = {
  id: "9000",
  alias: "Unlock when Alex arrives",
  trigger: { kind: "presence", personEntityId: "person.alex", event: "enter", zone: "home" },
  conditions: [],
  actions: [{ domain: "lock", verb: "unlock", targetEntityIds: ["lock.front_door_lock"] }],
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

  it("maps a sun trigger with a negative offset", () => {
    expect(ruleToConfig(porchLightAtSunset).trigger).toEqual([
      { platform: "sun", event: "sunset", offset: "-00:15:00" },
    ]);
  });

  it("omits the offset key when a sun offset is zero", () => {
    const config = ruleToConfig({
      ...porchLightAtSunset,
      trigger: { kind: "sun", event: "sunrise", offsetMinutes: 0 },
    });
    expect(config.trigger).toEqual([{ platform: "sun", event: "sunrise" }]);
  });

  it("maps a time trigger, padding seconds", () => {
    expect(ruleToConfig(lockAtNight).trigger).toEqual([
      { platform: "time", at: "23:00:00" },
    ]);
  });

  it("maps a presence trigger to an HA zone trigger", () => {
    expect(ruleToConfig(unlockWhenHome).trigger).toEqual([
      { platform: "zone", entity_id: "person.alex", zone: "zone.home", event: "enter" },
    ]);
  });
});

describe("sun offset helpers", () => {
  it("encodes signed minutes to ±HH:MM:SS", () => {
    expect(minutesToOffset(-15)).toBe("-00:15:00");
    expect(minutesToOffset(90)).toBe("+01:30:00");
    expect(minutesToOffset(0)).toBe("+00:00:00");
  });

  it("decodes ±HH:MM:SS and bare seconds back to minutes", () => {
    expect(offsetToMinutes("-00:15:00")).toBe(-15);
    expect(offsetToMinutes("+01:30:00")).toBe(90);
    expect(offsetToMinutes(-900)).toBe(-15);
    expect(offsetToMinutes(undefined)).toBe(0);
  });
});

describe("configToRule round-trips", () => {
  it("survives a round trip for the lock-all rule", () => {
    expect(configToRule(ruleToConfig(lockAllWhenArmed))).toEqual(lockAllWhenArmed);
  });

  it("survives a round trip for the motion rule with a condition", () => {
    expect(configToRule(ruleToConfig(lightOnMotion))).toEqual(lightOnMotion);
  });

  it("survives a round trip for a sun trigger", () => {
    expect(configToRule(ruleToConfig(porchLightAtSunset))).toEqual(porchLightAtSunset);
  });

  it("survives a round trip for a time trigger", () => {
    expect(configToRule(ruleToConfig(lockAtNight))).toEqual(lockAtNight);
  });

  it("survives a round trip for a presence trigger", () => {
    expect(configToRule(ruleToConfig(unlockWhenHome))).toEqual(unlockWhenHome);
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
    expect(rule?.trigger).toEqual({ kind: "state", entityId: "binary_sensor.m", to: "on" });
    expect(rule?.actions[0]).toEqual({
      domain: "light",
      verb: "turn_on",
      targetEntityIds: ["light.hall"],
    });
  });

  it("returns null for an unsupported trigger platform (edit-in-HA fallback)", () => {
    const config: AutomationConfig = {
      id: "4000",
      trigger: [{ platform: "template", value_template: "{{ is_state('x','on') }}" }],
      action: [{ service: "light.turn_on", target: { entity_id: ["light.hall"] } }],
    };
    expect(configToRule(config)).toBeNull();
  });

  it("returns null for a zone trigger that isn't the home zone (V1 limit)", () => {
    const config: AutomationConfig = {
      id: "4100",
      trigger: [
        { platform: "zone", entity_id: "person.alex", zone: "zone.office", event: "enter" },
      ],
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
