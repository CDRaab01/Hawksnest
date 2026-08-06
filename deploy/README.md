# Deploying Hawksnest into the k3s cluster

Hawksnest runs as a pod in the **same K3s cluster and `home-automation` namespace** as Home
Assistant (see the `hawksnest-automation` repo). Its nginx serves the built SPA **and
reverse-proxies the HA API**, so the browser only ever talks to one origin — no CORS, no
mixed content, and HA is reached over cluster DNS (`home-assistant.home-automation.svc`),
not the NodePort/portproxy.

```
browser ──http──> Hawksnest pod (nginx :80)
                    ├─ /                → static SPA (dist/)
                    ├─ /api/websocket   → ws  → home-assistant.home-automation.svc:8123
                    └─ /api/            → http → home-assistant.home-automation.svc:8123
```

## What's here
- `../Dockerfile` — multi-stage build (node build → nginx serving `dist/`).
- `nginx.conf` — SPA fallback + `/api` + `/api/websocket` proxy to HA's Service.
- `k8s/` — kustomize: `deployment.yaml`, `service.yaml` (NodePort **30080**), `kustomization.yaml`
  (namespace `home-automation`).
- `windows/hawksnest-serve.ps1` — **HTTPS exposure over the tailnet** via Tailscale Serve
  (`https://<host>.ts.net:8443 → 127.0.0.1:8090 → wsl:30080`, and `:8444 → 127.0.0.1:8391 →
  wsl:30081` for ntfy). Safe to run at logon or by hand; no Administrator needed. This supersedes
  `portproxy-hawksnest.ps1` (below) now that the Dragonfly WSL distro runs in **mirrored**
  networking, where the old `netsh portproxy → <wsl-eth0>:30080` model breaks (no NAT interface;
  the NodePort is an iptables DNAT, not a listening socket the host can see). The fix is a real
  socat listener inside WSL that mirrored mode surfaces to host loopback, fronted by Tailscale's TLS.
  **The script no longer creates those listeners (changed 2026-08-06).** They are permanent,
  enabled, on-disk systemd units in the distro — `hawksnest-web-fwd` (8090→30080) and
  `hawksnest-ntfy-fwd` (8391→30081) — so the script only *verifies* them and ensures the Serve
  mappings. Until 2026-08-06 it created **transient** `systemd-run` units instead, and its ntfy unit
  name collided with the on-disk unit of the same name: every logon it stopped the working forwarder,
  failed to replace it, and left `:8391` dead, so **push died after every reboot** while Home
  Assistant looked fine. It also left a redundant socat on `:8390`, and its old `$ForwardPort`
  default of `8390` disagreed with the live `:8443 → 8090` mapping. Ports are now read from the
  units themselves so this can't drift again. Run with `-CleanupLegacy` once to clear the stale
  `:8390` unit. See the script header for the full rationale.
- `windows/portproxy-hawksnest.ps1` — *legacy* NAT-mode LAN/Tailscale exposure
  `0.0.0.0:8080 → wsl:30080`. Broken under mirrored WSL networking; kept for the NAT-mode case.
- `../.github/workflows/deploy.yml` — self-hosted-runner build + import + apply.

## Bring-up (on the Dragonfly host)
1. **Deploy.** Either run the **Deploy** GitHub Action (self-hosted runner), or by hand:
   ```bash
   docker build -t hawksnest:local .
   docker save hawksnest:local | sudo k3s ctr -n k8s.io images import -
   kubectl apply -k deploy/k8s
   kubectl -n home-automation rollout restart deployment/hawksnest
   kubectl -n home-automation rollout status deployment/hawksnest
   ```
   Confirm: `kubectl -n home-automation get pod,svc -l app=hawksnest` shows the pod Ready.
2. **Expose over HTTPS on the tailnet** (PowerShell on Windows):
   ```powershell
   .\deploy\windows\hawksnest-serve.ps1
   ```
   The host runs this as the `Hawksnest-Serve` logon task. Note it is **not load-bearing at boot**:
   the forwarders are enabled systemd units that come up with the distro, and Tailscale Serve config
   persists in `tailscaled` across reboots. It is a verifier and a first-time/drift-repair tool.
   (On a NAT-mode host, use the legacy `portproxy-hawksnest.ps1` instead.)
3. **Open** `https://<host>.ts.net:8443` (the script prints the exact URL). Go to **Settings** —
   the URL defaults to this site (the proxy) — paste a Home Assistant **long-lived access token**
   (HA → your profile → Long-lived access tokens) → **Connect**.
