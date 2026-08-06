#!/usr/bin/env bash
# Starts one stream per connected monitor, each on its own port.
#
# Monitor order and port assignment come from monitors.py, which is the single
# source of truth shared with the input agent and the headset app. Do not
# compute them here as well: if the orderings ever disagree, clicks land on the
# wrong screen and nothing says so.
#
# Stop everything with Ctrl-C, or `sudo pkill -x ffmpeg`.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
pids=()

cleanup() {
    echo
    echo "stopping streams"
    for pid in "${pids[@]:-}"; do
        [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
    done
    # The workers run ffmpeg under sudo, so killing the wrapper is not enough.
    sudo pkill -x ffmpeg 2>/dev/null || true
    exit 0
}
trap cleanup INT TERM

"$HERE/monitors.py"
echo

while read -r index connector crtc port width height; do
    [ -z "$index" ] && continue
    if [ "$crtc" = "None" ]; then
        echo "skipping $connector: no CRTC (needs sudo to read DRM state)" >&2
        continue
    fi

    echo "monitor $index $connector -> port $port (crtc $crtc)"
    CRTC="$crtc" PORT="$port" WIDTH="$width" HEIGHT="$height" \
        "$HERE/stream.sh" > "/tmp/linux-vr-stream-$index.log" 2>&1 &
    pids+=($!)
done < <("$HERE/monitors.py" --json | python3 -c '
import json, sys
for m in json.load(sys.stdin)["monitors"]:
    print(m["index"], m["connector"], m["crtc"], m["port"], m["width"], m["height"])
')

echo
echo "logs: /tmp/linux-vr-stream-<index>.log"
echo "Ctrl-C to stop"
wait
