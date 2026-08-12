import { useMemo } from "react";
import type { Channel } from "./PanelCard";
import type { HistoryPoint } from "../store/source";
import {
  chartSeries,
  timeAxis,
  timeFraction,
  valueAxis,
  valueFraction,
} from "../lib/chart";

const STROKE: Record<Channel, string> = {
  effort: "var(--effort)",
  recovery: "var(--recovery)",
  strength: "var(--strength)",
  streak: "var(--streak)",
};

interface HistoryChartProps {
  points: HistoryPoint[];
  channel?: Channel;
  /** `unit_of_measurement`, appended to the value-axis labels when present. */
  unit?: string;
  /** Plot height in px (width is fluid). */
  height?: number;
  className?: string;
}

/** viewBox units — the plot stretches to its container, so these are arbitrary. */
const VIEW_W = 300;
const VIEW_H = 100;

/**
 * The entity-history chart: a 2px channel line over a labelled value axis and a
 * labelled time axis, with a glow dot on the latest sample. Numeric series
 * (sensors, climate, levels) render as a line; series whose states aren't all
 * numbers (lock/binary/cover/…) render as a step chart over discrete levels,
 * each level named on the axis.
 *
 * Axis math lives in `lib/chart.ts` (ported to Android as `core/logic/Chart.kt`);
 * this file is placement only. Two placement rules matter:
 * - **x is time, not sample index.** HA history is event-driven, so index
 *   spacing would misplace every sample under a labelled clock axis.
 * - **Labels are HTML, not SVG text.** The plot stretches with
 *   `preserveAspectRatio="none"`, which would squash any glyph inside it — so
 *   text (and the round latest-sample dot) is positioned over the plot instead.
 *
 * Returns null for <2 points — the caller shows an empty state.
 */
export function HistoryChart({
  points,
  channel = "effort",
  unit,
  height = 96,
  className = "",
}: HistoryChartProps) {
  const chart = useMemo(() => {
    if (points.length < 2) return null;

    const series = chartSeries(points);
    const axis = valueAxis(series, unit);
    const t0 = series.times[0];
    const t1 = series.times[series.times.length - 1];
    const times = timeAxis(t0, t1);

    const x = (i: number) => timeFraction(series.times[i], t0, t1) * VIEW_W;
    const y = (v: number) => VIEW_H - valueFraction(v, axis.lo, axis.hi) * VIEW_H;

    // Discrete states hold their value until the next sample — a diagonal
    // between "locked" and "unlocked" would draw a state that never existed.
    const d = series.values
      .map((v, i) => {
        if (i === 0) return `M ${x(0)} ${y(v)}`;
        return series.numeric
          ? `L ${x(i)} ${y(v)}`
          : `L ${x(i)} ${y(series.values[i - 1])} L ${x(i)} ${y(v)}`;
      })
      .join(" ");

    const last = series.values.length - 1;
    return {
      d,
      axis,
      numeric: series.numeric,
      times,
      t0,
      t1,
      lastLeft: timeFraction(series.times[last], t0, t1) * 100,
      lastTop: (1 - valueFraction(series.values[last], axis.lo, axis.hi)) * 100,
    };
  }, [points, unit]);

  if (!chart) return null;
  const stroke = STROKE[channel];

  return (
    <div className={className}>
      <div className="flex gap-sm">
        {/* Value axis. Ticks are evenly spaced across the domain by construction,
            so each label can be placed at its own fraction of the plot height. */}
        <div className="relative w-16 shrink-0" style={{ height }}>
          {chart.axis.ticks.map((tick) => (
            <span
              key={tick.value}
              className={[
                "absolute right-0 -translate-y-1/2 truncate text-caption leading-none text-ink-faint",
                // Numbers get the mono data face; state names read as words.
                chart.numeric ? "font-mono" : "font-body",
              ].join(" ")}
              style={{
                top: `${(1 - valueFraction(tick.value, chart.axis.lo, chart.axis.hi)) * 100}%`,
              }}
            >
              {tick.label}
            </span>
          ))}
        </div>

        <div className="relative min-w-0 flex-1" style={{ height }}>
          <svg
            viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
            preserveAspectRatio="none"
            role="img"
            aria-label="State history"
            className="h-full w-full overflow-visible"
          >
            {chart.axis.ticks.map((tick) => (
              <line
                key={`h${tick.value}`}
                x1={0}
                x2={VIEW_W}
                y1={
                  VIEW_H -
                  valueFraction(tick.value, chart.axis.lo, chart.axis.hi) * VIEW_H
                }
                y2={
                  VIEW_H -
                  valueFraction(tick.value, chart.axis.lo, chart.axis.hi) * VIEW_H
                }
                stroke="var(--hairline)"
                strokeWidth={1}
                vectorEffect="non-scaling-stroke"
              />
            ))}
            {chart.times.map((tick) => (
              <line
                key={`v${tick.t}`}
                x1={timeFraction(tick.t, chart.t0, chart.t1) * VIEW_W}
                x2={timeFraction(tick.t, chart.t0, chart.t1) * VIEW_W}
                y1={0}
                y2={VIEW_H}
                stroke="var(--hairline)"
                strokeWidth={1}
                strokeDasharray="2 4"
                vectorEffect="non-scaling-stroke"
              />
            ))}
            {/* pathLength=1 normalizes the dash space so the draw-in animates
                1 → 0 regardless of the real path length. Runs once on mount
                (each range change remounts the chart via its loading state);
                live data updates reshape the path without re-drawing. */}
            <path
              d={chart.d}
              fill="none"
              stroke={stroke}
              strokeWidth={2}
              strokeLinejoin="round"
              strokeLinecap="round"
              vectorEffect="non-scaling-stroke"
              pathLength={1}
              strokeDasharray={1}
              className="animate-draw motion-reduce:animate-none"
            />
          </svg>
          {/* Latest sample, drawn outside the squashed viewBox so it stays round. */}
          <span
            aria-hidden="true"
            className="pointer-events-none absolute block h-[5px] w-[5px] -translate-x-1/2 -translate-y-1/2 rounded-full animate-fade-in motion-reduce:animate-none"
            style={{
              left: `${chart.lastLeft}%`,
              top: `${chart.lastTop}%`,
              background: stroke,
              boxShadow: `0 0 0 4px color-mix(in srgb, ${stroke} 25%, transparent)`,
              animationDelay: "500ms",
            }}
          />
        </div>
      </div>

      {/* Time axis, offset by the value-axis gutter so it lines up with the plot. */}
      <div className="relative ml-[4.5rem] mt-xs h-4">
        {chart.times.map((tick) => {
          const pct = timeFraction(tick.t, chart.t0, chart.t1) * 100;
          // Edge labels align inward instead of hanging off the panel.
          const shift = pct < 6 ? "0" : pct > 94 ? "-100%" : "-50%";
          return (
            <span
              key={tick.t}
              className="absolute top-0 whitespace-nowrap font-mono text-caption leading-none text-ink-faint"
              style={{ left: `${pct}%`, transform: `translateX(${shift})` }}
            >
              {tick.label}
            </span>
          );
        })}
      </div>
    </div>
  );
}
