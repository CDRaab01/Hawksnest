import { describe, it, expect } from "vitest";
import { Shield, ShieldAlert, ShieldCheck } from "lucide-react";
import { alarmView, ARM_BUTTONS } from "../alarm";

describe("alarmView", () => {
  it("treats disarmed as settled (recovery, ShieldCheck)", () => {
    const v = alarmView("disarmed");
    expect(v).toMatchObject({
      label: "Disarmed",
      short: "Disarmed",
      channel: "recovery",
      armed: false,
      triggered: false,
      transitioning: false,
    });
    expect(v.icon).toBe(ShieldCheck);
  });

  it("treats any armed_* state as armed (effort, Shield)", () => {
    for (const s of ["armed_home", "armed_away", "armed_night"]) {
      const v = alarmView(s);
      expect(v.armed).toBe(true);
      expect(v.channel).toBe("effort");
      expect(v.icon).toBe(Shield);
    }
    expect(alarmView("armed_home").short).toBe("Home");
    expect(alarmView("armed_away").short).toBe("Away");
  });

  it("treats triggered as an alert (streak, ShieldAlert)", () => {
    const v = alarmView("triggered");
    expect(v.triggered).toBe(true);
    expect(v.channel).toBe("streak");
    expect(v.icon).toBe(ShieldAlert);
  });

  it("flags transitional states", () => {
    expect(alarmView("arming").transitioning).toBe(true);
    expect(alarmView("pending").transitioning).toBe(true);
    expect(alarmView("disarming").transitioning).toBe(true);
  });

  it("falls back to the raw state for unknown values", () => {
    expect(alarmView("custom_bypass").label).toBe("custom_bypass");
  });

  it("exposes Off/Home/Away with their services", () => {
    expect(ARM_BUTTONS.map((b) => b.service)).toEqual([
      "alarm_disarm",
      "alarm_arm_home",
      "alarm_arm_away",
    ]);
  });
});
