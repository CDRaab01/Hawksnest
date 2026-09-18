import { describe, expect, it } from "vitest";
import {
  DEFAULT_ASPECT,
  WIDE_ASPECT_THRESHOLD,
  aspectFromDimensions,
  isWideAspect,
} from "../mediaAspect";

describe("aspectFromDimensions", () => {
  it("returns width/height for real dimensions", () => {
    expect(aspectFromDimensions(1536, 432)).toBeCloseTo(3.56, 2); // dual-lens panorama
    expect(aspectFromDimensions(640, 480)).toBeCloseTo(1.33, 2); // 4:3 doorbell
    expect(aspectFromDimensions(1920, 1080)).toBeCloseTo(1.78, 2); // 16:9
  });

  it("returns null when either dimension is unknown (0)", () => {
    // A <video> reports 0×0 before loadedmetadata, an <img> before load — 0 must
    // never become an aspect ratio (it would collapse the frame to zero height).
    expect(aspectFromDimensions(0, 0)).toBeNull();
    expect(aspectFromDimensions(640, 0)).toBeNull();
    expect(aspectFromDimensions(0, 480)).toBeNull();
    expect(aspectFromDimensions(-1, 100)).toBeNull();
  });
});

describe("isWideAspect", () => {
  it("classifies dual-lens panoramas as wide", () => {
    expect(isWideAspect(3.56)).toBe(true); // 1536x432 stitched Duo
    expect(isWideAspect(WIDE_ASPECT_THRESHOLD)).toBe(true); // boundary is inclusive
  });

  it("classifies normal single-lens cameras as not wide", () => {
    expect(isWideAspect(DEFAULT_ASPECT)).toBe(false); // 16:9
    expect(isWideAspect(4 / 3)).toBe(false); // doorbell
    expect(isWideAspect(1.99)).toBe(false); // just under the threshold
  });
});
