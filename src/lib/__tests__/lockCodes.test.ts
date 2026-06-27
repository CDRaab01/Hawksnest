import { describe, it, expect } from "vitest";
import {
  isValidUserCode,
  setUserCodeCall,
  clearUserCodeCall,
  guestAutomationId,
  isGuestAutomation,
  buildGuestExpiryAutomation,
  isLockEntity,
} from "../lockCodes";

describe("isValidUserCode", () => {
  it("accepts 4–8 digit codes", () => {
    expect(isValidUserCode("1234")).toBe(true);
    expect(isValidUserCode("12345678")).toBe(true);
  });
  it("rejects too short / too long / non-numeric", () => {
    expect(isValidUserCode("123")).toBe(false);
    expect(isValidUserCode("123456789")).toBe(false);
    expect(isValidUserCode("12a4")).toBe(false);
    expect(isValidUserCode("")).toBe(false);
  });
});

describe("service calls", () => {
  it("builds set/clear usercode calls", () => {
    expect(setUserCodeCall(3, "4242")).toEqual({
      domain: "zwave_js",
      service: "set_lock_usercode",
      data: { code_slot: 3, usercode: "4242" },
    });
    expect(clearUserCodeCall(3)).toEqual({
      domain: "zwave_js",
      service: "clear_lock_usercode",
      data: { code_slot: 3 },
    });
  });
});

describe("guest automation ids", () => {
  it("is stable and recognizable per lock+slot", () => {
    const id = guestAutomationId("lock.front_door_lock", 3);
    expect(id).toBe("hawksnest_guest_lock_front_door_lock_slot3");
    expect(isGuestAutomation(id)).toBe(true);
    expect(isGuestAutomation("1700000000000")).toBe(false);
  });
});

describe("buildGuestExpiryAutomation", () => {
  it("returns null for an invalid datetime-local value", () => {
    expect(
      buildGuestExpiryAutomation({
        lockEntityId: "lock.front_door_lock",
        slot: 3,
        guestName: "Sitter",
        expiryLocal: "not-a-date",
      }),
    ).toBeNull();
  });

  it("builds a daily time trigger gated by a datetime template, clearing then self-disabling", () => {
    const cfg = buildGuestExpiryAutomation({
      lockEntityId: "lock.front_door_lock",
      slot: 4,
      guestName: "Sitter",
      expiryLocal: "2026-07-01T14:30",
    })!;
    expect(cfg.id).toBe("hawksnest_guest_lock_front_door_lock_slot4");
    expect(cfg.trigger).toEqual([{ platform: "time", at: "14:30:00" }]);
    expect(cfg.condition).toEqual([
      {
        condition: "template",
        value_template:
          "{{ as_timestamp(now()) >= as_timestamp('2026-07-01 14:30:00') }}",
      },
    ]);
    const actions = cfg.action as Array<Record<string, unknown>>;
    expect(actions[0]).toEqual({
      service: "zwave_js.clear_lock_usercode",
      target: { entity_id: "lock.front_door_lock" },
      data: { code_slot: 4 },
    });
    expect(actions[1]).toMatchObject({ service: "automation.turn_off" });
  });
});

describe("isLockEntity", () => {
  it("recognizes lock entities only", () => {
    expect(isLockEntity("lock.front_door_lock")).toBe(true);
    expect(isLockEntity("light.basement")).toBe(false);
  });
});
