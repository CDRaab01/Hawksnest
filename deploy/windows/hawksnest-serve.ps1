# Expose Hawksnest over HTTPS on the tailnet via Tailscale Serve. Run at logon
# (Administrator not required for `tailscale serve`; the socat step uses `wsl -u root`).
#
# Why this replaces portproxy-hawksnest.ps1:
#   The Dragonfly WSL distro now runs in MIRRORED networking mode (its interfaces
#   ARE the host's IPs). The old `netsh portproxy 8090 -> <wsl-eth0>:30080` model
#   assumed NAT (a private 172.x WSL IP) and breaks: there is no such interface,
#   and the k3s NodePort (:30080) is an iptables DNAT rule, not a real listening
#   socket, so it is NOT surfaced to the Windows host by mirrored mode.
#
#   The fix: run a real listening socket inside WSL (socat on :8390 -> the
#   NodePort's 127.0.0.1:30080, which works inside the distro), which mirrored
#   mode DOES surface to the Windows host at 127.0.0.1:8390. Tailscale Serve then
#   fronts that with a real Let's Encrypt cert:
#       https://<host>.ts.net:8443  ->  127.0.0.1:8390  ->  wsl:30080 (nginx pod) -> HA
#   (:443 is already taken by Magpie's Serve, so Hawksnest uses :8443.)
#   The same pattern fronts the self-hosted ntfy push server (hawksnest-automation
#   repo) so phones can subscribe over TLS:
#       https://<host>.ts.net:8444  ->  127.0.0.1:8391  ->  wsl:30081 (ntfy pod)
param(
    [int]$ForwardPort = 8390,     # WSL socat listen port (surfaced to host loopback)
    [int]$NodePort    = 30080,    # k3s NodePort (deploy/k8s/service.yaml)
    [int]$HttpsPort   = 8443,     # Tailscale Serve HTTPS port for Hawksnest
    [int]$NtfyForwardPort = 8391, # WSL socat listen port for ntfy
    [int]$NtfyNodePort    = 30081,# ntfy k3s NodePort (hawksnest-automation base/ntfy/service.yaml)
    [int]$NtfyHttpsPort   = 8444, # Tailscale Serve HTTPS port for ntfy
    [string]$Distribution = "Dragonfly"
)

$ErrorActionPreference = "Stop"
$tailscale = "C:\Program Files\Tailscale\tailscale.exe"

# Start (or restart) a socat forwarder as a managed systemd transient unit so it
# survives the launching `wsl.exe` exiting. Idempotent.
function Start-Forwarder([string]$Unit, [int]$Listen, [int]$Target) {
    $socat = "systemctl stop $Unit 2>/dev/null; systemctl reset-failed $Unit 2>/dev/null; " +
             "systemd-run --unit=$Unit --collect /usr/bin/socat " +
             "TCP-LISTEN:$Listen,fork,reuseaddr TCP:127.0.0.1:$Target"
    wsl.exe -d $Distribution -u root -e bash -c $socat | Out-Null
    Start-Sleep -Seconds 1
    $listening = wsl.exe -d $Distribution -e bash -c "ss -tlnp 2>/dev/null | grep -c ':$Listen '"
    if ($listening.Trim() -eq "0") { throw "socat unit $Unit failed to listen on :$Listen in $Distribution." }
}

# 1) Forwarders: Hawksnest nginx pod, and the ntfy push server.
Start-Forwarder -Unit "hawksnest-fwd"      -Listen $ForwardPort     -Target $NodePort
Start-Forwarder -Unit "hawksnest-ntfy-fwd" -Listen $NtfyForwardPort -Target $NtfyNodePort

# 2) Ensure Tailscale Serve fronts each with HTTPS. `serve --bg` persists in
#    tailscaled state across reboots, so this is a no-op after the first run.
& $tailscale serve --bg --https=$HttpsPort     "http://127.0.0.1:$ForwardPort"     | Out-Null
& $tailscale serve --bg --https=$NtfyHttpsPort "http://127.0.0.1:$NtfyForwardPort" | Out-Null

$dns = (& $tailscale status --json | ConvertFrom-Json).Self.DNSName.TrimEnd('.')
Write-Host "Hawksnest reachable at https://${dns}:$HttpsPort  (-> 127.0.0.1:$ForwardPort -> wsl:$NodePort)"
Write-Host "ntfy      reachable at https://${dns}:$NtfyHttpsPort  (-> 127.0.0.1:$NtfyForwardPort -> wsl:$NtfyNodePort)"
