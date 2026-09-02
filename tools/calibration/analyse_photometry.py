#!/usr/bin/env python3
"""The light half: what the strip actually emitted for each byte that was commanded.

  derived/response_full_ramp.csv   byte 0-255 -> measured light, both exposures
  derived/response_stitched.csv    the two exposures joined into one curve, normalised
  derived/response_per_led.csv     the macro dark ramp, one column of LEDs measured separately

## How a video frame becomes a number

`extract_frames.sh` decodes each capture to a 64x36 grid. For the photometric runs it also writes a
`.lin48` grid: the same downscale, but with `zscale=t=linear` ahead of it so the camera's transfer
curve is undone **before** pixels are averaged together. That ordering is the whole measurement. A
cell holding one lit LED against black background averages to something meaningless if the average
is taken in gamma-encoded space, and every byte-to-light number derived from it inherits the error.

## What is still not photometry

These numbers are proportional to scene luminance as the sensor saw it, not to radiometric output:
white balance, lens shading and the sensor's own spectral response are all still in there. They are
good for **shape** — is the byte-to-light curve linear, where does it saturate, how much do LEDs
differ from each other — and not for absolute output in any physical unit. Nothing here needed the
absolute figure.
"""
import csv, os, re, statistics, sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")
FRAMES = os.path.join(OUT, "frames")
W, H = 64, 36
FPS = 30.0

# Each photometric run: the linear-light grid, the colour log it was shot against, and the label
# prefix whose rows carry the commanded byte.
RUNS = {
    "bright": (
        "full_ramp_bright_VID_20260902_061640",
        "bright_fuse_calibration_full_ramp_1788326208021.csv",
        "full_",
    ),
    "dark": (
        "full_ramp_dark_VID_20260902_062440",
        "dark_fuse_calibration_full_ramp_1788326687772.csv",
        "full_",
    ),
    "macro": (
        "macro_dark_ramp_VID_20260902_072756",
        "macro_fuse_calibration_dark_ramp_1788330483839.csv",
        "dark_",
    ),
}


def load_linear(name):
    a = np.fromfile(os.path.join(FRAMES, name + ".lin48"), dtype="<u2")
    return a.reshape(-1, H, W, 3).astype(np.float64)


def led_mask(frames, keep=0.99):
    """The cells the strip is in: the ones whose value moves over the run. Everything else is
    room, and including it only adds a pedestal that changes with nothing."""
    lum = frames.mean(axis=3)
    v = lum.std(axis=0)
    return v > np.percentile(v, 100 * keep)


def read_colour(path):
    with open(os.path.join(HERE, path), newline="") as f:
        return list(csv.DictReader(r for r in f if not r.startswith("#")))


def align(frames, mask, rows):
    """Map colour-log milliseconds onto video frames, using the three sync flashes the sequence
    opens with. Returns the offset in ms and the residual, which is the honest error bar: a fit
    that disagrees with itself by more than a frame interval has not aligned anything."""
    trace = frames[:, mask].mean(axis=(1, 2))
    head = trace[: int(20 * FPS)]
    thr = (head.max() + head.min()) / 2
    on = head > thr
    onsets = [i for i in range(1, len(on)) if on[i] and not on[i - 1]]
    flashes = [int(r["elapsed_ms"]) for r in rows if r["label"] == "sync_flash"]
    n = min(len(onsets), len(flashes))
    if n < 2:
        raise SystemExit("sync flashes not found in video")
    offs = [onsets[i] / FPS * 1000 - flashes[i] for i in range(n)]
    return statistics.median(offs), (max(offs) - min(offs))


def sample(frames, mask, t_ms, offset_ms, lead=400, span=500):
    """Median of the frames held well inside one commanded step.

    The step is sampled from `lead` ms after the command, not from the command itself: the write
    still has to reach the strip, and the first frames of a step can straddle the change. `span`
    stops short of the next command for the same reason.
    """
    f0 = int((t_ms + offset_ms + lead) / 1000 * FPS)
    f1 = int((t_ms + offset_ms + lead + span) / 1000 * FPS)
    f0, f1 = max(0, f0), min(len(frames), f1)
    if f1 - f0 < 3:
        return None
    seg = frames[f0:f1][:, mask]  # (frames, cells, 3)
    return np.median(seg, axis=(0, 1))


