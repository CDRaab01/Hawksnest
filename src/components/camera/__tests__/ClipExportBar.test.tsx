import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent } from "@testing-library/react";
import { ClipExportBar } from "../ClipExportBar";
import { MAX_CLIP_MS, NUDGE_COARSE_MS, NUDGE_FINE_MS } from "../../../lib/clipExport";
import type { FootageSpan } from "../../../lib/ringFootage";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const NOW = 1_800_000_000_000;
const SEL = { startMs: NOW - 60_000, endMs: NOW - 30_000 };
const COVERED: FootageSpan[] = [{ startMs: NOW - 3600_000, endMs: NOW, playable: true }];

function setup(props: Partial<Parameters<typeof ClipExportBar>[0]> = {}) {
  const onNudge = vi.fn();
  const onSetEdgeToPlayhead = vi.fn();
  const onCancel = vi.fn();
  const onDownload = vi.fn();
  render(
    <ClipExportBar
      selection={SEL}
      playheadMs={NOW - 45_000}
      footage={COVERED}
      state="idle"
      onNudge={onNudge}
      onSetEdgeToPlayhead={onSetEdgeToPlayhead}
      onCancel={onCancel}
      onDownload={onDownload}
      {...props}
    />,
  );
  return { onNudge, onSetEdgeToPlayhead, onCancel, onDownload };
}

describe("ClipExportBar", () => {
  it("nudges the edge that was asked for, by the shared step constants", () => {
    const { onNudge } = setup();
    // Two rows of identical controls — index 0 is Start, index 1 is End.
    fireEvent.click(screen.getAllByRole("button", { name: "+1s" })[0]);
    expect(onNudge).toHaveBeenCalledWith("start", NUDGE_FINE_MS);
    fireEvent.click(screen.getAllByRole("button", { name: "−15s" })[1]);
    expect(onNudge).toHaveBeenCalledWith("end", -NUDGE_COARSE_MS);
  });

  it("marks an edge at the playhead — the primary interaction", () => {
    const { onSetEdgeToPlayhead } = setup();
    fireEvent.click(screen.getByRole("button", { name: /set clip start to playhead/i }));
    expect(onSetEdgeToPlayhead).toHaveBeenCalledWith("start");
    fireEvent.click(screen.getByRole("button", { name: /set clip end to playhead/i }));
    expect(onSetEdgeToPlayhead).toHaveBeenCalledWith("end");
  });

  it("shows the duration and downloads when the selection is valid", () => {
    const { onDownload } = setup();
    expect(screen.getByText("0:30")).toBeInTheDocument();
    const btn = screen.getByRole("button", { name: /download/i });
    expect(btn).toBeEnabled();
    fireEvent.click(btn);
    expect(onDownload).toHaveBeenCalledOnce();
  });

  it("blocks the download and says why for each problem", () => {
    setup({ selection: { startMs: NOW - 61_000, endMs: NOW - 60_000 } });
    expect(screen.getByRole("button", { name: /download/i })).toBeDisabled();
    expect(screen.getByText(/at least 5 seconds/i)).toBeInTheDocument();

    cleanup();
    setup({ selection: { startMs: NOW - MAX_CLIP_MS - 60_000, endMs: NOW - 30_000 } });
    expect(screen.getByRole("button", { name: /download/i })).toBeDisabled();
    expect(screen.getByText(/at most 10 minutes/i)).toBeInTheDocument();

    cleanup();
    // Footage exists, but nowhere near this selection.
    setup({ footage: [{ startMs: NOW - 86_400_000, endMs: NOW - 80_000_000, playable: true }] });
    expect(screen.getByRole("button", { name: /download/i })).toBeDisabled();
    expect(screen.getByText(/no recording exists/i)).toBeInTheDocument();
  });

  it("warns about a gap but still allows the export — Frigate returns what exists", () => {
    const { onDownload } = setup({
      footage: [{ startMs: NOW - 3600_000, endMs: NOW - 50_000, playable: true }],
    });
    expect(screen.getByText(/wasn't recorded/i)).toBeInTheDocument();
    const btn = screen.getByRole("button", { name: /download/i });
    expect(btn).toBeEnabled();
    fireEvent.click(btn);
    expect(onDownload).toHaveBeenCalledOnce();
  });

  it("does not block on an empty footage lane — a missing lane is not a missing recording", () => {
    // Coverage is UNKNOWN here, not NONE. Blocking would make export permanently dead whenever
    // the lane lookup failed or the source has no lane at all.
    setup({ footage: [] });
    expect(screen.getByRole("button", { name: /download/i })).toBeEnabled();
  });

  it("blocks re-entry while preparing, and surfaces a server message over everything else", () => {
    cleanup();
    setup({ state: "preparing" });
    expect(screen.getByRole("button", { name: /preparing/i })).toBeDisabled();

    cleanup();
    setup({ state: "failed", error: "No recordings found for the specified time range" });
    expect(screen.getByText(/no recordings found/i)).toBeInTheDocument();
  });

  it("cancels", () => {
    const { onCancel } = setup();
    fireEvent.click(screen.getByRole("button", { name: /cancel/i }));
    expect(onCancel).toHaveBeenCalledOnce();
  });
});
