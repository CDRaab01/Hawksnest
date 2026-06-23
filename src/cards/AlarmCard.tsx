import { useState } from "react";
import { ShieldCheck, ShieldAlert, Shield } from "lucide-react";
import { PanelCard } from "../components/PanelCard";
import { PulseButton } from "../components/PulseButton";
import { resolveName } from "../lib/resolve";
import type { CardProps } from "./types";

const LABEL: Record<string, string> = {
  disarmed: "Disarmed",
  armed_home: "Armed — Home",
  armed_away: "Armed — Away",
  triggered: "Triggered",
  arming: "Arming…",
};

/**
 * Alarm panel card. Note: a *tamper/intrusion* binary_sensor (as in the
 * reference screenshot) is NOT an alarm panel — when HA exposes no
 * alarm_control_panel, the Security section shows a read-only notice instead of
 * rendering this card (see the mockups).
 */
export function AlarmCard({ entity, overrides }: CardProps) {
  const name = resolveName(entity, overrides);
  const [state, setState] = useState(entity.state);

  const triggered = state === "triggered";
  const armed = state.startsWith("armed");
  const Icon = triggered ? ShieldAlert : armed ? Shield : ShieldCheck;
  const color = triggered
    ? "text-streak"
    : armed
      ? "text-effort"
      : "text-recovery";

  return (
    <PanelCard tint={triggered ? "streak" : undefined} className="p-lg">
      <div className="flex items-center gap-md">
        <Icon className={color} size={26} />
        <div className="min-w-0">
          <div className="truncate font-display text-title text-ink">{name}</div>
          <div className={["font-body text-body", color].join(" ")}>
            {LABEL[state] ?? state}
          </div>
        </div>
      </div>
      <div className="mt-lg grid grid-cols-3 gap-sm">
        <PulseButton
          variant="ghost"
          compact
          active={state === "disarmed"}
          onClick={() => setState("disarmed")}
        >
          Off
        </PulseButton>
        <PulseButton
          variant="ghost"
          compact
          active={state === "armed_home"}
          onClick={() => setState("armed_home")}
        >
          Home
        </PulseButton>
        <PulseButton
          variant="ghost"
          compact
          active={state === "armed_away"}
          onClick={() => setState("armed_away")}
        >
          Away
        </PulseButton>
      </div>
    </PanelCard>
  );
}