def curve(run_key):
    name, colour, prefix = RUNS[run_key]
    frames = load_linear(name)
    mask = led_mask(frames)
    rows = read_colour(colour)
    offset, resid = align(frames, mask, rows)
    out = []
    for r in rows:
        m = re.fullmatch(prefix + r"(\d+)", r["label"] or "")
        if not m or r["r"] != r["g"] or r["g"] != r["b"]:
            continue
        v = sample(frames, mask, int(r["elapsed_ms"]), offset)
        if v is None:
            continue
        out.append(
            dict(
                exposure=run_key,
                byte=int(r["r"]),
                lin_r=round(v[0], 1),
                lin_g=round(v[1], 1),
                lin_b=round(v[2], 1),
                lin_mean=round(float(v.mean()), 1),
            )
        )
    return out, offset, resid, frames, mask, rows


def per_led(frames, mask, rows, offset):
    """The macro take frames about twenty LEDs on the telephoto, so individual emitters land in
    separate cells. Grouping the mask into connected rows of the grid gives one trace per LED —
    which is the only way to ask whether the strip is uniform, as opposed to bright on average."""
    ys, xs = np.nonzero(mask)
    # cluster by grid row: LEDs on the vertical strand are separated by blank rows
    order = sorted(set(ys.tolist()))
    groups, cur = [], [order[0]]
    for y in order[1:]:
        if y - cur[-1] <= 1:
            cur.append(y)
        else:
            groups.append(cur)
            cur = [y]
    groups.append(cur)
    steps = [
        (int(r["elapsed_ms"]), int(r["r"]))
        for r in rows
        if re.fullmatch(r"dark_\d+", r["label"] or "") and r["r"] == r["b"]
    ]
    out = []
    for gi, g in enumerate(groups):
        sub = np.zeros((H, W), bool)
        for y in g:
            sub[y] = mask[y]
        for t, b in steps:
            v = sample(frames, sub, t, offset)
            if v is None:
                continue
            out.append(dict(led_index=gi, byte=b, lin_mean=round(float(v.mean()), 1)))
    return out


def stitch(rows):
    """Join the two exposures into one curve.

    No single exposure holds byte 1 and byte 255 — that is the sensor's dynamic range, not a
    workflow mistake, and it is why the ramp was shot twice. The dark take resolves the bottom and
    saturates somewhere above it; the bright take is clean at the top and in noise at the bottom.

    The join is also the check on the whole method. A change of exposure is a multiplication in
    linear light and nothing else, so wherever both takes are behaving the ratio between them must
    be **flat**. It is not flat everywhere, and where it bends says which take to distrust:

      - below byte ~50 the ratio sags, because the bright take is down among the last few 8-bit
        codes there. Undoing a transfer curve on a code of 3 cannot recover what the code never
        held, and the rounding only ever biases upward.
      - above byte ~150 the ratio sags the other way, as the dark take starts to compress its top.

    So the overlap is chosen as the longest stretch where the ratio holds still, and each end of
    the curve is taken from the exposure that was actually resolving it there.
    """
    bright = {r["byte"]: r["lin_mean"] for r in rows if r["exposure"] == "bright"}
    dark = {r["byte"]: r["lin_mean"] for r in rows if r["exposure"] == "dark"}
    both = [b for b in sorted(set(bright) & set(dark)) if bright[b] > 0]
    ratio = {b: dark[b] / bright[b] for b in both}
    # longest run of bytes whose ratio stays within 3% of the run's own middle
    best = (0, 0)
    for i in range(len(both)):
        for j in range(len(both), i, -1):
            if j - i <= best[1] - best[0]:
                break
            seg = [ratio[b] for b in both[i:j]]
            mid = statistics.median(seg)
            if max(seg) <= mid * 1.03 and min(seg) >= mid * 0.97:
                best = (i, j)
                break
    lap = both[best[0]: best[1]]
    ratios = [ratio[b] for b in lap]
    k = statistics.median(ratios) if ratios else float("nan")
    spread = (max(ratios) - min(ratios)) / k if ratios else float("nan")

    lo_edge, hi_edge = (min(lap), max(lap)) if lap else (0, 255)
    full = max(bright.values())
    out = []
    for b in sorted(set(bright) | set(dark)):
        # Below the overlap only the dark take resolves anything; above it only the bright take is
        # still linear. Inside it either would do, and the dark take is the less noisy of the two.
        use_dark = b in dark and b <= hi_edge
        v = (dark[b] / k) if use_dark else bright.get(b)
        if v is None:
            continue
        # Carry the disagreement rather than hiding it. Outside the flat stretch the two takes do
        # not agree, and a reader of this file needs to know which rows are two measurements and
        # which are one.
        pair = (dark.get(b), bright.get(b))
        dis = (
            abs(pair[0] / k - pair[1]) / max(pair[0] / k, pair[1])
            if pair[0] and pair[1]
            else ""
        )
        out.append(
            dict(
                byte=b,
                source="dark" if use_dark else "bright",
                light=round(v, 1),
                light_norm=round(v / full, 5),
                from_dark_norm=round(pair[0] / k / full, 5) if pair[0] else "",
                from_bright_norm=round(pair[1] / full, 5) if pair[1] else "",
                exposures_disagree_pct=round(100 * dis, 1) if dis != "" else "",
                confirmed_by_both=("yes" if dis != "" and dis < 0.05 else "no"),
            )
        )
    return out, k, spread, lap


