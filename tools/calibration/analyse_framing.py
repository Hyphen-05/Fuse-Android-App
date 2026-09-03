#!/usr/bin/env python3
"""Is the camera pointed at the strip well enough to start a two-hour session?

    python tools/calibration/analyse_framing.py <fuse_latency_framing_check_*.csv>

Prints a verdict and exits 0 if the framing is usable, 1 if a person needs to move the phone, and
2 if the file could not be read at all. The exit code is the point: this is the one gate in an
otherwise unattended session, so a script can act on it without anyone reading the output.

## What it is looking at

`framing_check` holds black, then white, then each primary, cycling the camera through all three
exposures at each. `CalibrationPhotometer` writes a `grid` row every twelfth frame carrying an 8x8
block of Y, U and V means over the whole frame. Cells that brighten between black and white are
where the strip is; everything else is room.

## The four ways framing goes wrong

**Not there.** Nothing brightened. Either the phone is aimed at the wrong wall, or the strip never
lit — which the liveness guard also catches, and which this cannot distinguish from a lens cap.

**Too small.** The strip lights two or three cells out of sixty-four. Per-LED work needs emitters
in separate cells, and at that size everything smears into one number. Move the phone closer.

**Cropped.** Lit cells touch the frame edge, which almost always means part of the strip is outside
it. This is the failure a preview makes easiest to miss, because what is in shot looks perfect.

**Wrongly exposed.** Handled separately from position, because the fix is different: no exposure in
the ladder both resolves the dark end and holds white unclipped. Reported as a recommendation
rather than a failure, since the ramp sweeps exposure anyway and only needs one of the three to be
right at each end.
"""
import csv
import re
import statistics
import sys

GRID = 8
# A cell counts as lit if white beats black by this much in Y. Sensor noise at these exposures is
# well under a level; five is far above it and far below any real signal.
LIT_MARGIN = 5.0
# Y is 8-bit. Treat anything this high as clipped: above it the cell has stopped reporting how
# much light there is and started reporting the top of the scale.
SATURATED_Y = 250
# A cell is part of the strip if it clears this fraction of the brightest cell's rise. Relative,
# because the absolute level depends on exposure, distance and how much strip is in shot.
LIT_FRACTION = 0.25
# Below this the shortest exposure is reading sensor noise, not light; step to a longer one.
MIN_PEAK_DELTA = 15.0
# Brightest cell over median cell. A strip in frame measured 23x; a wall lit by that same strip
# measured 1.4x. Three is comfortably between them and nowhere near either.
MIN_CONTRAST = 3.0


def rows(path):
    with open(path, newline="") as f:
        header = f.readline()
        m = re.search(r"grid=(\d+)", header)
        grid = int(m.group(1)) if m else GRID
        return grid, list(csv.DictReader(f))


def grids_by_label(rows_):
    """Grid rows carry no label of their own — they are frames, not commands — so each one belongs
    to whichever `write` most recently preceded it."""
    out, label = {}, None
    for r in rows_:
        if r["event"] == "write" and r["label"].startswith("framing_"):
            label = r["label"]
        elif r["event"] == "grid" and label:
            ys = [int(v) for v in r["luma"].split(";")[0].split("|")]
            out.setdefault((label, r["exposure_ns"], r["iso"]), []).append(ys)
    # Median across the frames of one hold: one frame caught mid-change cannot move it.
    return {
        k: [statistics.median(c) for c in zip(*v)]
        for k, v in out.items()
        if len(v) >= 2
    }


