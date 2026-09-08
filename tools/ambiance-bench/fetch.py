#!/usr/bin/env python3
"""Build the frame set AmbianceVideoBench runs on.

Downloads Tears of Steel (Blender Foundation, CC-BY 3.0), finds its darkest and brightest
stretches by measuring linear luminance across the whole film, and writes three 30s clips as PNG
sequences shaped like a screen capture: letterboxed into a phone-sized frame at the rate ambiance
actually samples.

Why a real film rather than synthetic content: the shipped rules include a letterbox crop and a
4x4 zone grid, and neither of those does anything to a synthetic aggregate colour. The clips are
picked by measurement, not by eye, so the "dark" ones are dark on the same scale the app sees.

Nothing here goes in the repo - the output is ~150MB. Run it into a scratch directory and point
the bench at that:

    python tools/ambiance-bench/fetch.py --out /tmp/ambiance-frames
    ./gradlew :app:testDebugUnitTest --tests '*AmbianceVideoBench*' -Dambiance.frames=/tmp/ambiance-frames

Requires `pip install imageio-ffmpeg numpy` (the ffmpeg binary comes with the first).
"""

import argparse
import os
import subprocess
import urllib.request

import imageio_ffmpeg
import numpy as np

SOURCE = "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov"

# A capture is screen pixels / 4 (AmbianceCaptureService). This is a landscape phone at that scale;
# the exact number matters less than that the frame is bigger than the picture, so the letterbox
# detector has bars to find - which is the case this bench exists to exercise.
WIDTH, HEIGHT = 374, 168

# max(1000/20fps, pacing 50ms) - what AmbianceProcessor accepts.
FPS = 20
CLIP_SECONDS = 30


def srgb_to_linear(a):
    a = a / 255.0
    return np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)


def luminance_profile(ff, path, fps=2, w=32, h=18):
    """Mean linear luminance of the whole film, sampled at `fps`."""
    cmd = [ff, "-v", "error", "-i", path, "-vf", f"fps={fps},scale={w}:{h}",
           "-pix_fmt", "rgb24", "-f", "rawvideo", "-"]
    raw = subprocess.run(cmd, capture_output=True).stdout
    n = len(raw) // (w * h * 3)
    a = np.frombuffer(raw[:n * w * h * 3], dtype=np.uint8).reshape(n, h, w, 3).astype(np.float64)
    lin = srgb_to_linear(a)
    return (0.2126 * lin[..., 0] + 0.7152 * lin[..., 1] + 0.0722 * lin[..., 2]).mean(axis=(1, 2)), fps


def pick_windows(lum, fps):
    """The two darkest non-overlapping windows, plus the brightest, as start times in seconds."""
    win = int(CLIP_SECONDS * fps)
    mean = np.convolve(lum, np.ones(win) / win, mode="valid")
    darkest = []
    for i in np.argsort(mean):
        t = i / fps
        if all(abs(t - p) > CLIP_SECONDS + 5 for p in darkest):
            darkest.append(float(t))
        if len(darkest) == 2:
            break
    brightest = float(np.argmax(mean) / fps)
    return {"dark_a": min(darkest), "dark_b": max(darkest), "bright": brightest}, mean


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="directory to write frames/<clip>/NNNN.png into")
    ap.add_argument("--video", default=None, help="reuse an already-downloaded source file")
    args = ap.parse_args()

    ff = imageio_ffmpeg.get_ffmpeg_exe()
    os.makedirs(args.out, exist_ok=True)

    video = args.video
    if not video:
        video = os.path.join(args.out, "tears_of_steel_720p.mov")
        if not os.path.exists(video):
            print(f"downloading {SOURCE} (355MB)")
            urllib.request.urlretrieve(SOURCE, video)

    print("measuring luminance across the film")
    lum, fps = luminance_profile(ff, video)
    clips, mean = pick_windows(lum, fps)
    for name, start in sorted(clips.items(), key=lambda kv: kv[1]):
        print(f"  {name:8s} start {start:7.1f}s  mean linear luminance {mean[int(start * fps)]:.5f}")

    vf = (f"fps={FPS},scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=decrease,"
          f"pad={WIDTH}:{HEIGHT}:(ow-iw)/2:(oh-ih)/2:black")
    for name, start in clips.items():
        d = os.path.join(args.out, name)
        os.makedirs(d, exist_ok=True)
        subprocess.run([ff, "-v", "error", "-ss", str(start), "-i", video, "-t", str(CLIP_SECONDS),
                        "-vf", vf, "-start_number", "0", os.path.join(d, "%04d.png")], check=True)
        print(f"  wrote {len(os.listdir(d))} frames to {d}")


if __name__ == "__main__":
    main()
