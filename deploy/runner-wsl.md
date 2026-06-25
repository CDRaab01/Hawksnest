# Durable self-hosted runner (Dragonfly / WSL2)

The **Deploy** workflow (`.github/workflows/deploy.yml`) is pinned to
`runs-on: [self-hosted, linux, dragonfly]` — it builds the image, imports it into
K3s containerd, and rolls the deployment, all of which must run **on the Dragonfly
host** (its Docker daemon + K3s + kubeconfig, on the LAN). No GitHub-hosted runner
or cloud machine can do it.

So if that runner is offline, deploys don't fail — they **queue indefinitely**
(symptom: pushes to `main` show a Deploy run stuck in *Queued*, and
`kubectl rollout` never happens). Running the runner interactively with `run.cmd`
/ `./run.sh` means it dies on logout or reboot. This makes it durable: it survives
reboots and runs with nobody logged in.

It's two independent problems — keep them separate:
1. **The runner process** must auto-start and self-heal *inside* the distro.
2. **The WSL distro** must boot on Windows startup (WSL distros do **not**
   auto-start), or systemd never comes up.

## Layer 1 — run the runner as a systemd service (inside the distro)

Modern WSL2 supports systemd, which the runner's own `svc.sh` installer uses.

1. Enable systemd. In the **Dragonfly** distro, edit `/etc/wsl.conf`:
   ```ini
   [boot]
   systemd=true
   ```
   Then from Windows, restart the distro:
   ```powershell
   wsl --shutdown
   ```
   Requires WSL >= 0.67.6 (`wsl --version`) on Windows 11 or Windows 10 22H2.
   Confirm after reboot: `systemctl is-system-running` returns `running`/`degraded`
   (not `offline`).

2. Install the runner as a service, from the `actions-runner` directory:
   ```bash
   sudo ./svc.sh install <your-linux-user>   # omit user to use the current one
   sudo ./svc.sh start
   sudo ./svc.sh status
   ```
   The runner now starts with systemd and restarts on crash — no more `run.cmd`.
   (`svc.sh` installs and enables an `actions.runner.*` unit.)

   **No systemd?** Fallback: skip `svc.sh` and have Layer 2 launch the runner
   directly, e.g. `wsl.exe -d Dragonfly -u <user> --cd <runner-dir> -e ./run.sh`.

## Layer 2 — boot the distro on Windows startup

WSL distros don't auto-start on boot, so nothing triggers systemd. Pick one:

- **Task Scheduler (simplest).** Create a task:
  - Trigger: **At startup**
  - Action: `wsl.exe -d Dragonfly` (one boot command — systemd then keeps the
    distro, and the runner, alive)
  - **Run whether user is logged on or not**, **Run with highest privileges**
  - **Run as your user account** (see gotcha below)

- **NSSM Windows service.** Wrap the same command as a real service for the
  cleanest "starts at boot, no login" behavior:
  ```cmd
  nssm install HawksnestRunner "C:\Windows\System32\wsl.exe" "-d Dragonfly"
  nssm set HawksnestRunner ObjectName ".\<your-windows-user>" "<password>"
  nssm start HawksnestRunner
  ```

> **Gotcha:** WSL2 distros are **per-user**, so the auto-start task/service must run
> **as your user account** (with saved credentials), *not* as `SYSTEM` — `SYSTEM`
> can't see your distro. This is also why the LAN portproxy task
> (`windows/portproxy-hawksnest.ps1`) should run under the same account.

## Verify

- GitHub → repo **Settings → Actions → Runners**: the `dragonfly` runner shows
  **Idle** (green), not Offline.
- In the distro: `sudo ./svc.sh status` shows the service `active (running)`.
- Push to `main` (or re-run a queued Deploy): the run leaves *Queued* within seconds
  and `kubectl -n home-automation rollout status deployment/hawksnest` goes Ready.

## Troubleshooting

- **Deploy stuck in *Queued*** → runner offline. Check `wsl -l -v` (is Dragonfly
  *Running*?), then `sudo ./svc.sh status`. Re-running the queued job won't help
  until the runner is back; once it is, the queued run drains on its own.
- **`svc.sh` errors about systemd** → systemd isn't enabled (Layer 1 step 1) or the
  WSL version is too old.
- **Runner online only while logged in** → Layer 2 is missing or its task is set to
  "Run only when user is logged on."
