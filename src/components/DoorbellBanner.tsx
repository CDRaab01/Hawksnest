import { useEffect, useState } from "react";
import { BellRing, X } from "lucide-react";
import { useLogicalCameras, useEntityStore } from "../store/entityStore";
import { useCameraOverlay } from "../store/cameraOverlay";
import { activeDoorbellPress } from "../lib/doorbell";

const AUTO_DISMISS_MS = 12_000;

/**
 * App-wide doorbell banner. When a camera's `_ding` sensor rings (ring-mqtt),
 * slides in a "Someone's at …" alert with a **View** action that opens that
 * camera's live player. Also raises a Web Notification when permitted and the
 * tab is backgrounded. Auto-dismisses, and a fresh press re-shows.
 */
export function DoorbellBanner() {
  const cameras = useLogicalCameras();
  const entities = useEntityStore((s) => s.entities);
  const openCamera = useCameraOverlay((s) => s.open);
  const [dismissedAt, setDismissedAt] = useState(0);

  const press = activeDoorbellPress(cameras, entities);
  const show = press !== null && press.whenMs > dismissedAt;

  // Auto-dismiss after a while.
  useEffect(() => {
    if (!show) return;
    const id = setTimeout(() => setDismissedAt(press!.whenMs), AUTO_DISMISS_MS);
    return () => clearTimeout(id);
    // press is tracked by whenMs; its object identity changes each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [show, press?.whenMs]);

  // Mirror to a Web Notification when allowed and the tab isn't focused.
  useEffect(() => {
    if (!show || typeof Notification === "undefined") return;
    if (Notification.permission !== "granted" || document.visibilityState === "visible") return;
    const n = new Notification("Doorbell", { body: `Someone's at ${press!.name}` });
    return () => n.close();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [show, press?.whenMs, press?.name]);

  if (!show || !press) return null;

  const dismiss = () => setDismissedAt(press.whenMs);

  return (
    <div className="fixed inset-x-0 top-0 z-[60] flex justify-center p-md">
      {/* Keyed by the press time so a fresh ding re-runs the spring entrance. */}
      <div
        key={press.whenMs}
        className="flex w-full max-w-md items-center gap-md rounded-lg border border-hairline bg-panel-high px-lg py-md shadow-lg animate-banner-in motion-reduce:animate-none"
      >
        <BellRing className="shrink-0 text-recovery" size={22} />
        <div className="min-w-0 flex-1">
          <div className="font-display text-body text-ink">Doorbell</div>
          <div className="truncate font-body text-caption text-ink-dim">
            Someone's at {press.name}
          </div>
        </div>
        <button
          type="button"
          onClick={() => {
            openCamera(press.cameraId);
            dismiss();
          }}
          className="shrink-0 rounded-full bg-recovery px-lg py-sm font-body text-body text-black"
        >
          View
        </button>
        <button
          type="button"
          onClick={dismiss}
          aria-label="Dismiss doorbell alert"
          className="shrink-0 rounded-sm p-xs text-ink-dim transition-colors duration-fast hover:text-ink"
        >
          <X size={18} />
        </button>
      </div>
    </div>
  );
}
