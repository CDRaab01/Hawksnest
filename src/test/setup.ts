import "@testing-library/jest-dom/vitest";

// jsdom has no layout engine or ResizeObserver, but the camera timeline measures
// its track width (via getBoundingClientRect + ResizeObserver) before it renders
// ticks/event chips/playhead. Give it a stable non-zero width so it renders.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver;

const TRACK_RECT: DOMRect = {
  x: 0,
  y: 0,
  top: 0,
  left: 0,
  right: 1000,
  bottom: 56,
  width: 1000,
  height: 56,
  toJSON: () => ({}),
};
Element.prototype.getBoundingClientRect = function (): DOMRect {
  return TRACK_RECT;
};
