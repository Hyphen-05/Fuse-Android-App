"""Wire-side analysis of the rate ramp: what send rate did the phone actually achieve?

Each `rate_ramp` run asks for a series of write rates (2, 5, 10 ... 100 Hz) and logs the moment every
write was handed to the BLE stack. Where the achieved interval stops tracking the requested one is
the phone-side ceiling — no camera needed, this is all in the CSV.

Run it in the folder holding the CSVs. Prints one table per file, then the comparison across files
that gives the backgrounding cost and the per-strip contention.
"""
import csv, os, statistics, sys

# label -> what that run was, for the comparison at the end.
RUNS = {
    "1786842959739": "backgrounded, 2 strips",
    "1786843126085": "foreground, 2 strips",
    "1786843291187": "foreground, 1 strip",
}


def load(path):
    with open(path) as fh:
        return [(int(r["elapsed_ms"]), r["label"]) for r in csv.DictReader(fh)]


def groups(rows):
    """Requested rate -> the timestamps of its writes, in order."""
    out = {}
    for ms, label in rows:
        if not label.startswith("rate_") or label.endswith("_marker"):
            continue
        hz = int(label.split("_")[1])
        out.setdefault(hz, []).append(ms)
    return out


def analyse(path):
    rows = load(path)
    print(f"\n=== {os.path.basename(path)} ===")
    print(f"{'req Hz':>7} {'writes':>7} {'span s':>7} {'ach Hz':>7} {'req ms':>7} "
          f"{'med ms':>7} {'mean ms':>8} {'p95 ms':>7} {'max ms':>7} {'ratio':>6}")
    table = {}
    for hz, stamps in sorted(groups(rows).items()):
        if len(stamps) < 3:
            continue
        gaps = [b - a for a, b in zip(stamps, stamps[1:])]
        span = (stamps[-1] - stamps[0]) / 1000
        achieved = (len(stamps) - 1) / span if span else 0
        p95 = sorted(gaps)[int(len(gaps) * 0.95)]
        table[hz] = achieved
        print(f"{hz:>7} {len(stamps):>7} {span:>7.1f} {achieved:>7.1f} {1000 / hz:>7.1f} "
              f"{statistics.median(gaps):>7.1f} {statistics.mean(gaps):>8.1f} {p95:>7} "
              f"{max(gaps):>7} {achieved / hz:>6.2f}")
    return table


def main():
    files = sorted(f for f in os.listdir(".") if "rate_ramp" in f and f.endswith(".csv"))
    if not files:
        sys.exit("no rate_ramp CSVs in this folder")
    tables = {}
    for path in files:
        stamp = path.rsplit("_", 1)[-1][:-4]
        tables[RUNS.get(stamp, stamp)] = analyse(path)

    names = list(tables)
    print("\n=== achieved Hz, side by side ===")
    print(f"{'req Hz':>7} " + " ".join(f"{n:>24}" for n in names))
    for hz in sorted(set().union(*(t.keys() for t in tables.values()))):
        cells = " ".join(f"{tables[n].get(hz, float('nan')):>24.1f}" for n in names)
        print(f"{hz:>7} {cells}")

    # Nothing here saturates — achieved rate keeps climbing to the top of the ramp. What the runs
    # really show is a fixed per-write cost added to whatever interval was asked for, so fit that
    # instead of quoting a ceiling the data never reached.
    print("\n=== per-write overhead (achieved interval - requested interval) ===")
    for name, table in tables.items():
        over = [1000 / achieved - 1000 / hz for hz, achieved in table.items() if achieved > 0]
        print(f"{name:>24}: {statistics.median(over):>5.1f} ms median, "
              f"{min(over):.1f}-{max(over):.1f} ms range")

    fg2, fg1 = "foreground, 2 strips", "foreground, 1 strip"
    bg2 = "backgrounded, 2 strips"
    if fg2 in tables and fg1 in tables:
        ceil2 = max(tables[fg2].values())
        ceil1 = max(tables[fg1].values())
        print(f"\nceiling, foreground: {ceil1:.1f} Hz with 1 strip, {ceil2:.1f} Hz with 2 "
              f"-> per-strip factor {ceil2 / ceil1:.2f}")
    if bg2 in tables and fg2 in tables:
        print(f"ceiling, 2 strips: {max(tables[fg2].values()):.1f} Hz foreground, "
              f"{max(tables[bg2].values()):.1f} Hz backgrounded "
              f"-> backgrounding keeps {max(tables[bg2].values()) / max(tables[fg2].values()):.2f}")


main()
