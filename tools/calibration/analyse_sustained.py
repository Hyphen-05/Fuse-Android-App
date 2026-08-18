"""Does the link survive an hour flat out? Wire-side only — no video, no camera.

This is the measurement standing between the pacing work and shipping it. Every ramp before it ran
flat out for ~15 seconds, so "an hour of this" was genuinely unmeasured, and `sustained_load` fills
that gap by writing with no artificial delay at all — precisely the configuration that removing the
pacing wait would ship.

Three failure modes are possible and only the first is one the app currently notices at runtime:

  * **disconnection** — the run stops early, or a long gap appears mid-trace
  * **degradation** — the achieved rate sags over time (thermal, buffer pressure, radio contention)
  * **stalls** — the rate holds on average while hiding multi-second gaps

So the summary reports all three separately. A mean rate that looks healthy can sit on top of a
trace that stalled for four seconds every minute, and averaging is exactly what would hide it.

Run it in the folder holding the CSV:  python analyse_sustained.py [file.csv]
"""
import csv, glob, statistics, sys

# A gap longer than this is not jitter. The measured per-write cost is ~5ms and delivery jitter is
# sd 36ms, so a quarter-second of silence means something actually stopped.
STALL_MS = 250

# Rate is reported per this window. Long enough to be stable, short enough that an hour is 60 points.
BUCKET_MS = 60_000


def load(path):
    rows = []
    with open(path) as fh:
        for r in csv.DictReader(fh):
            label = r["label"]
            if label.startswith("sustained") and not label.endswith("_marker"):
                rows.append(int(r["elapsed_ms"]))
    return sorted(rows)


def main():
    paths = sys.argv[1:] or sorted(glob.glob("fuse_calibration_sustained_load_*.csv"))
    if not paths:
        print("No sustained_load CSV found here.")
        return

    for path in paths:
        ts = load(path)
        if len(ts) < 2:
            print(f"{path}: no writes logged.")
            continue

        duration_s = (ts[-1] - ts[0]) / 1000.0
        gaps = [b - a for a, b in zip(ts, ts[1:])]
        stalls = [(a, b - a) for a, b in zip(ts, ts[1:]) if b - a > STALL_MS]

        print(f"\n=== {path} ===")
        print(f"writes           {len(ts)}")
        print(f"ran for          {duration_s / 60:.1f} min")
        print(f"overall rate     {len(ts) / duration_s:.1f} writes/s")
        print(f"median gap       {statistics.median(gaps):.1f} ms")
        print(f"p99 gap          {sorted(gaps)[int(len(gaps) * 0.99)]:.1f} ms")

        # Did it actually last the hour it was asked for?
        if duration_s < 55 * 60:
            print(f"\n** ENDED EARLY — asked for 60 min, got {duration_s / 60:.1f}. "
                  f"That is a disconnection, not a rate result. **")

        print(f"\nstalls over {STALL_MS}ms: {len(stalls)}")
        for at, ms in stalls[:15]:
            print(f"    at {at / 1000:7.1f}s   {ms:6d} ms")
        if len(stalls) > 15:
            print(f"    ... and {len(stalls) - 15} more")

        # Degradation: rate per minute, first tenth against last tenth.
        buckets = {}
        for t in ts:
            buckets.setdefault((t - ts[0]) // BUCKET_MS, []).append(t)
        keys = sorted(buckets)
        print("\nrate by minute (writes/s):")
        line = "  ".join(f"{k:02d}:{len(buckets[k]) / (BUCKET_MS / 1000):.0f}" for k in keys)
        print("    " + line)

        head = keys[: max(1, len(keys) // 10)]
        tail = keys[-max(1, len(keys) // 10):]
        first = sum(len(buckets[k]) for k in head) / (len(head) * BUCKET_MS / 1000)
        last = sum(len(buckets[k]) for k in tail) / (len(tail) * BUCKET_MS / 1000)
        change = (last - first) / first * 100 if first else 0
        print(f"\nfirst tenth      {first:.1f} writes/s")
        print(f"last tenth       {last:.1f} writes/s")
        print(f"change           {change:+.1f}%")
        if change < -10:
            print("\n** DEGRADED — the link is slower at the end than the start. "
                  "Do not remove the pacing wait on this evidence. **")
        elif not stalls and duration_s >= 55 * 60:
            print("\nClean: full duration, no stalls, no sag. This is what clears Phase 3 step 2.")


if __name__ == "__main__":
    main()
