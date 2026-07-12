import { useEffect, useState } from "react";
import { Lightbulb, LightbulbOff } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { DataText } from "../components/DataText";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

const toPercent = (brightness: number) => Math.round((brightness / 255) * 100);

/** Warmth wash bounds: a lit card glows with its level, but stays a panel. */
const WARMTH_MIN = 0.05;
const WARMTH_MAX = 0.16;

/**
 * Light/dimmer card. Toggle is the interactive action (effort/blue); brightness
 * is a magnitude on the level channel (strength/violet).
 *
 * Feel notes:
 * - The toggle renders **optimistically** (the thumb follows the tap; HA's echo
 *   reconciles; a failed call snaps back with a message) — lights are not a
 *   security surface, so the lock/alarm non-optimism invariant doesn't apply.
 * - The card **is the dimmer**: a strength-channel wash over the panel tracks
 *   the brightness level, so every light's level reads at a glance.
 * - The slider drags locally and commits **on release** (one service call per
 *   gesture, not one per pixel); the mono readout live-tracks the drag.
 */
export function LightCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const actual = entity.state === "on";
  const brightnessPct =
    typeof entity.attributes.brightness === "number"
      ? toPercent(entity.attributes.brightness)
      : 0;

  // Optimistic toggle target: the thumb follows the finger, the echo resets it.
  const [target, setTarget] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setTarget(null);
  }, [entity.state]);
  const on = target ?? actual;

  // Local slider value for snappy dragging; resync when HA reports a new level.
  const [pct, setPct] = useState(brightnessPct || 60);
  useEffect(() => {
    if (typeof entity.attributes.brightness === "number") {
      setPct(toPercent(entity.attributes.brightness));
    }
  }, [entity.attributes.brightness]);

  function toggle() {
    const next = !on;
    setError(null);
    setTarget(next);
    void callService("light", next ? "turn_on" : "turn_off", {
      entity_id: entity.entity_id,
    }).catch(() => {
      setTarget(null); // snap back — the store state never changed
      setError("Couldn't reach the light.");
    });
  }

  /** Commit the dragged level — one call per gesture, on release. */
  function commitBrightness() {
    setError(null);
    void callService("light", "turn_on", {
      entity_id: entity.entity_id,
      brightness_pct: pct,
    }).catch(() => setError("Couldn't reach the light."));
  }

  // The card is the dimmer: wash opacity tracks the (live-dragged) level.
  const warmth = on ? WARMTH_MIN + (pct / 100) * (WARMTH_MAX - WARMTH_MIN) : 0;

  return (
    <PanelCard tint={on ? "strength" : undefined} className="relative overflow-hidden p-lg">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 transition-opacity duration-standard ease-ease"
        style={{ background: "var(--strength)", opacity: warmth }}
      />
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
            "ml-auto h-7 w-12 shrink-0 rounded-full border border-hairline transition-colors duration-standard",
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
        onChange={(e) => setPct(Number(e.target.value))}
        onPointerUp={commitBrightness}
        onKeyUp={(e) => {
          if (e.key.startsWith("Arrow") || e.key === "Home" || e.key === "End") {
            commitBrightness();
          }
        }}
        className="mt-lg w-full accent-strength disabled:opacity-40"
      />
    </PanelCard>
  );
}
