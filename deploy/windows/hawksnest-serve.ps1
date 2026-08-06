# Expose Hawksnest (and the ntfy push server) over HTTPS on the tailnet via Tailscale Serve.
# Safe to run at logon or by hand; idempotent, and does NOT need Administrator.
#
# WHAT THIS SCRIPT DOES *NOT* DO ANY MORE (changed 2026-08-06):
#   It no longer creates the socat forwarders. They are now permanent, enabled,
#   on-disk systemd units inside the Dragonfly distro (added 2026-07-22 when the
#   host moved to mirrored WSL networking):
#
#       hawksnest-web-fwd.service    socat TCP-LISTEN:8090 -> 127.0.0.1:30080  (nginx pod)
#       hawksnest-ntfy-fwd.service   socat TCP-LISTEN:8391 -> 127.0.0.1:30081  (ntfy pod)
#
#   The previous version created *transient* `systemd-run` units, which was correct
#   before those on-disk units existed and actively harmful afterwards:
#
#     1. It picked the unit name `hawksnest-ntfy-fwd` - identical to the on-disk
#        unit. Its `systemctl stop <unit>` therefore killed the working forwarder,
#        and `systemd-run` then refused to create a transient unit over an existing
#        unit file, so the script threw and left :8391 dead. Net effect: Tailscale
#        Serve :8444 (phone push) 502'd after EVERY logon, with nothing visibly
#        wrong in Home Assistant. Found 2026-08-06; it had been silently recurring
#        on every boot since 2026-07-22.
#     2. For the web path it used the name `hawksnest-fwd` on port 8390, which did
#        NOT collide - so it quietly ran a redundant second socat on :8390 next to
#        the real one on :8090, forever.
#     3. Its `$ForwardPort` default (8390) disagreed with the live Serve mapping
#        (:8443 -> 8090), so anyone trusting this file's defaults would have
#        repointed Hawksnest at the wrong socat.
#
#   So the forwarders are left alone here. This script only verifies they are up
#   and ensures the Serve mappings exist.
#
#   Ports are read from the units themselves rather than hardcoded, so this file
#   cannot drift out of sync with them the way the old defaults did.
#
# Topology:
#   https://<host>.ts.net:8443  ->  127.0.0.1:8090  ->  wsl:30080  (nginx pod -> HA)
#   https://<host>.ts.net:8444  ->  127.0.0.1:8391  ->  wsl:30081  (ntfy pod)
#   (:443 is taken by Magpie's Serve, hence :8443/:8444.)

param(
    [int]$HttpsPort       = 8443,   # Tailscale Serve HTTPS port for Hawksnest
    [int]$NtfyHttpsPort   = 8444,   # Tailscale Serve HTTPS port for ntfy
    [string]$Distribution = "Dragonfly",
    [switch]$CleanupLegacy          # also remove the stale transient hawksnest-fwd unit
)

$ErrorActionPreference = "Stop"
$tailscale = "C:\Program Files\Tailscale\tailscale.exe"

# Read the listen port straight out of the unit definition. If a unit is ever
# re-pointed, this follows it instead of silently disagreeing.
function Get-ForwarderPort([string]$Unit) {
    $exec = wsl.exe -d $Distribution -u root -e bash -lc "systemctl show $Unit -p ExecStart --value 2>/dev/null"
    if ($exec -match 'TCP-LISTEN:(\d+)') { return [int]$Matches[1] }
    return 0
}

function Assert-Forwarder([string]$Unit) {
    $state = (wsl.exe -d $Distribution -u root -e bash -lc "systemctl is-active $Unit 2>&1").Trim()
    if ($state -ne 'active') {
        Write-Warning "$Unit is '$state' - starting it."
        wsl.exe -d $Distribution -u root -e bash -lc "systemctl reset-failed $Unit 2>/dev/null; systemctl start $Unit" | Out-Null
        Start-Sleep -Seconds 2
        $state = (wsl.exe -d $Distribution -u root -e bash -lc "systemctl is-active $Unit 2>&1").Trim()
        if ($state -ne 'active') { throw "$Unit failed to start (state: $state) in $Distribution." }
    }
    $port = Get-ForwarderPort $Unit
    if ($port -eq 0) { throw "Could not read a TCP-LISTEN port from $Unit." }
    $listening = wsl.exe -d $Distribution -e bash -lc "ss -tln 2>/dev/null | grep -c ':$port '"
    if ($listening.Trim() -eq "0") { throw "$Unit is active but nothing is listening on :$port." }
    Write-Host "  $Unit  active, listening on :$port"
    return $port
}

Write-Host "Checking on-disk forwarders in '$Distribution' ..."
$ForwardPort     = Assert-Forwarder 'hawksnest-web-fwd'
$NtfyForwardPort = Assert-Forwarder 'hawksnest-ntfy-fwd'

# The old script's redundant transient unit on :8390. Harmless but confusing -
# it looks like a second, competing web forwarder. Opt-in, so this script stays
# read-only by default.
if ($CleanupLegacy) {
    $legacy = (wsl.exe -d $Distribution -u root -e bash -lc "systemctl is-active hawksnest-fwd 2>&1").Trim()
    if ($legacy -eq 'active') {
        wsl.exe -d $Distribution -u root -e bash -lc "systemctl stop hawksnest-fwd; systemctl reset-failed hawksnest-fwd 2>/dev/null" | Out-Null
        Write-Host "  removed stale transient unit 'hawksnest-fwd' (redundant socat on :8390)"
    } else {
        Write-Host "  no stale 'hawksnest-fwd' transient unit present"
    }
}

# Tailscale Serve state persists in tailscaled across reboots, so these are
# no-ops after the first run. Kept for first-time setup and drift repair.
# NOTE: one target per port - re-running with a different port silently
# OVERWRITES whatever else was mapped there (this is how Remnant clobbered
# Hawksnest's :8443 once; see the host's OPERATIONS.md).
& $tailscale serve --bg --https=$HttpsPort     "http://127.0.0.1:$ForwardPort"     | Out-Null
& $tailscale serve --bg --https=$NtfyHttpsPort "http://127.0.0.1:$NtfyForwardPort" | Out-Null

$dns = (& $tailscale status --json | ConvertFrom-Json).Self.DNSName.TrimEnd('.')
Write-Host "Hawksnest reachable at https://${dns}:$HttpsPort  (-> 127.0.0.1:$ForwardPort -> wsl:30080)"
Write-Host "ntfy      reachable at https://${dns}:$NtfyHttpsPort  (-> 127.0.0.1:$NtfyForwardPort -> wsl:30081)"
