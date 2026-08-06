#!/usr/bin/env bash
# Test material for milestone B: judging text readability in a composition layer.
#
# The point is not "does the decoder work" but the single question the custom
# client exists for: at what font size does text stop being readable in the
# headset.
#
# Hence lines at sizes from 10 to 32 pixels, plus a moving element: a static
# image would not reveal compression or reprojection artefacts.

set -euo pipefail

OUT="${1:-$(dirname "$0")/../assets/testpattern.mp4}"
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf
W=2560
H=1440
FPS=90
SECONDS_LEN=30

mkdir -p "$(dirname "$OUT")"

[ -f "$FONT" ] || { echo "Font not found: $FONT" >&2; exit 1; }

# Mixed Latin, Cyrillic and code-like punctuation — these are where subpixel
# detail falls apart first.
SAMPLE='The quick brown fox jumps over the lazy dog 0123456789'
SAMPLE_RU='Съешь ещё этих мягких французских булок да выпей чаю'
SAMPLE_CODE='if (ptr != nullptr) { return ptr->value * 2; } // comment'

filters=""
y=80
for size in 32 28 24 20 18 16 14 12 11 10; do
    for text in "$SAMPLE" "$SAMPLE_RU" "$SAMPLE_CODE"; do
        esc=${text//:/\\:}
        esc=${esc//\'/\\\\\\\'}
        filters+="drawtext=fontfile=${FONT}:text='${size}px  ${esc}':"
        filters+="fontsize=${size}:fontcolor=white:x=60:y=${y},"
        y=$((y + size + 6))
    done
    y=$((y + 14))
done

# A sweeping bar: without it neither inter-frame compression artefacts nor a
# mismatch between stream rate and headset rate would be visible.
filters+="drawbox=x='mod(t*400\,${W})':y=0:w=6:h=${H}:color=cyan@0.8:t=fill,"

# Frame counter — to spot drops and repeats by eye
filters+="drawtext=fontfile=${FONT}:text='%{n}':fontsize=48:fontcolor=yellow:"
filters+="x=${W}-260:y=${H}-90"

echo "Generating ${W}x${H}@${FPS}, ${SECONDS_LEN}s -> ${OUT}"

ffmpeg -hide_banner -loglevel error -stats -y \
    -vaapi_device /dev/dri/renderD128 \
    -f lavfi -i "color=c=#101014:s=${W}x${H}:r=${FPS}:d=${SECONDS_LEN}" \
    -vf "${filters},format=nv12,hwupload" \
    -c:v h264_vaapi -profile:v high -rc_mode CBR -b:v 100M -bf 0 -g 90 \
    "$OUT"

ls -lh "$OUT"
