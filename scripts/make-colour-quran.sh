#!/usr/bin/env bash
# Builds quran-13-line-colour.pdf (847 pages, ~94 MB) from the colour-coded 13-line Qur'an PDF
# uploaded to the "quran-colour" release. The source has 4 front pages and 24 back pages:
# its pages 5..851 are the 847 printed pages, one for one. Each page is rendered 1000 px wide
# and reduced to 64 colours, which keeps the tajweed colours sharp at about a third of the size.
set -euo pipefail
SRC="$1"; OUT="$2"; WORK="$(mktemp -d)"
PAGES=$(pdfinfo "$SRC" 2>/dev/null | awk '/^Pages:/{print $2}')
[ "$PAGES" = 875 ] || { echo "Expected 875 pages in the source PDF, found $PAGES"; exit 1; }
seq 5 851 | xargs -P "$(nproc)" -I{} sh -c '
  n=$(printf %03d $(( {} - 4 )))
  pdftoppm -q -f {} -l {} -scale-to-x 1000 -scale-to-y -1 -png "$0" "$1/r$n"
  f=$(ls "$1"/r$n*.png); convert "$f" -colors 64 -define png:compression-level=9 "$1/c$n.png"; rm "$f"' "$SRC" "$WORK"
python3 -c "
import img2pdf,glob,sys
fs=sorted(glob.glob(sys.argv[1]+'/c*.png'));assert len(fs)==847,len(fs)
open(sys.argv[2],'wb').write(img2pdf.convert(fs))" "$WORK" "$OUT"
[ "$(pdfinfo "$OUT" | awk '/^Pages:/{print $2}')" = 847 ]
ls -l "$OUT"
