#!/usr/bin/env python3
"""Collapse the derived tables into one file a simulator can be built from.

  derived/device_model.json

Every entry carries where it came from and how far it can be trusted, because a model that states
a number without its provenance is indistinguishable from a model that made it up. `confidence` is
the honest verdict, not a decoration: `measured` means two independent looks agree, `single` means
one measurement stands alone, `bounded` means only a range was established, and `absent` means the
sequence ran and the strip did nothing.
"""
import csv, json, os, statistics

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "derived")


def rows(name):
    with open(os.path.join(OUT, name), newline="") as f:
        return list(csv.DictReader(f))


def num(v):
    return float(v) if v not in ("", None) else None


def main():
    st = rows("response_stitched.csv")
    lut = {int(r["byte"]): num(r["light_norm"]) for r in st}
    solid = [int(r["byte"]) for r in st if r["confirmed_by_both"] == "yes"]

    rate = rows("wire_rate_curve.csv")
    ceiling = statistics.median(
        float(r["delivered_hz"]) for r in rate if int(r["requested_hz"]) >= 30
    )
    per_device = sorted({(r["condition"], r["device"]) for r in rate})

    lat = [r for r in rows("latency_summary.csv") if r["usable"] == "yes"]
    cross = rows("channel_crosstalk.csv")
    trans = rows("transitions.csv")
    pwm = rows("pwm_duty.csv")

    model = {
        "device": "DuoCo-protocol LED strip, as measured 2026-09-02 on Joe's two units",
        "generated_from": "tools/calibration/derived/*.csv",
        "colour_byte_to_light": {
            "what": "relative light emitted for a commanded byte, white (r=g=b), firmware "
                    "brightness pinned to 100%. Normalised so byte 255 = 1.0.",
            "lut": [lut[b] for b in range(256)],
            "power_law_approximation": "light = (byte/255) ** 0.416",
            "power_law_worst_error": 0.072,
            "black_point_bytes": [b for b in range(256) if lut.get(b) == 0],
            "minimum_non_zero_output": lut.get(2),
            "confidence": "measured",
            "trusted_byte_range": [min(solid), max(solid)],
            "caveat": "outside that range the two exposures disagree by up to 42%; the shape "
                      "(strongly compressive, a floor at byte 2 well above zero) is common to "
                      "both, the exact values below byte 48 are not settled.",
            "source": "response_stitched.csv",
        },
        "firmware_brightness_to_light": {
            "what": "the strip's own brightness setting, measured two independent ways",
            "duty_points": {r["duty_pct"]: num(r["luma_frac_of_full"]) for r in pwm},
            "notes": "1% and 2% emit identically - the setting has no effect below 3%",
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
        "write_path": {
            "what": "how many of the app's writes reach the strip, and how fast",
            "delivered_ceiling_hz_per_device": round(ceiling, 1),
            "ceiling_cause": "the app's own per-device write pacing (default 50ms in "
                             "RgbControllerViewModel's pacingProvider), not BLE and not the strip",
            "survival_model": "acked_fraction = min(1, delivered_ceiling_hz / requested_hz)",
            "second_device_costs": "nothing - each device gets its own full ceiling",
            "devices_measured": len(per_device),
            "confidence": "measured",
            "source": "wire_rate_curve.csv, wire_runs.csv",
        },
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
        "cct": {
            "what": "the warm/cold white sweep",
            "verdict": "no light at all, across all 25 steps, measured over the whole frame",
            "confidence": "absent",
            "source": "steps_measured.csv (family cct)",
        },
    }
    p = os.path.join(OUT, "device_model.json")
    with open(p, "w") as f:
        json.dump(model, f, indent=2)
    print(f"device_model.json: {len(model)} sections")


if __name__ == "__main__":
    main()
