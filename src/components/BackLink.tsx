import { ArrowLeft } from "lucide-react";
import { useLocation, useNavigate } from "react-router-dom";

/**
 * "Back" that goes back.
 *
 * The drill-in screens each hardcoded `<Link to="/">Home</Link>`, which is only right when you
 * arrived from the Dashboard. Reaching a device from Devices, or a room from the Rooms grid — the
 * two normal routes into both screens — and pressing it dropped you on the Dashboard, several
 * taps from where you were, with no way back to a scrolled list position.
 *
 * Uses real history when there is any, so the browser's own Back and this button agree. `key` is
 * React Router's per-entry marker: it is `"default"` only for the first entry in the stack, which
 * is the deep-link / refresh case — there, history holds nothing of ours and `fallback` is the
 * honest destination.
 */
export function BackLink({
  fallback = "/",
  label = "Back",
}: {
  /** Where to go when this screen IS the first history entry (deep link, refresh). */
  fallback?: string;
  label?: string;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const canGoBack = location.key !== "default";

  return (
    <button
      type="button"
      onClick={() => (canGoBack ? navigate(-1) : navigate(fallback))}
      className="inline-flex items-center gap-xs text-body text-ink-dim transition-colors duration-fast hover:text-ink"
    >
      <ArrowLeft size={16} /> {label}
    </button>
  );
}
