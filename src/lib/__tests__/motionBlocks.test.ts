import { describe, it, expect } from "vitest";
import { motionBlocksFromHistory, mergePlayable } from "../motionBlocks";
import type { CameraEvent } from "../cameraEvents";

const T0 = 1_700_000_000_000;
const min = (n: number) => n * 60_000;
const pt = (offsetMin: number, state: string) => ({ t: T0 + min(offsetMin), state });

const clip = (id: string, startMs: number): CameraEvent => ({
  id,
  camera: "front",
  label: "motion",
  startMs,
  endMs: null,
  hasClip: true,
  hasSnapshot: false,
  thumbnailUrl: null,
  snapshotUrl: null,
});

describe("motionBlocksFromHistory", () => {
  it("folds an on→off run into one block with real start and end", () => {
    const blocks = motionBlocksFromHistory(
      [pt(0, "off"), pt(10, "on"), pt(12, "off")],
      "front",
    );
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({
      startMs: T0 + min(10),
      endMs: T0 + min(12),
      label: "motion",
      camera: "front",
      hasClip: false,
    });
  });

  it("keeps runs beyond the merge gap separate", () => {
    // Two motions 5 min apart (> default 1 min gap) → two blocks.
    const blocks = motionBlocksFromHistory(
      [pt(0, "on"), pt(1, "off"), pt(6, "on"), pt(7, "off")],
      "front",
    );
    expect(blocks.map((b) => b.startMs)).toEqual([T0 + min(0), T0 + min(6)]);
  });

  it("coalesces flicker within the merge gap into one block", () => {
    // off for 20s (< 1 min) between two runs → one merged block spanning both.
    const blocks = motionBlocksFromHistory(
      [
        { t: T0, state: "on" },
        { t: T0 + 20_000, state: "off" },
        { t: T0 + 40_000, state: "on" },
        { t: T0 + 60_000, state: "off" },
      ],
      "front",
    );
    expect(blocks).toHaveLength(1);
    expect(blocks[0].startMs).toBe(T0);
    expect(blocks[0].endMs).toBe(T0 + 60_000);
  });

  it("leaves a run still on at the end of history ongoing", () => {
    const blocks = motionBlocksFromHistory([pt(0, "off"), pt(5, "on")], "front");
    expect(blocks).toHaveLength(1);
    expect(blocks[0].startMs).toBe(T0 + min(5));
    expect(blocks[0].endMs).toBeNull();
  });

  it("yields no blocks for empty or all-off history", () => {
    expect(motionBlocksFromHistory([], "front")).toEqual([]);
    expect(
      motionBlocksFromHistory([pt(0, "off"), pt(5, "unavailable")], "front"),
    ).toEqual([]);
  });

  it("sorts points defensively before folding", () => {
    const blocks = motionBlocksFromHistory(
      [pt(12, "off"), pt(10, "on"), pt(0, "off")],
      "front",
    );
    expect(blocks).toHaveLength(1);
    expect(blocks[0].startMs).toBe(T0 + min(10));
    expect(blocks[0].endMs).toBe(T0 + min(12));
  });
});

describe("mergePlayable", () => {
  it("marks the block containing a clip and swaps its id, keeping the block's duration", () => {
    const blocks = motionBlocksFromHistory([pt(10, "on"), pt(12, "off")], "front");
    const merged = mergePlayable(blocks, [clip("Motion 1", T0 + min(11))]);
    expect(merged).toHaveLength(1);
    expect(merged[0]).toMatchObject({
      id: "Motion 1",
      hasClip: true,
      startMs: T0 + min(10),
      endMs: T0 + min(12),
    });
  });

  it("appends clips that fall in no block, oldest-first", () => {
    const blocks = motionBlocksFromHistory([pt(10, "on"), pt(12, "off")], "front");
    const merged = mergePlayable(blocks, [clip("Motion 9", T0 + min(100))]);
    expect(merged.map((e) => e.startMs)).toEqual([T0 + min(10), T0 + min(100)]);
    // The block stayed a non-playable marker; the far clip stayed playable.
    expect(merged[0].hasClip).toBe(false);
    expect(merged[1].hasClip).toBe(true);
  });

  it("matches each clip at most once", () => {
    const blocks = motionBlocksFromHistory(
      [pt(10, "on"), pt(12, "off"), pt(20, "on"), pt(22, "off")],
      "front",
    );
    // One clip near the first block: only the first block becomes playable.
    const merged = mergePlayable(blocks, [clip("Motion 1", T0 + min(11))]);
    expect(merged.filter((e) => e.hasClip)).toHaveLength(1);
    expect(merged[0].id).toBe("Motion 1");
    expect(merged[1].hasClip).toBe(false);
  });
});
