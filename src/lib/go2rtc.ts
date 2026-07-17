/**
 * Client helpers for the **dedicated go2rtc** live path (native Ring source, the
 * lowest-latency feed + the two-way Talk backchannel — see hawksnest-automation
 * §7c). Signaling rides go2rtc's WebSocket API, proxied same-origin by the app's
 * nginx at `/go2rtc/`; the media is WebRTC straight to go2rtc's `:8555` host port.
 * The go2rtc stream name is kept equal to the HA camera base (`camera.<base>` →
 * `<base>`).
 */

/** WebSocket signaling URL for a go2rtc stream (same-origin via the nginx proxy). */
export function go2rtcWsUrl(src: string): string {
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${window.location.host}/go2rtc/api/ws?src=${encodeURIComponent(src)}`;
}

// The set of stream names go2rtc is currently serving, fetched once per session.
// null = not fetched yet (be optimistic); a Set = known. A fetch failure caches an
// empty set for a short while so we don't hammer a down go2rtc.
let streamsCache: Set<string> | null = null;
let streamsFetchedAt = 0;
let streamsInFlight: Promise<Set<string>> | null = null;
const STREAMS_TTL_MS = 60_000;

/**
 * Session circuit-breaker for the go2rtc **media** path. Signaling can succeed
 * (WS via nginx) while media (WebRTC to `GO2RTC_HOST_IP:8555`) can't be reached —
 * e.g. before the §7c host forwarder is up, or off the tailnet. The first camera
 * that fails media flips this to false, and every camera after skips the go2rtc
 * tier for the rest of the session (no repeated multi-second stalls). A success
 * flips it true. Reset on reload.
 */
let mediaHealthy: boolean | null = null;

export function reportGo2rtcMedia(ok: boolean): void {
  mediaHealthy = ok;
}

/** Kick off (and cache) the stream-list fetch; safe to call repeatedly. */
export function primeGo2rtcStreams(): void {
  const fresh = Date.now() - streamsFetchedAt < STREAMS_TTL_MS;
  if (streamsInFlight || (streamsCache && fresh)) return;
  streamsInFlight = fetch("/go2rtc/api/streams", { cache: "no-store" })
    .then((r) => (r.ok ? r.json() : {}))
    .then((json: Record<string, unknown>) => new Set(Object.keys(json ?? {})))
    .catch(() => new Set<string>())
    .then((set) => {
      streamsCache = set;
      streamsFetchedAt = Date.now();
      streamsInFlight = null;
      return set;
    });
}

/**
 * Pure synchronous best-guess for whether the go2rtc live tier is worth
 * attempting for `src`, used to pick the initial transport without awaiting a
 * fetch (call `primeGo2rtcStreams` from an effect to populate the cache):
 *  - never, once media is known-broken this session (circuit-breaker);
 *  - never, if we've fetched the stream list and it doesn't include `src`;
 *  - otherwise yes (optimistic) — a genuinely-absent stream fails the WS
 *    negotiation fast and the player steps down.
 */
export function go2rtcMaybeAvailable(src: string): boolean {
  if (mediaHealthy === false) return false;
  if (streamsCache && !streamsCache.has(src)) return false;
  return true;
}
