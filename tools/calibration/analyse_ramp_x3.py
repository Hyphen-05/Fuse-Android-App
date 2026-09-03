#!/usr/bin/env python3
"""The byte-to-light curve, from one run that measured every byte at three known exposures.

    python tools/calibration/analyse_ramp_x3.py <fuse_latency_full_ramp_x3_*.csv>

Writes derived/response_x3.csv: one row per commanded byte, with the reading at each exposure and
the combined curve.

## Why this replaces the stitch

The 2026-09-02 ramp was shot twice at two exposures and joined afterwards with a scale factor
fitted from the overlap. Between bytes 48 and 203 the two takes agreed to 5% and the join was
sound; below byte 48 they disagreed by up to 42%, and the bottom of the curve — the part anyone
actually cares about, since half the strip's output arrives by byte 40 — stayed unsettled.

`full_ramp_x3` holds each byte at all three exposures before moving on, so the ratio between them
is the ratio of their exposure x ISO products, which is *known* rather than fitted. There is
nothing left to justify: readings are divided by that product and land on one scale.

## What a reading is, and what it is not

`CalibrationPhotometer` averages 8-bit gamma-encoded Y over the ROI on the phone, so the average
happens before this script can linearise it. Undoing sRGB afterwards is an approximation — exact
only where the ROI is uniform, and increasingly wrong as the frame mixes bright emitters with dark
background. It is good for the *shape* of the curve, which is what the byte-to-light question is,
and it is not photometry: white balance, lens shading and spectral response are all still in there.

Bytes whose reading clips (>=250) or sits in the noise (<1.5) at a given exposure are dropped for
that exposure only; the combined column takes the least sensitive exposure that still has signal,
which is the one with the most headroom left.
"""
import csv, os, re, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")

MARKER = re.compile(r"fx3_(\d+)_exp_(\d+)_(\d+)$")
# The sequence sets the exposure, waits 500ms and then marks. Frames in this window before the
# mark are settled on that setting; the first of the 500ms may still be draining the old queue.
WINDOW_NS = 350_000_000
CLIPPED = 250.0
# In linear units after the dark floor comes off. Anything under this is the sensor arguing with
# itself; byte 0 and byte 1 emit nothing at all and should land here.
NOISE_LINEAR = 2e-4


def srgb_to_linear(y8):
    c = y8 / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def main():
    if len(sys.argv) < 2:
        print("usage: analyse_ramp_x3.py <fuse_latency_full_ramp_x3_*.csv>")
        return 2
    with open(sys.argv[1], newline="") as f:
        f.readline()  # the "# sensor_timestamp_source=... grid=..." header
        rows = list(csv.DictReader(f))

    frames = [
        (int(r["elapsed_ns"]), float(r["luma"]), int(r["exposure_ns"]), int(r["iso"]))
        for r in rows
        if r["event"] == "frame" and r["luma"]
    ]
    marks = []
    for r in rows:
        m = MARKER.match(r["label"] or "")
        if r["event"] == "write" and m:
            marks.append((int(r["elapsed_ns"]), int(m.group(1)), int(m.group(2)), int(m.group(3))))
    if not marks:
        print("no fx3_<byte>_exp_<ns>_<iso> markers in the file — wrong sequence?")
        return 2

    # byte -> (exposure, iso) -> median luma over the settled window before the mark
    readings = {}
    for t, byte, exposure, iso in marks:
        window = [
            luma
            for ts, luma, e, i in frames
            if e == exposure and i == iso and t - WINDOW_NS <= ts <= t
        ]
        if window:
            readings.setdefault(byte, {})[(exposure, iso)] = statistics.median(window)

    exposures = sorted({(e, i) for _, _, e, i in marks}, key=lambda p: p[0] * p[1])
    # Sensitivity is exposure time x gain. Dividing by it puts all three on one scale, and the
    # ratios are known from the request rather than fitted from an overlap.
    basis = {p: p[0] * p[1] for p in exposures}
    least = min(basis.values())

    # Byte 0 is the strip off, so whatever the sensor reads there is the floor: black level, plus
    # whatever the room returns at that exposure. It has to come off before anything is compared,
    # and it has to come off *after* linearising, because the two do not add in gamma space. Without
    # this the curve reports byte 2 at 18% of peak, which is the wall.
    dark = {}
    for p in exposures:
        y0 = readings.get(0, {}).get(p)
        dark[p] = srgb_to_linear(y0) if y0 is not None else 0.0

    out = []
    for byte in sorted(readings):
        row = {"byte": byte}
        combined, chosen = None, None
        # Most sensitive first: the dimmest exposure that is not clipped has the best signal to
        # noise, and for the bottom of the curve it is the only one with any signal at all. Taking
        # the least sensitive one instead reads the noise floor and calls it light.
        for p in sorted(exposures, key=lambda q: -basis[q]):
            y = readings[byte].get(p)
            row[f"y_{p[0] // 1000000}ms_iso{p[1]}"] = "" if y is None else round(y, 3)
            if y is None or y >= CLIPPED:
                continue
            signal = srgb_to_linear(y) - dark[p]
            if signal <= NOISE_LINEAR:
                continue
            if combined is None:
                combined, chosen = signal * (least / basis[p]), p
        row["light"] = "" if combined is None else f"{combined:.6g}"
        row["exposure_used"] = "" if chosen is None else f"{chosen[0] // 1000000}ms/iso{chosen[1]}"
        out.append(row)

    peak = max((float(r["light"]) for r in out if r["light"]), default=0.0)
    for r in out:
        r["light_norm"] = f"{float(r['light']) / peak:.6g}" if r["light"] and peak else ""

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "response_x3.csv")
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)
    print(f"response_x3.csv: {len(out)} bytes, exposures {[f'{e//1000000}ms/iso{i}' for e, i in exposures]}")

    have = [r for r in out if r["light_norm"]]
    print(f"  usable at {len(have)}/{len(out)} bytes")
    for target in (2, 8, 16, 32, 40, 64, 128, 255):
        hit = next((r for r in have if r["byte"] == target), None)
        if hit:
            print(f"  byte {target:3d}: {float(hit['light_norm']):6.1%} of peak   ({hit['exposure_used']})")
    half = next((r["byte"] for r in have if float(r["light_norm"]) >= 0.5), None)
    print(f"  half the peak output is reached by byte {half}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
