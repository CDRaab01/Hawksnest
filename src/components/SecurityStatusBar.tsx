import { useMemo } from "react";
import { PanelCard } from "./PanelCard";
import { PulseButton } from "./PulseButton";
import type { Channel } from "./PanelCard";
import { usePrimaryAlarm, useEntityStore } from "../store/entityStore";
import { alarmView, ARM_BUTTONS } from "../lib/alarm";
import { resolveName } from "../lib/resolve";
import { overrides } from "../config/overrides";
import { callService } from "../store/connection";

const TEXT: Record<Channel, string> = {
  effort: "text-effort",
  recovery: "text-recovery",
  strength: "text-strength",
  streak: "text-streak",
};

/**
 * The Dashboard's headline security panel — Ring-style. The arm/disarm/away
 * control is front-and-center with the current state; a single quiet line below
 * flags anything offline (or confirms all-clear). Kept deliberately sparse so the
 * camera wall is the visual focus. The alarm view-model is shared with the
 * `AlarmCard`/nav pill via `lib/alarm`.
 */
export function SecurityStatusBar() {
  const alarm = usePrimaryAlarm();
  const entities = useEntityStore((s) => s.entities);

  // A single Ring-style "<device> is offline" line; nothing dense.
  const offline = useMemo(
    () =>
      Object.values(entities).filter((e) => e.state === "unavailable"),
    [entities],
  );
  const offlineLabel =
    offline.length === 0
      ? null
      : offline.length === 1
        ? `${resolveName(offline[0], overrides)} is offline`
        : `${resolveName(offline[0], overrides)} +${offline.length - 1} more offline`;

  const view = alarm ? alarmView(alarm.state) : null;
  const Icon = view?.icon;

  function arm(service: string) {
    if (!alarm) return;
    void callService("alarm_control_panel", service, { entity_id: alarm.entity_id });
  }

  return (
    <PanelCard tint={view?.triggered ? "streak" : undefined} raised className="p-lg">
      <div className="flex flex-col gap-lg lg:flex-row lg:items-center">
        {/* Arm state */}
        <div className="flex min-w-0 flex-1 items-center gap-md">
          {Icon && view ? <Icon className={TEXT[view.channel]} size={34} /> : null}
          <div className="min-w-0">
            <div className="caption-label text-ink-faint">Security</div>
            <div
              className={[
                "font-display text-headline",
                view ? TEXT[view.channel] : "text-ink-dim",
              ].join(" ")}
            >
              {view ? view.label : "No alarm panel"}
            </div>
          </div>
        </div>

        {alarm && (
          <div className="grid grid-cols-3 gap-sm lg:w-[280px]">
            {ARM_BUTTONS.map((b) => (
              <PulseButton
                key={b.service}
                variant="ghost"
                compact
                active={alarm.state === b.state}
                onClick={() => arm(b.service)}
              >
                {b.label}
              </PulseButton>
            ))}
          </div>
        )}
      </div>

      {offlineLabel && (
        <div className="mt-md border-t border-hairline pt-md">
          <span className="font-body text-caption text-streak">{offlineLabel}</span>
        </div>
      )}
    </PanelCard>
  );
}
