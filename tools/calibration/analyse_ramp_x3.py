#!/usr/bin/env python3
"""The byte-to-light curve, from one run that measured every byte at three known exposures.

    python tools/calibration/analyse_ramp_x3.py <fuse_latency_full_ramp_x3_*.csv>

Writes derived/response_x3.csv: one row per commanded byte, the reading at each exposure, the
curve each exposure implies on its own, and how far they disagree.

## What the three exposures were for, and what they actually delivered

The 2026-09-02 ramp was shot twice at two exposures and joined with a scale factor fitted from the
overlap. `full_ramp_x3` holds each byte at all three exposures before moving on, so the ratio
between them is the ratio of their exposure x ISO products - known, not fitted. The intent was that
this settles the curve.

**It does not, and the data says so plainly.** Put on one scale by that known ratio, the exposures
disagree, and they disagree *by level*, which a scale error cannot do:

| byte | 5ms/iso800 relative to 15ms/iso3200 |
|---|---|
| 16 | 0.27 |
| 32 | 0.31 |
| 64 | 0.45 |
| 128 | 0.65 |
| 255 | 0.89 |

Two of the three exposures are bad instruments here, and the reasons are separable:

- **2ms/iso200 never sees the strip.** Across the whole ramp its signal spans about 16 luma counts,
  and it is the only exposure whose window is contaminated by the previous setting - the frame
  metadata switches a frame or two before the pixels do, and 2ms follows 15ms in the cycle, so its
  first frames are still bright. Taking the median over the settled tail fixes the contamination and
  leaves noise. It is excluded from the curve.
- **5ms/iso800 is crushed.** Full white reads 61 of 255 there, so the entire curve lives in the
  bottom quarter of the encoding, where undoing sRGB does not undo what the ISP did. The independent
  check is `pwm_duty.csv`, whose four firmware-brightness steps are close to a linear stimulus: at
  luma 53 the sRGB-undone reading lands within 6% of the commanded duty, at luma 17.5 it reads 32%
  low, and at luma 5.7 it reads 60% low. That is the same shortfall, in the same direction, at the
  same luma levels where 5ms disagrees with 15ms.

So **the curve rests on 15ms/iso3200 alone**, which is the only exposure that spans the encoding
(1.8 to 209 of 255). The other two are recorded, not believed. Do not describe this table as
confirmed by three exposures.

A camera-response recovery (Debevec-style: the levels as scene points, the known exposure ratios as
constraints) does reconcile 5ms with 15ms, but the response it recovers puts 40% firmware duty at
94% of full light where the raw reading puts it at 42%. It was tried and rejected - the ISP's tone
curve is scene-dependent, so there is no one response function to recover.

## What is still true of the number that survives

`CalibrationPhotometer` averages 8-bit gamma-encoded Y over the ROI on the phone, so the average
happens before this script can linearise it. Undoing sRGB afterwards is exact only where the ROI is
uniform. It is good for the *shape* of the curve, and it is not photometry: white balance, lens
shading and spectral response are all still in there.

And from the `pwm_duty.csv` check above, the shape is trustworthy only where the reading is well off
the floor. Byte 8 reads luma 40 and is fine; below about byte 6 the reading falls under luma 20,
where the same check shows a 30-60% understatement. **The bottom few bytes are a lower bound on the
light, not a measurement of it.**
"""
import csv, os, re, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")

