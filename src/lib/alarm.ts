import { Shield, ShieldAlert, ShieldCheck, type LucideIcon } from "lucide-react";
import type { Channel } from "../components/PanelCard";

/** The three arm services we expose (HA `alarm_control_panel.*`). */
export type ArmService = "alarm_disarm" | "alarm_arm_home" | "alarm_arm_away";

const LABEL: Record<string, string> = {
  disarmed: "Disarmed",
  armed_home: "Armed — Home",
  armed_away: "Armed — Away",
  armed_night: "Armed — Night",
  armed_vacation: "Armed — Vacation",
  arming: "Arming…",
  pending: "Pending…",
  disarming: "Disarming…",
  triggered: "Triggered",
};

const SHORT: Record<string, string> = {
  disarmed: "Disarmed",
  armed_home: "Home",
  armed_away: "Away",
  armed_night: "Night",
  armed_vacation: "Vacation",
  triggered: "Triggered",
};

export interface AlarmView {
  /** Full human label, e.g. "Armed — Away". */
  label: string;
  /** Short label for the nav pill, e.g. "Away". */
  short: string;
  icon: LucideIcon;
  /** PULSE channel: green when settled, blue when armed, orange when triggered. */
  channel: Channel;
  armed: boolean;
  triggered: boolean;
  transitioning: boolean;
}

/**
 * Pure view-model for an alarm_control_panel state. Shared by `AlarmCard` and
 * the `SecurityStatusBar`/`TopNav` pill so the security read-out is identical
 * everywhere. State semantics mirror the original AlarmCard exactly.
 */
export function alarmView(state: string): AlarmView {
  const triggered = state === "triggered";
  const armed = state.startsWith("armed");
  const transitioning =
    state === "arming" || state === "pending" || state === "disarming";
  const icon = triggered ? ShieldAlert : armed ? Shield : ShieldCheck;
  const channel: Channel = triggered ? "streak" : armed ? "effort" : "recovery";
  return {
    label: LABEL[state] ?? state,
    short: SHORT[state] ?? LABEL[state] ?? state,
    icon,
    channel,
    armed,
    triggered,
    transitioning,
  };
}

/** The Off / Home / Away segmented control, in display order. */
export const ARM_BUTTONS: ReadonlyArray<{
  label: string;
  service: ArmService;
  /** The resulting state this button represents (for the `active` highlight). */
  state: string;
}> = [
  { label: "Off", service: "alarm_disarm", state: "disarmed" },
  { label: "Home", service: "alarm_arm_home", state: "armed_home" },
  { label: "Away", service: "alarm_arm_away", state: "armed_away" },
];
