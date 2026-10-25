/**
 * What a go2rtc live session does when go2rtc answers the offer with `{"type":"error"}` — i.e.
 * go2rtc is up and signalling fine, but could not open the camera's source.
 *
 * For a camera that SLEEPS (`isBatteryCamera`: the Argus 4 Pros behind the Reolink Home Hub)
 * that is the normal first answer, not a fault. The hub starts waking the camera on the first
 * RTSP request and, while it wakes, answers the SETUP with `454 Session Not Found` (measured on
 * the Frigate walk tests, hawksnest-automation docs/adding-a-camera.md). go2rtc does not retry a
 * failed source, so the session is dead on arrival — and the players used to ignore the error
 * message entirely, sitting on "Waking camera…" until the 30 s watchdog, then stepping down to a
 * slower tier just as the camera finished waking. The vendor app simply asks again, which is most
 * of why it reads as fast. So: ask again, every second, inside the same 30 s budget the watchdog
 * always gave a wake.
 *
 * For an always-on camera the error is a verdict about that ONE camera's source (unreachable,
 * rebooting), so step down at once — and never as a global verdict on go2rtc's media path, which
 * the error itself just proved is answering.
 *
 * 1:1 port in `android/.../core/logic/Go2rtcWake.kt` — keep the constants identical.
 */

/** Pause between attempts while a sleeping camera wakes. Each attempt is also what keeps the
 *  hub waking it, so this is short. */
export const WAKE_RETRY_MS = 1_000;

/**
 * Total time a sleeping camera gets, measured from the FIRST attempt of an open — the same leash
 * the single-attempt watchdog gave. Reolink's own figure for a wake is up to ~20 s.
 */
export const WAKE_BUDGET_MS = 30_000;

/** Connect watchdog for a camera that is awake: go2rtc-direct exists because it is fast. */
export const CONNECT_WATCHDOG_MS = 8_000;

/**
 * Delay before retrying after go2rtc reported a source error, or `null` to step down now.
 * `elapsedMs` is the time since the first attempt of this open.
 */
export function sourceErrorRetryDelayMs(wakeable: boolean, elapsedMs: number): number | null {
  if (!wakeable) return null;
  return Math.max(0, elapsedMs) + WAKE_RETRY_MS < WAKE_BUDGET_MS ? WAKE_RETRY_MS : null;
}

/**
 * The connect watchdog for one attempt. An awake camera gets the short leash every time. A
 * sleeping camera's attempts share the wake budget — the first gets all 30 s, a retry only what
 * is left — but never less than the awake leash, so the attempt that finally reaches an awake
 * camera still has time to negotiate. Worst case for an open: ~30 s of retries + 8 s.
 */
export function go2rtcWatchdogMs(wakeable: boolean, elapsedMs: number): number {
  if (!wakeable) return CONNECT_WATCHDOG_MS;
  return Math.max(WAKE_BUDGET_MS - Math.max(0, elapsedMs), CONNECT_WATCHDOG_MS);
}
