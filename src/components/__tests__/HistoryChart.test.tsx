import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { HistoryChart } from "../HistoryChart";
import type { HistoryPoint } from "../../store/source";

const HOUR = 3_600_000;
const T0 = Date.UTC(2026, 7, 12, 0, 0, 0);

/** Evenly-spaced hourly samples — the shape HA history takes over a short range. */
const series = (states: string[], stepMs = HOUR): HistoryPoint[] =>
  states.map((state, i) => ({ t: T0 + i * stepMs, state }));

describe("HistoryChart", () => {
  it("renders an SVG path for a numeric series", () => {
    const { container, getByRole } = render(
      <HistoryChart points={series(["10", "20", "15", "30"])} />,
    );
    expect(getByRole("img", { name: "State history" })).toBeInTheDocument();
    const path = container.querySelector("path");
    expect(path?.getAttribute("d")).toMatch(/^M /);
  });

  it("renders a step path for a discrete (non-numeric) series", () => {
    const { container } = render(
      <HistoryChart points={series(["locked", "unlocked", "locked"])} />,
    );
    // Step rendering inserts an extra vertical segment per transition.
    const d = container.querySelector("path")?.getAttribute("d") ?? "";
    expect(d.match(/L /g)?.length).toBeGreaterThan(2);
  });

  it("labels the value axis, with the unit when one is given", () => {
    const { getByText } = render(
      <HistoryChart points={series(["10", "20", "15", "30"])} unit="°F" />,
    );
    expect(getByText("30°F")).toBeInTheDocument();
    expect(getByText("10°F")).toBeInTheDocument();
  });

  it("names each state on the value axis of a discrete series", () => {
    const { getByText } = render(
      <HistoryChart points={series(["locked", "unlocked", "locked"])} />,
    );
    expect(getByText("Locked")).toBeInTheDocument();
    expect(getByText("Unlocked")).toBeInTheDocument();
  });

  it("labels the time axis with clock times across the range", () => {
    const { container } = render(
      <HistoryChart points={series(["1", "2", "3", "4", "5", "6", "7"])} />,
    );
    const labels = [...container.querySelectorAll("span")]
      .map((s) => s.textContent ?? "")
      .filter((t) => /^\d{2}:\d{2}$/.test(t));
    expect(labels.length).toBeGreaterThanOrEqual(2);
  });

  it("spaces samples by time, not by index", () => {
    // Three samples where the middle one sits 1/4 of the way through the span:
    // an index-based x would place it at the halfway mark.
    const points: HistoryPoint[] = [
      { t: T0, state: "0" },
      { t: T0 + HOUR, state: "5" },
      { t: T0 + 4 * HOUR, state: "10" },
    ];
    const { container } = render(<HistoryChart points={points} />);
    const d = container.querySelector("path")?.getAttribute("d") ?? "";
    // viewBox is 300 wide: 1/4 of the span is x=75, not x=150.
    expect(d).toContain("L 75 ");
  });

  it("renders nothing for fewer than two points", () => {
    const { container } = render(<HistoryChart points={series(["10"])} />);
    expect(container.querySelector("svg")).toBeNull();
  });
});
