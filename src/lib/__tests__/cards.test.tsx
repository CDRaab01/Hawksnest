import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { domainToCard } from "../cards";
import { CONTROL_DOMAINS } from "../density";
import { NON_DEVICE_DOMAINS, type HassEntity } from "../ha";
import { callService } from "../../store/connection";
import { SwitchCard } from "../../cards/SwitchCard";
import { SceneCard } from "../../cards/SceneCard";

vi.mock("../../store/connection", () => ({ callService: vi.fn(() => Promise.resolve()) }));
const mockCall = vi.mocked(callService);
beforeEach(() => mockCall.mockClear());
import { LockCard } from "../../cards/LockCard";
import { LightCard } from "../../cards/LightCard";
import { CameraTile } from "../../cards/CameraTile";
import { BinarySensorCard } from "../../cards/BinarySensorCard";
import { AlarmCard } from "../../cards/AlarmCard";
import { CoverCard } from "../../cards/CoverCard";
import { ClimateCard } from "../../cards/ClimateCard";
import { MediaPlayerCard } from "../../cards/MediaPlayerCard";
import { FanCard } from "../../cards/FanCard";
import { GenericCard } from "../../cards/GenericCard";

describe("domainToCard", () => {
  it("maps first-class domains to their cards", () => {
    expect(domainToCard("lock.front_door_lock")).toBe(LockCard);
    expect(domainToCard("light.basement")).toBe(LightCard);
    expect(domainToCard("camera.front_door")).toBe(CameraTile);
    expect(domainToCard("image.snapshot")).toBe(CameraTile);
    expect(domainToCard("binary_sensor.front_door")).toBe(BinarySensorCard);
    expect(domainToCard("alarm_control_panel.home")).toBe(AlarmCard);
    expect(domainToCard("cover.living_room_blinds")).toBe(CoverCard);
    expect(domainToCard("climate.living_room")).toBe(ClimateCard);
    expect(domainToCard("media_player.living_room")).toBe(MediaPlayerCard);
    expect(domainToCard("fan.bedroom")).toBe(FanCard);
  });

  it("falls back to GenericCard for unmapped and unknown domains", () => {
    expect(domainToCard("weather.home")).toBe(GenericCard);
    expect(() => domainToCard("totally_made_up.thing")).not.toThrow();
    expect(domainToCard("totally_made_up.thing")).toBe(GenericCard);
  });
});

describe("NON_DEVICE_DOMAINS", () => {
  it("excludes non-device and plumbing domains from the Devices hub", () => {
    // Non-devices with their own surfaces / infrastructure entities.
    for (const domain of ["automation", "script", "scene", "person", "zone", "sun"]) {
      expect(NON_DEVICE_DOMAINS.has(domain), `expected ${domain} excluded`).toBe(true);
    }
    // Device plumbing (PTZ buttons, scene-controller event streams, AI-snapshot
    // images) — trimmed 2026-08-07; reachable via entity-detail diagnostics instead.
    for (const domain of ["button", "event", "image"]) {
      expect(NON_DEVICE_DOMAINS.has(domain), `expected ${domain} excluded`).toBe(true);
    }
    // The domains the tab exists for must never creep into the exclusion set.
    for (const domain of ["lock", "light", "switch", "camera", "sensor", "binary_sensor"]) {
      expect(NON_DEVICE_DOMAINS.has(domain), `expected ${domain} included`).toBe(false);
    }
  });
});

/**
 * The two lists that must not drift apart.
 *
 * `density.ts` deciding a domain is a control and `cards.ts` having no card for it produces the
 * worst kind of UI bug: something that looks exactly like a control and isn't. `switch` was in
 * that state for the app's whole life — the only way to flip one was the Devices list's inline
 * QuickControl, so a room's lamp switch was a read-only row. `scene` was too, showing its
 * last-run ISO timestamp where a Run button belonged.
 */
describe("control domains all have a control card", () => {
  it("maps every CONTROL_DOMAINS entry to something other than GenericCard", () => {
    for (const domain of CONTROL_DOMAINS) {
      const Card = domainToCard(`${domain}.example`);
      expect(Card, `expected ${domain} to have a first-class card`).not.toBe(GenericCard);
    }
  });
});

describe("SwitchCard", () => {
  const sw = (state: string): HassEntity => ({
    entity_id: "switch.basement_lamp",
    state,
    attributes: { friendly_name: "Basement Lamp" },
  });

  it("renders a real toggle for a switch, not a read-only row", () => {
    render(<SwitchCard entity={sw("off")} overrides={{}} />);
    const toggle = screen.getByRole("switch", { name: "Toggle Basement Lamp" });
    expect(toggle).toBeEnabled();
    expect(toggle).toHaveAttribute("aria-checked", "false");
  });

  it("calls switch.turn_on and follows the tap optimistically", () => {
    render(<SwitchCard entity={sw("off")} overrides={{}} />);
    fireEvent.click(screen.getByRole("switch", { name: "Toggle Basement Lamp" }));
    expect(mockCall).toHaveBeenCalledWith("switch", "turn_on", {
      entity_id: "switch.basement_lamp",
    });
    // Lights, fans and switches are not security surfaces — the thumb moves now and HA's echo
    // reconciles. (Invariant 1 covers locks and the alarm, which do wait.)
    expect(screen.getByRole("switch", { name: "Toggle Basement Lamp" })).toHaveAttribute(
      "aria-checked",
      "true",
    );
  });

  it("turns a switch that is on back off", () => {
    render(<SwitchCard entity={sw("on")} overrides={{}} />);
    fireEvent.click(screen.getByRole("switch", { name: "Toggle Basement Lamp" }));
    expect(mockCall).toHaveBeenCalledWith("switch", "turn_off", {
      entity_id: "switch.basement_lamp",
    });
  });

  it("refuses to look pressable when the switch is unavailable", () => {
    // `unavailable` is not `off`. Rendering it as a live Off invites a tap that does nothing.
    render(<SwitchCard entity={sw("unavailable")} overrides={{}} />);
    expect(screen.getByRole("switch", { name: "Toggle Basement Lamp" })).toBeDisabled();
    expect(screen.getByText("Unavailable")).toBeInTheDocument();
  });
});

describe("SceneCard", () => {
  const scene = (state: string): HassEntity => ({
    entity_id: "scene.movie_night",
    state,
    attributes: { friendly_name: "Movie Night" },
  });

  it("runs the scene instead of showing its last-run timestamp as a reading", () => {
    render(<SceneCard entity={scene("2026-08-12T14:00:00+00:00")} overrides={{}} />);
    fireEvent.click(screen.getByRole("button", { name: "Run Movie Night" }));
    expect(mockCall).toHaveBeenCalledWith("scene", "turn_on", { entity_id: "scene.movie_night" });
  });

  it("says so when a scene has never run", () => {
    render(<SceneCard entity={scene("unknown")} overrides={{}} />);
    expect(screen.getByText("Not run yet")).toBeInTheDocument();
  });
});
