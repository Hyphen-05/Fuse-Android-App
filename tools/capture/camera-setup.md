# Camera setup — Open Camera on the Pixel 9, and when to fall back to stock

Joe's call, 2026-09-01: **Open Camera, with the stock camera as a fallback for anything Open Camera
cannot do.** `provision-camera.sh` installs it and grants its permissions. It cannot configure it —
Open Camera's settings live in its own SharedPreferences, which adb cannot write without root.

So the settings below are set by hand, or by simulated taps, and **every one of them is read back
before a run starts**. A missed tap is the worst failure mode in the session: the recording looks
perfectly fine and the data is void, and nobody finds out until the analysis.

Read back with the view hierarchy, not with screenshots:

```bash
adb -s <serial> shell "uiautomator dump /sdcard/ui.xml" && adb -s <serial> shell "cat /sdcard/ui.xml"
```

## The settings that make two frames comparable

Locked exposure is the whole point. An auto-exposing camera re-normalises every frame, so a brighter
strip and a longer exposure are indistinguishable, and every photometric run — the brightness ramp,
the dark ramp, colour primaries, brightness × colour — measures nothing.

| Setting | Value | Why it voids the run if wrong |
|---|---|---|
| ISO | Manual, lowest that still exposes (start 100) | Auto ISO is auto exposure by another name |
| Shutter | Manual, written down per run | The PWM count at P3 is dashes ÷ exposure time — an unknown shutter makes it unsolvable |
| Focus | Manual, locked on the strip | Refocusing mid-run changes the blur, and blur changes measured intensity |
| White balance | Manual, fixed Kelvin | Auto WB re-tints per frame, so `colour_primaries` and `cct_sweep` measure the camera |
| Exposure compensation | 0 | A non-zero value carried between runs is invisible and shifts everything |
| Video resolution | 4K where the run needs detail, 1080p otherwise | 4K at 60fps eats storage; the macro and PWM runs need the pixels |
| Frame rate | Highest the run needs — see below | |
| Flash | Off | |
| Audio | On | The clap or the sync flash gives a second alignment cue if the video is ever re-cut |

**Write the ISO and shutter into the run's filename or a note.** Numbers that were locked but not
recorded are only slightly better than numbers that drifted.

## Where stock takes over

Open Camera exposes manual controls the stock app does not, which is exactly why it was chosen. It
does **not** match the stock app on the Pixel's high-frame-rate paths:

- **Slow-mo / 240fps at P4 (latency).** Take this on the stock camera. Losing manual exposure costs
  nothing here — P4 measures *timing*, not photometry. It needs the flash edge detectable and
  nothing more, which is exactly the run the plan marks "daytime fine".
- **Anything else Open Camera refuses on the day.** The test is which half of the run matters: if it
  is photometric, Open Camera is mandatory and a stock recording is not worth taking. If it is
  timing or classification (`latency_pulse`, `spacing_staircase`, `control_bursts`), stock is fine.

## Before each position

1. Tape the phone in place, so the position can be returned to.
2. Lock every setting above, then read them back.
3. Film a white or grey card at those settings.
4. `run_calibration --es sequence hold_white` on the driver, and lock exposure against *that* —
   the brightest state any run will produce. Locking against a dimmer state clips the top of the
   ramp, and clipping is not recoverable afterwards.
5. Start recording **before** the sequence, stop **after**. A recording camera stays put; the moto's
   camera app once exited to the launcher while idle and cost a whole run.
