import { useMemo } from "react";
import { PanelCard } from "./PanelCard";
import { PulseButton } from "./PulseButton";
import { DataText } from "./DataText";
import type { Channel } from "./PanelCard";
import { usePrimaryAlarm, useCameraEntities, useEntityStore } from "../store/entityStore";
import { alarmView, ARM_BUTTONS } from "../lib/alarm";
import { isCameraLive } from "../lib/cameraUrl";
import { summarizeHealth } from "../lib/deviceHealth";
import { domainOf } from "../lib/ha";
import { callService } from "../store/connection";

const TEXT: Record<Channel, string> = {
  effort: "text-effort",
  recovery: "text-recovery",
  strength: "text-strength",
  streak: "text-streak",
};

function StatReadout({
  value,
  label,
  channel = "effort",
}: {
  value: string;
  label: string;
  channel?: Channel;
}) {
  return (
    <div className="flex flex-col gap-xs px-lg first:pl-0">
      <DataText size="sm" className={TEXT[channel]}>
        {value}
      </DataText>
      <span className="caption-label text-ink-faint">{label}</span>
    </div>
  );
}

/**
 * The Dashboard's headline security panel — Ring-style. The arm/disarm/away
 * control is front-and-center, with a live status read-out (armed cameras,
 * monitored sensors, devices online, anything offline). The alarm view-model is
 * shared with `AlarmCard` via `lib/alarm`.
 */
export function SecurityStatusBar() {
  const alarm = usePrimaryAlarm();
  const cameras = useCameraEntities();
  const entities = useEntityStore((s) => s.entities);

  const { sensors, health } = useMemo(() => {
    const all = Object.values(entities);
    return {
      sensors: all.filter((e) => domainOf(e.entity_id) === "binary_sensor").length,
      health: summarizeHealth(all),
    };
  }, [entities]);

  const liveCameras = cameras.filter(isCameraLive).length;
  const view = alarm ? alarmView(alarm.state) : null;
  const Icon = view?.icon;

  function arm(service: string) {
    if (!alarm) return;
    void callService("alarm_control_panel", service, { entity_id: alarm.entity_id });
  }

  return (
    <PanelCard
      tint={view?.triggered ? "streak" : undefined}
      raised
      className="p-lg"
    >
      <div className="flex flex-col gap-lg lg:flex-row lg:items-center">
        {/* Arm state + control */}
        <div className="flex min-w-0 flex-1 items-center gap-md">
          {Icon && view ? (
            <Icon className={TEXT[view.channel]} size={34} />
          ) : null}
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

      {/* Status read-out row */}
      <div className="mt-lg flex flex-wrap items-center divide-x divide-hairline border-t border-hairline pt-lg">
        <StatReadout
          value={`${liveCameras}/${cameras.length}`}
          label="Cameras live"
          channel="effort"
        />
        <StatReadout value={String(sensors)} label="Sensors" channel="strength" />
        <StatReadout
          value={String(health.online)}
          label="Devices online"
          channel="recovery"
        />
        <StatReadout
          value={String(health.offline)}
          label="Offline"
          channel={health.offline > 0 ? "streak" : "recovery"}
        />
      </div>
    </PanelCard>
  );
}
