#!/usr/bin/env python3
"""Collapse the derived tables into one file a simulator can be built from.

  derived/device_model.json

Every entry carries where it came from and how far it can be trusted, because a model that states
a number without its provenance is indistinguishable from a model that made it up. `confidence` is
the honest verdict, not a decoration: `measured` means two independent looks agree, `single` means
one measurement stands alone, `bounded` means only a range was established, and `absent` means the
sequence ran and the strip did nothing.

## What changed on 2026-09-03

The byte-to-light curve came from `response_stitched.csv` — two takes at two exposures, joined by a
factor fitted from their overlap, with no dark floor subtracted. It now comes from
`response_x3.csv`, which subtracts the floor and measures every byte in one run. The bottom of the
curve moved a long way as a result: half the output arrives at byte 67, not byte 40.

**Its confidence dropped at the same time, and that is the more important half.** The x3 run's
premise was that three known exposures would confirm each other. They do not: two of the three are
bad instruments, the disagreement is level-dependent, and the curve rests on one exposure. The
reasoning is in `analyse_ramp_x3.py`'s docstring, and the per-byte disagreement now travels with
the curve in the `agree_ratio` column. Anything downstream that treats this LUT as settled to
better than a factor of two below byte 16 is over-reading it.
"""
import csv, json, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")


def rows(name):
    with open(os.path.join(OUT, name), newline="") as f:
        return list(csv.DictReader(f))


def num(v):
    return float(v) if v not in ("", None) else None


def response_section():
    x3 = rows("response_x3.csv")
    lut = {int(r["byte"]): num(r["light_norm"]) for r in x3}
    # Byte 0 is the reference floor and byte 1 fell under the noise: both emit nothing.
    for b in (0, 1):
        lut.setdefault(b, 0.0)
        if lut[b] is None:
            lut[b] = 0.0
    agree = {int(r["byte"]): num(r["agree_ratio"]) for r in x3}
    # The longest *contiguous* stretch where the two exposures are within 25% of each other.
    # Min-to-max over scattered hits would report a range that mostly disagrees.
    best = run = []
    for b in range(256):
        a = agree.get(b)
        run = run + [b] if a is not None and abs(a - 1) <= 0.25 else []
        best = run if len(run) > len(best) else best
    close = best
    return {
        "what": "relative light emitted for a commanded byte, white (r=g=b), firmware "
                "brightness pinned to 100%. Normalised so byte 255 = 1.0.",
        "lut": [lut[b] for b in range(256)],
        "half_output_reached_at_byte": next(b for b in range(256) if lut[b] >= 0.5),
        "black_point_bytes": [b for b in range(256) if lut[b] == 0.0],
        "minimum_non_zero_output": lut[2],
        "confidence": "single",
        "measured_from": "15ms/iso3200 only, the one exposure of the three that spans the "
                         "encoding (luma 1.8 to 209 of 255)",
        "exposures_disagree": {
            "what": "5ms/iso800 relative to 15ms/iso3200 once both are put on the known "
                    "exposure x ISO scale. 1.00 would be agreement.",
            "at_byte_16": agree.get(16),
            "at_byte_64": agree.get(64),
            "at_byte_255": agree.get(255),
            "bytes_within_25_pct": [min(close), max(close)] if close else None,
            "why": "the 5ms take never rises above luma 61, so the whole curve sits in the "
                   "bottom quarter of the encoding where undoing sRGB does not undo what the "
                   "ISP did. pwm_duty.csv reads 32% low at luma 17.5 and 60% low at luma 5.7 "
                   "against a near-linear stimulus, which is the same shortfall.",
        },
        "caveat": "the bottom few bytes are a lower bound on the light, not a measurement of "
                  "it: byte 6 and below read under luma 20, where the pwm_duty check shows a "
                  "30-60% understatement. The shape above byte 8 is sound; a specific low-byte "
                  "value is not.",
        "supersedes": "response_stitched.csv, which put half the output at byte 40 with no dark "
                      "floor subtracted and so counted the wall the strip was lighting",
        "source": "response_x3.csv",
    }


