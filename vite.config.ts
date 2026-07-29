import { defineConfig, configDefaults } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// In dev, proxy /api to HA so the app is same-origin (mirrors the nginx pod in
// production). Override the target with HA_PROXY_TARGET.
//
// Default is loopback, not the host's LAN address: WSL runs mirrored networking
// since 2026-07-22, so HA is reached through the `socat` unit in the Dragonfly
// distro (host 8123 → NodePort 30123) and the old `192.168.4.34:8123` no longer
// answers. Set HA_PROXY_TARGET when the dev server isn't on the Dragonfly host.
const haTarget = process.env.HA_PROXY_TARGET ?? "http://127.0.0.1:8123";

// go2rtc (live WebRTC signaling + the stream list) and ring-timeline (recorded
// events, spans and the 24/7 continuous track) are cluster-internal services with
// no NodePort of their own, so dev can't reach them directly the way it reaches
// HA. Point at the **Hawksnest nginx pod** instead (host 8090 → NodePort 30080),
// which already proxies both under these exact prefixes — so dev inherits
// production's routing rather than restating it, and no path rewrite is needed.
//
// Without these, `/go2rtc/api/streams` and `/ring-timeline/cameras` hit the vite
// dev server itself and return the SPA's index.html with a 200. That's worse than
// a connection error: `primeGo2rtcStreams` parses the HTML as JSON, caches an
// empty stream set, and `go2rtcMaybeAvailable` then reports false for every
// camera — silently disabling the whole go2rtc live tier in dev only.
const proxyTarget = process.env.HAWKSNEST_PROXY_TARGET ?? "http://127.0.0.1:8090";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // "prompt", not "autoUpdate": a wall-mounted tablet may sit on one tab for
      // days and never navigate, so silent skip-waiting would strand it on a
      // stale shell. Instead `UpdateToast` (useRegisterSW) surfaces a "reload"
      // prompt when a new SW is waiting. injectRegister:false because the React
      // hook registers the SW itself — leaving it on "auto" would double-register.
      registerType: "prompt",
      injectRegister: false,
      // Precache the built app shell only. CRITICAL: nothing under /api is ever
      // cached — that's the live HA WebSocket/REST surface and the only thing
      // that carries the long-lived token. The token itself lives in
      // localStorage and is never touched by the service worker. Offline, the
      // shell loads and the app shows its existing Offline/Demo state rather
      // than stale HA data.
      workbox: {
        globPatterns: ["**/*.{js,css,html,svg,woff,woff2}"],
        // hls.js is a large (~500KB) lazy chunk only loaded for non-native HLS
        // playback (a network-only feature) — keep it out of the offline shell
        // precache. It's fetched on demand; offline, the player falls back.
        globIgnores: ["**/hls-*.js"],
        // SPA navigations fall back to index.html, but never for /api routes.
        navigateFallback: "index.html",
        navigateFallbackDenylist: [/^\/api/],
        // No runtimeCaching: we deliberately do not cache any HA responses.
      },
      includeAssets: ["pwa-icon.svg"],
      manifest: {
        name: "Hawksnest",
        short_name: "Hawksnest",
        description: "A polished home dashboard for Home Assistant.",
        theme_color: "#0B0D10",
        background_color: "#0B0D10",
        display: "standalone",
        start_url: "/",
        scope: "/",
        icons: [
          {
            src: "pwa-icon.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "any maskable",
          },
        ],
      },
    }),
  ],
  server: {
    proxy: {
      "/api": { target: haTarget, changeOrigin: true, ws: true },
      // ws: go2rtc's signaling is `/go2rtc/api/ws`; the same block serves its
      // plain HTTP endpoints, mirroring nginx's single `location /go2rtc/`.
      "/go2rtc": { target: proxyTarget, changeOrigin: true, ws: true },
      "/ring-timeline": { target: proxyTarget, changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
    // Playwright owns e2e/ (its *.spec.ts use @playwright/test, not vitest).
    exclude: [...configDefaults.exclude, "e2e/**"],
  },
});
