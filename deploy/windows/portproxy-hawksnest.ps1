# Expose the Hawksnest NodePort to the LAN / Tailscale, mirroring the HA
# portproxy in hawksnest-automation. WSL2's IP changes on reboot, so this must
# re-run at logon (add it to the host's boot task next to portproxy-ha.ps1).
# Run as Administrator.
param(
    [int]$ListenPort = 8090,      # Clients reach Hawksnest here (LAN / Tailscale).
                                  # Not 8080 — SABnzbd's default already owns that on this host.
    [int]$NodePort   = 30080,     # Must match deploy/k8s/service.yaml nodePort
    [string]$Distribution = "Dragonfly"
)

$ErrorActionPreference = "Stop"

# Discover the current WSL2 IP for the distro's eth0.
$wslIp = (wsl -d $Distribution -- hostname -I).Trim().Split(" ")[0]
if (-not $wslIp) { throw "Could not determine WSL2 IP for distro '$Distribution'." }

# Idempotent: drop any prior mapping on this listen port, then re-add.
netsh interface portproxy delete v4tov4 listenport=$ListenPort listenaddress=0.0.0.0 2>$null | Out-Null
netsh interface portproxy add v4tov4 `
    listenport=$ListenPort listenaddress=0.0.0.0 `
    connectport=$NodePort connectaddress=$wslIp

# Open the firewall for the listen port (idempotent).
$ruleName = "Hawksnest ($ListenPort)"
if (-not (Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound `
        -Action Allow -Protocol TCP -LocalPort $ListenPort | Out-Null
}

Write-Host "Hawksnest reachable at http://<this-host>:$ListenPort  (-> ${wslIp}:$NodePort)"
