import { useCallback, useEffect, useRef } from "react";
import { ChevronDown, ChevronLeft, ChevronRight, ChevronUp, Square } from "lucide-react";
import { callService } from "../../store/connection";
import type { PtzControls } from "../../lib/cameraPtz";

type Direction = "up" | "down" | "left" | "right";

/**
 * The camera-movement pad: press and hold a direction to move, release to stop.
 *
 * **Hold-to-move works whichever way the camera behaves.** Whether a Reolink
 * direction press starts a continuous move (until `ptz_stop`) or advances one
 * step is not settled — testing it physically re-aims a recording camera, so it
 * is an on-device smoke-test item. Press-on-down / stop-on-release is correct
 * under both readings: continuous becomes hold-to-move, and step becomes one
 * step per tap with the stop a harmless no-op. Nothing here needs to change when
 * the answer lands.
 *
 * No speed control, deliberately: the cameras report
 * `supportPtzSpeed: {permit: 0}`, so speed-parameterised moves are impossible on
 * this hardware and a slider would be a lie.
 *
 * The safety property is that **the camera must never be left moving**. A move is
 * stopped on pointer release, on pointer cancel, on the pointer leaving the
 * button, on unmount, and on the page being hidden — and `stop` is sent even if
 * the original press failed, because a failed-looking press may still have
 * reached the camera.
 */
export function PtzPad({ ptz }: { ptz: PtzControls }) {
  // Which direction is in flight, so a second press can't stack a second move
  // and so teardown knows whether a stop is owed.
  const movingRef = useRef<Direction | null>(null);

  const stop = useCallback(() => {
    if (!movingRef.current) return;
    movingRef.current = null;
    void callService("button", "press", { entity_id: ptz.stop }).catch(() => {});
  }, [ptz.stop]);

  const start = useCallback(
    (dir: Direction) => {
      if (movingRef.current) return;
      movingRef.current = dir;
      void callService("button", "press", { entity_id: ptz[dir] }).catch(() => {
        // Do NOT clear movingRef here: the press may have reached the camera
        // even though the call reported failure. Releasing still sends stop.
      });
    },
    [ptz],
  );

  // Stop on unmount (switching cameras, scrubbing to recorded, closing the
  // lightbox) and whenever the tab is hidden — a backgrounded tab that never
  // fires pointerup would otherwise leave the camera panning.
  useEffect(() => {
    const onHide = () => {
      if (document.hidden) stop();
    };
    document.addEventListener("visibilitychange", onHide);
    return () => {
      document.removeEventListener("visibilitychange", onHide);
      stop();
    };
  }, [stop]);

  const button = (dir: Direction, label: string, Icon: typeof ChevronUp, cls: string) => (
    <button
      type="button"
      aria-label={`Pan ${label}`}
      className={[
        "flex items-center justify-center rounded-sm bg-panel-high text-ink-dim",
        "transition-colors duration-fast hover:text-ink active:bg-effort active:text-ink",
        "touch-none select-none",
        cls,
      ].join(" ")}
      onPointerDown={(e) => {
        // Capture so the release still reaches this button if the finger slides
        // off it — without capture the pointerup lands elsewhere and never stops.
        e.currentTarget.setPointerCapture?.(e.pointerId);
        start(dir);
      }}
      onPointerUp={stop}
      onPointerCancel={stop}
      onLostPointerCapture={stop}
      // Keyboard parity: hold Enter/Space to move, release to stop.
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          start(dir);
        }
      }}
      onKeyUp={(e) => {
        if (e.key === "Enter" || e.key === " ") stop();
      }}
      onBlur={stop}
    >
      <Icon size={18} />
    </button>
  );

  return (
    <div
      role="group"
      aria-label="Camera position"
      className="grid grid-cols-3 grid-rows-3 gap-xs"
      style={{ width: "9rem", height: "9rem" }}
    >
      <div />
      {button("up", "up", ChevronUp, "")}
      <div />
      {button("left", "left", ChevronLeft, "")}
      <button
        type="button"
        aria-label="Stop camera movement"
        onClick={() => {
          // An explicit stop always sends, even with nothing tracked as moving:
          // this is the manual escape hatch if a move was ever left running.
          movingRef.current = null;
          void callService("button", "press", { entity_id: ptz.stop }).catch(() => {});
        }}
        className="flex items-center justify-center rounded-sm bg-panel text-ink-faint transition-colors duration-fast hover:text-ink"
      >
        <Square size={12} />
      </button>
      {button("right", "right", ChevronRight, "")}
      <div />
      {button("down", "down", ChevronDown, "")}
      <div />
    </div>
  );
}
