/**
 * Default Home Assistant base URL.
 *
 * Hawksnest is normally served by its own nginx pod, which reverse-proxies
 * `/api` + `/api/websocket` to HA's in-cluster Service. So the app connects to
 * its OWN origin — no hardcoded HA IP, no CORS, no mixed content. In `npm run
 * dev`, Vite proxies `/api` to `HA_PROXY_TARGET`, so same-origin holds there too.
 *
 * The user can still override this in Settings to point at HA directly.
 */
export function defaultHaUrl(): string {
  if (typeof window !== "undefined" && window.location?.origin) {
    return window.location.origin;
  }
  return "";
}
