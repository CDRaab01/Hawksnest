import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import { Sparkline } from "../Sparkline";
import type { HistoryPoint } from "../../store/source";

const series = (states: string[]): HistoryPoint[] =>
  states.map((state, i) => ({ t: i * 1000, state }));

describe("Sparkline", () => {
  it("renders an SVG path for a numeric series", () => {
    const { container, getByRole } = render(
      <Sparkline points={series(["10", "20", "15", "30"])} />,
    );
    expect(getByRole("img", { name: "State history" })).toBeInTheDocument();
    const path = container.querySelector("path");
    expect(path?.getAttribute("d")).toMatch(/^M /);
  });

  it("renders a step path for a discrete (non-numeric) series", () => {
    const { container } = render(
      <Sparkline points={series(["locked", "unlocked", "locked"])} />,
    );
    // Step rendering inserts an extra vertical segment per transition.
    const d = container.querySelector("path")?.getAttribute("d") ?? "";
    expect(d.match(/L /g)?.length).toBeGreaterThan(2);
  });

  it("renders nothing for fewer than two points", () => {
    const { container } = render(<Sparkline points={series(["10"])} />);
    expect(container.querySelector("svg")).toBeNull();
  });
});
