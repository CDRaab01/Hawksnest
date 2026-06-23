import { useMemo } from "react";
import type { Channel } from "./PanelCard";
import type { HistoryPoint } from "../store/source";

const STROKE: Record<Channel, string> = {
  effort: "var(--effort)",
  recovery: "var(--recovery)",
  strength: "var(--strength)",
  streak: "var(--streak)",
};

interface SparklineProps {
  points: HistoryPoint[];
  channel?: Channel;
  /** Render height in px (width is fluid via viewBox). */
  height?: number;
  className?: string;
}

const VIEW_W = 300;
const PAD = 4;

/**
 * Dependency-free inline-SVG history chart in the PULSE idiom: a 2px channel
 * line over a hairline baseline, with a glow dot on the latest point only.
 * Numeric series (sensors, climate) render as a line; series whose states aren't
 * all numbers (lock/binary/cover/…) render as a step chart over discrete levels.
 * Returns null for <2 points — the caller shows an empty state.
 */
export function Sparkline({
  points,
  channel = "effort",
  height = 64,
  className = "",
}: SparklineProps) {
  const geom = useMemo(() => {
    if (points.length < 2) return null;

    const numbers = points.map((p) => Number(p.state));
    const numeric = numbers.every((n) => Number.isFinite(n));

    // For discrete series, map each distinct state to an evenly-spaced level.
    let values: number[];
    let min: number;
    let max: number;
    if (numeric) {
      values = numbers;
      min = Math.min(...values);
      max = Math.max(...values);
    } else {
      const levels = [...new Set(points.map((p) => p.state))];
      const index = new Map(levels.map((s, i) => [s, i]));
      values = points.map((p) => index.get(p.state) ?? 0);
      min = 0;
      max = Math.max(1, levels.length - 1);
    }

    const span = max - min || 1;
    const innerH = height - PAD * 2;
    const innerW = VIEW_W - PAD * 2;
    const x = (i: number) => PAD + (i / (points.length - 1)) * innerW;
    const y = (v: number) => PAD + innerH - ((v - min) / span) * innerH;

    // Step path for discrete data (hold each value until the next sample).
    const d = values
      .map((v, i) => {
        if (i === 0) return `M ${x(0)} ${y(v)}`;
        return numeric
          ? `L ${x(i)} ${y(v)}`
          : `L ${x(i)} ${y(values[i - 1])} L ${x(i)} ${y(v)}`;
      })
      .join(" ");

    const last = values.length - 1;
    return { d, cx: x(last), cy: y(values[last]) };
  }, [points, height]);

  if (!geom) return null;

  return (
    <svg
      viewBox={`0 0 ${VIEW_W} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label="State history"
      className={["w-full", className].join(" ")}
      style={{ height }}
    >
      {/* hairline baseline */}
      <line
        x1={PAD}
        y1={height - PAD}
        x2={VIEW_W - PAD}
        y2={height - PAD}
        stroke="var(--hairline)"
        strokeWidth={1}
        vectorEffect="non-scaling-stroke"
      />
      <path
        d={geom.d}
        fill="none"
        stroke={STROKE[channel]}
        strokeWidth={2}
        strokeLinejoin="round"
        strokeLinecap="round"
        vectorEffect="non-scaling-stroke"
      />
      {/* glow dot on the latest point only */}
      <circle cx={geom.cx} cy={geom.cy} r={5} fill={STROKE[channel]} opacity={0.25} />
      <circle cx={geom.cx} cy={geom.cy} r={2.5} fill={STROKE[channel]} />
    </svg>
  );
}
