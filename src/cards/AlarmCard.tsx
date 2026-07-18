import { Loader, CloudOff } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import type { Channel } from "../components/PanelCard";
import { useAlarmControl } from "../components/useAlarmControl";
import { resolveName } from "../lib/resolve";
import { alarmView, ARM_BUTTONS } from "../lib/alarm";
import { useConnection } from "../store/entityStore";
import type { CardProps } from "./types";

const TEXT: Record<Channel, string> = {
  effort: "text-effort",
  recovery: "text-recovery",
  strength: "text-strength",
  streak: "text-streak",
};

/**
 * Alarm panel card. Like the LockCard, arm/disarm is **non-optimistic** (via
 * `useAlarmControl`): the tapped segment shows a pending spinner until HA reaches
 * the requested state, and a failed call surfaces an error rather than silently
 * doing nothing. Note: a *tamper/intrusion* binary_sensor is NOT an alarm panel —
 * when HA exposes no alarm_control_panel the Security section shows a read-only
 * notice instead of rendering this card (see the screens).
 */
export function AlarmCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  // Security invariant: the alarm's mode is NEVER rendered stale. While the HA socket is down
  // the store has already masked the panel's state; this card additionally presents an explicit
  // "Unknown — offline" with the arm segments disabled (nothing can be delivered anyway).
  const { status } = useConnection();
  const disconnected = status === "connecting" || status === "error";
  const view = alarmView(entity.state);
  const Icon = disconnected ? CloudOff : view.icon;
  const color = disconnected ? "text-ink-dim" : TEXT[view.channel];
  const { pending, error, arm } = useAlarmControl(entity);

  return (
    <PanelCard tint={!disconnected && view.triggered ? "streak" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        <Icon className={color} size={26} />
        <div className="min-w-0">
          <div className="truncate font-display text-title text-ink">{name}</div>
          <div className={["font-body text-body", color].join(" ")}>
            {disconnected ? "Unknown — offline" : view.label}
          </div>
          {error && (
            <div className="font-body text-caption text-streak">{error}</div>
          )}
        </div>
      </div>
      <div className="mt-lg grid grid-cols-3 gap-sm">
        {ARM_BUTTONS.map((b) => (
          <PulseButton
            key={b.service}
            variant="ghost"
            compact
            active={!disconnected && entity.state === b.state}
            disabled={pending !== null || disconnected}
            onClick={() => arm(b.service)}
          >
            {pending === b.service ? (
              <Loader className="animate-spin" size={16} />
            ) : (
              b.label
            )}
          </PulseButton>
        ))}
      </div>
    </PanelCard>
  );
}
