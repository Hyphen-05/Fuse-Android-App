# `derived/` — the capture session, turned into tables

Everything here is regenerated from the raw CSVs in `tools/calibration/` and the video in
`captures/`. Nothing here is hand-edited. To rebuild the lot:

```bash
bash tools/calibration/extract_frames.sh        # video -> frame grids (needs ~1GB free)
python tools/calibration/analyse_wire.py
python tools/calibration/analyse_probe.py
python tools/calibration/analyse_photometry.py
python tools/calibration/analyse_steps.py
python tools/calibration/build_model.py
```

`extract_frames.sh` needs an ffmpeg. There is none on the machine's PATH; the one used came from
`pip install imageio-ffmpeg`, which ships a binary and needs no admin rights. The script finds it
by itself. `analyse_photometry.py` and `analyse_steps.py` need `numpy`.

`frames/` holds the decoded grids and is gitignored — about 1GB, and rebuildable in five minutes
from `captures/`. **`captures/` itself is not rebuildable and exists nowhere else.**

## The files

| file | one row per | what it answers |
|---|---|---|
| `wire_runs.csv` | run × device | how many writes were offered, how many reached the radio |
| `wire_rate_curve.csv` | rate stair × device | delivered rate against requested rate — the write model |
| `latency_edges.csv` | commanded on/off edge | how long the light took, as a bracket |
| `latency_summary.csv` | run × edge direction | the distribution, and whether the run is usable at all |
| `pwm_duty.csv` | held duty cycle | mean light and its spread — the flicker question |
| `response_full_ramp.csv` | byte × exposure | raw per-exposure light for each commanded byte |
| `response_stitched.csv` | byte | the joined response curve, with the two exposures' disagreement kept |
| `response_per_led.csv` | LED × byte | the macro take, each emitter measured separately |
| `steps_measured.csv` | held step of `capture_all` | commanded RGB → measured light, for the whole battery |
| `channel_crosstalk.csv` | primary × level | whether driving one channel disturbs the others |
| `transitions.csv` | hard colour jump | step or glide |
| `device_model.json` | — | all of the above collapsed to the parameters a simulator needs |

## Two things to know before quoting any of it

**Light is measured in linear camera units, not photometric ones.** The frame grids are decoded
with the camera transfer curve undone *before* pixels are averaged, so ratios between two numbers
in the same column are meaningful. White balance, lens shading and the sensor's spectral response
are all still in there, so a single number is not an output in any physical unit, and the three
channels are not comparable to each other. Nothing that was asked of this data needed either.

**`confirmed_by_both` in `response_stitched.csv` is not decoration.** Between bytes 48 and 203 the
two exposures agree to within 5%, which is what makes the curve there a measurement. Below byte 48
they disagree by up to 42% and only the shape survives. Rows carry both takes so the disagreement
travels with the number.