def write_path_section():
    rate = rows("wire_rate_curve.csv")
    # One ceiling per pacing setting, not one number for the lot: the 2026-09-03 sweep is four
    # separate conditions and a median across them describes nothing.
    ceilings = {}
    for cond in sorted({r["condition"] for r in rate}):
        v = [r for r in rate if r["condition"] == cond]
        top = sorted(v, key=lambda r: int(r["requested_hz"]))[-4:]
        delivered = [float(r["delivered_hz"]) for r in top]
        # A ceiling is a plateau: the top of the ladder stops moving. Where delivered is still
        # climbing with the request, the run found no ceiling and the median of it means nothing.
        plateau = (max(delivered) - min(delivered)) / max(delivered) < 0.15
        ceilings[cond] = {
            "ceiling_hz": round(statistics.median(delivered), 1) if plateau else None,
            "max_delivered_hz": round(max(float(r["delivered_hz"]) for r in v), 1),
            "plateaued": plateau,
        }
    # Where loss starts: the highest offered rate at which everything still arrived.
    clean = {}
    for cond in ceilings:
        ok = [
            float(r["actual_send_hz"])
            for r in rate
            if r["condition"] == cond and float(r["survival"]) >= 0.98
        ]
        if ok:
            clean[cond] = round(max(ok), 1)
    per_device = sorted({(r["condition"], r["device"]) for r in rate})
    return {
        "what": "how many of the app's writes reach the strip, and how fast",
        "delivered_ceiling_hz_by_condition": ceilings,
        "highest_offered_rate_with_no_loss_hz": clean,
        "ceiling_model": "delivered = min(offered, 1000 / (pacing_ms + 9)), the 9ms being write "
                         "turnaround. Below that ceiling nothing is lost at any pacing.",
        "ceiling_cause": "the app's own per-device write pacing (DeviceWriteManager, default "
                         "50ms), not BLE and not the strip",
        "strip_own_limit_hz": [89, 108],
        "strip_own_limit_note": "with pacing out of the way the app offered 107-111Hz and the "
                                "strip acked 76-79 of it, while everything at or below 89Hz "
                                "survived intact. The hardware is roughly six times faster than "
                                "the 50ms default has ever let it be.",
        "over_driving_penalty": "offering far more than the ceiling costs throughput and adds "
                                "multi-second stalls: 502Hz offered for 15 minutes delivered "
                                "67.5Hz in lumps, with 441 stalls over a second and 664s of the "
                                "904s run stalled. Asking for 89Hz delivered a clean 89Hz.",
        "second_device_costs": "nothing - each device gets its own full ceiling",
        "devices_measured": len(per_device),
        "confidence": "measured",
        "source": "wire_rate_curve.csv, wire_runs.csv",
    }


def cct_section():
    cct = rows("cct_probe.csv")
    lit = {r["sub_mode"] for r in cct if r["lit"] == "yes"}
    good = [r for r in cct if r["black_ref_ok"] == "yes" and r["sub_mode"] in lit]
    return {
        "what": "whether the strip's native warm/cold channels can be driven directly",
        "verdict": "yes, at sub-mode 0x03 only. Sub-modes 0x01 and 0x02 emit nothing at any "
                   "warm/cold combination.",
        "working_sub_mode": int(sorted(lit)[0]) if lit else None,
        "tail_byte_matters": False,
        "mode_switch_required": False,
        "levels_are_0_255_not_percent": True,
        "light_relative_to_white": {
            f"warm{r['warm']}_cold{r['cold']}": num(r["ref_frac"])
            for r in good
            if r["tail"] == "0"
        },
        "supersedes": "the earlier reading of cct_sweep as 'no light at all', which was the "
                      "wrong sub-mode byte rather than a missing white channel",
        "app_impact": "none. DuoCoProtocol.createCctCommand sends 0x02, but it is called only "
                      "from CalibrationSequences - the app's warmth slider goes through "
                      "RgbIntent.SetWarmth, which converts to Kelvin to RGB and sends an "
                      "ordinary colour command. Nothing shipping is broken.",
        "confidence": "measured",
        "source": "cct_probe.csv",
    }


