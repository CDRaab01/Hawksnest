import { Link } from "react-router-dom";
import { CloudOff } from "lucide-react";
import { usePrimaryAlarm, useConnection } from "../store/entityStore";
import { alarmView } from "../lib/alarm";
import type { Channel } from "./PanelCard";

const DOT: Record<Channel, string> = {
  effort: "bg-effort",
  recovery: "bg-recovery",
  strength: "bg-strength",
  streak: "bg-streak",
};
const TEXT: Record<Channel, string> = {
  effort: "text-effort",
  recovery: "text-recovery",
  strength: "text-strength",
  streak: "text-streak",
};

/**
 * Compact armed-state chip for the top nav — keeps the home's security posture
 * visible on every screen. Taps through to the Dashboard's full control. Renders
 * nothing when HA exposes no alarm panel.
 */
export function ArmedPill() {
  const alarm = usePrimaryAlarm();
  const { status } = useConnection();
  if (!alarm) return null;
  // Security invariant: never a stale armed mode in the nav — while the socket is down the pill
  // reads an explicit "Unknown" instead of the last-known posture.
  if (status === "connecting" || status === "error") {
    return (
      <Link
        to="/"
        aria-label="Security: unknown while offline"
        className="inline-flex items-center gap-xs rounded-sm border border-hairline px-sm py-xs transition-colors duration-fast hover:border-hairline-strong"
      >
        <span className="h-2 w-2 rounded-full bg-ink-faint" />
        <CloudOff className="text-ink-dim" size={14} />
        <span className="font-body text-caption font-semibold text-ink-dim">Unknown</span>
      </Link>
    );
  }
  const view = alarmView(alarm.state);
  const Icon = view.icon;

  return (
    <Link
      to="/"
      aria-label={`Security: ${view.label}`}
      className="inline-flex items-center gap-xs rounded-sm border border-hairline px-sm py-xs transition-colors duration-fast hover:border-hairline-strong"
    >
      <span className={["h-2 w-2 rounded-full", DOT[view.channel]].join(" ")} />
      <Icon className={TEXT[view.channel]} size={14} />
      <span className={["font-body text-caption font-semibold", TEXT[view.channel]].join(" ")}>
        {view.short}
      </span>
    </Link>
  );
}
