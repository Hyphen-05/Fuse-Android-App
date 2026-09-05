#!/usr/bin/env python3
"""Read a Perception Lab sitting or quantisation probe and say what it establishes.

Usage:  python tools/perception/analyse.py tools/perception/results/perception_*.json
        python tools/perception/analyse.py tools/perception/results/probe_*.json

Both file kinds are handled; the `kind` field picks the reader.

The load-bearing number is the false-positive rate on the catch trials, so it is printed
first and everything else is printed underneath a verdict about it. A sitting with a high
guessing rate is not a weaker result, it is not a result: the thresholds from it record
somebody picking a side, and the right response is to discard them rather than to
discount them.

Note on "correct" for the fade block: it is defined as picking the *dithered* fade, which
is a naming convenience, not a claim that dithered is the right answer. What the block
measures is a preference, so read `fade` below as a vote count and not as a score.
"""

import collections
import glob
import json
import sys

# Below this, thresholds are worth designing against. Above it, the answers are guesses.
FALSE_POSITIVE_LIMIT = 0.20


def load(paths):
    for pattern in paths:
        for path in sorted(glob.glob(pattern)):
            with open(path, encoding="utf-8") as handle:
                yield path, json.load(handle)


def summarise(path, d):
    trials = d["trials"]
    catches = [t for t in trials if t["isCatch"]]
    fp = d["falsePositiveRate"]

    print(f"=== {path}")
    floor = d.get("floor")
    if floor:
        print(f"floor: first visible byte {floor['firstVisible']}, clearly lit {floor['clearlyOn']}")
    print(f"{len(trials)} trials, {len(catches)} of them catch trials")
    verdict = "trustworthy" if fp <= FALSE_POSITIVE_LIMIT else "NOT TRUSTWORTHY - discard"
    print(f"guessing rate: {fp:.0%}  -> {verdict}")
    if fp > FALSE_POSITIVE_LIMIT:
        print("  Everything below is a record of guessing. Do not design against it.")
    print()

    print("step visibility - smallest change spotted, in commanded bytes:")
    for base, threshold in sorted(d["stepThresholdByBase"].items(), key=lambda kv: int(kv[0])):
        if threshold is None:
            print(f"  base {base:>3}: not established")
        else:
            print(f"  base {base:>3}: {threshold:.1f} bytes  ({threshold / int(base):.0%} of the base level)")
    print()

    print("dither flicker - fraction of trials where the dithered interval was picked out:")
    for interval, rate in sorted(d["ditherVisibleByInterval"].items(), key=lambda kv: int(kv[0])):
        seen = "visible as flicker" if rate >= 0.75 else "not distinguishable"
        print(f"  {interval:>4}ms writes: {rate:.0%}  {seen}")
    print()

    fade = d["fadePreference"]
    decisive = [
        t for t in trials
        if t["kind"] == "FADE_SMOOTHNESS" and not t["isCatch"] and t["response"] != "CANT_TELL"
    ]
    print(
        f"fade smoothness - dithered preferred {fade['dithered']}, plain preferred {fade['plain']}"
        f" ({len(decisive)} decisive answers)"
    )
    print()

    print("response mix by block (non-catch):")
    for kind in ("STEP_VISIBILITY", "DITHER_FLICKER", "FADE_SMOOTHNESS"):
        rs = [t for t in trials if t["kind"] == kind and not t["isCatch"]]
        mix = dict(collections.Counter(t["response"] for t in rs))
        print(f"  {kind:<18} n={len(rs):<4} {mix}")
    times = sorted(t["responseMs"] for t in trials)
    if times:
        print(f"median response {times[len(times) // 2]}ms")
    print()


def summarise_probe(path, d):
    """Read a quantisation probe: does the emitted level move on a grid, and how coarse.

    The two outcomes look nothing alike and both are results. Periodic yes answers at a
    regular spacing say the strip renders every N commanded bytes and N is that spacing.
    No yes answers anywhere - with the anchors hit - says one commanded byte is genuinely
    below what the eye resolves here, which refutes the grid and leaves the sitting's
    three-byte threshold standing as a real one.

    Neither reading survives bad controls, so they are printed first.
    """
    print(f"=== {path}  (quantisation probe)")
    floor = d.get("floor")
    if floor:
        print(f"floor: first visible byte {floor['firstVisible']}, clearly lit {floor['clearlyOn']}")
    print(f"brightness as Joe had it: {d['brightnessPercent']}%")

    catch_n, catch_fp = d["catchTrials"], d["catchFalsePositives"]
    anchor_n, anchor_missed = d["anchorTrials"], d["anchorsMissed"]
    fp = catch_fp / catch_n if catch_n else 0.0
    miss = anchor_missed / anchor_n if anchor_n else 0.0
    print(f"no-change trials called changed: {catch_fp}/{catch_n} ({fp:.0%})")
    print(f"obvious changes missed:          {anchor_missed}/{anchor_n} ({miss:.0%})")
    if fp > FALSE_POSITIVE_LIMIT:
        print("  Guessing. The spacings below are not spacings. Discard.")
    if miss > FALSE_POSITIVE_LIMIT:
        print("  Attention lapsed. A segment reading 'saw nothing' below means nothing.")
    print()

    for seg in d["segments"]:
        commanded = seg["commandedBrightness"]
        at = f"at {commanded}% brightness" if commanded is not None else "at his own brightness"
        changes, spacings = seg["changeAtBytes"], seg["spacings"]
        print(f"{seg['label']} ({at}), {seg['stepTrials']} one-byte steps:")
        if seg["stepTrials"] == 0:
            print("  not reached")
        elif not changes:
            print("  no change seen at any single byte - no grid this coarse, or none at all")
        else:
            print(f"  changed at bytes {changes}")
            if spacings:
                print(f"  spacings {spacings}, mean {seg['meanSpacing']:.2f} bytes")
            else:
                print("  one change only - not enough to call a spacing")
        print()

    low = next((s for s in d["segments"] if s["label"] == "low"), None)
    high = next((s for s in d["segments"] if s["label"] == "high"), None)
    full = next((s for s in d["segments"] if s["label"] == "low_full"), None)
    if low and high and low["meanSpacing"] and high["meanSpacing"]:
        ratio = high["meanSpacing"] / low["meanSpacing"]
        shape = "constant - consistent with an integer multiply" if ratio < 1.5 else \
            "widens with level - NOT a plain integer multiply"
        print(f"low vs high spacing: {low['meanSpacing']:.2f} -> {high['meanSpacing']:.2f}  {shape}")
    if low and full and low["meanSpacing"]:
        if full["meanSpacing"]:
            print(
                f"his brightness vs 100%: {low['meanSpacing']:.2f} -> {full['meanSpacing']:.2f} bytes"
            )
            predicted = low["meanSpacing"] * d["brightnessPercent"] / 100.0
            print(
                f"  an integer multiply predicts {predicted:.2f} at 100%"
                f" (spacing x {d['brightnessPercent']}%)"
            )
        elif full["stepTrials"]:
            print("his brightness gridded; at 100% no single byte was visible at all - unexpected")
    print()


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    found = False
    for path, d in load(argv[1:]):
        found = True
        if d.get("kind") == "quantisation_probe":
            summarise_probe(path, d)
        else:
            summarise(path, d)
    if not found:
        print("no sittings matched")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
