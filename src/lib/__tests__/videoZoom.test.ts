import { describe, expect, it } from "vitest";
import {
  MAX_SCALE,
  NO_ZOOM,
  applyGesture,
  clampScale,
  clampZoom,
  cssTransform,
  isZoomed,
  maxOffset,
  shouldCaptureDrag,
} from "../videoZoom";

const FRAME = { width: 320, height: 180 };
const still = { scaleChange: 1, panX: 0, panY: 0, focusX: 0, focusY: 0 };

describe("clampScale", () => {
  it("never zooms out past 1:1 — there is no content outside the frame to reveal", () => {
    expect(clampScale(0.25)).toBe(1);
    expect(clampScale(-3)).toBe(1);
  });

  it("caps magnification at MAX_SCALE", () => {
    expect(clampScale(99)).toBe(MAX_SCALE);
  });

  it("snaps pinch residue back to exactly 1 so the zoomed affordances turn off", () => {
    expect(clampScale(1.004)).toBe(1);
    expect(isZoomed({ ...NO_ZOOM, scale: clampScale(1.004) })).toBe(false);
  });

  it("survives NaN rather than propagating it into the transform", () => {
    expect(clampScale(Number.NaN)).toBe(1);
  });
});

describe("maxOffset", () => {
  it("is zero at 1x — an unzoomed picture must not be draggable at all", () => {
    expect(maxOffset(320, 1)).toBe(0);
  });

  it("is half the overflow at higher scales", () => {
    // 320 wide at 2x = 640 of content in a 320 window -> 160 of slack, 80 either side.
    expect(maxOffset(320, 2)).toBe(160);
  });
});

describe("clampZoom", () => {
  it("keeps the picture covering the frame — no black gap at the edge", () => {
    const z = clampZoom({ scale: 2, offsetX: 9999, offsetY: -9999 }, FRAME);
    expect(z.offsetX).toBe(maxOffset(FRAME.width, 2));
    expect(z.offsetY).toBe(-maxOffset(FRAME.height, 2));
  });

  it("pulls a far-panned picture back to centre when the scale drops", () => {
    // The regression this guards: an offset legal at 4x is illegal at 1.2x. Clamping the
    // offset against the OLD scale would leave a visible gap after a pinch-out.
    const zoomedRight = clampZoom({ scale: 4, offsetX: 480, offsetY: 0 }, FRAME);
    expect(zoomedRight.offsetX).toBe(480);
    const zoomedBackOut = clampZoom({ ...zoomedRight, scale: 1.2 }, FRAME);
    expect(zoomedBackOut.offsetX).toBeCloseTo(maxOffset(FRAME.width, 1.2), 5);
    expect(zoomedBackOut.offsetX).toBeLessThan(480);
  });

  it("recentres completely on the way back to 1x", () => {
    const z = clampZoom({ scale: 1, offsetX: 200, offsetY: 200 }, FRAME);
    expect(z).toEqual(NO_ZOOM);
  });
});

describe("applyGesture", () => {
  it("pans a zoomed picture by the drag delta", () => {
    const start = { scale: 2, offsetX: 0, offsetY: 0 };
    const z = applyGesture(start, FRAME, { ...still, panX: 20, panY: 10 });
    expect(z.offsetX).toBe(20);
    expect(z.offsetY).toBe(10);
  });

  it("refuses to pan at 1x, so the gesture can never smear an unzoomed picture", () => {
    const z = applyGesture(NO_ZOOM, FRAME, { ...still, panX: 50, panY: 50 });
    expect(z).toEqual(NO_ZOOM);
  });

  it("keeps the point under the fingers pinned while zooming", () => {
    // Pinch centred 80px right of centre, doubling the scale. The content point under the
    // centroid must not move, which requires the offset to shift by focus * (1 - ratio).
    const z = applyGesture(NO_ZOOM, FRAME, { ...still, scaleChange: 2, focusX: 80, focusY: 0 });
    expect(z.scale).toBe(2);
    expect(z.offsetX).toBeCloseTo(-80, 5);
  });

  it("stops translating once the pinch hits the max scale", () => {
    // Already at MAX_SCALE: no magnification is achievable, so ratio is 1 and the focal
    // correction must vanish. Otherwise the picture keeps sliding under stationary fingers.
    const atMax = { scale: MAX_SCALE, offsetX: 0, offsetY: 0 };
    const z = applyGesture(atMax, FRAME, { ...still, scaleChange: 2, focusX: 80, focusY: 0 });
    expect(z.scale).toBe(MAX_SCALE);
    expect(z.offsetX).toBe(0);
  });

  it("clamps a zoom-out so the picture cannot be left off-centre", () => {
    const panned = { scale: 4, offsetX: 480, offsetY: 270 };
    const z = applyGesture(panned, FRAME, { ...still, scaleChange: 0.1 });
    expect(z).toEqual(NO_ZOOM);
  });

  it("treats a zero or negative scale change as no change", () => {
    const start = { scale: 2, offsetX: 0, offsetY: 0 };
    expect(applyGesture(start, FRAME, { ...still, scaleChange: 0 }).scale).toBe(2);
    expect(applyGesture(start, FRAME, { ...still, scaleChange: -1 }).scale).toBe(2);
  });
});

describe("shouldCaptureDrag", () => {
  it("lets the page scroll when the picture is not zoomed", () => {
    // The bug this prevents: a video that always captures drags eats the vertical
    // page scroll on a phone, which reads as the whole app being frozen.
    expect(shouldCaptureDrag(NO_ZOOM)).toBe(false);
  });

  it("captures drags once zoomed, so panning works", () => {
    expect(shouldCaptureDrag({ scale: 2, offsetX: 0, offsetY: 0 })).toBe(true);
  });
});

describe("cssTransform", () => {
  it("is the identity transform when unzoomed", () => {
    expect(cssTransform(NO_ZOOM)).toBe("translate(0px, 0px) scale(1)");
  });

  it("translates before scaling so offsets stay in unscaled frame pixels", () => {
    expect(cssTransform({ scale: 2, offsetX: 10, offsetY: -5 })).toBe(
      "translate(10px, -5px) scale(2)",
    );
  });
});
