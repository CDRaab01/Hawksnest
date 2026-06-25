/**
 * Compact "x ago" label from an epoch-ms timestamp — the Ring-style freshness
 * stamp on camera tiles ("30s ago") and the History feed. `now` is injectable
 * so it's deterministic in tests. Future timestamps clamp to "now".
 */
export function relativeTime(thenMs: number, nowMs: number = Date.now()): string {
  const diff = Math.max(0, nowMs - thenMs);
  const s = Math.floor(diff / 1000);
  if (s < 5) return "now";
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.floor(h / 24);
  if (d < 7) return `${d}d ago`;
  const w = Math.floor(d / 7);
  return `${w}w ago`;
}

/** Wall-clock "h:mm AM/PM" for History rows. */
export function clockTime(thenMs: number): string {
  return new Date(thenMs).toLocaleTimeString(undefined, {
    hour: "numeric",
    minute: "2-digit",
  });
}
