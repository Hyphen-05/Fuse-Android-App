#!/usr/bin/env python3
"""The two in-app camera probes, turned into per-event tables.

  derived/latency_edges.csv    one row per commanded on/off edge, as a bracket
  derived/latency_summary.csv  rise/fall distribution per run, with an exposure-lock verdict
  derived/pwm_duty.csv         one row per held duty cycle: luma, spread, flicker verdict

`LatencyCameraProbe` writes one `frame` row per camera frame (with mean luma) and one `write` row
per command, all stamped on the same clock in the same process — which is the whole reason these
numbers mean anything. Frame timestamps are compared on `sensor_ts_ns` when the header says the
sensor clock is `realtime`, and on `uptime_ns` otherwise; picking wrong yields a plausible number
that is wrong by however long the device has been asleep.

An edge is reported as a **bracket**, not a point. The camera runs at 30fps, so all the recording
can say is "still dark at t1, lit by t2". Quoting the crossing frame alone silently rounds every
edge up by up to a frame interval; quoting the bracket keeps the uncertainty attached to the
number. `latency_ms` is the midpoint, for when a single figure is needed.
"""
import csv, glob, os, re, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")
os.makedirs(OUT, exist_ok=True)


def load(path):
    with open(path, newline="") as f:
        head = f.readline()
        m = re.search(r"sensor_timestamp_source=(\S+)", head)
        return (m.group(1) if m else "unknown"), list(csv.DictReader(f))


