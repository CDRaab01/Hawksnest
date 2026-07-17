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
}) {
  const ref = useRef<HTMLVideoElement>(null);
  // Ref'd so a new callback identity per render can't re-init the source effect.
  const onDurationRef = useRef(onDuration);
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
            onError?.();
            return;
          }
          const hls = new Hls();
          hls.loadSource(src);
          hls.attachMedia(video);
          hls.on(Hls.Events.ERROR, (_e: unknown, data: { fatal?: boolean }) => {
            if (data?.fatal) onError?.();
          });
          destroy = () => hls.destroy();
        })
        .catch(() => {
          // hls.js not installed yet (pre-Frigate) — fall back gracefully.
          if (!cancelled) onError?.();
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
  }, [src, onError]);

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