MARKER = re.compile(r"fx3_(\d+)_exp_(\d+)_(\d+)$")
# The sequence sets the exposure, waits 500ms and then marks. Frames in this window before the
# mark are settled on that setting; the first of the 500ms may still be draining the old queue.
WINDOW_NS = 350_000_000
# ...except that the queue drains later than the metadata says it does. Frames carry the new
# exposure before they carry its pixels, so only the tail of the window is honest. Measured on the
# 2ms exposure, which follows the brightest one: over the whole window its first third reads 11x
# its last third. Five frames is a sixth of a second and leaves the median something to work with.
SETTLED_TAIL = 5
CLIPPED = 250.0
# In linear units after the dark floor comes off. Anything under this is the sensor arguing with
# itself; byte 0 and byte 1 emit nothing at all and should land here.
NOISE_LINEAR = 2e-4
# 2ms/iso200 is recorded for the audit trail and excluded from the curve - see the docstring.
EXCLUDED = {(2000000, 200)}


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
        print("no fx3_<byte>_exp_<ns>_<iso> markers in the file - wrong sequence?")
        return 2

    # byte -> (exposure, iso) -> median luma over the settled tail of the window before the mark
    readings = {}
    for t, byte, exposure, iso in marks:
        window = [
            luma
            for ts, luma, e, i in frames
            if e == exposure and i == iso and t - WINDOW_NS <= ts <= t
        ]
        if window:
            readings.setdefault(byte, {})[(exposure, iso)] = statistics.median(
                window[-SETTLED_TAIL:]
            )

    exposures = sorted({(e, i) for _, _, e, i in marks}, key=lambda p: p[0] * p[1])
    # Sensitivity is exposure time x gain. Dividing by it puts all three on one scale, and the
    # ratios are known from the request rather than fitted from an overlap.
    basis = {p: p[0] * p[1] for p in exposures}
    least = min(basis.values())
    used = [p for p in exposures if p not in EXCLUDED]

    # Byte 0 is the strip off, so whatever the sensor reads there is the floor: black level, plus
    # whatever the room returns at that exposure. It has to come off before anything is compared,
    # and it has to come off *after* linearising, because the two do not add in gamma space. Without
    # this the curve reports byte 2 at 18% of peak, which is the wall.
    dark = {}
    for p in exposures:
        y0 = readings.get(0, {}).get(p)
        dark[p] = srgb_to_linear(y0) if y0 is not None else 0.0

    def signal(byte, p):
        """Dark-subtracted light at one exposure, on the common scale, or None."""
        y = readings.get(byte, {}).get(p)
        if y is None or y >= CLIPPED:
            return None
        s = srgb_to_linear(y) - dark[p]
        return None if s <= NOISE_LINEAR else s * (least / basis[p])

    out = []
    for byte in sorted(readings):
        row = {"byte": byte}
        for p in exposures:
            y = readings[byte].get(p)
            row[f"y_{p[0] // 1000000}ms_iso{p[1]}"] = "" if y is None else round(y, 3)
        # Most sensitive first, and the first one that is not *clipped* wins outright. A reading
        # that is unclipped but sub-noise means the strip emitted nothing at that byte: falling
        # through to a less sensitive exposure there reads that one's noise floor and multiplies it
        # by the sensitivity ratio, which is how byte 1 once came out at 13% of full light.
        chosen = next(
            (
                p
                for p in sorted(used, key=lambda q: -basis[q])
                if readings[byte].get(p) is not None and readings[byte][p] < CLIPPED
            ),
            None,
        )
        combined = signal(byte, chosen) if chosen else None
        row["light"] = "" if combined is None else f"{combined:.6g}"
        row["exposure_used"] = "" if chosen is None else f"{chosen[0] // 1000000}ms/iso{chosen[1]}"
        out.append(row)

    peak = max((float(r["light"]) for r in out if r["light"]), default=0.0)
    for r in out:
        r["light_norm"] = f"{float(r['light']) / peak:.6g}" if r["light"] and peak else ""

    # The disagreement travels with the number, the way `confirmed_by_both` does in the stitched
    # table. Each exposure's own curve, normalised to its own peak, and the ratio between the two
    # that carry any signal.
    per_exposure = {}
    for p in exposures:
        vals = {r["byte"]: signal(r["byte"], p) for r in out}
        top = vals.get(255)
        per_exposure[p] = {b: (v / top if v and top else None) for b, v in vals.items()}
    lo, hi = min(used, key=lambda p: basis[p]), max(used, key=lambda p: basis[p])
    for r in out:
        b = r["byte"]
        for p in exposures:
            v = per_exposure[p][b]
            r[f"norm_{p[0] // 1000000}ms"] = "" if v is None else f"{v:.6g}"
        a, c = per_exposure[lo][b], per_exposure[hi][b]
        r["agree_ratio"] = f"{a / c:.3f}" if a and c else ""

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "response_x3.csv")
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)
    print(
        f"response_x3.csv: {len(out)} bytes, exposures "
        f"{[f'{e // 1000000}ms/iso{i}' for e, i in exposures]}"
    )
    print(f"  curve from {hi[0] // 1000000}ms/iso{hi[1]} alone; {sorted(EXCLUDED)} excluded")

    have = [r for r in out if r["light_norm"]]
    print(f"  usable at {len(have)}/{len(out)} bytes")
    print(f"  {'byte':>5} {'of peak':>8} {'agree':>6}")
    for target in (2, 8, 16, 32, 40, 64, 67, 128, 255):
        hit = next((r for r in have if r["byte"] == target), None)
        if hit:
            print(f"  {target:5d} {float(hit['light_norm']):7.1%} {hit['agree_ratio']:>6}")
    half = next((r["byte"] for r in have if float(r["light_norm"]) >= 0.5), None)
    print(f"  half the peak output is reached by byte {half}")
    ratios = [float(r["agree_ratio"]) for r in out if r["agree_ratio"]]
    print(f"  exposure agreement spans {min(ratios):.2f}-{max(ratios):.2f} (1.00 would be agreement)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