4. **Verify:** the header pill shows **Connected**, entities appear grouped by your real HA areas,
   `/api/websocket` upgrades (101), and **camera frames paint** (the XFF path — nginx clears
   `X-Forwarded-For`, else HA 400s every frame; see nginx.conf). No CORS errors.

## Rollback
```bash
kubectl -n home-automation rollout undo deployment/hawksnest
```

## Camera streaming locations (WebRTC / HLS, ring-mqtt + Frigate)
There are two camera backends. **ring-mqtt** (Ring devices over MQTT, with an embedded **go2rtc**)
bridges each Ring camera into HA as several entities — `camera.<base>_snapshot`,
`select.<base>_event_select`, and `binary_sensor.<base>_motion`/`_ding` — which the apps collapse
into one logical camera. **Frigate** has recorded the seven Reolink cameras since 2026-07-29. A
camera's recorded backend is derived per render as `ring | frigate | none`
(`src/lib/recordedBackend.ts`, and its Kotlin twin `core/logic/RecordedBackend.kt`); the derivation
fails closed, so a camera Frigate doesn't record resolves exactly as it did before Frigate existed.

> **ring-mqtt 5.x has no `camera.<base>_event` entity.** Selecting an option on the event selector
> makes ring-mqtt fetch Ring's signed cloud recording and publish it as the selector's
> `recordingUrl` attribute, which is what the players load. Assuming the 4.x `_event` camera is what
> pinned every recorded clip on "Loading recording…" (fixed 2026-07-26).

How each transport reaches HA through this one nginx origin:
- **WebRTC (live, lowest latency)** — negotiated entirely over the existing `/api/websocket`
  (`camera/webrtc/offer`); the media is UDP straight to go2rtc via ICE and never touches nginx. No
  new route. Both clients prefer it, gated on go2rtc's own stream list rather than on the camera's
  kind. Android additionally has an **RTSP-direct-to-camera** top tier the browser cannot have.
- **HLS (live fallback)** — `camera/stream` rides `/api/hls/` and `/api/camera_proxy_stream/` (both
  **buffering-off**). Resolved lazily, because `camera/stream` wakes a battery camera's pipeline.
- **`/api/frigate/`** — Frigate's continuous-VOD clips and playlists, buffering-off. **Live and in
  use.** Segment URLs are signed via `auth/sign_path`.
- **`/ring-timeline/`** — the sidecar `ring-timeline` service (sibling repo `hawksnest-automation`),
  which serves Ring's own `video_search` timeline: real event times and pre-signed mp4 URLs, which
  HA exposes no other way. Note it is **not** under `/api/`.
- **`/go2rtc/`** — signalling for the dedicated go2rtc instance, used by the two-way Talk path.
  Media rides `:8555` and bypasses nginx.

Recorded playback prefers `ring-timeline` (real event times, real spans, plus a 24/7 continuous
lane) and falls back to the ring-mqtt event selector — the last ~5 events, Ring Protect required —
when that service is down. Signed Ring URLs expire in ~15 minutes, so the timeline is refetched,
never held. All HA-proxied locations clear `X-Forwarded-For` for the reason below. The web/Android
apps run the whole player — live, timeline, transport, doorbell banner — against **demo data** (a
bundled clip + synthesized events) with no backend, so the UI is exercisable before any of this
is up.

## Note: HA trusted_proxies and X-Forwarded-For
`nginx.conf` deliberately does **not** forward `X-Forwarded-For` to HA. When HA has
`use_x_forwarded_for` enabled and the request's proxy IP isn't in `trusted_proxies`, HA does **not**
ignore the header — it **rejects the request with HTTP 400** ("Received X-Forwarded-For header from an
untrusted proxy"). The WebSocket survives because its location never sent XFF, but the camera
snapshot/stream GETs did, so HA 400'd every frame and **no video painted**. Dropping XFF costs nothing
(auth is by token; HA just logs the pod IP as the client).

If you want correct client-IP logging instead, add `X-Forwarded-For`/`X-Forwarded-Proto` back to the
`/api/` and `/api/camera_proxy_stream/` locations **and** add the K3s flannel pod CIDR `10.42.0.0/16`
to `trusted_proxies` in `hawksnest-automation/kustomize/home-assistant/configmap.yaml` (+ the
`ha-config` PVC). Do one or the other — sending XFF without trusting the proxy breaks cameras.

## Dev (no cluster)
`npm run dev` proxies `/api` to `HA_PROXY_TARGET` (default `http://192.168.4.34:8123`) so the app is
same-origin locally too:
```bash
HA_PROXY_TARGET=http://192.168.4.34:8123 npm run dev
```
