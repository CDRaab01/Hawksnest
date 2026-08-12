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

/**
 * Whether `url` is a cleartext HA address the page's own origin will refuse.
 *
 * A TLS-served page cannot open `http://` or `ws://` to anywhere: the browser blocks it as mixed
 * content before a request is made, and all the app ever sees is a generic connect failure. That
 * matters here because the deployed front IS TLS — Tailscale Serve at `https://<host>.ts.net:8443`
 * — while the Settings form's own placeholder suggested `http://homeassistant.local:8123` and its
 * help text `http://192.168.4.34:8123`. Both are correct for a LAN page and impossible for the
 * shipped one, and the resulting error names neither the scheme nor the reason.
 *
 * The same class of trap as the Android placeholder fixed in #112 — an in-app hint contradicting
 * the shipped transport policy.
 */
export function isBlockedMixedContent(url: string): boolean {
  if (typeof window === "undefined" || window.location?.protocol !== "https:") return false;
  return /^(http|ws):\/\//i.test(url.trim());
}
