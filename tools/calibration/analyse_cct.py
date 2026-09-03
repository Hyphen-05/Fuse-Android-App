#!/usr/bin/env python3
"""The warm/cold sub-mode probe: which CCT command byte actually lights the strip.

    python tools/calibration/analyse_cct.py <fuse_latency_cct_probe_*.csv>

Writes derived/cct_probe.csv: one row per commanded step, with the light it produced relative to
the known-good white that was shown just before it.

## Why the probe existed

`cct_sweep` on 2026-09-02 emitted no light at all across 25 steps, and the two available
explanations - the wrong sub-mode byte, or no white channel on this unit - could not be told apart
by filming harder. The probe sweeps the command `7E 06 05 <sub> <warm> <cold> FF <tail> EF` over
sub-modes 1, 2 and 3 and tails 0x00/0x08, with an ordinary white in between every candidate so that
a dead strip and a dead command cannot be confused. Steps prefixed `mode` send a mode switch first,
to answer whether one is required.

## Reading the output

`ref_frac` is the step's light as a fraction of the white reference that preceded it, after the
black floor comes off both. It is a ratio of readings from one camera at one exposure within a
couple of seconds, which is the only comparison this data supports; it is not photometry, and the
caveats in `analyse_ramp_x3.py` about the ISP apply to the absolute numbers here too.

**`black_ref_ok` decides whether `ref_frac` means anything.** The `mode_` group's black step does
not go black: the mode switch leaves the strip lit, so its floor reads 63 where every other group
reads 1, and every `ref_frac` in that group is depressed by it. Compare that group on the raw
`luma` column instead - where it is identical, step for step, to the group that sent no mode
switch, which is the answer to whether a mode switch is required. It is not.
"""
import csv, os, re, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")

STEP = re.compile(r"^cctp_(live_)?(.+)$")
# Each step is held 1.5s and the label marks the start of the hold. The first half second is the
# write landing and the camera catching up; the rest is the measurement.
SETTLE_NS = 500_000_000


def main():
    if len(sys.argv) < 2:
        print("usage: analyse_cct.py <fuse_latency_cct_probe_*.csv>")
        return 2
    with open(sys.argv[1], newline="") as f:
        f.readline()
        rows = list(csv.DictReader(f))

    frames = sorted(
        (int(r["elapsed_ns"]), float(r["luma"]))
        for r in rows
        if r["event"] == "frame" and r["luma"]
    )
    writes = [
        (int(r["elapsed_ns"]), r["label"])
        for r in rows
        if r["event"] == "write" and STEP.match(r["label"] or "")
    ]
    if not writes:
        print("no cctp_* markers in the file - wrong sequence?")
        return 2

    held = []
    for i, (t, label) in enumerate(writes):
        stop = writes[i + 1][0] if i + 1 < len(writes) else frames[-1][0]
        window = [l for ts, l in frames if t + SETTLE_NS <= ts <= stop]
        if window:
            held.append((label, statistics.median(window), len(window)))

    # The reference pair that brackets each group of candidates: the ordinary white command (which
    # is known to work) and the black that follows it. Everything between one pair and the next is
    # measured against that pair.
    out, white, black = [], None, None
    for label, luma, n in held:
        name = label[len("cctp_"):]
        if name.endswith("_white"):
            white = luma
            continue
        if name.endswith("_black"):
            black = luma
            continue
        if white is None or black is None:
            continue
        span = white - black
        frac = (luma - black) / span if span else None
        # A floor that is not near the darkest reading in the run is not a floor. It happens when
        # the preceding command left the strip lit - see the docstring on the `mode_` group.
        black_ok = black < 0.1 * white
        out.append(
            {
                "step": name,
                "sub_mode": re.search(r"s(\d)", name).group(1),
                "mode_switch_first": "yes" if name.startswith("mode_") else "no",
                "tail": re.search(r"t(\d)", name).group(1) if "_t" in name else "",
                "warm": re.search(r"w(\d+)", name).group(1),
                "cold": re.search(r"c(\d+)", name).group(1),
                "luma": round(luma, 3),
                "white_ref": round(white, 3),
                "black_ref": round(black, 3),
                "frames": n,
                "black_ref_ok": "yes" if black_ok else "no",
                "ref_frac": f"{frac:.4f}" if frac is not None else "",
                "lit": "yes" if luma > black + 0.05 * (white - black) else "no",
            }
        )

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "cct_probe.csv")
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
        w.writeheader()
        w.writerows(out)
    print(f"cct_probe.csv: {len(out)} steps")
    for sub in sorted({r["sub_mode"] for r in out}):
        rs = [r for r in out if r["sub_mode"] == sub]
        n = sum(1 for r in rs if r["lit"] == "yes")
        print(f"  sub-mode 0x0{sub}: {n}/{len(rs)} steps lit")
    suspect = {r["step"] for r in out if r["black_ref_ok"] == "no"}
    if suspect:
        print(f"  {len(suspect)} steps have an unusable black reference - compare those on luma")
    lit = [r for r in out if r["lit"] == "yes" and r["black_ref_ok"] == "yes"]
    if lit:
        print(f"  {'step':>24} {'of white':>9}")
        for r in lit:
            print(f"  {r['step']:>24} {float(r['ref_frac']):8.1%}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
