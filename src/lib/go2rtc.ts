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
 * Circuit-breaker for the go2rtc **media** path. Signaling can succeed (WS via
 * nginx) while media (WebRTC to `GO2RTC_HOST_IP:8555`) can't be reached — e.g.
 * before the §7c host forwarder is up, or off the tailnet. A camera that fails
 * media trips this, and every camera after skips the go2rtc tier (no repeated
 * multi-second stalls).
 *
 * ## It EXPIRES, and that is the whole point
 *
 * This was a permanent latch until 2026-08-25. `reportGo2rtcMedia(false)` set a
 * flag with no expiry, and its only reader — `go2rtcMaybeAvailable` — is what
 * decides whether the go2rtc player is mounted at all. So once false, nothing
 * could mount the player, and the success that would clear it became
 * **unreachable**: one transient blip disabled go2rtc for every camera until a
 * full page reload.
 *
 * Android's twin produced the visible incident (the live ladder fell through to a
 * 10s-refresh still image behind a green "Live" badge, reported as "very choppy
 * live video"); web shares the shape, so it is fixed in lockstep.
 *
 * A failure now records WHEN it happened and stops counting after
 * `BREAKER_TTL_MS`, letting one open retry the tier and re-establish the truth.
 * A success clears it immediately — the tier working proves the path is fine.
 */
let mediaFailedAt: number | null = null;
const BREAKER_TTL_MS = STREAMS_TTL_MS;

export function reportGo2rtcMedia(ok: boolean): void {
  mediaFailedAt = ok ? null : Date.now();
}

/** Whether the stream list has been fetched at least once this session. */
export function go2rtcStreamsKnown(): boolean {
  return streamsCache !== null;
}

/**
 * Kick off (and cache) the stream-list fetch; safe to call repeatedly. Resolves
 * once the cache is populated, so a caller can wait for an accurate answer from
 * `go2rtcMaybeAvailable` instead of acting on its optimistic default.
 */
export function primeGo2rtcStreams(): Promise<void> {
  const fresh = Date.now() - streamsFetchedAt < STREAMS_TTL_MS;
  if (streamsInFlight) return streamsInFlight.then(() => undefined);
  if (streamsCache && fresh) return Promise.resolve();
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
  return streamsInFlight.then(() => undefined);
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
  if (mediaFailedAt !== null && Date.now() - mediaFailedAt < BREAKER_TTL_MS) return false;
  if (streamsCache && !streamsCache.has(src)) return false;
  return true;
}

/**
 * Drop the cached stream list and circuit-breaker state. **Tests only** — the twin of
 * `Go2rtcStreams.resetForTest()` on Android.
 *
 * Both are module-level and survive a `render`, so without this a test that stubs one stream
 * list silently reuses the previous test's (the TTL is a minute; a suite runs in seconds).
 * That makes a gate test pass whatever the gate does, which is worse than no test.
 */
export function resetGo2rtcForTest(): void {
  streamsCache = null;
  streamsFetchedAt = 0;
  streamsInFlight = null;
  mediaFailedAt = null;
}
