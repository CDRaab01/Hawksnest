import type { CSSProperties } from "react";

/**
 * PULSE-toned loading skeleton: a panel-high surface with one low-amplitude
 * shimmer sweep in hairline-strong. Deliberately quiet — no gradient candy, no
 * glow — so a loading state reads as "the instrument is warming up", not as
 * decoration. Used by the camera wall (first-frame decode), the history chart
 * and timeline, and the Rooms hub. Size/shape it via `className` (or `style`
 * for computed widths); `label` is announced to screen readers while the
 * shimmer itself stays decorative.
 */
export function Skeleton({
  className = "",
  style,
  label,
}: {
  className?: string;
  style?: CSSProperties;
  label?: string;
}) {
  return (
    <div
      data-testid="skeleton"
      role={label ? "status" : undefined}
      style={style}
      className={["relative overflow-hidden bg-panel-high", className].join(" ")}
    >
      {label && <span className="sr-only">{label}</span>}
      <div
        aria-hidden="true"
        className="absolute inset-0 animate-shimmer bg-gradient-to-r from-transparent via-hairline-strong to-transparent motion-reduce:hidden"
      />
    </div>
  );
}