def main():
    if len(sys.argv) < 2:
        print("usage: analyse_framing.py <fuse_latency_framing_check_*.csv>")
        return 2
    try:
        grid, raw = rows(sys.argv[1])
        cells = grids_by_label(raw)
    except Exception as e:  # a file that will not parse is a failed check, not a crash
        print(f"FAIL  could not read framing check: {e}")
        return 2
    if not cells:
        print("FAIL  no grid rows in the file — the photometer did not run")
        return 2

    exposures = sorted({(int(e), int(i)) for _, e, i in cells})
    problems, notes = [], []
    best = None

    for exposure, iso in exposures:
        black = cells.get(("framing_black", str(exposure), str(iso)))
        white = cells.get(("framing_white", str(exposure), str(iso)))
        if not black or not white:
            continue
        lit = [i for i in range(len(white)) if white[i] - black[i] > LIT_MARGIN]
        clipped = [i for i in lit if white[i] >= SATURATED_Y]
        notes.append(
            f"  exposure {exposure / 1e6:g}ms iso {iso}: {len(lit)}/{len(white)} cells lit, "
            f"{len(clipped)} clipped"
        )
        # The usable exposure is the one that lights the most cells without clipping any of them.
        if lit and not clipped and (best is None or len(lit) > best[2]):
            best = (exposure, iso, len(lit))

    # Position is judged at the SHORTEST exposure that has real signal in it, and by contrast
    # rather than by an absolute margin. Judging it at the widest exposure — which is what this
    # did until 2026-09-03 — measures the room, not the strip: in the dark this runs in, a strip
    # bright enough to measure lights the whole wall, every cell brightens between black and
    # white, and the verdict comes back "the strip fills 100% of the frame" for a camera that has
    # the strip perfectly framed with dark wall all around it. Both phones failed that way, one of
    # them while a photograph showed individual LEDs down the middle of the frame.
    #
    # At the short exposure the wall does not register and the emitters do, so the discriminating
    # number is max cell delta over median cell delta: 23 for a strip in frame, 1.4 for a camera
    # pointed at a wall lit by one. Everything below keys off that.
    judged, lit, contrast = None, [], 0.0
    for exposure, iso in exposures:
        black = cells.get(("framing_black", str(exposure), str(iso)))
        white = cells.get(("framing_white", str(exposure), str(iso)))
        if not black or not white:
            continue
        deltas = [white[i] - black[i] for i in range(len(white))]
        peak = max(deltas)
        if peak < MIN_PEAK_DELTA:  # nothing but noise at this exposure; try a longer one
            continue
        median = statistics.median(deltas)
        judged = (exposure, iso)
        contrast = peak / max(median, 0.5)
        lit = [i for i in range(len(deltas)) if deltas[i] > LIT_FRACTION * peak]
        break

    if judged is None:
        print("FAIL  the strip is not in frame — nothing brightened between black and white.")
        print("      Either the phone is aimed somewhere else, or the strip did not light.")
        print("\n".join(notes))
        return 1

    if contrast < MIN_CONTRAST:
        print(
            f"FAIL  no localised light: at {judged[0] / 1e6:g}ms the brightest cell is only "
            f"{contrast:.1f}x the median."
        )
        print("      That is a wall lit by the strip, not the strip. Aim the camera at the strand")
        print("      itself — a phone facing a lit wall produces exactly this, and looks fine to")
        print("      the eye.")
        print("\n".join(notes))
        return 1

    coords = [(i // grid, i % grid) for i in lit]
    rows_used = {r for r, _ in coords}
    cols_used = {c for _, c in coords}
    edge = any(r in (0, grid - 1) or c in (0, grid - 1) for r, c in coords)
    coverage = len(lit) / (grid * grid)

    if len(lit) < 3:
        problems.append(
            f"the strip lights only {len(lit)} of {grid * grid} cells — too small to separate "
            "positions along it. Move the phone closer."
        )
    if coverage > 0.6:
        problems.append(
            f"the strip fills {coverage:.0%} of the frame, leaving no dark reference. "
            "Pull the phone back a little."
        )
    # Not a failure. The strand is longer than any sensible frame, so its ends run off the edge in
    # every good setup this rig has had; per-cell work does not care, and failing on it sent
    # someone to re-aim a camera that was right.
    if edge:
        notes.append(
            "  note: lit cells touch the frame edge, so the ends of the strand are outside it. "
            "Fine unless you need the whole strip at once."
        )

    widest_lit = lit
    print(f"         judged at {judged[0] / 1e6:g}ms iso {judged[1]}, contrast {contrast:.1f}x")
    print(f"framing: {len(widest_lit)}/{grid * grid} cells lit ({coverage:.0%} of frame)")
    print(f"         rows {min(rows_used)}-{max(rows_used)}, cols {min(cols_used)}-{max(cols_used)}")
    print("\n".join(notes))
    if best:
        print(f"         best unclipped exposure: {best[0] / 1e6:g}ms at iso {best[1]}")
    else:
        print("         NOTE: every exposure clips white. The ramp sweeps exposure anyway, so this")
        print("               is survivable, but the top of the curve will come from the shortest.")

    if problems:
        print("\nMOVE THE CAMERA:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("\nOK — framing is usable, no one needs to touch anything.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
