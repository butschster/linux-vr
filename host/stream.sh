#!/usr/bin/env bash
# Live desktop stream to the headset.
#
# This is transport for the concept, not the final one. There is no FEC, no
# bitrate adaptation and no loss recovery — it works on a wire or on clean
# Wi-Fi and falls apart under packet loss. In the final version Sunshine plus
# moonlight-common-c takes this role; the client half does not change, because
# receiving NAL units and feeding MediaCodec is the same under any transport.
#
# Capture goes through KMS: ffmpeg gets the framebuffer as a DMA-BUF and hands
# it straight to VAAPI. There is no copy of the frame through system memory —
# the same 2-4 ms the budget does not have. The price is sudo: kmsgrab requires
# CAP_SYS_ADMIN.

set -euo pipefail

PORT="${PORT:-9100}"
WIDTH="${WIDTH:-2560}"
HEIGHT="${HEIGHT:-1440}"
FPS="${FPS:-90}"
BITRATE="${BITRATE:-100M}"
DEVICE="${DEVICE:-/dev/dri/card1}"
CRTC="${CRTC:-}"

crtc_args=()
[ -n "$CRTC" ] && crtc_args=(-crtc_id "$CRTC")

echo "Capturing ${WIDTH}x${HEIGHT}@${FPS}, ${BITRATE}, port ${PORT}"
echo "Connected outputs (set CRTC=<id> if the wrong monitor is captured):"
for c in /sys/class/drm/card*/; do
    n=$(basename "$c")
    s=$(cat "$c/status" 2>/dev/null || true)
    [ "$s" = "connected" ] && echo "    $n"
done
echo

# -g equals FPS: one IDR per second. A client connecting at an arbitrary moment
# waits at most one second for a picture. More often than that means extra
# bitrate spikes, which over Wi-Fi turn into dropped frames.
#
# -bf 0 is mandatory: B-frames add reordering latency, and their bitrate saving
# is not needed here.
exec sudo ffmpeg -hide_banner -loglevel warning -stats \
    -device "$DEVICE" -f kmsgrab -framerate "$FPS" "${crtc_args[@]}" -i - \
    -vf "hwmap=derive_device=vaapi,scale_vaapi=w=${WIDTH}:h=${HEIGHT}:format=nv12" \
    -c:v h264_vaapi -profile:v high -rc_mode CBR -b:v "$BITRATE" \
    -bf 0 -g "$FPS" \
    -flags +low_delay -fflags +nobuffer -flush_packets 1 \
    -f h264 "tcp://0.0.0.0:${PORT}?listen=1&listen_timeout=-1"
