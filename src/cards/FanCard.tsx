import { useEffect, useState } from "react";
import { Fan } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { DataText } from "../components/DataText";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

/**
 * Fan card. Toggle is the interactive action (effort/blue); speed is a
 * percentage magnitude. Mirrors LightCard's slider pattern: a local value for
 * snappy dragging that resyncs when HA reports a new percentage.
 */
export function FanCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const on = entity.state === "on";
  const reported =
    typeof entity.attributes.percentage === "number"
      ? (entity.attributes.percentage as number)
      : on
        ? 100
        : 0;

  const [pct, setPct] = useState(reported || 50);
  useEffect(() => {
    if (typeof entity.attributes.percentage === "number") {
      setPct(entity.attributes.percentage as number);
    }
  }, [entity.attributes.percentage]);

  function toggle() {
    void callService("fan", on ? "turn_off" : "turn_on", {
      entity_id: entity.entity_id,
    });
  }

  function setSpeed(value: number) {
    setPct(value);
    void callService("fan", "set_percentage", {
      entity_id: entity.entity_id,
      percentage: value,
    });
  }

  return (
    <PanelCard tint={on ? "effort" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        <Fan
          className={[on ? "text-effort" : "text-ink-faint", on ? "animate-spin" : ""].join(" ")}
          size={26}
          style={on ? { animationDuration: "2.4s" } : undefined}
        />
        <div className="min-w-0">
          <div className="truncate font-body text-body-lg text-ink">{name}</div>
          <div className="font-body text-body text-ink-dim">
            {on ? (
              <>
                <DataText className="text-effort">{pct}</DataText>% speed
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
        aria-label={`${name} speed`}
        onChange={(e) => setSpeed(Number(e.target.value))}
        className="mt-lg w-full accent-effort disabled:opacity-40"
      />
    </PanelCard>
  );
}
