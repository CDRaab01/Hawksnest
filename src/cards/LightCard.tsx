import { useEffect, useState } from "react";
import { Lightbulb, LightbulbOff } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { DataText } from "../components/DataText";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

const toPercent = (brightness: number) => Math.round((brightness / 255) * 100);

/**
 * Light/dimmer card. Toggle is the interactive action (effort/blue); brightness
 * is a magnitude on the level channel (strength/violet). State is read from the
 * store; HA's echo reconciles after each service call.
 */
export function LightCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const on = entity.state === "on";
  const brightnessPct =
    typeof entity.attributes.brightness === "number"
      ? toPercent(entity.attributes.brightness)
      : 0;

  // Local slider value for snappy dragging; resync when HA reports a new level.
  const [pct, setPct] = useState(brightnessPct || 60);
  useEffect(() => {
    if (typeof entity.attributes.brightness === "number") {
      setPct(toPercent(entity.attributes.brightness));
    }
  }, [entity.attributes.brightness]);

  function toggle() {
    void callService("light", on ? "turn_off" : "turn_on", {
      entity_id: entity.entity_id,
    });
  }

  function setBrightness(value: number) {
    setPct(value);
    void callService("light", "turn_on", {
      entity_id: entity.entity_id,
      brightness_pct: value,
    });
  }

  return (
    <PanelCard tint={on ? "strength" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        {on ? (
          <Lightbulb className="text-strength" size={26} />
        ) : (
          <LightbulbOff className="text-ink-faint" size={26} />
        )}
        <div className="min-w-0">
          <div className="truncate font-body text-body-lg text-ink">{name}</div>
          <div className="font-body text-body text-ink-dim">
            {on ? (
              <>
                <DataText className="text-strength">{pct}</DataText>% brightness
              </>
            ) : (
              "Off"
            )}
          </div>
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label={`Toggle ${name}`}
          onClick={toggle}
          className={[
            "ml-auto h-7 w-12 rounded-full border border-hairline transition-colors duration-standard",
            on ? "bg-effort/80" : "bg-panel-high",
          ].join(" ")}
        >
          <span
            className={[
              "block h-5 w-5 rounded-full bg-white transition-transform duration-standard ease-ease",
              on ? "translate-x-6" : "translate-x-1",
            ].join(" ")}
          />
        </button>
      </div>
      <input
        type="range"
        min={1}
        max={100}
        value={pct}
        disabled={!on}
        aria-label={`${name} brightness`}
        onChange={(e) => setBrightness(Number(e.target.value))}
        className="mt-lg w-full accent-strength disabled:opacity-40"
      />
    </PanelCard>
  );
}
