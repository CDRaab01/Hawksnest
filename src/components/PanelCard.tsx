import type { ReactNode } from "react";

export type Channel = "effort" | "recovery" | "strength" | "streak";

const TINT_BORDER: Record<Channel, string> = {
  effort: "border-effort/40",
  recovery: "border-recovery/40",
  strength: "border-strength/40",
  streak: "border-streak/40",
};

interface PanelCardProps {
  children: ReactNode;
  /** Optional channel tint applied to the hairline stroke. */
  tint?: Channel;
  /** Use the raised panel tone. */
  raised?: boolean;
  className?: string;
  onClick?: () => void;
}

/**
 * The one surface treatment in Hawksnest: a flat panel with a 1px hairline
 * stroke. Depth comes from stroke + tone, never shadows (PULSE rule).
 */
export function PanelCard({
  children,
  tint,
  raised,
  className = "",
  onClick,
}: PanelCardProps) {
  const interactive = onClick
    ? "cursor-pointer transition-transform duration-fast ease-ease active:scale-[0.98]"
    : "";
  return (
    <div
      onClick={onClick}
      className={[
        raised ? "bg-panel-high" : "bg-panel",
        "rounded-lg border",
        tint ? TINT_BORDER[tint] : "border-hairline",
        interactive,
        className,
      ].join(" ")}
    >
      {children}
    </div>
  );
}
