import { useEffect, useState } from "react";
import { Power, PowerOff } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

/**
 * `switch` card — the web counterpart of Android's `RockerSwitch`.
 *
 * Its absence was the app's largest functional hole and an easy one to miss, because the *shape*
 * was right: `density.ts` has always classed `switch` as a control domain, so a switch rendered a
 * comfortable, control-sized card — it just fell through `cards.ts` to the read-only
 * `GenericCard`. The result was a card that looked exactly like a lamp you could press and wasn't
 * one, and the only way to flip a switch anywhere in the web app was the Devices list's inline
 * `QuickControl`. Room detail and entity detail offered nothing.
 *
 * Modelled on `LightCard`'s on/off half deliberately — same toggle geometry, same `role="switch"`,
 * same wash — because a switch and a non-dimmable light are the same gesture to a user and should
 * not be two different controls. What is NOT carried over is the brightness half: `switch.*` has
 * no level, and inventing one is the mistake `isDimmableLight` exists to avoid.
 *
 * **Optimistic**, like lights and fans and unlike locks and the alarm. Invariant 1 covers security
 * surfaces; a switch is not one, and making the thumb wait for HA's echo would make every lamp
 * feel broken. A failed call snaps back and says so.
 */
export function SwitchCard({ entity, overrides, density = "comfortable" }: CardProps) {
  const name = resolveName(entity, overrides);
  const compact = density === "compact";
  // `unavailable`/`unknown` is not "off" — a switch that has dropped off the mesh must not render
  // as a pressable Off, or the card invites a tap that silently does nothing.
  const unavailable = entity.state === "unavailable" || entity.state === "unknown";
  const actual = entity.state === "on";

  // Optimistic toggle target: the thumb follows the finger, HA's echo resets it.
  const [target, setTarget] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setTarget(null);
  }, [entity.state]);
  const on = target ?? actual;

  function toggle() {
    const next = !on;
    setError(null);
    setTarget(next);
    void callService("switch", next ? "turn_on" : "turn_off", {
      entity_id: entity.entity_id,
    }).catch(() => {
      setTarget(null); // snap back — the store state never changed
      setError("Couldn't reach the switch.");
    });
  }

  return (
    <PanelCard
      tint={on && !unavailable ? "strength" : undefined}
      className={["relative overflow-hidden", compact ? "p-md" : "p-lg"].join(" ")}
    >
      {/* Same wash as a lit non-dimmable light, at the same fixed weight: on/off is still a
          state worth reading across a room without focusing on the card. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 transition-opacity duration-standard ease-ease"
        style={{ background: "var(--strength)", opacity: on && !unavailable ? 0.09 : 0 }}
      />
      <div className="flex items-center gap-md">
        {on && !unavailable ? (
          <Power className="shrink-0 text-strength" size={compact ? 22 : 26} />
        ) : (
          <PowerOff className="shrink-0 text-ink-faint" size={compact ? 22 : 26} />
        )}
        <div className="min-w-0">
          <div
            className={[
              "truncate font-body text-ink",
              compact ? "text-body" : "text-body-lg",
            ].join(" ")}
          >
            {name}
          </div>
          <div
            className={[
              "font-body",
              compact ? "text-caption" : "text-body",
              unavailable ? "text-streak" : "text-ink-dim",
            ].join(" ")}
          >
            {unavailable ? "Unavailable" : on ? "On" : "Off"}
          </div>
          {error && <div className="font-body text-caption text-streak">{error}</div>}
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label={`Toggle ${name}`}
          disabled={unavailable}
          onClick={toggle}
          className={[
            "ml-auto h-7 w-12 shrink-0 rounded-full border border-hairline transition-colors duration-standard",
            on ? "bg-effort/80" : "bg-panel-high",
            unavailable ? "opacity-40" : "",
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
    </PanelCard>
  );
}
