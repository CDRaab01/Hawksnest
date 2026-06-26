import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// In dev, proxy /api to HA so the app is same-origin (mirrors the nginx pod in
// production). Override the target with HA_PROXY_TARGET.
const haTarget = process.env.HA_PROXY_TARGET ?? "http://192.168.4.34:8123";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: "autoUpdate",
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
    },
  },
  test: {
    globals: true,
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
    css: true,
  },
});
