"""Decode the whole calibration video once, into per-frame RGB caches.

`analyse_ramp.py` only ever needed the first 105s (the brightness ramp), so its caches stop there.
Latency and rate-ramp analysis need the rest of the recording, and decoding 775 MB of 1080p60 takes
minutes — so do it once here and let every other script read the cache.

Writes `cache_full_<region>.bin`: three bytes (r, g, b) per frame, one frame after another, being
the mean colour of that region's wall patch.
"""
import subprocess, sys, os

FFMPEG = r"C:\Users\attgm\AppData\Roaming\Python\Python314\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"
VIDEO = "PXL_20260816_011348194.mp4"

# Same patches as analyse_ramp.py — wall, never the emitters, which clip to white.
REGIONS = {
    "fireworks": (150, 400, 300, 300),
    "bar": (1500, 600, 300, 200),
}


def decode(name, region):
    out = f"cache_full_{name}.bin"
    if os.path.exists(out):
        print(f"{name}: {out} already exists, skipping")
        return
    x, y, w, h = region
    cmd = [
        FFMPEG, "-v", "error", "-i", VIDEO,
        "-vf", f"crop={w}:{h}:{x}:{y},scale=1:1",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
    ]
    raw = subprocess.run(cmd, capture_output=True).stdout
    open(out, "wb").write(raw)
    print(f"{name}: {len(raw) // 3} frames -> {out}")


for name, region in REGIONS.items():
    decode(name, region)
