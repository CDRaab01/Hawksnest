import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import type { Channel } from "../components/PanelCard";
import { resolveName } from "../lib/resolve";
import { alarmView, ARM_BUTTONS } from "../lib/alarm";
import { callService } from "../store/connection";
import type { CardProps } from "./types";

const TEXT: Record<Channel, string> = {
  effort: "text-effort",
  recovery: "text-recovery",
  strength: "text-strength",
  streak: "text-streak",
};

/**
 * Alarm panel card. Note: a *tamper/intrusion* binary_sensor (as in the
 * reference screenshot) is NOT an alarm panel — when HA exposes no
 * alarm_control_panel, the Security section shows a read-only notice instead of
 * rendering this card (see the screens). State is read from the store, and the
 * label/icon/channel come from the shared `lib/alarm` view-model.
 */
export function AlarmCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const view = alarmView(entity.state);
  const Icon = view.icon;
  const color = TEXT[view.channel];

  function call(service: string) {
    void callService("alarm_control_panel", service, {
      entity_id: entity.entity_id,
    });
  }

  return (
    <PanelCard tint={view.triggered ? "streak" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        <Icon className={color} size={26} />
        <div className="min-w-0">
          <div className="truncate font-display text-title text-ink">{name}</div>
          <div className={["font-body text-body", color].join(" ")}>{view.label}</div>
        </div>
      </div>
      <div className="mt-lg grid grid-cols-3 gap-sm">
        {ARM_BUTTONS.map((b) => (
          <PulseButton
            key={b.service}
            variant="ghost"
            compact
            active={entity.state === b.state}
            onClick={() => call(b.service)}
          >
            {b.label}
          </PulseButton>
        ))}
      </div>
    </PanelCard>
  );
}
