import { useEffect, useRef, useState } from "react";
import type { CameraEvent } from "../../lib/cameraEvents";
import { clockTime } from "../../lib/relativeTime";
import {
  HOUR_MS,
  type TimeWindow,
  type Viewport,
  pan,
  timeToX,
  ticks,
  viewportForSpan,
  visibleSpanMs,
  xToTime,
  zoom,
} from "../../lib/timelineViewport";

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

/** Opening zoom: ~3h visible, clamped into the [10min, 24h] range. */
const DEFAULT_SPAN_MS = 3 * HOUR_MS;
/** Movement under this many px counts as a tap (seek), not a pan. */
const TAP_SLOP_PX = 6;

/**
 * Ring-style scrubbable timeline: a center-anchored, zoomable + pannable strip.
 * Drag left/right to move through time; pinch / mouse-wheel to zoom (≈10 min →
 * 24 h). The playhead marks the current time — pinned at center while scrubbing,
 * at the right edge while live. A clean tap seeks to the tapped time; tapping an
 * event chip jumps to it. All the mapping/clamp math lives in `lib/timelineViewport`.
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
  const [width, setWidth] = useState(0);
  const [vp, setVp] = useState<Viewport | null>(null);
  const drag = useRef<{ startX: number; startVp: Viewport; moved: boolean } | null>(null);

  const win: TimeWindow = { startMs, endMs };
  const scrubTime = playhead === "live" ? endMs : playhead;

  // Measure the track width (and keep it current on resize).
  useEffect(() => {
    const el = trackRef.current;
    if (!el) return;
    const apply = () => setWidth(el.getBoundingClientRect().width);
    apply();
    const ro = new ResizeObserver(apply);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // Re-center on external seeks (Live / prev / next / event tap) and on width
  // changes, preserving the current zoom. Suppressed while actively dragging.
  useEffect(() => {
    if (width <= 0 || drag.current) return;
    setVp((cur) =>
      viewportForSpan(
        scrubTime,
        cur ? visibleSpanMs(cur, width) : DEFAULT_SPAN_MS,
        width,
        { startMs, endMs },
      ),
    );
    // scrubTime is derived from playhead/endMs — those cover it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playhead, width, startMs, endMs]);

  // Mouse-wheel zoom about the center. Native + non-passive so we can preventDefault
  // (stop the page scrolling) — React's onWheel can't guarantee that.
  useEffect(() => {
    const el = trackRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (width <= 0) return;
      e.preventDefault();
      const factor = Math.exp(-e.deltaY * 0.0015);
      setVp((cur) => (cur ? zoom(cur, factor, width, { startMs, endMs }) : cur));
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [width, startMs, endMs]);

  function onPointerDown(e: React.PointerEvent) {
    if (!vp || width <= 0) return;
    // Let an event chip handle its own tap.
    if ((e.target as HTMLElement).closest("[data-chip]")) return;
    e.currentTarget.setPointerCapture?.(e.pointerId);
    drag.current = { startX: e.clientX, startVp: vp, moved: false };
  }

  function onPointerMove(e: React.PointerEvent) {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.startX;
    if (Math.abs(dx) > TAP_SLOP_PX) d.moved = true;
    setVp(pan(d.startVp, dx, width, win));
  }

  function onPointerUp(e: React.PointerEvent) {
    const d = drag.current;
    if (!d) return;
    drag.current = null;
    e.currentTarget.releasePointerCapture?.(e.pointerId);
    if (d.moved) {
      // Commit the pan: the time now under the center playhead.
      if (vp) onSeek(vp.centerMs);
    } else if (vp) {
      // Clean tap → seek to the tapped time.
      const rect = trackRef.current?.getBoundingClientRect();
      if (rect) onSeek(xToTime(e.clientX - rect.left, vp, width));
    }
  }

  const scrubX = vp ? timeToX(scrubTime, vp, width) : width / 2;
  const tickTimes = vp ? ticks(vp, width) : [];
  const spanLabel = vp ? formatSpan(visibleSpanMs(vp, width)) : "";

  return (
    <div className="space-y-xs">
      <div className="flex items-center justify-between">
        <span className="caption-label text-ink-faint">
          {playhead === "live" ? "Live" : clockTime(scrubTime)}
        </span>
        <span className="caption-label text-ink-faint">{events.length} events</span>
      </div>

      <div
        ref={trackRef}
        role="slider"
        aria-label="Recording timeline"
        aria-valuemin={startMs}
        aria-valuemax={endMs}
        aria-valuenow={scrubTime}
        tabIndex={0}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        className="relative h-14 w-full cursor-ew-resize touch-none select-none overflow-hidden rounded-md bg-panel-high"
      >
        {/* Hour/minute ticks */}
        {vp &&
          tickTimes.map((t) => {
            const x = timeToX(t, vp, width);
            return (
              <div
                key={t}
                style={{ left: `${x}px` }}
                className="pointer-events-none absolute top-0 h-full w-px -translate-x-1/2 bg-white/10"
              >
                <span className="absolute left-1 top-0.5 font-mono text-[10px] text-ink-faint">
                  {clockTime(t)}
                </span>
              </div>
            );
          })}

        {/* Event chips (all rendered; off-screen ones are clipped by overflow-hidden) */}
        {vp &&
          events.map((ev) => {
            const left = timeToX(ev.startMs, vp, width);
            const end = ev.endMs ?? ev.startMs + 30_000;
            const w = Math.max(3, timeToX(end, vp, width) - left);
            return (
              <button
                key={ev.id}
                type="button"
                data-chip
                title={`${ev.label} · ${clockTime(ev.startMs)}`}
                aria-label={`${ev.label} at ${clockTime(ev.startMs)}`}
                onClick={(e) => {
                  e.stopPropagation();
                  onSeek(ev.startMs);
                }}
                style={{ left: `${left}px`, width: `${w}px` }}
                className={`absolute bottom-1.5 top-6 rounded-sm ${colorFor(ev.label)} opacity-80 transition-opacity hover:opacity-100`}
              />
            );
          })}

        {/* Playhead — at center while scrubbing, at the right edge while live. */}
        <div
          style={{ left: `${scrubX}px` }}
          className="pointer-events-none absolute top-0 h-full w-0.5 -translate-x-1/2 bg-white"
        >
          <div className="absolute -top-0.5 left-1/2 h-2 w-2 -translate-x-1/2 rounded-full bg-white" />
        </div>
      </div>

      <div className="flex items-center justify-between">
        <span className="font-mono text-caption text-ink-faint">Drag to scrub · pinch to zoom</span>
        <span className="font-mono text-caption text-ink-faint">{spanLabel} view</span>
      </div>
    </div>
  );
}

/** Compact span label for the zoom indicator ("45m", "3h", "1h 30m"). */
function formatSpan(ms: number): string {
  const totalMin = Math.max(1, Math.round(ms / 60_000));
  if (totalMin < 60) return `${totalMin}m`;
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return m === 0 ? `${h}h` : `${h}h ${m}m`;
}
