import { useCallback, useEffect, useRef, useState } from "react";
import {
  NO_ZOOM,
  type ZoomState,
  applyGesture,
  cssTransform,
  isZoomed,
  shouldCaptureDrag,
} from "../../lib/videoZoom";

/**
 * Pinch-to-zoom + drag-to-pan over the camera picture, plus the fullscreen toggle. Twin of
 * Android's `ui/cameras/ZoomableFrame.kt` — keep behaviour in lockstep.
 *
 * Wraps the WHOLE player (live / recorded / placeholder) rather than any one of them, so every
 * source zooms identically and there is one implementation instead of three.
 *
 * The clamping math is in `lib/videoZoom.ts` and unit-tested there; this component only turns
 * pointer events into calls and the result into a CSS transform. The magnification is a view
 * transform — bigger pixels, not a sharper image, and it does not move the camera. Real optical
 * zoom on the E1 Zoom is PTZ (the separate Move control).
 */
export function ZoomableFrame({
  children,
  className = "",
}: {
  children: React.ReactNode;
  className?: string;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState<ZoomState>(NO_ZOOM);
  const [fullscreen, setFullscreen] = useState(false);

  // Live pointer positions, keyed by pointerId. Two entries = a pinch, one = a drag.
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  // Previous pinch distance / centroid, so each move reports a *delta* rather than an absolute.
  const last = useRef<{ dist: number; cx: number; cy: number } | null>(null);

  const frame = useCallback(() => {
    const r = hostRef.current?.getBoundingClientRect();
    return { width: r?.width ?? 0, height: r?.height ?? 0 };
  }, []);

  /** Browser fullscreen can also be left with Esc or the system UI — mirror it back into state,
   *  otherwise the button says "Exit" while the page is no longer fullscreen. */
  useEffect(() => {
    const sync = () => setFullscreen(document.fullscreenElement === hostRef.current);
    document.addEventListener("fullscreenchange", sync);
    return () => document.removeEventListener("fullscreenchange", sync);
  }, []);

  async function toggleFullscreen() {
    const el = hostRef.current;
    if (!el) return;
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await el.requestFullscreen();
    } catch {
      // Fullscreen is gesture-gated and can be blocked outright (iOS Safari has no
      // Element.requestFullscreen at all). Failing silently leaves the inline player working,
      // which is the honest fallback — there is nothing to retry and nothing to tell the user.
    }
  }

  function onPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 1 && !shouldCaptureDrag(zoom)) return;
    e.currentTarget.setPointerCapture?.(e.pointerId);
  }

  function onPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    const pts = pointers.current;
    if (!pts.has(e.pointerId)) return;
    const prev = pts.get(e.pointerId)!;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });

    const size = frame();
    if (size.width <= 0) return;
    const rect = hostRef.current!.getBoundingClientRect();

    if (pts.size >= 2) {
      const [a, b] = [...pts.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      const cx = (a.x + b.x) / 2;
      const cy = (a.y + b.y) / 2;
      const prevPinch = last.current;
      last.current = { dist, cx, cy };
      if (!prevPinch || prevPinch.dist <= 0) return;
      setZoom((z) =>
        applyGesture(z, size, {
          scaleChange: dist / prevPinch.dist,
          panX: cx - prevPinch.cx,
          panY: cy - prevPinch.cy,
          focusX: cx - rect.left - size.width / 2,
          focusY: cy - rect.top - size.height / 2,
        }),
      );
      return;
    }

    // Single pointer: pan, but only when zoomed. An unzoomed frame must let the drag
    // through so the page can still be scrolled on a phone.
    last.current = null;
    if (!shouldCaptureDrag(zoom)) return;
    setZoom((z) =>
      applyGesture(z, size, {
        scaleChange: 1,
        panX: e.clientX - prev.x,
        panY: e.clientY - prev.y,
        focusX: 0,
        focusY: 0,
      }),
    );
  }

  function endPointer(e: React.PointerEvent<HTMLDivElement>) {
    pointers.current.delete(e.pointerId);
    if (pointers.current.size < 2) last.current = null;
  }

  const zoomed = isZoomed(zoom);

  return (
    <div
      ref={hostRef}
      className={[
        "relative overflow-hidden",
        // A zoomed frame owns its gestures; an unzoomed one must not swallow page scroll.
        zoomed ? "touch-none" : "",
        fullscreen ? "flex h-full w-full items-center justify-center bg-black" : "",
        className,
      ].join(" ")}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endPointer}
      onPointerCancel={endPointer}
      onDoubleClick={() => setZoom(NO_ZOOM)}
    >
      <div
        className="h-full w-full"
        style={{ transform: cssTransform(zoom), transformOrigin: "center center" }}
      >
        {children}
      </div>

      {/* Outside the transformed div on purpose: the controls must stay put and stay the same
          size while the picture underneath is magnified and panned. */}
      <div className="absolute right-2 top-2 flex gap-xs">
        {zoomed && (
          <button
            type="button"
            onClick={() => setZoom(NO_ZOOM)}
            aria-label="Reset zoom"
            className="rounded-md bg-black/55 px-2 py-1 caption-label text-ink backdrop-blur-sm"
          >
            {zoom.scale.toFixed(1)}× · Reset
          </button>
        )}
        <button
          type="button"
          onClick={toggleFullscreen}
          aria-label={fullscreen ? "Exit fullscreen" : "Enter fullscreen"}
          className="rounded-md bg-black/55 px-2 py-1 caption-label text-ink backdrop-blur-sm"
        >
          {fullscreen ? "Exit" : "Full"}
        </button>
      </div>
    </div>
  );
}