def main():
    cross = rows("channel_crosstalk.csv")
    trans = rows("transitions.csv")
    pwm = rows("pwm_duty.csv")
    lat = [r for r in rows("latency_summary.csv") if r["usable"] == "yes"]

    model = {
        "device": "DuoCo-protocol LED strip, as measured 2026-09-02 and 2026-09-03 on Joe's "
                  "two units",
        "generated_from": "tools/calibration/derived/*.csv",
        "colour_byte_to_light": response_section(),
        "firmware_brightness_to_light": {
            "what": "the strip's own brightness setting, measured two independent ways",
            "duty_points": {r["duty_pct"]: num(r["luma_frac_of_full"]) for r in pwm},
            "duty_points_are_gamma_encoded": True,
            "notes": "1% and 2% emit identically - the setting has no effect below 3%. These "
                     "four points double as the check that catches the imaging pipeline "
                     "crushing shadows; see colour_byte_to_light.exposures_disagree.",
            "confidence": "measured",
            "source": "pwm_duty.csv, steps_measured.csv (bright_*_colour)",
        },
        "channels": {
            "what": "whether driving one colour channel disturbs the others",
            "verdict": "independent - the measured share of each channel holds constant as the "
                       "driven channel is swept, so there is no level-dependent mixing to model",
            "off_channel_share_is_camera_not_strip": True,
            # Compared within each driven channel, never across them: the three channels sit at
            # quite different shares (the camera's white balance), and pooling them would report
            # that difference as level dependence.
            "worst_share_drift_within_a_channel_above_byte_32": round(
                max(
                    max(v) - min(v)
                    for d in {r["driven"] for r in cross}
                    if (
                        v := [
                            float(r["off_channel_share"])
                            for r in cross
                            if r["driven"] == d and int(r["commanded"]) >= 32
                        ]
                    )
                ),
                3,
            ),
            "confidence": "measured",
            "source": "channel_crosstalk.csv",
        },
        "transition": {
            "what": "does the firmware jump to a new colour or glide to it",
            "verdict": "step",
            "slew_rate_needed_in_model": False,
            "resolved_to_ms": 33,
            "n": len(trans),
            "confidence": "bounded",
            "source": "transitions.csv",
        },
        "write_path": write_path_section(),
        "latency": {
            "what": "command handed to the BLE stack -> light changes, both on one clock",
            "rise_median_ms": num(next(r["median_ms"] for r in lat if r["edge"] == "rise")),
            "fall_median_ms": num(next(r["median_ms"] for r in lat if r["edge"] == "fall")),
            "bracket_ms": 33,
            "note": "midpoint of the frame bracket. A crossing-frame figure - which is what was "
                    "quoted before this analysis - is biased high by half a frame interval.",
            "confidence": "single",
            "source": "latency_summary.csv (the run whose exposure was locked)",
        },
        "cct": cct_section(),
    }
    p = os.path.join(OUT, "device_model.json")
    with open(p, "w") as f:
        json.dump(model, f, indent=2)
    print(f"device_model.json: {len(model)} sections")
    r = model["colour_byte_to_light"]
    print(f"  half output at byte {r['half_output_reached_at_byte']}, "
          f"confidence {r['confidence']}, exposures agree over bytes "
          f"{r['exposures_disagree']['bytes_within_25_pct']}")
    for c, v in model["write_path"]["delivered_ceiling_hz_by_condition"].items():
        print(f"  {c:>9}: ceiling {v['ceiling_hz']}, max delivered {v['max_delivered_hz']}Hz")
    print(f"  cct sub-mode {model['cct']['working_sub_mode']}")


if __name__ == "__main__":
    main()
