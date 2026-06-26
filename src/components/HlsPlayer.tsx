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
  onError,
  className,
}: {
  src: string;
  poster?: string;
  loop?: boolean;
  muted?: boolean;
  paused?: boolean;
  onError?: () => void;
  className?: string;
}) {
  const ref = useRef<HTMLVideoElement>(null);

  // Drive play/pause from the transport bar without remounting the element.
  useEffect(() => {
    const video = ref.current;
    if (!video) return;
    if (paused) safePause(video);
    else safePlay(video);
  }, [paused]);

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
      video.removeAttribute("src");
      try {
        video.load?.();
      } catch {
        /* jsdom: not implemented */
      }
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
