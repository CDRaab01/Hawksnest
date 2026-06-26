import { useMemo } from "react";
import { Home, Lock, ShieldOff, type LucideIcon } from "lucide-react";
import { PanelCard } from "./PanelCard";
import type { Channel } from "./PanelCard";
import { usePrimaryAlarm, useEntityStore } from "../store/entityStore";
import { alarmView, ARM_BUTTONS } from "../lib/alarm";
import { resolveName } from "../lib/resolve";
import { overrides } from "../config/overrides";
import { domainOf } from "../lib/ha";
import { callService } from "../store/connection";

const CHANNEL_BG: Record<Channel, string> = {
  effort: "bg-effort",
  recovery: "bg-recovery",
  strength: "bg-strength",
  streak: "bg-streak",
};
const CHANNEL_TEXT: Record<Channel, string> = {
  effort: "text-effort",
  recovery: "text-recovery",
  strength: "text-strength",
  streak: "text-streak",
};

/** Per-arm-button icon (Ring uses a distinct glyph per mode). */
const ARM_ICON: Record<string, LucideIcon> = {
  alarm_disarm: ShieldOff,
  alarm_arm_home: Home,
  alarm_arm_away: Lock,
};

const DOOR_CLASSES = new Set(["door", "window", "garage_door"]);

/**
 * The Dashboard's headline security panel — Ring-style. Three big circular arm buttons
 * (Disarmed / Home / Away) are the focus; the active mode fills with its channel color. A single
 * plain-language line summarizes whether the house is buttoned up (unlocked locks / open doors),
 * and anything offline. Deliberately sparse so the camera wall is the visual focus.
 */
export function SecurityStatusBar() {
  const alarm = usePrimaryAlarm();
  const entities = useEntityStore((s) => s.entities);

  const { securityLine, offlineLabel } = useMemo(() => {
    const all = Object.values(entities);
    const unlocked = all.filter(
      (e) => domainOf(e.entity_id) === "lock" && e.state !== "locked" && e.state !== "locking",
    );
    const openDoors = all.filter(
      (e) =>
        domainOf(e.entity_id) === "binary_sensor" &&
        DOOR_CLASSES.has(String(e.attributes.device_class ?? "")) &&
        e.state === "on",
    );
    const parts = [
      ...unlocked.map((e) => `${resolveName(e, overrides)} unlocked`),
      ...openDoors.map((e) => `${resolveName(e, overrides)} open`),
    ];
    const offline = all.filter((e) => e.state === "unavailable");
    return {
      securityLine: parts.length === 0 ? "All doors locked" : parts.join(" · "),
      offlineLabel:
        offline.length === 0
          ? null
          : offline.length === 1
            ? `${resolveName(offline[0], overrides)} is offline`
            : `${resolveName(offline[0], overrides)} +${offline.length - 1} more offline`,
    };
  }, [entities]);

  const view = alarm ? alarmView(alarm.state) : null;
  const allSecure = securityLine === "All doors locked";

  function arm(service: string) {
    if (!alarm) return;
    void callService("alarm_control_panel", service, { entity_id: alarm.entity_id });
  }

  return (
    <PanelCard tint={view?.triggered ? "streak" : undefined} raised className="p-lg">
      {alarm ? (
        <div className="flex items-end justify-center gap-xl">
          {ARM_BUTTONS.map((b) => {
            const active = alarm.state === b.state;
            const channel = alarmView(b.state).channel;
            const Icon = ARM_ICON[b.service] ?? ShieldOff;
            return (
              <button
                key={b.service}
                type="button"
                onClick={() => arm(b.service)}
                aria-pressed={active}
                aria-label={b.label}
                className="flex flex-col items-center gap-sm transition-transform duration-fast active:scale-[0.96]"
              >
                <span
                  className={[
                    "flex h-16 w-16 items-center justify-center rounded-full transition-colors",
                    active
                      ? `${CHANNEL_BG[channel]} text-bg`
                      : "border border-hairline bg-panel text-ink-dim",
                  ].join(" ")}
                >
                  <Icon size={26} />
                </span>
                <span
                  className={[
                    "font-body text-caption",
                    active ? CHANNEL_TEXT[channel] : "text-ink-dim",
                  ].join(" ")}
                >
                  {b.label}
                </span>
              </button>
            );
          })}
        </div>
      ) : (
        <div className="font-display text-headline text-ink-dim">No alarm panel</div>
      )}

      {/* One plain-language security line + any offline notice. */}
      <div className="mt-lg border-t border-hairline pt-md text-center">
        <span
          className={[
            "font-body text-body",
            allSecure ? "text-recovery" : "text-streak",
          ].join(" ")}
        >
          {securityLine}
        </span>
        {offlineLabel && (
          <span className="ml-sm font-body text-caption text-streak">· {offlineLabel}</span>
        )}
      </div>
    </PanelCard>
  );
}
