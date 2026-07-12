import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Lock } from "lucide-react";
import { SlideToAct } from "../SlideToAct";

function renderSlide(overrides: Partial<Parameters<typeof SlideToAct>[0]> = {}) {
  const onCommit = vi.fn();
  render(
    <SlideToAct
      label="Slide to lock"
      pendingLabel="Locking…"
      icon={<Lock size={20} />}
      channel="recovery"
      pending={false}
      onCommit={onCommit}
      testId="slide-test"
      {...overrides}
    />,
  );
  return onCommit;
}

describe("SlideToAct", () => {
  it("commits via the keyboard path (Enter on the focused thumb)", () => {
    const onCommit = renderSlide();
    fireEvent.keyDown(screen.getByRole("button", { name: "Slide to lock" }), {
      key: "Enter",
    });
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it("holds the pending state: label swaps and the thumb won't commit", () => {
    const onCommit = renderSlide({ pending: true });
    expect(screen.getByText("Locking…")).toBeInTheDocument();
    const thumb = screen.getByRole("button", { name: "Slide to lock" });
    expect(thumb).toBeDisabled();
    fireEvent.keyDown(thumb, { key: "Enter" });
    expect(onCommit).not.toHaveBeenCalled();
  });

  it("does nothing when disabled (unavailable lock)", () => {
    const onCommit = renderSlide({ disabled: true });
    fireEvent.keyDown(screen.getByRole("button", { name: "Slide to lock" }), {
      key: "Enter",
    });
    expect(onCommit).not.toHaveBeenCalled();
  });
});
