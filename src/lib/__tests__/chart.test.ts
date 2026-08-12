import { describe, it, expect } from "vitest";
import {
  chartSeries,
  formatAxisTime,
  niceStep,
  stepDecimals,
  timeAxis,
  timeFraction,
  valueAxis,
  valueFraction,
} from "../chart";
import type { HistoryPoint } from "../../store/source";

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
const T0 = Date.UTC(2026, 7, 12, 0, 0, 0);

const series = (states: string[], stepMs = HOUR): HistoryPoint[] =>
  states.map((state, i) => ({ t: T0 + i * stepMs, state }));

describe("chartSeries", () => {
  it("plots an all-numeric series as its own values", () => {
    const s = chartSeries(series(["10", "20.5", "15"]));
    expect(s.numeric).toBe(true);
    expect(s.values).toEqual([10, 20.5, 15]);
    expect(s.labels).toEqual([]);
    expect(s.times).toEqual([T0, T0 + HOUR, T0 + 2 * HOUR]);
  });

  it("maps a discrete series onto evenly-spaced levels", () => {
    const s = chartSeries(series(["locked", "unlocked", "locked", "jammed"]));
    expect(s.numeric).toBe(false);
    expect(s.labels).toEqual(["locked", "unlocked", "jammed"]);
    expect(s.values).toEqual([0, 1, 0, 2]);
  });

  it("treats a series with any non-number as discrete", () => {
    const s = chartSeries(series(["10", "unavailable", "12"]));
    expect(s.numeric).toBe(false);
    expect(s.labels).toEqual(["10", "unavailable", "12"]);
  });

  it("is empty-safe", () => {
    const s = chartSeries([]);
    expect(s.numeric).toBe(false);
    expect(s.values).toEqual([]);
  });
});

describe("niceStep", () => {
  it("rounds up to 1/2/5 × a power of ten", () => {
    expect(niceStep(0.9)).toBe(1);
    expect(niceStep(1.7)).toBe(2);
    expect(niceStep(4)).toBe(5);
    expect(niceStep(7)).toBe(10);
    expect(niceStep(23)).toBe(50);
    expect(niceStep(0.03)).toBeCloseTo(0.05);
  });

  it("never returns zero or NaN for degenerate input", () => {
    expect(niceStep(0)).toBe(1);
    expect(niceStep(-5)).toBe(1);
    expect(niceStep(Number.NaN)).toBe(1);
  });
});

describe("stepDecimals", () => {
  it("prints only the digits the step can resolve", () => {
    expect(stepDecimals(10)).toBe(0);
    expect(stepDecimals(1)).toBe(0);
    expect(stepDecimals(0.5)).toBe(1);
    expect(stepDecimals(0.05)).toBe(2);
    expect(stepDecimals(0.000001)).toBe(3); // capped
  });
});

describe("valueAxis", () => {
  it("gives a numeric series a rounded domain with evenly-spaced ticks", () => {
    const axis = valueAxis(chartSeries(series(["12", "47", "31"])));
    expect(axis.lo).toBeLessThanOrEqual(12);
    expect(axis.hi).toBeGreaterThanOrEqual(47);
    const values = axis.ticks.map((t) => t.value);
    const gaps = values.slice(1).map((v, i) => v - values[i]);
    expect(new Set(gaps.map((g) => g.toFixed(6))).size).toBe(1);
    expect(values[0]).toBe(axis.lo);
    expect(values[values.length - 1]).toBe(axis.hi);
  });

  it("appends the unit to numeric tick labels", () => {
    const axis = valueAxis(chartSeries(series(["10", "30"])), "°F");
    expect(axis.ticks.every((t) => t.label.endsWith("°F"))).toBe(true);
  });

  it("still spans a readable band for a flat series", () => {
    const axis = valueAxis(chartSeries(series(["20", "20", "20"])));
    expect(axis.hi).toBeGreaterThan(axis.lo);
    expect(axis.ticks.length).toBeGreaterThanOrEqual(2);
  });

  it("names each state of a discrete series", () => {
    const axis = valueAxis(chartSeries(series(["locked", "unlocked"])));
    expect(axis.lo).toBe(0);
    expect(axis.hi).toBe(1);
    expect(axis.ticks.map((t) => t.label)).toEqual(["Locked", "Unlocked"]);
  });

  it("thins a many-state axis to its ends", () => {
    const axis = valueAxis(chartSeries(series(["a", "b", "c", "d", "e", "f", "g"])));
    expect(axis.ticks).toHaveLength(2);
    expect(axis.ticks[0].label).toBe("A");
    expect(axis.ticks[1].label).toBe("G");
  });

  it("prettifies underscored states", () => {
    const axis = valueAxis(chartSeries(series(["not_home", "home"])));
    expect(axis.ticks[0].label).toBe("Not home");
  });
});

describe("timeAxis", () => {
  it("spaces ticks on a human step inside the range", () => {
    const ticks = timeAxis(T0, T0 + 24 * HOUR);
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks.every((t) => t.t >= T0 && t.t <= T0 + 24 * HOUR)).toBe(true);
    const gaps = ticks.slice(1).map((t, i) => t.t - ticks[i].t);
    expect(new Set(gaps).size).toBe(1);
    // 24h over ~4 ticks lands on the 6-hour step.
    expect(gaps[0]).toBe(6 * HOUR);
  });

  it("uses day steps and date labels over a 30-day range", () => {
    const ticks = timeAxis(T0, T0 + 30 * DAY);
    expect(ticks.length).toBeGreaterThanOrEqual(3);
    expect(ticks.every((t) => /\d/.test(t.label) && !/:/.test(t.label))).toBe(true);
  });

  it("uses clock labels over a 6-hour range", () => {
    const ticks = timeAxis(T0, T0 + 6 * HOUR);
    expect(ticks.every((t) => /^\d{2}:\d{2}$/.test(t.label))).toBe(true);
  });

  it("returns nothing for an empty or inverted span", () => {
    expect(timeAxis(T0, T0)).toEqual([]);
    expect(timeAxis(T0, T0 - HOUR)).toEqual([]);
  });
});

describe("formatAxisTime", () => {
  it("switches from clock to calendar past three days", () => {
    expect(formatAxisTime(T0, 6 * HOUR)).toMatch(/^\d{2}:\d{2}$/);
    expect(formatAxisTime(T0, 30 * DAY)).not.toMatch(/:/);
  });
});

describe("fractions", () => {
  it("places a time across the span and clamps outside it", () => {
    expect(timeFraction(T0 + HOUR, T0, T0 + 4 * HOUR)).toBeCloseTo(0.25);
    expect(timeFraction(T0 - HOUR, T0, T0 + 4 * HOUR)).toBe(0);
    expect(timeFraction(T0 + 9 * HOUR, T0, T0 + 4 * HOUR)).toBe(1);
    expect(timeFraction(T0, T0, T0)).toBe(0);
  });

  it("places a value across the domain and clamps outside it", () => {
    expect(valueFraction(25, 0, 100)).toBeCloseTo(0.25);
    expect(valueFraction(-5, 0, 100)).toBe(0);
    expect(valueFraction(5, 10, 10)).toBe(0);
  });
});
