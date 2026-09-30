#!/bin/sh
# Renders the Android app's direct-stream settings (the camera account plus each wired camera's
# LAN IP) from the frigate-credentials Secret, so no phone has to be set up by hand.
#
# Runs as the `render-direct-streams` initContainer (deploy/k8s/deployment.yaml) with the Secret
# mounted at $SECRETS, and writes $OUT into a memory-backed volume that the nginx container serves
# at /hawksnest/direct-streams (deploy/nginx.conf), only to requests carrying a Home Assistant
# token HA accepts. The long-running nginx container never sees the Secret itself, only this file.
# Nothing here echoes a value: the log says how many cameras were rendered, never what.
#
# It never fails the pod. Anything missing means no file, the endpoint answers 404, and the app
# reads that as "not provided", which is exactly how it behaved before this existed.
#
# Keys read (the same ones Frigate's camera paths use, see hawksnest-automation's frigate config):
#   FRIGATE_REOLINK_USER, FRIGATE_REOLINK_PASSWORD   the fleet's camera account
#   FRIGATE_REOLINK_IP_<CAMERA>                      one per camera; <CAMERA> lowercased is the
#                                                    Frigate / go2rtc camera name the app keys on
# The account and password are base64-encoded in the output so any character survives JSON.

SECRETS=${SECRETS:-/secrets}
OUT=${OUT:-/out/direct-streams.json}

rm -f "$OUT"

if [ ! -r "$SECRETS/FRIGATE_REOLINK_USER" ] || [ ! -r "$SECRETS/FRIGATE_REOLINK_PASSWORD" ]; then
    echo "direct-streams: no camera account in the secret, not rendering"
    exit 0
fi

b64() { base64 < "$1" | tr -d '\n'; }

cams=""
count=0
for f in "$SECRETS"/FRIGATE_REOLINK_IP_*; do
    [ -r "$f" ] || continue
    key=$(basename "$f")
    name=$(printf '%s' "${key#FRIGATE_REOLINK_IP_}" | tr 'A-Z' 'a-z')
    # The Home Hub serves its battery cameras as channels on ONE address, and the app always asks
    # a camera for channel 1, so offering it would play the wrong camera.
    [ "$name" = "home_hub" ] && continue
    printf '%s' "$name" | grep -Eq '^[a-z0-9_]+$' || continue
    ip=$(tr -d ' \t\r\n' < "$f")
    printf '%s' "$ip" | grep -Eq '^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$' || continue
    cams="$cams${cams:+,}\"$name\":\"$ip\""
    count=$((count + 1))
done

if [ "$count" -eq 0 ]; then
    echo "direct-streams: no camera addresses in the secret, not rendering"
    exit 0
fi

umask 022
tmp="$OUT.tmp"
if printf '{"version":1,"userB64":"%s","passB64":"%s","cameras":{%s}}\n' \
    "$(b64 "$SECRETS/FRIGATE_REOLINK_USER")" "$(b64 "$SECRETS/FRIGATE_REOLINK_PASSWORD")" "$cams" > "$tmp" \
    && mv "$tmp" "$OUT"; then
    echo "direct-streams: rendered $count camera(s)"
else
    rm -f "$tmp" "$OUT"
    echo "direct-streams: could not write $OUT, not rendering"
fi
exit 0
