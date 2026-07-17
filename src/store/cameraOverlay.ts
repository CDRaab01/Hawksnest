import { create } from "zustand";
import { flushSync } from "react-dom";

/**
 * Which logical camera (if any) is open in the full-screen player, app-wide. A
 * single global overlay lets the camera wall *and* the doorbell banner open the
 * same player without each owning its own modal state.
 *
 * Open/close run inside a **View Transition** where the browser supports it, so
 * the tapped wall tile visually expands into the player (and collapses back)
 * instead of a modal popping in. Tiles and the player share a per-camera
 * `view-transition-name` (see [viewTransitionNameFor]); unsupported browsers and
 * reduced-motion users get the instant swap they always had.
 */
interface CameraOverlayState {
  openId: string | null;
  open: (cameraId: string) => void;
  close: () => void;
}

/** A camera id as a valid CSS view-transition-name ("camera.front_door" → "cam-camera-front_door"). */
export function viewTransitionNameFor(cameraId: string): string {
  return `cam-${cameraId.replace(/[^a-zA-Z0-9_-]/g, "-")}`;
}

/** Run a state update inside a View Transition when supported (else just run it). */
function withViewTransition(update: () => void) {
  const doc = document as Document & {
    startViewTransition?: (cb: () => void) => unknown;
  };
  if (
    typeof doc.startViewTransition === "function" &&
    !window.matchMedia("(prefers-reduced-motion: reduce)").matches
  ) {
    doc.startViewTransition(() => {
      // The browser snapshots old/new frames around this callback — the DOM
      // change must land synchronously inside it.
      flushSync(update);
    });
  } else {
    update();
  }
}

export const useCameraOverlay = create<CameraOverlayState>((set) => ({
  openId: null,
  open: (cameraId) => withViewTransition(() => set({ openId: cameraId })),
  close: () => withViewTransition(() => set({ openId: null })),
}));
