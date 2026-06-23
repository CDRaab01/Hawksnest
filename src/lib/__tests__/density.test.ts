import { describe, it, expect } from "vitest";
import { cardDensityFor, isFeature } from "../density";

describe("cardDensityFor", () => {
  it("controls render comfortable", () => {
    expect(cardDensityFor("lock.front_door_lock")).toBe("comfortable");
    expect(cardDensityFor("light.basement")).toBe("comfortable");
    expect(cardDensityFor("alarm_control_panel.home")).toBe("comfortable");
  });

  it("read-only entities render compact", () => {
    expect(cardDensityFor("sensor.front_door_battery")).toBe("compact");
    expect(cardDensityFor("binary_sensor.front_door_current_status")).toBe("compact");
  });

  it("camera is a comfortable feature tile", () => {
    expect(cardDensityFor("camera.front_door")).toBe("comfortable");
    expect(isFeature("camera.front_door")).toBe(true);
    expect(isFeature("lock.front_door_lock")).toBe(false);
  });
});
