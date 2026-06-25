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
- `windows/portproxy-hawksnest.ps1` — LAN/Tailscale exposure `0.0.0.0:8080 → wsl:30080`.
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
2. **Expose to the LAN/Tailscale** (Administrator PowerShell on Windows):
   ```powershell
   .\deploy\windows\portproxy-hawksnest.ps1
   ```
   Add it to the host's logon/boot task next to `portproxy-ha.ps1` (WSL2's IP changes on reboot).
3. **Open** `http://192.168.4.34:8080` (or the host's Tailscale IP). Go to **Settings** — the URL
   already defaults to this site (the proxy) — paste a Home Assistant **long-lived access token**
   (HA → your profile → Long-lived access tokens) → **Connect**.
4. **Verify:** the header pill shows **Connected**, entities appear grouped by your real HA areas,
   and DevTools → Network → WS shows `/api/websocket` upgraded (101). No CORS errors.

## Rollback
```bash
kubectl -n home-automation rollout undo deployment/hawksnest
```

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