def latency(path):
    src, rows = load(path)
    fcol = "sensor_ts_ns" if src == "realtime" else "uptime_ns"
    wcol = "elapsed_ns" if src == "realtime" else "uptime_ns"
    frames = sorted((int(r[fcol]), float(r["luma"])) for r in rows if r["event"] == "frame")
    writes = [
        (int(r[wcol]), r["label"])
        for r in rows
        if r["event"] == "write" and re.match(r"latcam_\d+_(on|off)", r["label"] or "")
    ]
    lums = sorted(l for _, l in frames)
    lo = statistics.median(lums[: len(lums) // 4])
    hi = statistics.median(lums[-len(lums) // 4:])
    half = (lo + hi) / 2

    out = []
    for i, (t, label) in enumerate(writes):
        rising = label.endswith("_on")
        stop = writes[i + 1][0] if i + 1 < len(writes) else frames[-1][0]
        after = [(ft, fl) for ft, fl in frames if t <= ft <= stop]
        if not after:
            continue
        # Last frame still on the old side, first frame on the new side: the light moved
        # somewhere between the two, and nothing in a 30fps recording can say where.
        last_old = first_new = None
        for ft, fl in after:
            crossed = fl >= half if rising else fl <= half
            if crossed:
                first_new = ft
                break
            last_old = ft
        if first_new is None:
            continue
        # The level the light was holding just before this edge, for the exposure-lock check:
        # on a locked exposure a fall is preceded by the same level a rise settled to.
        before = [fl for ft, fl in frames if ft < t][-3:]
        out.append(
            dict(
                run=os.path.basename(path),
                edge="rise" if rising else "fall",
                label=label,
                clock=fcol,
                lat_lo_ms=round(((last_old - t) / 1e6) if last_old else 0.0, 1),
                lat_hi_ms=round((first_new - t) / 1e6, 1),
                latency_ms=round(((((last_old or t) + first_new) / 2) - t) / 1e6, 1),
                pre_edge_luma=round(statistics.mean(before), 1) if before else "",
                post_edge_luma=round(
                    statistics.mean(
                        [fl for ft, fl in frames if ft > first_new][2:5] or [0.0]
                    ),
                    1,
                ),
            )
        )
    return src, lo, hi, out


def trim_settled(lum):
    """Drop the lead-in.

    Every pwm run opens at full brightness for sync before the duty is applied, and those frames
    are not the measurement — at 5% duty they are fourteen times the held level and would dominate
    any mean. Cut everything up to the last large excursion from the held level, then a further
    second to settle.
    """
    tail = lum[len(lum) // 2:]
    ref = statistics.median(tail)
    band = max(1.5, 0.15 * ref)
    start = 0
    for i, v in enumerate(lum):
        if abs(v - ref) > band:
            start = i + 1
    return lum[start + 30:] or lum


def pwm(path):
    _, rows = load(path)
    raw = [float(r["luma"]) for r in rows if r["event"] == "frame"]
    lum = trim_settled(raw)
    m, sd = statistics.mean(lum), statistics.pstdev(lum)
    return dict(
        file=os.path.basename(path),
        frames_total=len(raw),
        frames_held=len(lum),
        mean_luma=round(m, 3),
        sd=round(sd, 4),
        cv_pct=round(100 * sd / m, 2) if m else "",
        min_luma=round(min(lum), 2),
        max_luma=round(max(lum), 2),
        # Exposure is pinned at 5ms. A carrier slow enough to be *seen* has a period longer than
        # that and so scatters the frame means; a faster one averages out inside the exposure and
        # is imperceptible anyway. Under a fifth of one 8-bit level is not a visible carrier.
        flicker_visible="no" if sd < 0.5 else "CHECK",
    )


def write(name, rows):
    if not rows:
        return
    with open(os.path.join(OUT, name), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f"{name}: {len(rows)} rows")


def main():
    edges, summary = [], []
    for p in sorted(glob.glob(os.path.join(HERE, "*fuse_latency_latency_camera*.csv"))):
        src, lo, hi, rows = latency(p)
        if not rows:
            continue
        edges += rows
        # If auto-exposure was still hunting, the level the light holds sags away during each
        # on-period and every threshold crossing is measured against a moving target. The tell is
        # comparing what a rise settled to against what the following fall started from: on a
        # locked exposure those are the same number.
        pairs = [
            (rise["post_edge_luma"], fall["pre_edge_luma"])
            for rise, fall in zip(rows[0::2], rows[1::2])
            if rise["edge"] == "rise" and fall["edge"] == "fall" and rise["post_edge_luma"]
        ]
        decay = statistics.mean((a - b) / a for a, b in pairs) if pairs else 0
        for kind in ("rise", "fall"):
            v = sorted(r["latency_ms"] for r in rows if r["edge"] == kind)
            if not v:
                continue
            summary.append(
                dict(
                    run=os.path.basename(p),
                    sensor_clock=src,
                    edge=kind,
                    n=len(v),
                    min_ms=v[0],
                    median_ms=statistics.median(v),
                    mean_ms=round(statistics.mean(v), 1),
                    sd_ms=round(statistics.pstdev(v), 1),
                    max_ms=v[-1],
                    bracket_width_ms=round(
                        statistics.mean(
                            r["lat_hi_ms"] - r["lat_lo_ms"] for r in rows if r["edge"] == kind
                        ),
                        1,
                    ),
                    settled_low_luma=round(lo, 2),
                    settled_high_luma=round(hi, 2),
                    on_level_decay_pct=round(100 * decay, 1),
                    usable="yes" if decay < 0.1 else "no - exposure hunting",
                )
            )
    write("latency_edges.csv", edges)
    write("latency_summary.csv", summary)

    rows = [pwm(p) for p in sorted(glob.glob(os.path.join(HERE, "*fuse_latency_pwm_probe*.csv")))]
    # The probe does not record the duty it was holding; the four runs were shot in ascending
    # duty order, which must therefore also be ascending luma. Asserted rather than assumed.
    ladder = [5, 10, 40, 100]
    assert [r["mean_luma"] for r in rows] == sorted(r["mean_luma"] for r in rows), "duty order"
    full = rows[-1]["mean_luma"]
    rows = [
        {"duty_pct": d, **r, "luma_frac_of_full": round(r["mean_luma"] / full, 4)}
        for d, r in zip(ladder, rows)
    ]
    write("pwm_duty.csv", rows)


if __name__ == "__main__":
    main()
