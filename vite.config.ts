import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In dev, proxy /api to HA so the app is same-origin (mirrors the nginx pod in
// production). Override the target with HA_PROXY_TARGET.
const haTarget = process.env.HA_PROXY_TARGET ?? "http://192.168.4.34:8123";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
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
