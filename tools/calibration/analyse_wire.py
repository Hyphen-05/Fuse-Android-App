#!/usr/bin/env python3
"""Turn the raw wire logs into the two tables a model actually needs:

  derived/wire_runs.csv       one row per run x device: sends, acks, survival
  derived/wire_rate_curve.csv one row per rate stair: requested Hz vs delivered Hz

Reads the paired CSVs in tools/calibration/ (a `*_wire_*` log and the `*_calibration_*`
or `*_latency_*` colour log written by the same run, matched on the run timestamp).
"""
import csv, glob, os, re, statistics
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")
os.makedirs(OUT, exist_ok=True)

NAMES = {  # address -> what it physically is (docs/positions.md)
    "11:66:F0:00:01:3D": "Fireworks (wall starburst)",
    "BE:16:0A:00:3B:DA": "RGB Bars (window pair)",
}


def read_rows(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(r for r in f if not r.startswith("#")))


def runs():
    """Every wire log, paired with its colour log by the millisecond timestamp in the name."""
    for wire in sorted(glob.glob(os.path.join(HERE, "*_wire_*.csv"))):
        base = os.path.basename(wire)
        m = re.match(r"(.*?)fuse_wire_(.+)_(\d+)\.csv$", base)
        prefix, seq, ts = m.group(1), m.group(2), int(m.group(3))
        # the colour log of the same run carries a timestamp within a few ms
        best, bestd = None, 10_000
        for c in glob.glob(os.path.join(HERE, "*_calibration_*.csv")) + glob.glob(
            os.path.join(HERE, "*_latency_*.csv")
        ):
            cm = re.search(r"_(\d+)\.csv$", os.path.basename(c))
            d = abs(int(cm.group(1)) - ts)
            if d < bestd and seq in os.path.basename(c):
                best, bestd = c, d
        yield prefix.rstrip("_") or "-", seq, wire, best


def main():
    per_run = []
    rate_rows = []

    for prefix, seq, wire_path, colour_path in runs():
        wire = read_rows(wire_path)
        devices = sorted({r["address"] for r in wire})
        for addr in devices:
            sends = [r for r in wire if r["address"] == addr and r["event"] == "send"]
            acks = [r for r in wire if r["address"] == addr and r["event"] == "ack"]
            bad = [a for a in acks if a["status"] not in ("0", "")]
            span = (int(wire[-1]["elapsed_ms"]) - int(wire[0]["elapsed_ms"])) / 1000.0
            out = [int(r["outstanding"]) for r in sends if r["outstanding"]]
            per_run.append(
                dict(
                    condition=prefix,
                    sequence=seq,
                    device=NAMES.get(addr, addr),
                    address=addr,
                    devices_in_run=len(devices),
                    span_s=round(span, 2),
                    sends=len(sends),
                    acks=len(acks),
                    survival=round(len(acks) / len(sends), 4) if sends else "",
                    send_hz=round(len(sends) / span, 2) if span else "",
                    ack_hz=round(len(acks) / span, 2) if span else "",
                    nonzero_status_acks=len(bad),
                    max_outstanding=max(out) if out else "",
                )
            )

        # rate stairs: the colour log's rate_<hz>_marker rows cut the run into windows
        if colour_path and seq == "rate_ramp":
            colour = read_rows(colour_path)
            marks = [
                (int(r["elapsed_ms"]), int(re.match(r"rate_(\d+)_marker", r["label"]).group(1)))
                for r in colour
                if re.match(r"rate_\d+_marker", r["label"] or "")
            ]
            for i, (t0, hz) in enumerate(marks):
                t1 = marks[i + 1][0] if i + 1 < len(marks) else int(colour[-1]["elapsed_ms"])
                dur = (t1 - t0) / 1000.0
                for addr in devices:
                    s = [
                        r
                        for r in wire
                        if r["address"] == addr
                        and r["event"] == "send"
                        and t0 <= int(r["elapsed_ms"]) < t1
                    ]
                    a = [
                        r
                        for r in wire
                        if r["address"] == addr
                        and r["event"] == "ack"
                        and t0 <= int(r["elapsed_ms"]) < t1
                    ]
                    gaps = sorted(
                        int(a[j + 1]["elapsed_ms"]) - int(a[j]["elapsed_ms"])
                        for j in range(len(a) - 1)
                    )
                    rate_rows.append(
                        dict(
                            condition=prefix,
                            device=NAMES.get(addr, addr),
                            devices_in_run=len(devices),
                            requested_hz=hz,
                            window_s=round(dur, 2),
                            sends=len(s),
                            acks=len(a),
                            survival=round(len(a) / len(s), 4) if s else "",
                            actual_send_hz=round(len(s) / dur, 2) if dur else "",
                            delivered_hz=round(len(a) / dur, 2) if dur else "",
                            median_ack_gap_ms=statistics.median(gaps) if gaps else "",
                        )
                    )

    def write(name, rows):
        if not rows:
            return
        with open(os.path.join(OUT, name), "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
        print(f"{name}: {len(rows)} rows")

    per_run.sort(key=lambda r: (r["sequence"], r["condition"], r["address"]))
    rate_rows.sort(key=lambda r: (r["condition"], r["device"], r["requested_hz"]))
    write("wire_runs.csv", per_run)
    write("wire_rate_curve.csv", rate_rows)


if __name__ == "__main__":
    main()
