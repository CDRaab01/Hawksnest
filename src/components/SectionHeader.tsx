import type { ReactNode } from "react";
import type { Channel } from "./PanelCard";

const TICK: Record<Channel, string> = {
  effort: "bg-effort",
  recovery: "bg-recovery",
  strength: "bg-strength",
  streak: "bg-streak",
};

interface SectionHeaderProps {
  label: string;
  channel?: Channel;
  /** Optional trailing affordance (e.g. a chevron / "see all"). */
  trailing?: ReactNode;
}

/** Channel tick + uppercase instrument caption. */
export function SectionHeader({
  label,
  channel = "effort",
  trailing,
}: SectionHeaderProps) {
  return (
    <div className="flex items-center gap-sm">
      <span className={["h-3 w-1 rounded-full", TICK[channel]].join(" ")} />
      <span className="caption-label">{label}</span>
      {trailing ? <span className="ml-auto">{trailing}</span> : null}
    </div>
  );
}
