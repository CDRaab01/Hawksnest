import { describe, it, expect } from "vitest";
import { CircleHelp, Lock } from "lucide-react";
import {
  resolveName,
  resolveIcon,
  prettifyEntityId,
} from "../resolve";
import { overrides } from "../../config/overrides";
import type { HassEntity } from "../ha";

const make = (
  entity_id: string,
  friendly_name?: string,
  extra: Record<string, unknown> = {},
): HassEntity => ({
  entity_id,
  state: "on",
  attributes: { ...(friendly_name ? { friendly_name } : {}), ...extra },
});

describe("resolveName", () => {
  it("override wins over friendly_name and id (kills 'Lock Current status …')", () => {
    const contact = make(
      "binary_sensor.front_door_current_status",
      "Lock Current status",
    );
    expect(resolveName(contact, overrides)).toBe("Front Door");
  });

  it("falls back to friendly_name when no override", () => {
    const e = make("sensor.kitchen_temp", "Kitchen Temperature");
    expect(resolveName(e, overrides)).toBe("Kitchen Temperature");
  });

  it("prettifies the entity_id as last resort", () => {
    const e = make("switch.garage_work_light");
    expect(resolveName(e, {})).toBe("Garage Work Light");
  });

  it("ignores blank friendly_name", () => {
    const e = make("light.hall", "   ");
    expect(resolveName(e, {})).toBe("Hall");
  });
});

describe("prettifyEntityId", () => {
  it("strips the domain and title-cases", () => {
    expect(prettifyEntityId("binary_sensor.front_door_current_status")).toBe(
      "Front Door Current Status",
    );
  });
});

describe("resolveIcon", () => {
  it("uses the override icon when present", () => {
    const e = make("lock.front_door_lock", "Lock");
    expect(resolveIcon(e, overrides)).toBe(Lock);
  });

  it("falls back to a neutral icon for an unknown domain (never throws)", () => {
    const e = make("totally_made_up.thing");
    expect(() => resolveIcon(e, {})).not.toThrow();
    expect(resolveIcon(e, {})).toBe(CircleHelp);
  });
});
