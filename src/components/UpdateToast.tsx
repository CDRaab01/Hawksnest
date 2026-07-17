import { useRegisterSW } from "virtual:pwa-register/react";
import { RefreshCw, X } from "lucide-react";

/**
 * Pure presentation for the "update ready" prompt. Split out from the container
 * so it's testable without the `virtual:pwa-register` service-worker module.
 * Sits above the BottomBar (bottom-24) so it never collides with the doorbell
 * banner, which drops in from the top.
 */
export function UpdateToastView({
  onReload,
  onDismiss,
}: {
  onReload: () => void;
  onDismiss: () => void;
}) {
  return (
    <div
      role="status"
      className="fixed inset-x-0 bottom-24 z-[60] flex justify-center px-md"
    >
      <div className="flex w-full max-w-md items-center gap-md rounded-lg border border-hairline bg-panel-high px-lg py-md shadow-lg animate-fade-in motion-reduce:animate-none">
        <RefreshCw className="shrink-0 text-effort" size={20} />
        <div className="min-w-0 flex-1">
          <div className="font-display text-body text-ink">Update ready</div>
          <div className="truncate font-body text-caption text-ink-dim">
            A new version of Hawksnest is available.
          </div>
        </div>
        <button
          type="button"
          onClick={onReload}
          className="shrink-0 rounded-full bg-effort px-lg py-sm font-body text-body text-effort-on"
        >
          Reload
        </button>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss update prompt"
          className="shrink-0 rounded-sm p-xs text-ink-dim transition-colors duration-fast hover:text-ink"
        >
          <X size={18} />
        </button>
      </div>
    </div>
  );
}

/**
 * App-wide service-worker update prompt. `useRegisterSW` (registerType:"prompt")
 * flips `needRefresh` when a new SW has installed and is waiting; reloading
 * activates it and swaps the app shell. Dismissing leaves the current shell
 * running — the prompt returns on the next build. The SW never caches /api, so
 * an update only ever swaps static assets, never live HA data.
 */
export function UpdateToast() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW();

  if (!needRefresh) return null;

  return (
    <UpdateToastView
      onReload={() => void updateServiceWorker(true)}
      onDismiss={() => setNeedRefresh(false)}
    />
  );
}
