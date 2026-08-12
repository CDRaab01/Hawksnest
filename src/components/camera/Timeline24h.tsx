import { useEffect, useMemo, useRef, useState } from "react";
import type { CameraEvent } from "../../lib/cameraEvents";
import type { FootageSpan } from "../../lib/ringFootage";
import { clockTime } from "../../lib/relativeTime";
import {
  NUDGE_COARSE_MS,
  NUDGE_FINE_MS,
  setEdge,
  type ClipEdge,
  type ClipSelection,
} from "../../lib/clipExport";
import {
  DEFAULT_SPAN_MS,
  MINUTE_MS,
  type TimeWindow,
  type Viewport,
  pan,
  tickIntervalMs,
  timeToX,
  ticks,
  viewportForSpan,
  visibleRange,
  visibleSpanMs,
  xToTime,
  zoom,
} from "../../lib/timelineViewport";

/** Movement under this many px counts as a tap (seek), not a pan. */
const TAP_SLOP_PX = 6;

/**
 * How far one key press moves something, or null for a key this control ignores.
 *
 * Shared by the track and the clip handles because the *grammar* is the same — arrows step,
 * Page/Shift steps further, Home/End go to the ends — while the distances differ. `sign` is
 * separated from the magnitudes so each caller supplies its own pair without restating the
 * key mapping, which is the part that would drift.
 */
function keyStep(
  e: React.KeyboardEvent,
): { sign: -1 | 1; coarse: boolean } | "start" | "end" | null {
  switch (e.key) {
    case "ArrowLeft":
    case "ArrowDown":
      return { sign: -1, coarse: e.shiftKey };
    case "ArrowRight":
    case "ArrowUp":
      return { sign: 1, coarse: e.shiftKey };
    case "PageDown":
      return { sign: -1, coarse: true };
    case "PageUp":
      return { sign: 1, coarse: true };
    case "Home":
      return "start";
    case "End":
      return "end";
    default:
      return null;
  }
}

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
 * 24 h). The recordings render as solid effort-blue blocks (every block is a
 * playable clip). The playhead is Ring's triangle marker; everything right of
 * *now* is the dimmed "Live" region. While dragging, `onScrub` streams the time
 * under the center playhead (rAF-throttled) so the parent can preview footage
 * live; release still commits through `onSeek`/`onLive`. A clean tap seeks to
 * the tapped time; tapping a block jumps to it. All the mapping/clamp math
 * lives in `lib/timelineViewport`.
 */
