import type { HistoryPoint } from "../store/source";

/**
 * Pure axis math for the entity-history chart — no DOM, so the scale/tick/format
 * decisions are unit-tested in isolation. Ported 1:1 to Android as
 * `core/logic/Chart.kt`; keep the two in step.
 *
 * The chart plots **against time**, not against sample index: HA's history is
 * event-driven, so samples are unevenly spaced and an index axis would put a
 * quiet hour and a busy minute the same distance apart — which is exactly the
 * lie a labelled time axis must not tell.
 */

/** A history series reduced to plottable numbers. */
export interface ChartSeries {
  /** y value per sample, in sample order. */
  values: number[];
  /** epoch-ms per sample, in sample order. */
  times: number[];
  /** True when every sample parsed as a finite number (sensor/climate/level). */
  numeric: boolean;
  /**
   * Distinct state labels for a discrete series — level `i` is `labels[i]`.
   * Empty for a numeric series.
   */
  labels: string[];
}

export interface AxisTick {
  value: number;
  label: string;
}

/** The value (y) axis: the domain the plot maps onto plus its labelled ticks. */
export interface ValueAxis {
  lo: number;
  hi: number;
  ticks: AxisTick[];
}

export interface TimeTick {
  t: number;
  label: string;
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Reduce a state series to plottable numbers: an all-numeric series plots its
 * own values; a discrete series (lock/binary/cover/…) maps each distinct state
 * to an evenly-spaced level so on/off/open/locked still draws a readable step.
 */
export function chartSeries(points: HistoryPoint[]): ChartSeries {
  const numbers = points.map((p) => Number(p.state));
  const numeric = points.length > 0 && numbers.every((n) => Number.isFinite(n));
  if (numeric) {
    return { values: numbers, times: points.map((p) => p.t), numeric, labels: [] };
  }
  const labels = [...new Set(points.map((p) => p.state))];
  const index = new Map(labels.map((s, i) => [s, i]));
  return {
    values: points.map((p) => index.get(p.state) ?? 0),
    times: points.map((p) => p.t),
    numeric: false,
    labels,
  };
}

/**
 * The nearest "human" step at or above [raw] — 1, 2, 5 or 10 times a power of
 * ten. Axis labels people can read at a glance are the whole point of the axis.
 */
export function niceStep(raw: number): number {
  if (!Number.isFinite(raw) || raw <= 0) return 1;
  const exp = Math.floor(Math.log10(raw));
  const pow = 10 ** exp;
  const f = raw / pow;
  const nf = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return nf * pow;
}

/** Decimal places worth printing for values spaced [step] apart (capped at 3). */
export function stepDecimals(step: number): number {
  if (!Number.isFinite(step) || step <= 0) return 0;
  return Math.min(3, Math.max(0, -Math.floor(Math.log10(step))));
}

/** Prettify a raw HA state for a discrete axis label ("not_home" → "Not home"). */
function prettyState(state: string): string {
  const words = state.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** How many discrete levels still get one label each before the axis thins out. */
const MAX_DISCRETE_TICKS = 5;

/**
 * The value axis for a series. Numeric series get a rounded domain with evenly
 * spaced ticks (so the labels sit at even fractions of the plot height, which
 * is what lets the renderer place them without measuring text); discrete series
 * get one tick per state, thinned to the first and last when there are many.
 */
export function valueAxis(
  series: ChartSeries,
  unit?: string,
  targetTicks = 3,
): ValueAxis {
  if (!series.numeric) {
    const levels = series.labels;
    const hi = Math.max(1, levels.length - 1);
    const shown =
      levels.length <= MAX_DISCRETE_TICKS
        ? levels.map((s, i) => ({ value: i, label: prettyState(s) }))
        : [
            { value: 0, label: prettyState(levels[0]) },
            { value: levels.length - 1, label: prettyState(levels[levels.length - 1]) },
          ];
    return { lo: 0, hi, ticks: shown };
  }

  const finite = series.values.filter((v) => Number.isFinite(v));
  const min = finite.length ? Math.min(...finite) : 0;
  const max = finite.length ? Math.max(...finite) : 1;
  // A flat series still deserves a readable axis — spread a band around it.
  const span = max - min || Math.abs(max) || 1;
  const step = niceStep(span / Math.max(1, targetTicks - 1));
  const lo = Math.floor(min / step) * step;
  // A series that sits exactly on a step boundary (a flat one, or 20–20) would
  // collapse lo and hi onto each other — give it one step of headroom.
  const hi = Math.ceil(max / step) * step > lo ? Math.ceil(max / step) * step : lo + step;
  const decimals = stepDecimals(step);
  const suffix = unit ?? "";

  const ticks: AxisTick[] = [];
  // Guard the loop on a count, not on float accumulation — 0.1-sized steps
  // never land exactly on `hi`.
  const count = Math.round((hi - lo) / step);
  for (let i = 0; i <= count; i++) {
    const value = lo + step * i;
    ticks.push({ value, label: `${value.toFixed(decimals)}${suffix}` });
  }
  return { lo, hi, ticks };
}

/** Time steps the axis is allowed to land on, coarsest-last. */
const TIME_STEPS = [
  5 * MINUTE,
  15 * MINUTE,
  30 * MINUTE,
  HOUR,
  2 * HOUR,
  3 * HOUR,
  6 * HOUR,
  12 * HOUR,
  DAY,
  2 * DAY,
  7 * DAY,
  14 * DAY,
];

/** The first tick at or after [t] on a [step] grid, aligned to local time. */
function alignUp(t: number, step: number): number {
  const d = new Date(t);
  if (step >= DAY) d.setHours(0, 0, 0, 0);
  else d.setMinutes(0, 0, 0);
  let x = d.getTime();
  while (x < t) x += step;
  return x;
}

/**
 * Round clock/date labels across `[t0, t1]`, spaced on a human step (hours or
 * days, never "every 47 minutes"). Returns [] when the span is empty.
 */
export function timeAxis(t0: number, t1: number, target = 4): TimeTick[] {
  const span = t1 - t0;
  if (!Number.isFinite(span) || span <= 0) return [];
  const raw = span / Math.max(1, target);
  const step = TIME_STEPS.find((s) => s >= raw) ?? TIME_STEPS[TIME_STEPS.length - 1];
  const ticks: TimeTick[] = [];
  for (let t = alignUp(t0, step); t <= t1; t += step) {
    ticks.push({ t, label: formatAxisTime(t, span) });
  }
  return ticks;
}

/**
 * A tick's label: clock time for spans a person still thinks about in hours,
 * calendar date beyond that. 24-hour so the web and Android axes read alike.
 */
export function formatAxisTime(t: number, spanMs: number): string {
  const d = new Date(t);
  if (spanMs > 3 * DAY) {
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }
  return d.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

/** Where [t] falls across `[t0, t1]`, 0–1. A zero span pins everything to 0. */
export function timeFraction(t: number, t0: number, t1: number): number {
  const span = t1 - t0;
  if (span <= 0) return 0;
  return Math.min(1, Math.max(0, (t - t0) / span));
}

/** Where [v] falls across `[lo, hi]`, 0–1 measured from the axis floor. */
export function valueFraction(v: number, lo: number, hi: number): number {
  const span = hi - lo;
  if (span <= 0) return 0;
  return Math.min(1, Math.max(0, (v - lo) / span));
}
