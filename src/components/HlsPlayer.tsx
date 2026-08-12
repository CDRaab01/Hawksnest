import { useEffect, useRef } from "react";

/** play()/pause() throw "not implemented" in jsdom and can reject on autoplay
 *  policy in browsers — swallow both so the player never crashes its host. */
function safePlay(video: HTMLVideoElement): void {
  try {
    void video.play?.()?.catch?.(() => {});
  } catch {
    /* jsdom: not implemented */
  }
}
function safePause(video: HTMLVideoElement): void {
  try {
    video.pause?.();
  } catch {
    /* jsdom: not implemented */
  }
}

/**
 * A dependency-light `<video>` player for camera footage — the live HLS feed,
 * recorded VOD playlists, and (in demo) the bundled `camera-loop.mp4`.
 *
 * Plain `.mp4` and native-HLS browsers (Safari/iOS) play directly off
 * `video.src`. For `.m3u8` where the browser can't play HLS natively (Chrome/
 * Firefox), we lazy-load `hls.js` **only if it's installed** — it isn't needed
 * for demo (mp4) and gets added when Frigate/go2rtc land. If it's absent we call
 * `onError` so the parent's transport ladder falls back to MJPEG/snapshot.
 */
export function HlsPlayer({
  src,
  poster,
  loop = false,
  muted = true,
  paused = false,
  seekSeconds,
  onDuration,
  onError,
  className,
  authToken,
}: {
  src: string;
  poster?: string;
  loop?: boolean;
  muted?: boolean;
  paused?: boolean;
  /** Scrub position (seconds into the media). Seeks the existing element — no reload/re-init. */
  seekSeconds?: number;
  /** Reports the media duration (seconds) once known — and again when it grows
   *  (an HLS event playlist's duration extends as segments append). */
  onDuration?: (seconds: number) => void;
  onError?: () => void;
  className?: string;
  /**
   * HA token sent as `Authorization: Bearer` on every derived HLS request.
   *
   * Needed for Frigate VOD's nested `index-*.m3u8`, which a URL signature cannot cover (HA signs
   * an exact path, not a prefix). Omit for sources that need no auth — go2rtc live and
   * ring-timeline's pre-signed URLs.
   */
  authToken?: string | null;
}) {
  const ref = useRef<HTMLVideoElement>(null);
  // Ref'd so a new callback identity per render can't re-init the source effect.
  const onDurationRef = useRef(onDuration);
  // `onError` needs the SAME treatment, and for a sharper reason than `onDuration` does: callers
  // pass an inline arrow (`onError={() => stepDownFrom("video")}` in LivePlayer), so its identity
  // changes on every render of the parent. With it in the source effect's dep array, every parent
  // re-render ran `hls.destroy()` + `removeAttribute("src")` + `new Hls()` — restarting live video
  // mid-stream. Reffing it is what lets the deps be `[src, authToken]`: the only two things that
  // genuinely describe *which media is loaded*.
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  // React applies the `muted` attribute only at mount; live toggles must go
  // through the DOM property (same workaround as the WebRTC players).
  useEffect(() => {
    if (ref.current) ref.current.muted = muted;
  }, [muted]);
  onDurationRef.current = onDuration;

  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    const report = () => {
      const d = video.duration;
      if (Number.isFinite(d) && d > 0) onDurationRef.current?.(d);
    };
    video.addEventListener("loadedmetadata", report);
    video.addEventListener("durationchange", report);
    return () => {
      video.removeEventListener("loadedmetadata", report);
      video.removeEventListener("durationchange", report);
    };
  }, [src]);

  // Drive play/pause from the transport bar without remounting the element.
  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    if (paused) safePause(video);
    else safePlay(video);
  }, [paused]);

  // Scrub by seeking the SAME element instead of swapping `src` per move. A single
  // window-spanning VOD + seekTo is smooth; rebuilding the source each scrub re-buffered (stutter)
  // and could crash the player. Apply once the media can seek (metadata ready), else on load.
  useEffect(() => {
    const video = ref.current;
    if (!video || seekSeconds == null || !Number.isFinite(seekSeconds)) return;
    const target = Math.max(0, seekSeconds);
    const apply = () => {
      try {
        video.currentTime = target;
      } catch {
        /* not seekable yet / jsdom */
      }
    };
    if (video.readyState >= 1) apply();
    else {
      video.addEventListener("loadedmetadata", apply, { once: true });
      return () => video.removeEventListener("loadedmetadata", apply);
    }
  }, [seekSeconds, src]);

  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    const isHls = src.includes(".m3u8");
    const nativeHls =
      video.canPlayType("application/vnd.apple.mpegurl") !== "";

    let destroy: (() => void) | undefined;
    let cancelled = false;

    if (isHls && !nativeHls) {
      // Non-native HLS: try hls.js if present; otherwise let the parent fall back.
      void import(/* @vite-ignore */ "hls.js")
        .then((mod) => {
          if (cancelled) return;
          const Hls = mod.default;
          if (!Hls?.isSupported?.()) {
            onErrorRef.current?.();
            return;
          }
          // Frigate VOD needs BOTH credentials on derived requests, for different reasons:
          //
          //  * `authSig` on SEGMENTS — the integration validates it unconditionally, and a
          //    Bearer token does NOT satisfy that check.
          //  * a Bearer token on the NESTED `index-*.m3u8` — HA's signed-path auth validates the
          //    EXACT path signed, so the signature minted for `master.m3u8` does not authorise
          //    its sibling. Measured: index with authSig alone 401s, with a Bearer it is 200.
          //
          // Both are needed because hls.js resolves derived URLs RELATIVE to the manifest, which
          // drops the query string. Get either half wrong and you get a black video with no
          // error: the master loads and everything after it 401s.
          const authSig = new URL(src, globalThis.location.origin).searchParams.get("authSig");
          const hls = new Hls(
            authSig || authToken
              ? {
                  xhrSetup: (xhr: XMLHttpRequest, url: string) => {
                    if (authSig && !url.includes("authSig=")) {
                      const u = new URL(url, globalThis.location.origin);
                      u.searchParams.set("authSig", authSig);
                      xhr.open("GET", u.toString(), true);
                    }
                    // Set AFTER any re-open: xhr.open() resets request headers.
                    if (authToken) xhr.setRequestHeader("Authorization", `Bearer ${authToken}`);
                  },
                }
              : undefined,
          );
          hls.loadSource(src);
          hls.attachMedia(video);
          hls.on(Hls.Events.ERROR, (_e: unknown, data: { fatal?: boolean }) => {
            if (data?.fatal) onErrorRef.current?.();
          });
          destroy = () => hls.destroy();
        })
        .catch(() => {
          // hls.js not installed yet (pre-Frigate) — fall back gracefully.
          if (!cancelled) onErrorRef.current?.();
        });
    } else {
      video.src = src;
    }

    safePlay(video);

    return () => {
      cancelled = true;
      destroy?.();
      // Detach the source so the element stops fetching; no load() reset needed
      // (and load() is unimplemented in jsdom, so calling it just adds noise).
      video.removeAttribute("src");
    };
  }, [src, authToken]);

  return (
    <video
      ref={ref}
      poster={poster}
      loop={loop}
      muted={muted}
      autoPlay
      playsInline
      controls={false}
      onError={() => onError?.()}
      aria-label="Camera footage"
      className={className ?? "aspect-video w-full rounded-lg bg-black object-contain"}
    />
  );
}
