#!/usr/bin/env bash
# Собирает иконку приложения из исходного изображения.
#
# Исходник — квадратная картинка (icon-source.png). Скрипт добавляет поля,
# срезает углы по скруглению macOS и упаковывает все нужные размеры в .icns.
set -euo pipefail
cd "$(dirname "$0")"

SOURCE="${1:-icon-source.png}"
OUT="live-translator.icns"

python3 - "$SOURCE" <<'PY'
import sys
from PIL import Image, ImageDraw

SIZE, INSET = 1024, 64
side = SIZE - 2 * INSET

tile = Image.open(sys.argv[1]).convert('RGB').resize((side, side), Image.LANCZOS)

# Маска строится вчетверо крупнее и уменьшается — так край получается гладким.
mask = Image.new('L', (side * 4, side * 4), 0)
ImageDraw.Draw(mask).rounded_rectangle(
    [0, 0, side * 4 - 1, side * 4 - 1], radius=int(side * 4 * 0.2237), fill=255)
mask = mask.resize((side, side), Image.LANCZOS)

canvas = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
canvas.paste(tile, (INSET, INSET), mask)
canvas.save('icon-1024.png')
PY

rm -rf icon.iconset && mkdir icon.iconset
for s in 16 32 128 256 512; do
  sips -z "$s" "$s" icon-1024.png --out "icon.iconset/icon_${s}x${s}.png" >/dev/null
  sips -z $((s * 2)) $((s * 2)) icon-1024.png --out "icon.iconset/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns icon.iconset -o "$OUT"
rm -rf icon.iconset
cp "$OUT" ../src/main/resources/
echo "готово: $OUT"
