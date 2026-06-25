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
- `windows/portproxy-hawksnest.ps1` — LAN/Tailscale exposure `0.0.0.0:8090 → wsl:30080`
  (8090, not 8080 — SABnzbd's default owns 8080 on this host).
- `../.github/workflows/deploy.yml` — self-hosted-runner build + import + apply.
- `runner-wsl.md` — make the self-hosted runner durable inside the Dragonfly WSL2 distro
  (survive reboots / no interactive `run.cmd`).

## Bring-up (on the Dragonfly host)
1. **Deploy.** Either run the **Deploy** GitHub Action (self-hosted runner — it must be
   **online**, or the run sits *Queued* forever; see [`runner-wsl.md`](./runner-wsl.md) to
   make it durable), or by hand:
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
3. **Open** `http://192.168.4.34:8090` (or the host's Tailscale IP). Go to **Settings** — the URL
   already defaults to this site (the proxy) — paste a Home Assistant **long-lived access token**
   (HA → your profile → Long-lived access tokens) → **Connect**.
4. **Verify:** the header pill shows **Connected**, entities appear grouped by your real HA areas,
   and DevTools → Network → WS shows `/api/websocket` upgraded (101). No CORS errors.

## Rollback
```bash
kubectl -n home-automation rollout undo deployment/hawksnest
```

## Note: HA trusted_proxies (optional, in hawksnest-automation)
nginx forwards `X-Forwarded-For`, but the Hawksnest pod's IP isn't in HA's `trusted_proxies`, so HA
logs "X-Forwarded-For from untrusted proxy" and ignores it. **Functionally fine** (auth is by token
over the WS). For correct client-IP logging, add the K3s flannel pod CIDR `10.42.0.0/16` to
`trusted_proxies` in `hawksnest-automation/kustomize/home-assistant/configmap.yaml` and the
`ha-config` PVC. Not required for Hawksnest to work.

## Dev (no cluster)
`npm run dev` proxies `/api` to `HA_PROXY_TARGET` (default `http://192.168.4.34:8123`) so the app is
same-origin locally too:
```bash
HA_PROXY_TARGET=http://192.168.4.34:8123 npm run dev
```
