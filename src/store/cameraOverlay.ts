import { create } from "zustand";

/**
 * Which logical camera (if any) is open in the full-screen player, app-wide. A
 * single global overlay lets the camera wall *and* the doorbell banner open the
 * same player without each owning its own modal state.
 */
interface CameraOverlayState {
  openId: string | null;
  open: (cameraId: string) => void;
  close: () => void;
}

export const useCameraOverlay = create<CameraOverlayState>((set) => ({
  openId: null,
  open: (cameraId) => set({ openId: cameraId }),
  close: () => set({ openId: null }),
}));
