#!/usr/bin/env bash
# Generates every launcher-icon and TV-banner asset from two source images.
#
#   tools/branding/alive-player-banner.png   16:9 lockup (the full "alive● PLAYER"
#                                            artwork). Feeds the Android TV banner,
#                                            which is what the leanback launcher
#                                            actually displays. Min 1280x720.
#   tools/branding/alive-player-icon.png     1:1 square. Feeds the launcher icon.
#                                            Must be square artwork — the wide lockup
#                                            is illegible shrunk into a square, so use
#                                            just the "alive●" mark. Min 1024x1024.
#
# Usage:  ./tools/generate-icons.sh
# Needs:  ImageMagick 7 (`magick`) or 6 (`convert`).

set -euo pipefail
cd "$(dirname "$0")/.."

BRAND=tools/branding
RES=app/src/main/res
BANNER_SRC="$BRAND/alive-player-banner.png"
ICON_SRC="$BRAND/alive-player-icon.png"

if command -v magick >/dev/null 2>&1; then IM="magick"
elif command -v convert >/dev/null 2>&1; then IM="convert"
else echo "ERROR: ImageMagick not found (need 'magick' or 'convert')." >&2; exit 1; fi

for f in "$BANNER_SRC" "$ICON_SRC"; do
  [ -f "$f" ] || { echo "ERROR: missing $f — see the header of this script." >&2; exit 1; }
done

# ── TV banner ─────────────────────────────────────────────────────────────────
# xhdpi is the density Android TV actually uses; the others exist so the banner
# isn't upscaled from a smaller bucket on non-TV form factors.
# Replaces the old vector placeholder (a geometric approximation of the wordmark).
echo "Banner:"
for spec in "mdpi:320x180" "hdpi:480x270" "xhdpi:640x360" "xxhdpi:960x540"; do
  d="${spec%%:*}"; size="${spec##*:}"
  mkdir -p "$RES/drawable-$d"
  $IM "$BANNER_SRC" -resize "$size!" -strip "$RES/drawable-$d/ic_tv_banner.png"
  echo "  drawable-$d/ic_tv_banner.png ($size)"
done
# The vector placeholder must go, or drawable/ic_tv_banner.xml keeps winning for
# any density bucket that doesn't have a PNG.
rm -f "$RES/drawable/ic_tv_banner.xml" && echo "  removed drawable/ic_tv_banner.xml (placeholder)"

# ── Launcher icon ─────────────────────────────────────────────────────────────
echo "Launcher icon:"
for spec in "mdpi:48" "hdpi:72" "xhdpi:96" "xxhdpi:144" "xxxhdpi:192"; do
  d="${spec%%:*}"; px="${spec##*:}"
  mkdir -p "$RES/mipmap-$d"
  $IM "$ICON_SRC" -resize "${px}x${px}!" -strip "$RES/mipmap-$d/ic_launcher.png"
  # Round variant: same art, circular mask, for launchers that request it.
  $IM "$ICON_SRC" -resize "${px}x${px}!" \
      \( +clone -alpha extract -draw "fill black polygon 0,0 0,$px $px,0 fill white circle $((px/2)),$((px/2)) $((px/2)),0" \
         -alpha off \) -compose CopyOpacity -composite -strip \
      "$RES/mipmap-$d/ic_launcher_round.png"
  echo "  mipmap-$d/ic_launcher{,_round}.png (${px}px)"
done

# ── Adaptive icon foreground (API 26+) ────────────────────────────────────────
# 108dp canvas, but only the centre 72dp is guaranteed visible — the launcher may
# mask or animate the rest. So the artwork is scaled to the 66% safe zone and
# padded, otherwise round/squircle masks clip the mark.
echo "Adaptive foreground:"
for spec in "mdpi:108" "hdpi:162" "xhdpi:216" "xxhdpi:324" "xxxhdpi:432"; do
  d="${spec%%:*}"; px="${spec##*:}"
  inner=$(( px * 66 / 100 ))
  mkdir -p "$RES/drawable-$d"
  $IM "$ICON_SRC" -resize "${inner}x${inner}!" \
      -background none -gravity center -extent "${px}x${px}" -strip \
      "$RES/drawable-$d/ic_launcher_foreground.png"
  echo "  drawable-$d/ic_launcher_foreground.png (${px}px, art ${inner}px)"
done
# Same shadowing problem as the banner: the vector foreground would win for any
# density without a PNG. The background stays vector — it's a flat colour.
rm -f "$RES/drawable/ic_launcher_foreground.xml" && echo "  removed drawable/ic_launcher_foreground.xml (placeholder)"

echo
echo "Done. Build and confirm: ./gradlew :app:assembleDebug"
