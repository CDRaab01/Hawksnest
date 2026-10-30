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

/**
 * The source effect must be keyed on WHICH MEDIA IS LOADED and nothing else.
 *
 * Callers pass an inline arrow for `onError` (`LivePlayer` does: `() => stepDownFrom("video")`),
 * so its identity changes on every render of the parent — and the parent re-renders on roughly
 * every HA state push. With `onError` in the dep array that meant `hls.destroy()` +
 * `removeAttribute("src")` + `new Hls()` on each one: live video restarting, forever.
 */
describe("HlsPlayer source stability", () => {
  it("does not tear down hls.js when only the onError identity changes", async () => {
    const destroy = vi.fn();
    const loadSource = vi.fn();
    const constructed = vi.fn();
    vi.doMock("hls.js", () => ({
      default: class {
        static isSupported() {
          return true;
        }
        static Events = { ERROR: "hlsError" };
        constructor() {
          constructed();
        }
        loadSource = loadSource;
        attachMedia = vi.fn();
        on = vi.fn();
        destroy = destroy;
      },
    }));

    const { HlsPlayer: Player } = await import("../HlsPlayer");
    const { rerender } = render(
      <Player src="http://x/master.m3u8" onError={() => {}} />,
    );
    await vi.waitFor(() => expect(constructed).toHaveBeenCalledTimes(1));

    // A fresh arrow every render — exactly what LivePlayer passes.
    rerender(<Player src="http://x/master.m3u8" onError={() => {}} />);
    rerender(<Player src="http://x/master.m3u8" onError={() => {}} />);

    expect(destroy).not.toHaveBeenCalled();
    expect(constructed).toHaveBeenCalledTimes(1);
    expect(loadSource).toHaveBeenCalledTimes(1);

    // A genuinely new source still reloads — the effect is keyed on `src`, not frozen.
    rerender(<Player src="http://x/other.m3u8" onError={() => {}} />);
    await vi.waitFor(() => expect(constructed).toHaveBeenCalledTimes(2));
    expect(destroy).toHaveBeenCalledTimes(1);

    vi.doUnmock("hls.js");
  });
});

/**
 * hls.js wins over native HLS whenever MSE is available. Chrome 155 answers "maybe" to
 * `canPlayType("application/vnd.apple.mpegurl")`; preferring native there sent Frigate VOD down a
 * stack with no request hook, the nested `index-*.m3u8` went out without credentials and 401'd,
 * and every recorded scrub on desktop Chrome showed "Couldn't load this recording".
 */
describe("HlsPlayer engine choice", () => {
  const mockHls = (supported: boolean, constructed: () => void) =>
    vi.doMock("hls.js", () => ({
      default: class {
        static isSupported() {
          return supported;
        }
        static Events = { ERROR: "hlsError" };
        constructor() {
          constructed();
        }
        loadSource = vi.fn();
        attachMedia = vi.fn();
        on = vi.fn();
        destroy = vi.fn();
      },
    }));

  it("uses hls.js even when the browser claims native HLS", async () => {
    const spy = vi
      .spyOn(HTMLMediaElement.prototype, "canPlayType")
      .mockImplementation((t: string) => (/mpegurl/i.test(t) ? "maybe" : ""));
    const constructed = vi.fn();
    mockHls(true, constructed);
    const { HlsPlayer: Player } = await import("../HlsPlayer");
    const { container } = render(<Player src="http://x/vod/master.m3u8?authSig=abc" authToken="t" />);
    await vi.waitFor(() => expect(constructed).toHaveBeenCalledTimes(1));
    // The native path would have put the URL straight on the element.
    expect(container.querySelector("video")!.getAttribute("src")).toBeNull();
    vi.doUnmock("hls.js");
    spy.mockRestore();
  });

  it("falls back to native HLS when hls.js is unsupported (no MSE)", async () => {
    const spy = vi
      .spyOn(HTMLMediaElement.prototype, "canPlayType")
      .mockImplementation((t: string) => (/mpegurl/i.test(t) ? "maybe" : ""));
    const constructed = vi.fn();
    const onError = vi.fn();
    mockHls(false, constructed);
    const { HlsPlayer: Player } = await import("../HlsPlayer");
    const { container } = render(<Player src="http://x/live/master.m3u8" onError={onError} />);
    const video = container.querySelector("video")!;
    await vi.waitFor(() => expect(video.getAttribute("src")).toBe("http://x/live/master.m3u8"));
    expect(constructed).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
    vi.doUnmock("hls.js");
    spy.mockRestore();
  });

  it("reports an error when neither hls.js nor native HLS can play", async () => {
    const spy = vi.spyOn(HTMLMediaElement.prototype, "canPlayType").mockReturnValue("");
    const onError = vi.fn();
    mockHls(false, vi.fn());
    const { HlsPlayer: Player } = await import("../HlsPlayer");
    render(<Player src="http://x/live/master.m3u8" onError={onError} />);
    await vi.waitFor(() => expect(onError).toHaveBeenCalledTimes(1));
    vi.doUnmock("hls.js");
    spy.mockRestore();
  });
});
