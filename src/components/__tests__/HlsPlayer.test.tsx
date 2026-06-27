import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
import { HlsPlayer } from "../HlsPlayer";

/**
 * Scrubbing must SEEK the existing element, not swap `src` and reload (the old behavior that
 * re-buffered/stuttered and could crash). We use a non-HLS `.mp4` src so jsdom takes the direct
 * `video.src` path (no hls.js import).
 */
describe("HlsPlayer scrub seek", () => {
  it("reuses the same <video> element and never re-fires onError when only the seek changes", () => {
    const onError = vi.fn();
    const { container, rerender } = render(
      <HlsPlayer src="http://x/clip.mp4" seekSeconds={10} onError={onError} />,
    );
    const video = container.querySelector("video")!;
    expect(video).toBeTruthy();

    rerender(<HlsPlayer src="http://x/clip.mp4" seekSeconds={45} onError={onError} />);

    // Same element instance (not remounted → the source was not reloaded).
    expect(container.querySelector("video")).toBe(video);
    expect(onError).not.toHaveBeenCalled();
  });

  it("applies the seek once the media reports metadata", () => {
    const { container, rerender } = render(
      <HlsPlayer src="http://x/clip.mp4" seekSeconds={5} />,
    );
    const video = container.querySelector("video")!;
    let seeked = 0;
    // jsdom doesn't implement playback; capture currentTime writes directly.
    Object.defineProperty(video, "currentTime", {
      configurable: true,
      get: () => seeked,
      set: (v: number) => {
        seeked = v;
      },
    });

    rerender(<HlsPlayer src="http://x/clip.mp4" seekSeconds={90} />);
    video.dispatchEvent(new Event("loadedmetadata"));
    expect(seeked).toBe(90);
  });

  it("clamps a negative seek to 0 (backwards-scrub guard)", () => {
    const { container } = render(<HlsPlayer src="http://x/clip.mp4" seekSeconds={-12} />);
    const video = container.querySelector("video")!;
    let seeked = -1;
    Object.defineProperty(video, "currentTime", {
      configurable: true,
      get: () => seeked,
      set: (v: number) => {
        seeked = v;
      },
    });
    video.dispatchEvent(new Event("loadedmetadata"));
    expect(seeked).toBe(0);
  });
});
