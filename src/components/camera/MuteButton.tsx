import { Volume2, VolumeX } from "lucide-react";

/**
 * Speaker toggle for camera audio. Cameras with microphones (Ring, the Reolinks)
 * carry an audio track on both live WebRTC and recorded VOD, but every player
 * mounted muted with no way back — this is that way back.
 *
 * Unmuting is a user gesture on this very button, which is exactly what browser
 * autoplay policy wants: the video element itself keeps `autoPlay` and starts
 * muted, and only a tap here flips the track audible.
 */
export function MuteButton({
  muted,
  onToggle,
}: {
  muted: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={muted ? "Unmute camera audio" : "Mute camera audio"}
      aria-pressed={!muted}
      className={[
        "flex items-center gap-xs rounded-sm px-sm py-xs caption-label transition-colors duration-fast",
        muted ? "bg-panel text-ink-dim hover:text-ink" : "bg-panel-high text-ink",
      ].join(" ")}
    >
      {muted ? <VolumeX size={14} /> : <Volume2 size={14} />}
      {muted ? "Muted" : "Sound"}
    </button>
  );
}
