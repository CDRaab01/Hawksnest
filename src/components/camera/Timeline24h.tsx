import { useRef } from "react";
import type { CameraEvent } from "../../lib/cameraEvents";
import { clockTime } from "../../lib/relativeTime";

/** Object label → PULSE channel tint (explicit classes so Tailwind keeps them). */
const LABEL_COLOR: Record<string, string> = {
  person: "bg-strength",
  car: "bg-effort",
  truck: "bg-effort",
  dog: "bg-recovery",
  cat: "bg-recovery",
};
const DEFAULT_COLOR = "bg-streak"; // motion / anything else

function colorFor(label: string): string {
  return LABEL_COLOR[label] ?? DEFAULT_COLOR;
}

function pct(ms: number, startMs: number, endMs: number): number {
  const span = endMs - startMs;
  if (span <= 0) return 0;
  return Math.min(100, Math.max(0, ((ms - startMs) / span) * 100));
}

/**
 * The Ring-style scrubbable timeline. Recorded-event markers sit along a fixed
 * `[startMs, endMs]` track (a rolling 24h window); the playhead shows where we
 * are (right edge = live). Clicking the track seeks; clicking a marker jumps to
 * that event's start. Colors follow the detected object's PULSE channel.
 */
export function Timeline24h({
  events,
  startMs,
  endMs,
  playhead,
  onSeek,
}: {
  events: CameraEvent[];
  startMs: number;
  endMs: number;
  playhead: number | "live";
  onSeek: (ms: number) => void;
}) {
  const trackRef = useRef<HTMLDivElement>(null);

  const seekFromClientX = (clientX: number) => {
    const el = trackRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const frac = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    onSeek(startMs + frac * (endMs - startMs));
  };

  const headPct = playhead === "live" ? 100 : pct(playhead, startMs, endMs);

  return (
    <div className="space-y-xs">
      <div className="flex items-center justify-between">
        <span className="caption-label text-ink-faint">Last 24 hours</span>
        <span className="caption-label text-ink-faint">{events.length} events</span>
      </div>

      <div
        ref={trackRef}
        role="slider"
        aria-label="Recording timeline"
        aria-valuemin={startMs}
        aria-valuemax={endMs}
        aria-valuenow={playhead === "live" ? endMs : playhead}
        tabIndex={0}
        onClick={(e) => seekFromClientX(e.clientX)}
        className="relative h-12 w-full cursor-pointer overflow-hidden rounded-md bg-panel-high"
      >
        {events.map((ev) => {
          const left = pct(ev.startMs, startMs, endMs);
          const end = ev.endMs ?? ev.startMs + 30_000;
          const width = Math.max(0.6, pct(end, startMs, endMs) - left);
          return (
            <button
              key={ev.id}
              type="button"
              title={`${ev.label} · ${clockTime(ev.startMs)}`}
              aria-label={`${ev.label} at ${clockTime(ev.startMs)}`}
              onClick={(e) => {
                e.stopPropagation();
                onSeek(ev.startMs);
              }}
              style={{ left: `${left}%`, width: `${width}%` }}
              className={`absolute top-2 h-8 rounded-sm ${colorFor(ev.label)} opacity-80 transition-opacity hover:opacity-100`}
            />
          );
        })}

        {/* Playhead */}
        <div
          style={{ left: `${headPct}%` }}
          className="pointer-events-none absolute top-0 h-full w-0.5 -translate-x-1/2 bg-white"
        >
          <div className="absolute -top-0.5 left-1/2 h-2 w-2 -translate-x-1/2 rounded-full bg-white" />
        </div>
      </div>

      <div className="flex items-center justify-between">
        <span className="font-mono text-caption text-ink-faint">{clockTime(startMs)}</span>
        <span className="font-mono text-caption text-ink-faint">Live</span>
      </div>
    </div>
  );
}
