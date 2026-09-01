"""Wire-to-light latency: how long after a write does the wall actually get brighter?

The `latency_pulse` sequence blinks the strips 12 times, logging the ms at which each on/off command
was handed to BLE. This finds the video frame where the reflected light rises for each pulse.

**It cannot report absolute latency, and neither can any run shaped like this one.** The phone's
clock and the camera's clock share no reference, so video time has to be pinned to log time using
something visible — and the only visible things are the strips themselves, which arrive already
delayed by the very latency being measured. Aligning on the sync flashes therefore subtracts the
latency out: the mean is zero by construction, and the earlier negative means were that, not a
signal. What survives the alignment is the *spread* — how unevenly writes land — which is the part
that actually changes how a preset feels, and which no constant offset can hide.

Measuring the constant needs a second thing in frame whose time the phone knows: flash the phone's
own screen white on the same millisecond as the write and film screen and strip together. See
`README.md`.

Reads `cache_full_<region>.bin` from `decode_full.py` — the caches `analyse_ramp.py` wrote stop at
105s and the pulses run past that.
"""
import csv, os, statistics, sys

FPS = 59.47
# Video time of the pulse run's own sync flash is found per-region; commands are in its CSV.


def load_series(name):
    path = f"cache_full_{name}.bin"
    if not os.path.exists(path):
        sys.exit(f"{path} missing — run decode_full.py first")
    raw = open(path, "rb").read()
    return [tuple(raw[i:i + 3]) for i in range(0, len(raw) - 2, 3)]


def luma(px):
    return 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2]


def load_commands(path):
    with open(path) as fh:
        return [(int(r["elapsed_ms"]), r["label"]) for r in csv.DictReader(fh)]


def find_sync(series, from_frame, initial):
    """First flash of the three that open the sequence, searching from [from_frame].

    Same shape as analyse_ramp.find_sync, but the pulse run starts partway through the recording, so
    it takes an explicit starting point and an explicit "what does lit look like" level rather than
    assuming frame 0 is the bright reference.
    """
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


def rise_frame(series, start, baseline, peak):
    """First frame at or after [start] crossing halfway to [peak], plus a sub-frame estimate.

    One frame is 16.8ms, which is coarse next to the delay being measured, so interpolate across the
    transition frame: a change landing mid-exposure only part-lights that frame, and how part-lit it
    is says where in the frame it happened.
    """
    mid = baseline + (peak - baseline) * 0.5
    for index in range(start, min(len(series), start + int(FPS))):
        if luma(series[index]) > mid:
            prev = luma(series[index - 1]) if index > 0 else baseline
            here = luma(series[index])
            frac = (mid - prev) / (here - prev) if here > prev else 0.0
            return index, index - 1 + max(0.0, min(1.0, frac))
    return None, None


def main():
    csv_path = next(f for f in os.listdir(".") if "latency_pulse" in f and f.endswith(".csv"))
    commands = load_commands(csv_path)
    sync_cmd_ms = next(ms for ms, label in commands if label == "sync_flash")
    pulses = [(ms, label) for ms, label in commands if label.endswith("_on")]
    print(f"{csv_path}: {len(pulses)} pulses, sync flash at {sync_cmd_ms}ms")

    # The pulse run is the second sequence in the recording; skip past the brightness ramp so the
    # sync search cannot latch onto that run's flashes instead.
    SEARCH_FROM_S = 78

    for name in ("fireworks", "bar"):
        series = load_series(name)
        window = series[int(FPS * SEARCH_FROM_S):int(FPS * (SEARCH_FROM_S + 12))]
        bright = max(luma(p) for p in window)
        sync_frame = find_sync(series, int(FPS * SEARCH_FROM_S), bright)
        if sync_frame is None:
            print(f"\n{name}: no sync flash found after {SEARCH_FROM_S}s")
            continue
        print(f"\n=== {name} === sync flash at frame {sync_frame} ({sync_frame / FPS:.2f}s video)")
        print(f"{'pulse':>8} {'cmd ms':>8} {'pred frame':>11} {'rise':>7} {'interp':>8} "
              f"{'raw ms':>7} {'interp ms':>10}")

        deltas, interp_deltas, used, interp_frames = [], [], [], []
        for ms, label in pulses:
            predicted = sync_frame + (ms - sync_cmd_ms) / 1000 * FPS
            start = max(0, int(predicted) - 2)
            # Baseline from just before the command, peak from the lit part of the pulse.
            base_window = series[max(0, int(predicted) - 12):max(1, int(predicted) - 1)]
            baseline = sum(luma(p) for p in base_window) / len(base_window)
            peak_window = series[int(predicted) + 4:int(predicted) + 16]
            peak = max(luma(p) for p in peak_window) if peak_window else baseline
            if peak - baseline < 5:
                print(f"{label:>8} {ms:>8}  no rise (baseline {baseline:.1f}, peak {peak:.1f})")
                continue
            frame, sub = rise_frame(series, start, baseline, peak)
            if frame is None:
                print(f"{label:>8} {ms:>8}  no crossing found")
                continue
            raw_ms = (frame - predicted) / FPS * 1000
            sub_ms = (sub - predicted) / FPS * 1000
            deltas.append(raw_ms)
            interp_deltas.append(sub_ms)
            used.append((ms, label))
            interp_frames.append(sub)
            print(f"{label:>8} {ms:>8} {predicted:>11.1f} {frame:>7} {sub:>8.2f} "
                  f"{raw_ms:>7.1f} {sub_ms:>10.1f}")

        if len(deltas) >= 3:
            # Regress rise time on command time rather than trusting the sync frame and the nominal
            # FPS. The slope absorbs any camera-vs-phone clock drift over the 23s of pulses; the
            # intercept absorbs the unknowable constant offset. The residuals are then jitter alone.
            xs = [ms for ms, _ in used]
            ys = [sub for sub in interp_frames]
            n = len(xs)
            mx, my = statistics.mean(xs), statistics.mean(ys)
            slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
            residual_ms = [((y - (my + slope * (x - mx))) / FPS) * 1000 for x, y in zip(xs, ys)]
            print(f"  effective fps from the pulse span: {slope * 1000:.2f} "
                  f"(nominal {FPS}) — a check on the clock, not a result")
            print(f"  jitter about the fit: sd {statistics.stdev(residual_ms):.1f} ms, "
                  f"range {min(residual_ms):.1f}..{max(residual_ms):.1f} ms, "
                  f"peak-to-peak {max(residual_ms) - min(residual_ms):.1f} ms")
            print("  absolute latency: NOT MEASURABLE from this run — see the module docstring")


main()
