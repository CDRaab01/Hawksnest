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
  (`https://<host>.ts.net:8443 → 127.0.0.1:8390 → wsl:30080`), run at logon. This supersedes
  `portproxy-hawksnest.ps1` (below) now that the Dragonfly WSL distro runs in **mirrored**
  networking, where the old `netsh portproxy → <wsl-eth0>:30080` model breaks (no NAT interface;
  the NodePort is an iptables DNAT, not a listening socket the host can see). The script instead
  runs a real socat listener inside WSL that mirrored mode surfaces to host loopback, then fronts
  it with Tailscale's TLS. See the script header for the full rationale.
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
   Add it to the host's logon/boot task. It starts the WSL socat forwarder each boot; the
   Tailscale Serve config persists on its own. (On a NAT-mode host, use the legacy
   `portproxy-hawksnest.ps1` instead.)
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
The camera backend is **ring-mqtt** (Ring devices over MQTT, with an embedded **go2rtc** for
streaming). It bridges each Ring camera into HA as several entities — `camera.<base>_live`,
`_snapshot`, `_event`, `select.<base>_event_select`, and `binary_sensor.<base>_motion`/`_ding`
— which the apps collapse into one logical camera.

How each transport reaches HA through this one nginx origin:
- **WebRTC (live, lowest latency)** — negotiated entirely over the existing `/api/websocket`
  (`camera/webrtc/offer`); the media is UDP straight to go2rtc via ICE and never touches nginx. No
  new route. (Web uses native WebRTC; Android uses go2rtc **LL-HLS** via ExoPlayer.)
- **HLS (live fallback + ring recorded events)** — `camera/stream` and the `camera.<base>_event`
  recording stream ride `/api/hls/` and `/api/camera_proxy_stream/` (both **buffering-off**).
- **`/api/frigate/`** — optional: Frigate's continuous-VOD clips/playlists, kept buffering-off and
  ready should a Frigate NVR ever join (it needs hardware ring-mqtt doesn't).

Recorded-event playback on ring-mqtt picks one of the **last ~5 events** via the event-selector
entity (Ring Protect required); it isn't a continuous 24h VOD. All locations omit `X-Forwarded-For`
for the reason below. The web/Android apps run the whole player — live, timeline, transport,
doorbell banner — against **demo data** (a bundled clip + synthesized events) with no backend, so
the UI is exercisable before ring-mqtt is up.

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
