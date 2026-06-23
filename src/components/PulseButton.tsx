import type { ReactNode } from "react";
import type { Channel } from "./PanelCard";

type Variant = "hero" | "tonal" | "ghost";

// Tonal = channel "dim" container with the bright channel base as content.
// (The channel `on` color is for content atop the solid bright base, not the
// dark dim fill — using it here would be dark-on-dark for recovery.)
const TONAL: Record<Channel, string> = {
  effort: "bg-effort-dim text-effort",
  recovery: "bg-recovery-dim text-recovery",
  strength: "bg-strength-dim text-strength",
  streak: "bg-streak-dim text-streak",
};

interface PulseButtonProps {
  children: ReactNode;
  /** hero = blue→indigo gradient (primary CTA); tonal = channel container. */
  variant?: Variant;
  channel?: Channel;
  compact?: boolean;
  disabled?: boolean;
  active?: boolean;
  onClick?: () => void;
  className?: string;
}

/**
 * Solid action block. Default is the reserved hero gradient (one primary CTA per
 * view); tonal uses a channel container; ghost is a quiet hairline button.
 */
export function PulseButton({
  children,
  variant = "hero",
  channel = "effort",
  compact = false,
  disabled = false,
  active = false,
  onClick,
  className = "",
}: PulseButtonProps) {
  const base =
    "inline-flex items-center justify-center gap-sm rounded font-body font-semibold " +
    "transition-transform duration-fast ease-ease active:scale-[0.97] disabled:opacity-40 " +
    "disabled:active:scale-100 disabled:cursor-not-allowed";
  const size = compact ? "px-md py-sm text-body" : "px-lg py-md text-body-lg";
  const look =
    variant === "hero"
      ? "bg-hero text-white"
      : variant === "tonal"
        ? TONAL[channel]
        : `border border-hairline text-ink-dim ${active ? "border-hairline-strong text-ink" : ""}`;

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={[base, size, look, className].join(" ")}
    >
      {children}
    </button>
  );
}
