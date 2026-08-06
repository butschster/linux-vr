#!/usr/bin/env bash
# Тестовый материал для вехи B: оценка читаемости текста в composition layer.
#
# Смысл не в «работает ли декодер», а в единственном вопросе, ради которого
# затевался свой клиент: с какого кегля текст перестаёт читаться в гарнитуре.
#
# Поэтому в кадре — строки разного размера, от 10 до 32 пикселей, плюс движущийся
# элемент: статичная картинка не покажет артефактов компрессии и репроекции.

set -euo pipefail

OUT="${1:-$(dirname "$0")/../assets/testpattern.mp4}"
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf
W=2560
H=1440
FPS=90
SECONDS_LEN=30

mkdir -p "$(dirname "$OUT")"

[ -f "$FONT" ] || { echo "Шрифт не найден: $FONT" >&2; exit 1; }

# Строки разного кегля. Смесь латиницы, кириллицы и символов кода —
# именно на них видно, где начинает разваливаться субпиксельная детализация.
SAMPLE='The quick brown fox jumps over the lazy dog 0123456789'
SAMPLE_RU='Съешь ещё этих мягких французских булок да выпей чаю'
SAMPLE_CODE='if (ptr != nullptr) { return ptr->value * 2; } // комментарий'

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

# Движущаяся полоса: без неё не видно ни артефактов межкадрового сжатия,
# ни рассинхрона между частотой стрима и частотой гарнитуры.
filters+="drawbox=x='mod(t*400\,${W})':y=0:w=6:h=${H}:color=cyan@0.8:t=fill,"

# Счётчик кадров — чтобы на глаз ловить пропуски и повторы
filters+="drawtext=fontfile=${FONT}:text='%{n}':fontsize=48:fontcolor=yellow:"
filters+="x=${W}-260:y=${H}-90"

echo "Генерирую ${W}x${H}@${FPS}, ${SECONDS_LEN} с -> ${OUT}"

ffmpeg -hide_banner -loglevel error -stats -y \
    -vaapi_device /dev/dri/renderD128 \
    -f lavfi -i "color=c=#101014:s=${W}x${H}:r=${FPS}:d=${SECONDS_LEN}" \
    -vf "${filters},format=nv12,hwupload" \
    -c:v h264_vaapi -profile:v high -rc_mode CBR -b:v 100M -bf 0 -g 90 \
    "$OUT"

ls -lh "$OUT"
