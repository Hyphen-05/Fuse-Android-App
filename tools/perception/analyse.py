#!/usr/bin/env python3
"""Read a Perception Lab sitting and say what it establishes.

Usage:  python tools/perception/analyse.py tools/perception/results/perception_*.json

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


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    found = False
    for path, d in load(argv[1:]):
        found = True
        summarise(path, d)
    if not found:
        print("no sittings matched")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
