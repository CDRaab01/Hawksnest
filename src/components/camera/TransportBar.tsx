import { SkipBack, SkipForward, Play, Pause } from "lucide-react";

/**
 * Playback transport under the timeline — prev/next moment, play/pause, and a
 * snap-to-"Live" pill (Ring's ⏮ ⏸ ⏭ + Live), as big round buttons. Stepping and
 * live-state are driven by the parent `CameraPlayer`, which owns the playhead.
 */
export function TransportBar({
  isLive,
  isPaused,
  canPrev,
  canNext,
  onPrev,
  onNext,
  onTogglePlay,
  onLive,
}: {
  isLive: boolean;
  isPaused: boolean;
  canPrev: boolean;
  canNext: boolean;
  onPrev: () => void;
  onNext: () => void;
  onTogglePlay: () => void;
  onLive: () => void;
}) {
  const round =
    "flex items-center justify-center rounded-full bg-panel-high text-ink transition-colors duration-fast hover:bg-panel disabled:opacity-40 disabled:hover:bg-panel-high";

  return (
    <div className="flex items-center justify-center gap-lg">
      <button
        type="button"
        onClick={onPrev}
        disabled={!canPrev}
        aria-label="Previous moment"
        className={`${round} h-[52px] w-[52px]`}
      >
        <SkipBack size={22} />
      </button>

      <button
        type="button"
        onClick={onTogglePlay}
        disabled={isLive}
        aria-label={isPaused ? "Play" : "Pause"}
        className={`${round} h-16 w-16`}
      >
        {isPaused ? <Play size={28} /> : <Pause size={28} />}
      </button>

      <button
        type="button"
        onClick={onNext}
        disabled={!canNext}
        aria-label="Next moment"
        className={`${round} h-[52px] w-[52px]`}
      >
        <SkipForward size={22} />
      </button>

      <button
        type="button"
        onClick={onLive}
        aria-pressed={isLive}
        aria-label="Go live"
        className={[
          "ml-md rounded-full px-lg py-sm font-body text-body transition-colors duration-fast",
          isLive
            ? "bg-recovery text-black"
            : "bg-panel-high text-ink-dim hover:text-ink",
        ].join(" ")}
      >
        Live
      </button>
    </div>
  );
}
