import { describe, it, expect } from "vitest";
import { domainToCard } from "../cards";
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
