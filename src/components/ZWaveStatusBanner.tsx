import { useEffect, useState } from "react";
import { AlertTriangle, X } from "lucide-react";
import { useZWaveControllerOffline } from "../store/entityStore";

/**
 * App-wide warning when the Z-Wave controller looks offline — every Z-Wave entity
 * is unavailable at once, which means the USB stick or zwave-js-ui has dropped
 * (the deployment's documented "fragile link"). Critical because the locks go
 * silent: HA shows their last-known state, so without this the UI looks fine
 * while lock/unlock no longer reaches the deadbolts. Dismissible, but re-shows if
 * the radio recovers and drops again.
 */
export function ZWaveStatusBanner() {
  const offline = useZWaveControllerOffline();
  const [dismissed, setDismissed] = useState(false);

  // Re-arm the banner each time the radio recovers, so a later outage shows again.
  useEffect(() => {
    if (!offline) setDismissed(false);
  }, [offline]);

  if (!offline || dismissed) return null;

  return (
    <div className="fixed inset-x-0 top-0 z-[55] flex justify-center p-md">
      <div className="flex w-full max-w-md items-center gap-md rounded-lg border border-streak/40 bg-panel-high px-lg py-md shadow-lg">
        <AlertTriangle className="shrink-0 text-streak" size={22} />
        <div className="min-w-0 flex-1">
          <div className="font-display text-body text-ink">Z-Wave offline</div>
          <div className="font-body text-caption text-ink-dim">
            The controller isn't responding — your locks may not lock or unlock.
            Check the Z-Wave stick / zwave-js-ui.
          </div>
        </div>
        <button
          type="button"
          onClick={() => setDismissed(true)}
          aria-label="Dismiss Z-Wave warning"
          className="shrink-0 rounded-sm p-xs text-ink-dim transition-colors duration-fast hover:text-ink"
        >
          <X size={18} />
        </button>
      </div>
    </div>
  );
}