def fit(stitched):
    """Two one-parameter descriptions of the curve, so its shape can be stated rather than drawn.

    A power law `light = (byte/255)^g` is the one a renderer would actually use. It is fitted in
    log-log on bytes 8 and up, where both the measurement and the model have something to say —
    below that the strip's own quantisation dominates and no smooth law fits.
    """
    pts = [(r["byte"], r["light_norm"]) for r in stitched if r["byte"] >= 8 and r["light_norm"] > 0]
    xs = [np.log(b / 255) for b, _ in pts]
    ys = [np.log(v) for _, v in pts]
    g = float(np.polyfit(xs, ys, 1)[0])
    err = max(abs(np.exp(np.polyval([g, 0.0], x)) - v) for x, (_, v) in zip(xs, pts))
    return g, err


def write(name, rows):
    if not rows:
        return
    with open(os.path.join(OUT, name), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f"{name}: {len(rows)} rows")


def main():
    all_rows, meta = [], []
    keep = {}
    for k in ("bright", "dark", "macro"):
        if not os.path.exists(os.path.join(FRAMES, RUNS[k][0] + ".lin48")):
            print(f"skip {k}: no .lin48 grid yet", file=sys.stderr)
            continue
        rows, offset, resid, frames, mask, colour = curve(k)
        keep[k] = (rows, frames, mask, colour, offset)
        all_rows += rows
        meta.append((k, round(offset, 1), round(resid, 1), int(mask.sum())))
    for k, o, r, c in meta:
        print(f"  {k}: video-vs-log offset {o}ms, flash spread {r}ms, {c} lit cells")
    write("response_full_ramp.csv", all_rows)

    if "bright" in keep and "dark" in keep:
        st, k, spread, lap = stitch(all_rows)
        write("response_stitched.csv", st)
        print(
            f"  stitch: dark/bright = {k:.2f}x over bytes {min(lap)}-{max(lap)}, "
            f"ratio spread {100 * spread:.1f}% "
            f"({'flat - transfer curve undone' if spread < 0.15 else 'NOT FLAT - suspect'})"
        )
        g, err = fit(st)
        print(f"  power-law fit: light = (byte/255)^{g:.3f}, worst residual {err:.3f} of full")

    if "macro" in keep:
        rows, frames, mask, colour, offset = keep["macro"]
        write("response_per_led.csv", per_led(frames, mask, colour, offset))


if __name__ == "__main__":
    main()
