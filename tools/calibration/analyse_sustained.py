"""Does the link survive a long run flat out? Wire-side only - no video, no camera.

This is the measurement standing between the pacing work and shipping it. Every ramp before it ran
flat out for ~15 seconds, so sustained behaviour was unmeasured, and `sustained_load` fills that gap
by writing with no artificial delay at all - precisely the configuration that removing the pacing
wait would ship.

Three failure modes are possible and only the first is one the app notices at runtime:

  * **disconnection** - the run stops early, or a long gap appears mid-trace
  * **degradation** - the achieved rate sags over time (thermal, buffer pressure, contention)
  * **stalls** - the rate holds on average while hiding multi-second gaps

They are reported separately on purpose. A mean rate that looks healthy can sit on top of a trace
that stopped dead for four seconds every minute, and averaging is exactly what would hide it.

**What this CSV can and cannot tell you.** It logs every write the sequence *offered*, not every
write the strip *received*. Those differ a lot: the offer loop runs at ~300/s while the measured
ceiling is ~115/s for two strips, so most offers are coalesced away in the write queue by design.

The consequence matters more than it looks: **a disconnection mid-run would be invisible here.** The
emit loop keeps running happily against a dead link, so a clean trace is evidence the *app* kept
running, not that the *link* survived. To answer the question the run is actually for, pair it with
the app's own telemetry - the 1Hz DeviceWriteManager line, or a logcat check for disconnects across
the run window.

Run it in the folder holding the CSV:
    python analyse_sustained.py [file.csv] [expected_minutes]
"""
import csv, glob, statistics, sys

# A gap longer than this is not jitter. The measured per-write cost is ~5ms and delivery jitter is
# sd 36ms, so a quarter-second of silence means something actually stopped.
STALL_MS = 250

# Rate is reported per this window: long enough to be stable, short enough to show a trend.
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
    args = sys.argv[1:]
    # A trailing bare number is the duration the run was asked for. Without it this script has no
    # way to know a 15-minute trace was intended, and must not accuse it of ending early - which
    # is exactly what the first version of it did.
    expected_min = None
    if args and args[-1].isdigit():
        expected_min = int(args[-1])
        args = args[:-1]

    paths = args or sorted(glob.glob("fuse_calibration_sustained_load_*.csv"))
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
        print(f"offered rate     {len(ts) / duration_s:.1f} writes/s  (offered, not delivered)")
        print(f"median gap       {statistics.median(gaps):.1f} ms")
        print(f"p99 gap          {sorted(gaps)[int(len(gaps) * 0.99)]:.1f} ms")

        ended_early = expected_min is not None and duration_s < expected_min * 60 * 0.92
        if ended_early:
            print(f"\n** ENDED EARLY - asked for {expected_min} min, got {duration_s / 60:.1f}.")
            print("   That is the sequence stopping, not a rate result. **")

        print(f"\nstalls over {STALL_MS}ms: {len(stalls)}")
        for at, ms in stalls[:15]:
            print(f"    at {at / 1000:7.1f}s   {ms:6d} ms")
        if len(stalls) > 15:
            print(f"    ... and {len(stalls) - 15} more")

        buckets = {}
        for t in ts:
            buckets.setdefault((t - ts[0]) // BUCKET_MS, []).append(t)
        keys = sorted(buckets)
        print("\nrate by minute (writes/s):")
        print("    " + "  ".join(
            f"{k:02d}:{len(buckets[k]) / (BUCKET_MS / 1000):.0f}" for k in keys))

        head = keys[: max(1, len(keys) // 10)]
        tail = keys[-max(1, len(keys) // 10):]
        first = sum(len(buckets[k]) for k in head) / (len(head) * BUCKET_MS / 1000)
        last = sum(len(buckets[k]) for k in tail) / (len(tail) * BUCKET_MS / 1000)
        change = (last - first) / first * 100 if first else 0
        print(f"\nfirst tenth      {first:.1f} writes/s")
        print(f"last tenth       {last:.1f} writes/s")
        print(f"change           {change:+.1f}%")

        if change < -10:
            print("\n** DEGRADED - slower at the end than the start.")
            print("   Do not remove the pacing wait on this evidence. **")
        elif not stalls and not ended_early:
            print("\nNo stalls, no sag: the offer loop held up for the whole run.")
            print("This is NOT a verdict on the link - see the note at the top of this file.")
            print("Cross-check app telemetry for disconnects before clearing Phase 3 step 2.")


if __name__ == "__main__":
    main()
