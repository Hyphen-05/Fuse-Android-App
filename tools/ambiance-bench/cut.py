#!/usr/bin/env python3
"""Cut the block 7 excerpt clips out of the frames the traces were computed from.

Block 7 asks Joe which of two ambiance rules looks steadier. Until 2026-09-12 it played the two
recorded traces on the strip with nothing on screen, and he pointed out the obvious: judging
"steadier" with no picture is judging the lights in a vacuum, which is not how he ever watches
them. So the block now plays the film beside the strip.

The clip has to be **exactly** the frames the trace was computed from, or the light will not
match the picture. That is why this reads `excerpts.json` - written by `AmbianceVideoBench.emit
lab traces`, which is the code that chose the window - rather than re-deriving the offset here.
One source of truth for which moment this is.

    python tools/ambiance-bench/fetch.py --out <frames dir>
    ./gradlew :app:testDebugUnitTest --tests '*AmbianceVideoBench*' \
        -Dambiance.frames=<frames dir> -Dambiance.traces=<path to AmbianceTraces.kt>
    python tools/ambiance-bench/cut.py --frames <frames dir> \
        --excerpts <dir holding excerpts.json>/excerpts.json --out app/src/main/res/raw

The source is Tears of Steel (Blender Foundation, CC-BY 3.0), so the clips can be shipped in the
APK. CC-BY requires the attribution to reach whoever sees them, so it is part of block 7's
briefing rather than a file in the repo - `NOTICE.md` records the licence for the repo's sake.
"""

import argparse
import json
import os
import shutil
import subprocess

import imageio_ffmpeg

# The PNGs are at capture scale (374x168) because that is what ambiance actually samples. Doubling
# them for viewing changes nothing about the measurement and makes the picture watchable on a
# phone held at arm's length. Both dimensions stay even, which H.264 requires.
SCALE = 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frames", required=True, help="the directory fetch.py wrote clips into")
    ap.add_argument("--excerpts", required=True, help="excerpts.json from the bench")
    ap.add_argument("--out", required=True, help="where to write <id>.mp4")
    args = ap.parse_args()

    ff = imageio_ffmpeg.get_ffmpeg_exe()
    os.makedirs(args.out, exist_ok=True)
    excerpts = json.load(open(args.excerpts))

    for e in excerpts:
        src = os.path.join(args.frames, e["id"])
        if not os.path.isdir(src):
            raise SystemExit(f"no frames for {e['id']} at {src} - run fetch.py first")

        # ffmpeg's image2 demuxer counts from -start_number, so the excerpt is selected by starting
        # there and taking exactly as many frames as the trace has steps. Cutting by timestamp
        # instead would leave the alignment at the mercy of rounding.
        staged = os.path.join(args.out, f".{e['id']}-staging")
        shutil.rmtree(staged, ignore_errors=True)
        os.makedirs(staged)
        for i in range(e["frames"]):
            shutil.copyfile(
                os.path.join(src, f"{e['startFrame'] + i:04d}.png"),
                os.path.join(staged, f"{i:04d}.png"),
            )

        dest = os.path.join(args.out, f"{e['id']}.mp4")
        subprocess.run([
            ff, "-v", "error", "-y",
            "-framerate", str(e["fps"]), "-start_number", "0",
            "-i", os.path.join(staged, "%04d.png"),
            "-vf", f"scale=iw*{SCALE}:ih*{SCALE}:flags=lanczos",
            "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
            # Replays always restart from frame 0, which is a keyframe whatever the GOP is, so
            # there is nothing to buy by making every frame one - and these ship in the APK.
            "-g", "20", "-crf", "20",
            "-movflags", "+faststart",
            dest,
        ], check=True)
        shutil.rmtree(staged, ignore_errors=True)
        size = os.path.getsize(dest)
        print(f"  {e['id']}: frames {e['startFrame']}..{e['startFrame'] + e['frames'] - 1} "
              f"-> {dest} ({size // 1024} KB)")


if __name__ == "__main__":
    main()
