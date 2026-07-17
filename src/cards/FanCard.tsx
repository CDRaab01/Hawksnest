import { useEffect, useState } from "react";
import { Fan } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { DataText } from "../components/DataText";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

/**
 * Fan card. Toggle is the interactive action (effort/blue); speed is a
 * percentage magnitude. Mirrors LightCard's feel: an **optimistic** toggle
 * (echo reconciles, failure snaps back), a slider that drags locally and
 * commits **on release**, and a blade icon that spins at the actual speed —
 * spinning = on, faster = higher, at a glance.
 */
export function FanCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const actual = entity.state === "on";

  // Optimistic toggle target: the thumb follows the finger, the echo resets it.
  const [target, setTarget] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setTarget(null);
  }, [entity.state]);
  const on = target ?? actual;
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
    const next = !on;
    setError(null);
    setTarget(next);
    void callService("fan", next ? "turn_on" : "turn_off", {
      entity_id: entity.entity_id,
    }).catch(() => {
      setTarget(null); // snap back — the store state never changed
      setError("Couldn't reach the fan.");
    });
  }

  /** Commit the dragged speed — one call per gesture, on release. */
  function commitSpeed() {
    setError(null);
    void callService("fan", "set_percentage", {
      entity_id: entity.entity_id,
      percentage: pct,
    }).catch(() => setError("Couldn't reach the fan."));
  }

  // Blade rotation period maps to the (live-dragged) speed: ~3.2s at a crawl
  // down to ~0.6s flat out. Reduced-motion drops the spin via the utility.
  const spinSeconds = 3.2 - (pct / 100) * 2.6;

  return (
    <PanelCard tint={on ? "effort" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        <Fan
          className={[
            on ? "text-effort" : "text-ink-faint",
            on ? "animate-spin motion-reduce:animate-none" : "",
          ].join(" ")}
          size={26}
          style={on ? { animationDuration: `${spinSeconds.toFixed(2)}s` } : undefined}
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
          {error && (
            <div className="font-body text-caption text-streak">{error}</div>
          )}
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
        onChange={(e) => setPct(Number(e.target.value))}
        onPointerUp={commitSpeed}
        onKeyUp={(e) => {
          if (e.key.startsWith("Arrow") || e.key === "Home" || e.key === "End") {
            commitSpeed();
          }
        }}
        className="mt-lg w-full accent-effort disabled:opacity-40"
      />
    </PanelCard>
  );
}
