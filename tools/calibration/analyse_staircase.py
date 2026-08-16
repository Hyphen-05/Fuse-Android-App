"""Minimum write spacing: how close can two writes be before the second one is lost?

Reads the `spacing_staircase` sequence's CSV plus a video of the wall, and classifies what the strip
settled on after each two-write burst:

    blue  -> both writes rendered
    red   -> the second write was dropped
    black -> both were dropped

Bin those by the *achieved* gap between the two writes (never the requested one — coroutine `delay`
is not exact at 2ms, and the CSV records what really happened), and the drop rate against spacing is
the answer. `StripLimits.minWriteSpacingMs` is the spacing at which the drop rate reaches zero.

Deliberately reads a settled colour rather than an edge, so a 30 or 60fps camera is enough and the
±50ms delivery jitter that defeated `analyse_rate_light.py` cannot reach the result: the burst holds
its final colour for 700ms, which is 20+ frames even at 30fps.

Usage: put the video next to the CSV, set VIDEO below, run from that folder.
"""
import csv, os, subprocess, sys
from collections import defaultdict

FFMPEG = r"C:\Users\attgm\AppData\Roaming\Python\Python314\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"
VIDEO = "staircase.mp4"
FPS = 29.61   # moto g play, 1080p — plenty, since each burst holds its answer for ~20 frames

# Wall only, and clear of the emitters: an LED in the patch saturates toward white and dilutes the
# red/blue ratio the classification depends on. Two patches either side of the starburst, so a
# result has to appear in both to be believed.
REGIONS = {
    "wall_left": (120, 620, 260, 260),
    "wall_right": (880, 450, 240, 240),
}

# Where in the 700ms hold to read the colour: past any settling, before the next burst's reset.
READ_FROM_MS = 250
READ_TO_MS = 600


def sample(name, region):
    cache = f"cache_stair_{name}.bin"
    if os.path.exists(cache):
        raw = open(cache, "rb").read()
    else:
        x, y, w, h = region
        raw = subprocess.run(
            [FFMPEG, "-v", "error", "-i", VIDEO,
             "-vf", f"crop={w}:{h}:{x}:{y},scale=1:1",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            capture_output=True).stdout
        open(cache, "wb").write(raw)
    return [tuple(raw[i:i + 3]) for i in range(0, len(raw) - 2, 3)]


def luma(px):
    return 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2]


def find_sync(series):
    """First of the three opening flashes: white at rest, so find the drop to black, then the rise.

    **Do not trust this to better than a few hundred ms.** On the 2026-08-16 staircase footage it
    latched onto the *second* flash, putting every prediction 10 frames (337ms — almost exactly the
    400ms flash spacing) late. It is used here only to find roughly where the run starts; every
    burst is then anchored on its own transition by [anchor_of], which is immune to both that error
    and to frame-rate drift over the run.
    """
    head = int(FPS * 0.5)
    initial = sum(luma(p) for p in series[:head]) / head
    dark_at = next((i for i, p in enumerate(series) if luma(p) < initial * 0.4), None)
    if dark_at is None:
        return None
    return next((i for i in range(dark_at, len(series)) if luma(series[i]) > initial * 0.6), None)


def anchor_of(series, predicted):
    """The frame this burst actually lit up on, searched near [predicted].

    Reading a burst 250-600ms after its second write leaves only 100ms of margin before the next
    burst's reset, so an alignment error of a third of a second reads black — which is what the
    first pass over this footage did, on 5% of bursts, while silently reading the blue hold when it
    meant to read the red window on the rest.
    """
    window = series[predicted - 20:predicted + 25]
    if len(window) < 40:
        return None
    lit = max(luma(p) for p in window)
    dark = min(luma(p) for p in window)
    if lit - dark < 20:
        return None
    threshold = dark + (lit - dark) * 0.4
    rise = next((i for i in range(len(window)) if luma(window[i]) > threshold), None)
    return None if rise is None else predicted - 20 + rise


def classify(window):
    """red / blue / black / unclear, from the channel balance rather than the brightness.

    Red and blue can reflect off a wall at similar luma, so brightness cannot separate them — but
    the channel ratio can, and it survives the strip being dimmer than expected.
    """
    if not window:
        return "unclear"
    r = sum(p[0] for p in window) / len(window)
    g = sum(p[1] for p in window) / len(window)
    b = sum(p[2] for p in window) / len(window)
    if max(r, g, b) < 12:
        return "black"
    if r > b * 1.4:
        return "red"
    if b > r * 1.4:
        return "blue"
    return "unclear"


def main():
    if not os.path.exists(VIDEO):
        sys.exit(f"{VIDEO} not found — put the staircase recording here, or edit VIDEO")
    path = next(f for f in os.listdir(".") if "spacing_staircase" in f and f.endswith(".csv"))
    with open(path) as fh:
        rows = [(int(r["elapsed_ms"]), r["label"]) for r in csv.DictReader(fh)]
    sync_cmd_ms = next(ms for ms, label in rows if label == "sync_flash")

    # Pair each burst's two writes by the (spacing, index) in their labels.
    firsts, seconds = {}, {}
    for ms, label in rows:
        parts = label.split("_")
        if len(parts) == 4 and parts[0] == "stair" and parts[3] in ("first", "second"):
            key = (int(parts[1]), int(parts[2]))
            (firsts if parts[3] == "first" else seconds)[key] = ms
    bursts = sorted(set(firsts) & set(seconds))
    print(f"{path}: {len(bursts)} bursts")

    for name, region in REGIONS.items():
        series = sample(name, region)
        sync_frame = find_sync(series)
        if sync_frame is None:
            print(f"\n{name}: no sync flash found — cannot align")
            continue
        print(f"\n=== {name} === {len(series)} frames, sync at frame {sync_frame}")

        by_bin = defaultdict(lambda: defaultdict(int))
        for key in bursts:
            gap = seconds[key] - firsts[key]
            predicted = int(sync_frame + (firsts[key] - sync_cmd_ms) / 1000 * FPS)
            anchor = anchor_of(series, predicted)
            if anchor is None or anchor + 18 >= len(series):
                continue
            # Frames 8..18 after the burst lit: inside the 700ms hold, clear of both transitions.
            # Bin on what the gap really was, in 2ms buckets.
            by_bin[gap - gap % 2][classify(series[anchor + 8:anchor + 18])] += 1

        print(f"{'gap ms':>7} {'n':>4} {'blue':>5} {'red':>5} {'black':>6} {'?':>4} {'drop %':>7}")
        for gap in sorted(by_bin):
            counts = by_bin[gap]
            total = sum(counts.values())
            decided = counts["blue"] + counts["red"]
            drop = counts["red"] / decided * 100 if decided else float("nan")
            print(f"{gap:>7} {total:>4} {counts['blue']:>5} {counts['red']:>5} "
                  f"{counts['black']:>6} {counts['unclear']:>4} {drop:>7.0f}")

        clean = [gap for gap in sorted(by_bin) if by_bin[gap]["red"] == 0 and by_bin[gap]["blue"]]
        if clean:
            # The lowest gap with no drops is the candidate, but only believe it if nothing above it
            # dropped either — a single clean bin surrounded by lossy ones is luck, not a threshold.
            lossy_above = [g for g in sorted(by_bin) if g > min(clean) and by_bin[g]["red"]]
            verdict = "" if not lossy_above else f"  (but {lossy_above} still dropped — not a floor)"
            print(f"  minWriteSpacingMs candidate: {min(clean)} ms{verdict}")
        else:
            print("  every spacing dropped writes — extend the staircase upward")


main()
