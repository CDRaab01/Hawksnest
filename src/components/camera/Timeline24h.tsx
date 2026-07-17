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

/** Opening zoom: ~8h visible so the day reads at a glance (Ring-like), clamped into [10min, 24h]. */
const DEFAULT_SPAN_MS = 8 * HOUR_MS;
/** Movement under this many px counts as a tap (seek), not a pan. */
const TAP_SLOP_PX = 6;

/**
 * The clamp window, padded past *now* by half the visible span, so "now" can sit at CENTER with
 * the "Live" region filling the right half — the Ring layout. (Unpadded, the clamp pins now to
 * the right edge and the Live region could never show.) Panning right naturally stops when now
 * reaches center.
 */
function paddedWindow(
  startMs: number,
  endMs: number,
  v: Viewport | null,
  width: number,
): TimeWindow {
  const half = (v && width > 0 ? visibleSpanMs(v, width) : DEFAULT_SPAN_MS) / 2;
  return { startMs, endMs: endMs + half };
}

/** Ring's centered header: "TODAY" for today, otherwise the scrubbed day's date. */
function dayHeader(ms: number): string {
  const day = new Date(ms);
  const today = new Date();
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate();
  if (sameDay(day, today)) return "TODAY";
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (sameDay(day, yesterday)) return "YESTERDAY";
  return day
    .toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric" })
    .toUpperCase();
}

/**
 * Ring-style scrubbable timeline: a center-anchored, zoomable + pannable strip.
 * Drag left/right to move through time; pinch / mouse-wheel to zoom (≈10 min →
 * 24 h). The day's "moments of action" render as solid effort-blue blocks —
 * full-strength when a playable clip is kept, dimmer when history-only. The
 * playhead is Ring's triangle marker; everything right of *now* is the dimmed
 * "Live" region. A clean tap seeks to the tapped time; tapping a block jumps to
 * it. All the mapping/clamp math lives in `lib/timelineViewport`.
 */
export function Timeline24h({
  events,
  startMs,
  endMs,
  playhead,
  onSeek,
  onLive,
}: {
  events: CameraEvent[];
  startMs: number;
  endMs: number;
  playhead: number | "live";
  onSeek: (ms: number) => void;
  /** Snap back to live — fired when a tap/drag lands in the "Live" region right of now. */
  onLive?: () => void;
}) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [vp, setVp] = useState<Viewport | null>(null);
  const drag = useRef<{ startX: number; startVp: Viewport; moved: boolean } | null>(null);

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
        paddedWindow(startMs, endMs, cur, width),
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
      setVp((cur) => (cur ? zoom(cur, factor, width, paddedWindow(startMs, endMs, cur, width)) : cur));
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
    setVp(pan(d.startVp, dx, width, paddedWindow(startMs, endMs, d.startVp, width)));
  }

  /** Commit a scrub/tap time: at/past *now* means the Live region — snap back to live. */
  function commit(ms: number) {
    if (ms >= endMs && onLive) onLive();
    else onSeek(Math.min(ms, endMs));
  }

  function onPointerUp(e: React.PointerEvent) {
    const d = drag.current;
    if (!d) return;
    drag.current = null;
    e.currentTarget.releasePointerCapture?.(e.pointerId);
    if (d.moved) {
      // Commit the pan: the time now under the center playhead.
      if (vp) commit(vp.centerMs);
    } else if (vp) {
      // Clean tap → seek to the tapped time.
      const rect = trackRef.current?.getBoundingClientRect();
      if (rect) commit(xToTime(e.clientX - rect.left, vp, width));
    }
  }

  const scrubX = vp ? timeToX(scrubTime, vp, width) : width / 2;
  const nowX = vp ? timeToX(endMs, vp, width) : width;
  const tickTimes = vp ? ticks(vp, width) : [];

  return (
    <div className="space-y-xs">
      <div className="text-center font-display text-body font-bold tracking-wide text-ink">
        {dayHeader(scrubTime)}
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
        className="relative h-16 w-full cursor-ew-resize touch-none select-none overflow-hidden rounded-md bg-panel-high"
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

        {/* Action blocks — solid effort-blue "moments", tall like Ring's. Full strength when a
            playable clip is kept; dimmer when it's a history-only marker. (All rendered;
            off-screen ones are clipped by overflow-hidden.) */}
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
                title={`${ev.label} · ${clockTime(ev.startMs)}${ev.hasClip ? "" : " · no saved recording"}`}
                aria-label={`${ev.label} at ${clockTime(ev.startMs)}`}
                onClick={(e) => {
                  e.stopPropagation();
                  onSeek(ev.startMs);
                }}
                style={{ left: `${left}px`, width: `${w}px` }}
                className={`absolute bottom-2.5 top-2.5 rounded-sm bg-effort transition-opacity hover:opacity-100 ${
                  ev.hasClip ? "opacity-100" : "opacity-60"
                }`}
              />
            );
          })}

        {/* "Live" region — everything right of now (endMs) is the not-yet-recorded future; dim it
            and label it, so the centered playhead reads as "now" (the Ring layout). */}
        {vp && nowX < width && (
          <div
            style={{ left: `${nowX}px`, width: `${width - nowX}px` }}
            className="pointer-events-none absolute top-0 flex h-full items-center justify-center border-l border-recovery bg-black/35"
          >
            {width - nowX > 44 && (
              <span className="font-body text-caption font-medium text-recovery">Live</span>
            )}
          </div>
        )}

        {/* Playhead — Ring's inward triangles top & bottom on a hairline; at center while
            scrubbing, at the right edge (now) while live. */}
        <div
          style={{ left: `${scrubX}px` }}
          className="pointer-events-none absolute top-0 h-full w-0.5 -translate-x-1/2 bg-white/90"
        >
          <div className="absolute -top-px left-1/2 h-0 w-0 -translate-x-1/2 border-x-[6px] border-t-[7px] border-x-transparent border-t-white" />
          <div className="absolute -bottom-px left-1/2 h-0 w-0 -translate-x-1/2 border-x-[6px] border-b-[7px] border-x-transparent border-b-white" />
        </div>
      </div>

      <div className="flex items-center justify-between">
        <span className="caption-label text-ink-faint">
          {playhead === "live" ? "Live" : clockTime(scrubTime)}
        </span>
        <span className="caption-label text-ink-faint">{events.length} moments</span>
      </div>
    </div>
  );
}
