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

# FPS must match the DISPLAY refresh rate, not the headset's.
#
# Asking kmsgrab for more than the display produces breaks timestamp generation
# outright: capturing a 60 Hz output at 90 gives ~11000 duplicated frames with
# output time frozen at 0.01 s, and the client sees a single still frame.
#
# The mismatch with the headset is harmless, and that is the point of using a
# composition layer: the layer is world-locked and the compositor reprojects it
# at 90 Hz regardless of the stream rate.
FPS="${FPS:-60}"
# Constant quality, not constant bitrate.
#
# A desktop is mostly static, and CBR is the wrong trade for it: an unchanged
# frame still gets padded to the target bitrate. Measured with CBR 100M on a
# still desktop — 341 of 358 frames were duplicates, each padded to a megabyte,
# the Wi-Fi link could not carry it, ffmpeg blocked on the socket write and the
# whole pipeline ran at 0.49x real time. The stream fell behind reality by
# several seconds and looked frozen.
#
# Under CQP a duplicate encodes as an all-skip frame of a few hundred bytes,
# so a still desktop costs almost nothing and only real changes cost bitrate.
# Lower QP means better quality and more bits; 23 is a reasonable start for text.
QP="${QP:-23}"
DEVICE="${DEVICE:-/dev/dri/card1}"
CRTC="${CRTC:-}"

crtc_args=()
[ -n "$CRTC" ] && crtc_args=(-crtc_id "$CRTC")

echo "Capturing ${WIDTH}x${HEIGHT}@${FPS}, CQP ${QP}, port ${PORT}"
echo "Connected outputs (set CRTC=<id> if the wrong monitor is captured):"
for c in /sys/class/drm/card*/; do
    n=$(basename "$c")
    s=$(cat "$c/status" 2>/dev/null || true)
    [ "$s" = "connected" ] && echo "    $n  $(head -1 "$c/modes" 2>/dev/null)"
done
echo
echo "NOTE: the hardware cursor lives on a separate KMS plane and is NOT"
echo "captured. Mouse movement will be invisible until the input back-channel"
echo "lands and the in-headset pointer takes over."
echo

# -g equals FPS: one IDR per second. A client connecting at an arbitrary moment
# waits at most one second for a picture. More often than that means extra
# bitrate spikes, which over Wi-Fi turn into dropped frames.
#
# -bf 0 is mandatory: B-frames add reordering latency, and their bitrate saving
# is not needed here.
# ffmpeg with listen=1 serves exactly one connection and exits when the client
# goes away, so every client restart would otherwise need the host restarted
# too. Loop instead.
trap 'echo; echo "stopping"; exit 0' INT TERM

while true; do
    echo "waiting for a client on :${PORT}"
    sudo ffmpeg -hide_banner -loglevel warning -stats \
        -device "$DEVICE" -f kmsgrab -framerate "$FPS" "${crtc_args[@]}" -i - \
        -vf "hwmap=derive_device=vaapi,scale_vaapi=w=${WIDTH}:h=${HEIGHT}:format=nv12" \
        -c:v h264_vaapi -profile:v high -rc_mode CQP -global_quality "$QP" \
        -bf 0 -g "$FPS" \
        -flags +low_delay -fflags +nobuffer -flush_packets 1 \
        -f h264 "tcp://0.0.0.0:${PORT}?listen=1&listen_timeout=-1" || true
    echo "client disconnected"
    sleep 1
done
