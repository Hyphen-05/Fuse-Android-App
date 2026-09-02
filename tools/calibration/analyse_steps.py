#!/usr/bin/env python3
"""`capture_all` on video: what the strip emitted for every colour the battery commanded.

  derived/steps_measured.csv  one row per held step: commanded RGB -> measured light per channel
  derived/channel_crosstalk.csv  what a single-channel command does to the other two channels
  derived/transitions.csv     whether the firmware steps to a new colour or glides to it

`capture_all` runs the whole battery — primaries, brightness x colour, CCT, transitions, write
types, the rate ramp — in one unbroken pass on one device, so every family in it shares an
exposure, a framing and a clock. That makes it the file to read colour off, in preference to the
individual runs, because comparisons across families do not have to cross a setup change.

Only steps held long enough to land whole frames are measured. Anything faster than that is a
wire-rate question, not a colour question, and `analyse_wire.py` already answers it.
"""
import csv, os, re, statistics

import numpy as np

from analyse_photometry import FPS, H, W, align, led_mask, load_linear, read_colour, write

HERE = os.path.dirname(os.path.abspath(__file__))
VIDEO = "onedev_capture_all_VID_20260902_065818"
COLOUR = "onedev_fuse_calibration_capture_all_1788328705931.csv"
MIN_HOLD_MS = 800  # a step shorter than this cannot be sampled clear of its own edges


def family(label):
    for pre, fam in (
        ("prim_r", "primary_red"),
        ("prim_g", "primary_green"),
        ("prim_b", "primary_blue"),
        ("bxc_", "brightness_x_colour"),
        ("cct_", "cct"),
        ("trans_", "transition"),
        ("wt_", "write_type"),
        ("full_", "full_ramp"),
        ("dark_", "dark_ramp"),
        ("ramp_", "brightness_ramp"),
        ("bright_", "brightness_ramp"),
        ("sync", "sync"),
        ("rate", "rate"),
    ):
        if label.startswith(pre):
            return fam
    return "other"


def main():
    frames = load_linear(VIDEO)
    mask = led_mask(frames)
    rows = read_colour(COLOUR)
    offset, resid = align(frames, mask, rows)
    print(f"  {VIDEO}: offset {offset:.0f}ms, flash spread {resid:.0f}ms, {int(mask.sum())} lit cells")

    steps = [(int(r["elapsed_ms"]), r["label"], r["r"], r["g"], r["b"]) for r in rows if r["label"]]
    measured = []
    for i, (t, label, r, g, b) in enumerate(steps):
        if i + 1 >= len(steps):
            break
        hold = steps[i + 1][0] - t
        if hold < MIN_HOLD_MS or r == "-1":
            continue
        fam = family(label)
        if fam in ("sync", "rate"):
            continue
        f0 = int((t + offset + 400) / 1000 * FPS)
        f1 = int((t + offset + min(hold, 1400) - 100) / 1000 * FPS)
        if f1 - f0 < 3:
            continue
        v = np.median(frames[f0:f1][:, mask], axis=(0, 1))
        measured.append(
            dict(
                label=label,
                family=fam,
                hold_ms=hold,
                cmd_r=int(r),
                cmd_g=int(g),
                cmd_b=int(b),
                lin_r=round(float(v[0]), 1),
                lin_g=round(float(v[1]), 1),
                lin_b=round(float(v[2]), 1),
                lin_sum=round(float(v.sum()), 1),
            )
        )
    write("steps_measured.csv", measured)

    # Crosstalk: driving one channel should light one channel. A camera sees some of every
    # emitter in every channel regardless (its filters overlap, and the LEDs are not narrowband),
    # so what matters is not whether the off-channels read zero but whether their share stays
    # constant as the driven channel is swept. A share that moves with level is the strip mixing
    # channels; a share that holds is the camera.
    cross = []
    for chan, key in (("primary_red", "lin_r"), ("primary_green", "lin_g"), ("primary_blue", "lin_b")):
        fam = [m for m in measured if m["family"] == chan and m["lin_sum"] > 0]
        for m in sorted(fam, key=lambda m: m["cmd_r"] + m["cmd_g"] + m["cmd_b"]):
            cross.append(
                dict(
                    driven=chan.replace("primary_", ""),
                    commanded=m["cmd_r"] + m["cmd_g"] + m["cmd_b"],
                    share_r=round(m["lin_r"] / m["lin_sum"], 4),
                    share_g=round(m["lin_g"] / m["lin_sum"], 4),
                    share_b=round(m["lin_b"] / m["lin_sum"], 4),
                    off_channel_share=round(1 - m[key] / m["lin_sum"], 4),
                )
            )
    write("channel_crosstalk.csv", cross)

    # Transitions: does a hard jump arrive in one frame, or does the firmware glide? Sampled at
    # frame rate, so it can only distinguish "inside one frame interval" from "visibly longer" —
    # which is the distinction that matters for whether a model needs a slew rate at all.
    trans = []
    trace = frames[:, mask].mean(axis=(1, 2))
    for i, (t, label, r, g, b) in enumerate(steps):
        if not label.startswith("trans_") or i + 1 >= len(steps):
            continue
        f0 = int((t + offset) / 1000 * FPS)
        seg = trace[max(0, f0 - 2): f0 + 20]
        if len(seg) < 8:
            continue
        start, end = float(seg[0]), float(np.median(seg[-5:]))
        if abs(end - start) < 0.05 * max(abs(end), abs(start), 1):
            continue
        lo, hi = min(start, end), max(start, end)
        span = hi - lo
        crossed = [
            j
            for j, v in enumerate(seg)
            if (v - lo) / span >= 0.9 if end > start
        ] or [j for j, v in enumerate(seg) if (v - lo) / span <= 0.1 if end < start]
        first_settled = crossed[0] if crossed else None
        moved = [
            j
            for j, v in enumerate(seg)
            if abs(v - start) > 0.1 * span
        ]
        first_moved = moved[0] if moved else None
        if first_settled is None or first_moved is None:
            continue
        trans.append(
            dict(
                label=label,
                frames_from_first_movement_to_settled=first_settled - first_moved,
                ms_from_first_movement_to_settled=round((first_settled - first_moved) / FPS * 1000),
                verdict="step (within one frame)"
                if first_settled - first_moved <= 1
                else "glide",
            )
        )
    write("transitions.csv", trans)
    if trans:
        g = sum(1 for t in trans if t["verdict"] == "glide")
        print(f"  transitions: {len(trans) - g} step, {g} glide, of {len(trans)}")


if __name__ == "__main__":
    main()
