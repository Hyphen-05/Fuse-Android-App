"""Measure the strips' real brightness response from the calibration video.

Crops a wall patch lit by each device, averages it to one pixel per frame via ffmpeg, aligns video
time to the command log using the three sync flashes, then reports measured light against commanded
byte for every step of the ramp.

The question it answers: ColorConverter.hsvToRgb cubes every channel before it reaches the strip.
If the strip's own response is already non-linear in the same direction, that cube is doubling up
and is why everything looks dim.
"""
import subprocess, csv, sys, os

FFMPEG = r"C:\Users\attgm\AppData\Roaming\Python\Python314\site-packages\imageio_ffmpeg\binaries\ffmpeg-win-x86_64-v7.1.exe"
VIDEO = "PXL_20260816_011348194.mp4"
FPS = 59.47
SECONDS = 105

REGIONS = {
    # x, y, w, h — wall patches, deliberately not the emitters (those clip to pure white).
    "fireworks": (150, 400, 300, 300),
    "bar": (1500, 600, 300, 200),
}


def sample(region, name):
    cache = f"cache_{name}.bin"
    if os.path.exists(cache):
        raw = open(cache, "rb").read()
    else:
        x, y, w, h = region
        cmd = [
            FFMPEG, "-v", "error", "-t", str(SECONDS), "-i", VIDEO,
            "-vf", f"crop={w}:{h}:{x}:{y},scale=1:1",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
        ]
        raw = subprocess.run(cmd, capture_output=True).stdout
        open(cache, "wb").write(raw)
    return [tuple(raw[i:i + 3]) for i in range(0, len(raw) - 2, 3)]


def luma(px):
    return 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2]


def load_commands(path):
    with open(path) as fh:
        return [(int(r["elapsed_ms"]), r["label"], int(r["r"])) for r in csv.DictReader(fh)]


def find_sync(series):
    """First of the three opening flashes.

    The recording starts with the strips already at full white (they are parked there so the
    camera's exposure can be locked against the worst case), so "first bright frame" is frame 0 and
    useless. The sequence's own opening is white → black (sync_black) → flash: so find the drop
    into black first, and take the first rise after that.
    """
    initial = sum(luma(p) for p in series[:int(FPS * 0.5)]) / int(FPS * 0.5)
    dark_at = None
    for index, px in enumerate(series):
        if luma(px) < initial * 0.4:
            dark_at = index
            break
    if dark_at is None:
        return None
    for index in range(dark_at, len(series)):
        if luma(series[index]) > initial * 0.6:
            return index
    return None


def main():
    commands = load_commands([f for f in os.listdir("files") if "brightness_ramp" in f][0].join(["files/", ""]))
    sync_cmd_ms = next(ms for ms, label, _ in commands if label == "sync_flash")

    for name, region in REGIONS.items():
        series = sample(region, name)
        sync_frame = find_sync(series)
        if sync_frame is None:
            print(f"{name}: no sync flash found — cannot align")
            continue
        print(f"\n=== {name} ===  {len(series)} frames, sync flash at frame {sync_frame} "
              f"({sync_frame / FPS:.2f}s video = {sync_cmd_ms}ms log)")
        print(f"{'commanded':>10} {'measured':>10} {'norm':>7}")

        steps = [(ms, label, r) for ms, label, r in commands if label.startswith("ramp_") and not label.startswith("ramp_down")]
        results = []
        for i, (ms, label, r) in enumerate(steps):
            end_ms = steps[i + 1][0] if i + 1 < len(steps) else ms + 2000
            # Middle half of each hold, so neither the transition in nor out is included.
            a = ms + (end_ms - ms) * 0.35
            b = ms + (end_ms - ms) * 0.9
            fa = int(sync_frame + (a - sync_cmd_ms) / 1000 * FPS)
            fb = int(sync_frame + (b - sync_cmd_ms) / 1000 * FPS)
            window = series[max(0, fa):min(len(series), fb)]
            if not window:
                continue
            measured = sum(luma(p) for p in window) / len(window)
            results.append((r, measured))

        if not results:
            continue
        import math

        # The camera records gamma-encoded video, so its 8-bit values are not proportional to light.
        # Undo the BT.709/sRGB encode (~2.2) before treating any of this as a physical quantity —
        # skipping this step makes an ordinary response look dramatically compressive.
        def linear(v):
            return (max(v, 0.0) / 255.0) ** 2.2

        dark_lin = linear(results[0][1])
        top_lin = max(linear(m) for _, m in results)
        print(f"{'commanded':>10} {'raw':>8} {'light':>8} {'norm':>7}")
        rows = []
        for r, m in results:
            lin = max(linear(m) - dark_lin, 0.0)
            norm = lin / (top_lin - dark_lin) if top_lin > dark_lin else 0
            rows.append((r, norm))
            print(f"{r:>10} {m:>8.1f} {lin:>8.4f} {norm:>7.3f}")

        pairs = [(r / 255, v) for r, v in rows if r > 0 and 0.02 < v < 0.98]
        if len(pairs) >= 3:
            k = sum(math.log(v) / math.log(c) for c, v in pairs) / len(pairs)
            print(f"  fitted exponent k ~ {k:.2f}  (1.0 = linear in the byte, 2.2 = sRGB-like,")
            print(f"                              3.0 = what ColorConverter.hsvToRgb applies)")
            print(f"  net after the app's cube: light ~ v^{3 * k:.2f} for a perceptual value v")


main()
