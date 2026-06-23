import { describe, it, expect } from "vitest";
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "../../App";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("design directions render the Security scene with resolved labels", () => {
  for (const path of ["/a", "/b"]) {
    it(`${path} resolves labels and never leaks raw attribute names`, () => {
      renderAt(path);
      // The poster-child label is resolved away…
      expect(screen.queryByText(/Lock Current status/)).toBeNull();
      // …and the human label + state are present.
      expect(screen.getAllByText("Front Door").length).toBeGreaterThan(0);
      expect(screen.getByText("Open")).toBeInTheDocument();
    });
  }

  it("/c shows the area hub and drills into an area", () => {
    renderAt("/c");
    expect(screen.getByText("Areas")).toBeInTheDocument();
    expect(screen.getByText("Front Door")).toBeInTheDocument();
    expect(screen.queryByText(/Lock Current status/)).toBeNull();
  });

  it("/c/Front%20Door reproduces the scene contents", () => {
    renderAt("/c/Front%20Door");
    const main = screen.getByRole("main");
    expect(within(main).getByText("Open")).toBeInTheDocument();
    expect(within(main).getByText("Safe")).toBeInTheDocument();
    expect(within(main).queryByText(/Lock Intrusion/)).toBeNull();
  });
});
