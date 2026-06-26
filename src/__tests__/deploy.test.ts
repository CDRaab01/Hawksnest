import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

// Validates the deployment artifacts (Dockerfile, nginx config, k8s manifests,
// and the deploy workflow) without needing a cluster. These guard the same-origin
// reverse-proxy contract and the LAN exposure ports the runbook depends on, so a
// stray edit can't silently break "it deploys and reaches HA."
const repoRoot = process.cwd();
const read = (rel: string) => readFileSync(join(repoRoot, rel), "utf8");

describe("nginx.conf — same-origin HA reverse proxy", () => {
  const nginx = read("deploy/nginx.conf");

  it("proxies to HA's in-cluster Service (not a NodePort/IP)", () => {
    expect(nginx).toContain("home-assistant.home-automation.svc.cluster.local:8123");
  });

  it("upgrades the /api/websocket connection", () => {
    expect(nginx).toMatch(/location\s+\/api\/websocket/);
    expect(nginx).toContain("proxy_set_header Upgrade $http_upgrade");
    expect(nginx).toContain('proxy_set_header Connection "upgrade"');
    // The WS must be allowed to stay open well past the default 60s.
    expect(nginx).toMatch(/proxy_read_timeout\s+3600s/);
  });

  it("proxies the REST API WITHOUT forwarding X-Forwarded-For", () => {
    expect(nginx).toMatch(/location\s+\/api\//);
    // HA rejects an X-Forwarded-For header from an untrusted proxy with HTTP 400
    // (this pod isn't in trusted_proxies), which 400'd every camera snapshot and
    // stream GET while the WebSocket — which never sent XFF — kept working. The
    // directive must NOT be present, or cameras break again. (The phrase appears
    // in an explanatory comment; we match the actual proxy_set_header directive.)
    expect(nginx).not.toMatch(/proxy_set_header\s+X-Forwarded-For/);
  });

  it("streams MJPEG camera live view with buffering disabled", () => {
    // The MJPEG (multipart/x-mixed-replace) live view needs its own location
    // with proxy_buffering off, or nginx stalls the stream (it never ends).
    expect(nginx).toMatch(/location\s+\/api\/camera_proxy_stream\//);
    const block = nginx.slice(nginx.indexOf("/api/camera_proxy_stream/"));
    expect(block).toMatch(/proxy_buffering\s+off/);
  });

  it("streams HLS + Frigate VOD with buffering disabled (timeline scrubber)", () => {
    // The HLS live feed and Frigate's recorded clips/VOD playlists are served as
    // streams; each needs its own location with proxy_buffering off, or nginx
    // stalls them — and neither may forward XFF (checked globally above).
    for (const path of ["/api/hls/", "/api/frigate/"]) {
      expect(nginx).toContain(`location ${path}`);
      const block = nginx.slice(nginx.indexOf(`location ${path}`));
      expect(block).toMatch(/proxy_buffering\s+off/);
    }
  });

  it("falls back to the SPA index for client-side routes", () => {
    expect(nginx).toContain("try_files $uri $uri/ /index.html");
  });

  it("never lets the browser cache the service worker", () => {
    expect(nginx).toMatch(/location\s+=\s+\/sw\.js/);
    expect(nginx).toMatch(/no-cache/);
  });
});

describe("Dockerfile — build then serve via nginx", () => {
  const dockerfile = read("Dockerfile");

  it("is a multi-stage node build → nginx image", () => {
    expect(dockerfile).toMatch(/FROM node:\S+ AS build/);
    expect(dockerfile).toMatch(/FROM nginx:\S+/);
    expect(dockerfile).toContain("RUN npm run build");
  });

  it("ships our nginx config and the built SPA", () => {
    expect(dockerfile).toContain("COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf");
    expect(dockerfile).toContain("COPY --from=build /app/dist /usr/share/nginx/html");
  });
});

describe("k8s manifests", () => {
  const deployment = read("deploy/k8s/deployment.yaml");
  const service = read("deploy/k8s/service.yaml");
  const kustomization = read("deploy/k8s/kustomization.yaml");
  const deployWorkflow = read(".github/workflows/deploy.yml");

  it("deploys into the shared home-automation namespace", () => {
    expect(kustomization).toMatch(/namespace:\s+home-automation/);
  });

  it("runs the image the workflow builds, on container port 80", () => {
    expect(deployment).toMatch(/image:\s+hawksnest:local/);
    expect(deployment).toMatch(/containerPort:\s+80/);
    // The deploy workflow must build/import that exact tag, or the rollout
    // would pull a non-existent image.
    expect(deployWorkflow).toContain("hawksnest:local");
  });

  it("exposes the documented NodePort 30080", () => {
    expect(service).toMatch(/type:\s+NodePort/);
    expect(service).toMatch(/nodePort:\s+30080/);
  });

  it("has matching health probes wired to the http port", () => {
    expect(deployment).toContain("readinessProbe");
    expect(deployment).toContain("livenessProbe");
  });
});