export function Timeline24h({
  events,
  footage = [],
  startMs,
  endMs,
  playhead,
  onSeek,
  onScrub,
  onLive,
  selection = null,
  selectionBounds,
  onSelectionChange,
}: {
  events: CameraEvent[];
  /**
   * The 24/7 continuous track, as coalesced spans. Drawn as a low strip UNDER the event blocks:
   * the events are the moments worth looking at, the strip is the answer to "was anything even
   * recorded here?" — which, on a 24/7 camera, is yes across the whole day even where no event
   * fired. Empty for the battery cameras and the doorbell, which record events only.
   */
  footage?: FootageSpan[];
  startMs: number;
  endMs: number;
  playhead: number | "live";
  onSeek: (ms: number) => void;
  /** Streams the time under the playhead during an active drag (throttled to
   *  one call per animation frame). Release always follows with onSeek/onLive. */
  onScrub?: (ms: number) => void;
  /** Snap back to live — fired when a tap/drag lands in the "Live" region right of now. */
  onLive?: () => void;
  /**
   * The clip-export range being marked, or null when not in clip mode. Drawn as a band with a
   * draggable handle at each end. Optional so every existing call site is unaffected.
   */
  selection?: ClipSelection | null;
  /** The exportable range a dragged handle is clamped into (`clipExport.exportBounds`). */
  selectionBounds?: TimeWindow;
  onSelectionChange?: (sel: ClipSelection) => void;
}) {
  const trackRef = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  const [vp, setVp] = useState<Viewport | null>(null);
  const drag = useRef<{ startX: number; startVp: Viewport; moved: boolean } | null>(null);
  /** The selection handle currently under the finger. Mutually exclusive with `drag`. */
  const handleDrag = useRef<ClipEdge | null>(null);
  // Live pointer x-positions by id, and the previous two-finger separation. Only the x axis
  // matters: the strip is one-dimensional, so a vertical pinch should not zoom it.
  const pointers = useRef(new Map<number, number>());
  const pinchDist = useRef<number | null>(null);
  // rAF-throttled scrub emission: at most one onScrub per frame, cancelled on release/unmount.
  const scrubRaf = useRef<number | null>(null);
  const pendingScrubMs = useRef(0);
  const onScrubRef = useRef(onScrub);
  onScrubRef.current = onScrub;
  useEffect(
    () => () => {
      if (scrubRaf.current !== null) cancelAnimationFrame(scrubRaf.current);
    },
    [],
  );
  function scheduleScrub(ms: number) {
    pendingScrubMs.current = ms;
    if (scrubRaf.current !== null) return;
    scrubRaf.current = requestAnimationFrame(() => {
      scrubRaf.current = null;
      onScrubRef.current?.(pendingScrubMs.current);
    });
  }

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
    // `handleDrag` suppresses this as well as `drag`: without it, moving a selection handle
    // re-centres the viewport on the playhead mid-gesture, so the strip slides out from under the
    // finger and the handle runs away from it.
    if (width <= 0 || drag.current || handleDrag.current) return;
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
    // A selection handle claims the gesture outright — no pan, no tap-to-seek on release. Same
    // escape-hatch mechanism the chips use, so there is only one way this component yields control.
    const grabbed = (e.target as HTMLElement).closest("[data-clip-handle]");
    if (grabbed && selection && onSelectionChange) {
      e.currentTarget.setPointerCapture?.(e.pointerId);
      handleDrag.current = grabbed.getAttribute("data-clip-handle") as ClipEdge;
      return;
    }
    e.currentTarget.setPointerCapture?.(e.pointerId);
    pointers.current.set(e.pointerId, e.clientX);
    // A second finger turns the gesture into a pinch — abandon the pan so the strip doesn't
    // lurch sideways as the fingers spread, and so releasing doesn't commit a bogus seek.
    if (pointers.current.size >= 2) {
      drag.current = null;
      pinchDist.current = null;
      return;
    }
    drag.current = { startX: e.clientX, startVp: vp, moved: false };
  }

  function onPointerMove(e: React.PointerEvent) {
    // Dragging a selection handle: map the finger straight to a time and let the pure module do
    // the clamping. Returns before any pan/pinch/scrub handling — this gesture is not a scrub.
    const edge = handleDrag.current;
    if (edge) {
      if (!vp || !selection || !onSelectionChange || !selectionBounds) return;
      const rect = trackRef.current?.getBoundingClientRect();
      if (!rect) return;
      onSelectionChange(
        setEdge(selection, edge, xToTime(e.clientX - rect.left, vp, width), selectionBounds),
      );
      return;
    }

    if (pointers.current.has(e.pointerId)) pointers.current.set(e.pointerId, e.clientX);

    // Pinch to zoom — the phone's only way to zoom, since there is no wheel there. Android's
    // Timeline24h has had this since it shipped; web claimed it in a comment and never had it.
    if (pointers.current.size >= 2) {
      const [a, b] = [...pointers.current.values()];
      const dist = Math.abs(a - b);
      const prev = pinchDist.current;
      pinchDist.current = dist;
      if (prev && prev > 0 && dist > 0) {
        setVp((cur) =>
          cur ? zoom(cur, dist / prev, width, paddedWindow(startMs, endMs, cur, width)) : cur,
        );
      }
      return;
    }

    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.startX;
    if (Math.abs(dx) > TAP_SLOP_PX) d.moved = true;
    const next = pan(d.startVp, dx, width, paddedWindow(startMs, endMs, d.startVp, width));
    setVp(next);
    // Live scrub: stream the center time while panning (clamped out of the Live region).
    if (d.moved) scheduleScrub(Math.min(next.centerMs, endMs));
  }

  /** Commit a scrub/tap time: at/past *now* means the Live region — snap back to live. */
  function commit(ms: number) {
    if (ms >= endMs && onLive) onLive();
    else onSeek(Math.min(ms, endMs));
  }

  function onPointerUp(e: React.PointerEvent) {
    // Finishing a handle drag must NOT fall through to the commit below — that would seek the
    // player to wherever the handle was released, which is not what the user asked for.
    if (handleDrag.current) {
      handleDrag.current = null;
      e.currentTarget.releasePointerCapture?.(e.pointerId);
      return;
    }

    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) pinchDist.current = null;
    const d = drag.current;
    // Lifting the first of two pinching fingers must not commit a seek — there was no pan.
    if (!d) return;
    drag.current = null;
    e.currentTarget.releasePointerCapture?.(e.pointerId);
    // The release commits below — a trailing frame-throttled scrub would be stale.
    if (scrubRaf.current !== null) {
      cancelAnimationFrame(scrubRaf.current);
      scrubRaf.current = null;
    }
    if (d.moved) {
      // Commit the pan: the time now under the center playhead.
      if (vp) commit(vp.centerMs);
    } else if (vp) {
      // Clean tap → seek to the tapped time.
      const rect = trackRef.current?.getBoundingClientRect();
      if (rect) commit(xToTime(e.clientX - rect.left, vp, width));
    }
  }

  /**
   * Keyboard scrubbing on the track.
   *
   * This control has advertised `role="slider"` with `aria-valuemin/max/now` since it shipped and
   * had no key handling at all, so Tab landed on something screen readers announce as operable and
   * nothing responded. The pointer gestures (drag to pan, pinch/wheel to zoom) have no keyboard
   * equivalent and don't need one — what a slider promises is *move the value*, which is the
   * playhead.
   *
   * The step is the on-screen tick interval rather than a constant, so one press moves by
   * whatever the strip is currently labelled in: about a minute zoomed in, hours zoomed out. A
   * fixed step would be unusably slow at one end and unusably coarse at the other.
   */
  function onTrackKeyDown(e: React.KeyboardEvent) {
    const step = keyStep(e);
    if (!step) return;
    e.preventDefault();
    if (step === "start") return onSeek(startMs);
    // End means "now", and at/past now the commit grammar is already "snap back to live".
    if (step === "end") return commit(endMs);
    const fine = vp && width > 0 ? tickIntervalMs(visibleSpanMs(vp, width)) : MINUTE_MS;
    const delta = (step.coarse ? fine * 4 : fine) * step.sign;
    commit(Math.min(endMs, Math.max(startMs, scrubTime + delta)));
  }

  /** Keyboard adjust for one selection handle — same grammar, clip-sized steps. */
  function onHandleKeyDown(e: React.KeyboardEvent, edge: ClipEdge) {
    if (!selection || !onSelectionChange || !selectionBounds) return;
    const step = keyStep(e);
    if (!step) return;
    e.preventDefault();
    // The handles sit INSIDE the track, so without this the same press would also scrub the
    // playhead — moving the thing the user is measuring against while they measure.
    e.stopPropagation();
    const at = edge === "start" ? selection.startMs : selection.endMs;
    if (step === "start") return onSelectionChange(setEdge(selection, edge, selectionBounds.startMs, selectionBounds));
    if (step === "end") return onSelectionChange(setEdge(selection, edge, selectionBounds.endMs, selectionBounds));
    // The same ±1s / ±15s the ClipExportBar's buttons use — one instrument, two ways to reach it.
    const delta = (step.coarse ? NUDGE_COARSE_MS : NUDGE_FINE_MS) * step.sign;
    onSelectionChange(setEdge(selection, edge, at + delta, selectionBounds));
  }

  const scrubX = vp ? timeToX(scrubTime, vp, width) : width / 2;
  const nowX = vp ? timeToX(endMs, vp, width) : width;
  const tickTimes = vp ? ticks(vp, width) : [];

  // Events overlapping the visible range, padded by one screen either side. An event with a null
  // end is drawn 30s wide (see below), so use the same assumption when deciding visibility —
  // otherwise a long-running event scrolled past its start would vanish mid-drag.
  const visibleEvents = useMemo(() => {
    if (!vp || width <= 0) return events;
    const { startMs: from, endMs: to } = visibleRange(vp, width);
    const pad = (to - from) || 0;
    const lo = from - pad;
    const hi = to + pad;
    return events.filter((ev) => (ev.endMs ?? ev.startMs + 30_000) >= lo && ev.startMs <= hi);
  }, [events, vp, width]);

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
        onKeyDown={onTrackKeyDown}
        className="relative h-16 w-full cursor-ew-resize touch-none select-none overflow-hidden rounded-md bg-panel-high focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-effort"
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

        {/* 24/7 continuous track — a low strip along the bottom, below the event blocks (which
            keep their `bottom-2.5` inset, so the two never overlap). Translucent effort rather
            than a second hue: it is the same material as the blocks, at lower emphasis. A span
            that exists but can't be decoded (Ring end-to-end encryption) is drawn neutral, so
            "recorded but unplayable" never masquerades as playable footage. */}
        {vp &&
          footage.map((span) => {
            const left = timeToX(span.startMs, vp, width);
            const w = Math.max(1, timeToX(span.endMs, vp, width) - left);
            return (
              <div
                key={`${span.startMs}-${span.playable}`}
                aria-hidden
                style={{ left: `${left}px`, width: `${w}px` }}
                className={[
                  // `opacity-*`, not a `/45` color modifier: these tokens are `var()`-valued, and
                  // Tailwind 3 can't apply an alpha to those — it silently drops it and the lane
                  // would render at FULL effort, indistinguishable from the event blocks above it.
                  "pointer-events-none absolute bottom-1 h-1.5 rounded-sm",
                  span.playable ? "bg-effort opacity-40" : "bg-ink-faint opacity-50",
                ].join(" ")}
              />
            );
          })}

        {/* Clip-export selection. Drawn UNDER the event chips so tapping a chip still seeks, and
            over the footage lane so the marked range reads against it. The band is
            `pointer-events-none`; only the two handles are grabbable.

            `opacity-*` rather than a `bg-recovery/25` alpha modifier: the PULSE tokens are
            `var()`-valued and Tailwind 3 silently DROPS an alpha modifier on those, which would
            paint the band at full opacity and hide the timeline underneath it. Same trap the
            footage lane documents above. */}
        {vp && selection && (
          <>
            {/* Dim what is not selected, the way the Live region dims the future. */}
            <div
              aria-hidden
              style={{ left: 0, width: `${Math.max(0, timeToX(selection.startMs, vp, width))}px` }}
              className="pointer-events-none absolute top-0 h-full bg-black/40"
            />
            <div
              aria-hidden
              style={{
                left: `${timeToX(selection.endMs, vp, width)}px`,
                width: `${Math.max(0, width - timeToX(selection.endMs, vp, width))}px`,
              }}
              className="pointer-events-none absolute top-0 h-full bg-black/40"
            />
            <div
              aria-hidden
              style={{
                left: `${timeToX(selection.startMs, vp, width)}px`,
                width: `${Math.max(
                  2,
                  timeToX(selection.endMs, vp, width) - timeToX(selection.startMs, vp, width),
                )}px`,
              }}
              className="pointer-events-none absolute top-0 h-full bg-recovery opacity-25"
            />
            {(["start", "end"] as const).map((edge) => {
              const at = edge === "start" ? selection.startMs : selection.endMs;
              return (
                <div
                  key={edge}
                  data-clip-handle={edge}
                  role="slider"
                  aria-label={edge === "start" ? "Clip start" : "Clip end"}
                  aria-valuemin={startMs}
                  aria-valuemax={endMs}
                  aria-valuenow={at}
                  aria-valuetext={clockTime(at)}
                  tabIndex={0}
                  onKeyDown={(e) => onHandleKeyDown(e, edge)}
                  style={{ left: `${timeToX(at, vp, width)}px` }}
                  // w-10 is the invisible touch target: 40px, i.e. HANDLE_HIT_SLOP_PX (20) either
                  // side of the hairline, which is what that constant promises both platforms.
                  // It was w-6 — 12px a side — so a handle Android considered grabbed was a miss
                  // on the web, and the shared constant documented a contract nothing honoured.
                  // The visible bar is the inner div.
                  className="absolute top-0 flex h-full w-10 -translate-x-1/2 cursor-ew-resize touch-none items-center justify-center focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-recovery"
                >
                  <div className="h-full w-0.5 bg-recovery" />
                </div>
              );
            })}
          </>
        )}

        {/* Recording blocks — solid effort-blue, tall like Ring's; every block is a
            playable clip. Only the ones on (or just off) screen are rendered: a Frigate camera's
            window spans its whole retention and the fetch is capped at 500 events, so mapping the
            lot into absolutely-positioned nodes to let `overflow-hidden` clip them meant a few
            hundred DOM elements the user can never see — re-laid-out on every pan frame. One
            screen of padding either side keeps a block from popping in at the edge of a drag. */}
        {vp &&
          visibleEvents.map((ev) => {
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
                className="absolute bottom-2.5 top-2.5 rounded-sm bg-effort"
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
        <span className="caption-label text-ink-faint">
          {events.length} moments
          {/* Say when the gaps between those moments are still watchable — otherwise a day with
              few events reads as a day with little footage. */}
          {footage.length > 0 && " · 24/7"}
        </span>
      </div>
    </div>
  );
}
