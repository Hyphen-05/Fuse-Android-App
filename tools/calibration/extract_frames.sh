#!/usr/bin/env bash
# Decode every capture video to a raw per-frame RGB grid (64x36, area-averaged).
# Output: tools/calibration/derived/frames/<name>.rgb  — 64*36*3 bytes per frame, no header.
# Regenerable; gitignored. Needs the ffmpeg that ships with the pip package imageio-ffmpeg.
set -euo pipefail
FF="${FF:-$(python -c 'import imageio_ffmpeg;print(imageio_ffmpeg.get_ffmpeg_exe())')}"
OUT=tools/calibration/derived/frames
mkdir -p "$OUT"
W=64; H=36
for f in captures/*.mp4; do
  n=$(basename "$f" .mp4)
  o="$OUT/$n.rgb"
  [ -s "$o" ] && { echo "skip $n"; continue; }
  echo "decoding $n"
  "$FF" -hide_banner -loglevel error -i "$f" \
    -vf "scale=$W:$H:flags=area" -f rawvideo -pix_fmt rgb24 - > "$o"
done
echo done
