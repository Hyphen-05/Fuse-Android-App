"""Light side of the rate ramp: of the writes the phone sent, how many did the strip render?

`analyse_rate.py` says what left the phone. This says what arrived. Where the wall stops alternating
once per commanded write, writes are being dropped strip-side, which is what
`StripLimits.minWriteSpacingMs` models.

Only the **backgrounded** rate-ramp run is on video — it is the one that was running while the
camera was in front (which is why it was backgrounded at all). Its wire rate is roughly half the
foreground runs', so treat "rendered / sent" here as a per-write question, not as a rate ceiling.

Rates above ~25 Hz are skipped: alternating writes at 30 Hz produce a 15 Hz square wave, and 60fps
video cannot separate "rendered every write" from "rendered every other write" much beyond that.
"""
import csv, os, statistics, sys

FPS = 59.47
RATE_RAMP_STARTS_S = 130   # the third sequence in the recording
MAX_RATE_HZ = 25


def load_series(name):
    path = f"cache_full_{name}.bin"
    if not os.path.exists(path):
        sys.exit(f"{path} missing — run decode_full.py first")
    raw = open(path, "rb").read()
    return [tuple(raw[i:i + 3]) for i in range(0, len(raw) - 2, 3)]


def luma(px):
    return 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2]


def find_sync(series, from_frame, initial):
    dark_at = None
    for index in range(from_frame, len(series)):
        if luma(series[index]) < initial * 0.4:
            dark_at = index
            break
    if dark_at is None:
        return None
    for index in range(dark_at, len(series)):
        if luma(series[index]) > initial * 0.6:
            return index
    return None


def rendered(series, frame_of, writes):
    """Of these writes, how many moved the light the way they asked it to?

    Counting flashes blind does not work here. The lit level drifts downward within a group
    (75 → 48 across the 2 Hz run), so any fixed threshold stops being crossed partway through, and
    every edge-detector threshold I tried moved the answer by 30% — a sure sign the number was the
    detector's, not the hardware's. So ask the question per write instead: the log says this write
    should have driven the wall up (or down) at this moment, did it?

    Each write is checked over a window wide enough to absorb the delivery jitter measured by
    `analyse_latency.py` (about ±50ms), which is why this is only meaningful when writes are further
    apart than that.
    """
    hits, misses = 0, []
    for ms, value in writes:
        centre = frame_of(ms)
        before = [luma(p) for p in series[centre - 6:centre - 1]]
        after = [luma(p) for p in series[centre + 3:centre + 9]]
        if len(before) < 3 or len(after) < 3:
            continue
        moved = statistics.mean(after) - statistics.mean(before)
        want_up = value > 0
        if abs(moved) > 6 and (moved > 0) == want_up:
            hits += 1
        else:
            misses.append((ms, round(moved, 1)))
    return hits, misses


def main():
    path = next(f for f in os.listdir(".")
                if "rate_ramp_1786842959739" in f and f.endswith(".csv"))
    with open(path) as fh:
        rows = [(int(r["elapsed_ms"]), r["label"], int(r["r"])) for r in csv.DictReader(fh)]
    sync_cmd_ms = next(ms for ms, label, _ in rows if label == "sync_flash")

    groups = {}
    for ms, label, value in rows:
        if label.startswith("rate_") and not label.endswith("_marker"):
            groups.setdefault(int(label.split("_")[1]), []).append((ms, value))

    print(f"{os.path.basename(path)} — backgrounded run, the only one on video")

    for name in ("fireworks", "bar"):
        series = load_series(name)
        probe = series[int(FPS * RATE_RAMP_STARTS_S):int(FPS * (RATE_RAMP_STARTS_S + 12))]
        sync_frame = find_sync(series, int(FPS * RATE_RAMP_STARTS_S),
                               max(luma(p) for p in probe))
        if sync_frame is None:
            print(f"\n{name}: no sync flash found after {RATE_RAMP_STARTS_S}s")
            continue
        print(f"\n=== {name} === sync flash at frame {sync_frame} ({sync_frame / FPS:.2f}s video)")
        print(f"{'req Hz':>7} {'achieved':>9} {'spacing':>8} {'sent':>6} {'rendered':>9} "
              f"{'ratio':>6}  missed")

        def frame_of(ms):
            return int(sync_frame + (ms - sync_cmd_ms) / 1000 * FPS)

        for hz, writes in sorted(groups.items()):
            spacing = (writes[-1][0] - writes[0][0]) / (len(writes) - 1)
            # 200ms, not the 25 Hz the plan assumed. Delivery jitter is ±50ms (analyse_latency.py)
            # and a frame is 17ms, so below about this spacing a write's edge can land either side
            # of where the log says it should and "was it rendered" stops being answerable. The
            # backgrounded run's own slowness helps here — it stretched the low rates out.
            if hz > MAX_RATE_HZ or spacing < 200:
                print(f"{hz:>7} {1000 / spacing:>9.1f} {spacing:>8.0f}  writes closer together "
                      f"than the jitter and the frame rate can separate — needs a slow-mo run")
                continue
            if frame_of(writes[-1][0]) + 9 >= len(series):
                print(f"{hz:>7} {1000 / spacing:>9.1f} {spacing:>8.0f}  past the end of the "
                      f"recording")
                continue
            # The constant wire-to-light delay is unknown (see analyse_latency.py) and at these
            # spacings a wrong guess puts the sampling window on the wrong side of the edge, which
            # reads as a drop. So sweep the offset and keep the best fit: the question here is
            # whether every write got rendered, not when. A group with real drops has no offset that
            # explains it — the ratio stays low at every shift.
            best = max(
                (rendered(series, lambda ms, o=offset: frame_of(ms + o), writes) + (offset,)
                 for offset in range(-40, 200, 5)),
                key=lambda result: result[0],
            )
            hits, misses, offset = best
            print(f"{hz:>7} {1000 / spacing:>9.1f} {spacing:>8.0f} {len(writes):>6} {hits:>9} "
                  f"{hits / len(writes):>6.2f}  best at {offset:+d}ms"
                  f"{'' if not misses else f', missed {[m[0] for m in misses]}'}")


main()
