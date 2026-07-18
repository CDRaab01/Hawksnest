import type { HassEntity } from "./ha";
import { domainOf } from "./ha";
import type { ConnectionStatus } from "../store/entityStore";

/**
 * The honest degraded offline model, pure and unit-testable (no React, no clock, no socket).
 *
 * The refined invariant: after an in-session drop, non-security entities may keep rendering —
 * dimmed and labeled "Reconnecting — as of HH:MM" — for at most GRACE_WINDOW_MS; lock and alarm
 * state is **never** rendered stale (masked the moment the socket is gone); nothing is ever
 * persisted and no commands are queued. Mirrored by `core/logic/Offline.kt` on Android.
 */

/** How long non-security entities may render dimmed + labeled after an in-session drop. */
export const GRACE_WINDOW_MS = 120_000;

/** Domains whose state must never render stale — masked the moment the live socket drops. */
const SECURITY_STALE_DOMAINS = new Set(["lock", "alarm_control_panel"]);

export type OfflinePhase = "live" | "grace" | "offline";

/**
 * Where the dashboard sits on the live → grace → offline ladder. "grace" = an in-session drop
 * younger than the window (render last-known entities dimmed, controls disabled); "offline" = a
 * terminal error or an expired window (render the full OfflineState, no entity data at all). A
 * first-ever connect (no `staleSince`) stays "live" — there is nothing stale to show.
 */
export function offlinePhase(
  status: ConnectionStatus,
  staleSince: number | undefined,
  nowMs: number,
): OfflinePhase {
  if (status === "error") return "offline";
  if (status !== "connecting" || staleSince === undefined) return "live";
  return nowMs - staleSince < GRACE_WINDOW_MS ? "grace" : "offline";
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * Short "as of" readout for staleness banners: "3:42 PM" when `thenMs` falls on today,
 * "Nov 14, 3:42 PM" otherwise. Manual (not toLocaleString) so tests are deterministic
 * across environments; matches Android's `formatAsOf`.
 */
export function formatAsOf(thenMs: number, nowMs: number = Date.now()): string {
  const then = new Date(thenMs);
  const now = new Date(nowMs);
  let h = then.getHours();
  const ampm = h >= 12 ? "PM" : "AM";
  h = h % 12 || 12;
  const clock = `${h}:${String(then.getMinutes()).padStart(2, "0")} ${ampm}`;
  const sameDay =
    then.getFullYear() === now.getFullYear() &&
    then.getMonth() === now.getMonth() &&
    then.getDate() === now.getDate();
  return sameDay ? clock : `${MONTHS[then.getMonth()]} ${then.getDate()}, ${clock}`;
}

/**
 * Collapse lock/alarm entities to `unavailable` — the security-invariant half of the grace
 * window. Called by the entity store the moment the live socket is lost, so no surface can
 * render a stale "Locked"/"Armed" even for a moment; a successful reconnect's fresh entity
 * push replaces all of it. `unavailable` is deliberate: existing presentations already treat
 * it as an honest can't-know state with controls disabled. Returns the same object when
 * nothing needs masking (no spurious store updates).
 */
export function maskSecurityStates(
  entities: Record<string, HassEntity>,
): Record<string, HassEntity> {
  const needsMask = Object.values(entities).some(
    (e) => SECURITY_STALE_DOMAINS.has(domainOf(e.entity_id)) && e.state !== "unavailable",
  );
  if (!needsMask) return entities;
  const next: Record<string, HassEntity> = {};
  for (const [id, e] of Object.entries(entities)) {
    next[id] =
      SECURITY_STALE_DOMAINS.has(domainOf(id)) && e.state !== "unavailable"
        ? { ...e, state: "unavailable" }
        : e;
  }
  return next;
}

export type ReachabilityHint = "network-unreachable" | "ha-not-answering";

/** Bound for the reachability probe — it must never hang the offline screen. */
const PROBE_TIMEOUT_MS = 6_000;

/**
 * Passive reachability probe for the offline hint. The app is same-origin proxied to HA
 * (nginx forwards `/api`), so ANY HTTP response from `<base>/api/` — 401 unauthenticated from
 * HA, even a 502 from nginx with HA down — proves the server answered and the problem is HA;
 * only a transport failure (offline, no route, timeout) means the network itself is down.
 */
export async function probeHaReachability(
  baseUrl: string,
  timeoutMs: number = PROBE_TIMEOUT_MS,
): Promise<ReachabilityHint> {
  const base = baseUrl ? baseUrl.replace(/\/+$/, "") : "";
  try {
    await fetch(`${base}/api/`, {
      cache: "no-store",
      signal: AbortSignal.timeout(timeoutMs),
    });
    return "ha-not-answering";
  } catch {
    return "network-unreachable";
  }
}
