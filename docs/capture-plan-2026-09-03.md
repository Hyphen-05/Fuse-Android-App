# The last capture run — everything still open, in one unattended pass

Written 2026-09-02, after the analysis in [analysis-2026-09-02.md](analysis-2026-09-02.md). That
analysis ended with nine open items; this plan closes seven of them in a single run that needs Joe
for about five minutes at the start and nothing after.

The previous session's plan ([capture-session-plan.md](capture-session-plan.md)) is not superseded
as a record — its room discipline and its traps still apply. What changes is the instrument.

## The idea that makes it one run

**The driving phone films itself.** `LatencyCameraProbe` already does this: it binds the camera
in-process, pins exposure and sensitivity, and timestamps light and writes on the same clock. Two
sequences already used it. Widen it from one centre-ROI luma number to a small grid of per-channel
means, and let the *sequence* choose the exposure, and it replaces the external camera for
everything still open.

That is what collapses the session:

| what the external camera forced | with an in-app photometer |
|---|---|
| taps on Open Camera to set ISO and shutter, verified through `uiautomator dump` | the sequence sets them, and every row records what they were |
| two takes at two exposures, stitched afterwards with a scale factor that has to be justified | three exposures interleaved *within* one ramp, each byte measured at all three |
| a framing change between wide and macro, which means a second run and a second setup | one framing; the grid resolves per-LED cells directly |
| video pulled, decoded, aligned to a log by sync flashes | numbers, already aligned, already on the right clock |
| "did that tap land?" as the session's worst failure mode | no taps |

Fuse stays in the foreground throughout, which is the thing that matters. The old constraint was
that the phone cannot film itself with a *separate camera app* — Open Camera takes the foreground,
Android drops Fuse out of TOP, and `delay()` inside a sequence stops advancing. Binding CameraX
**inside Fuse** does not do that, and this is settled rather than argued: `latency_camera` and
`pwm_probe` both ran that way on 2026-09-02, and the latency result and the entire flicker result
came from the driver filming itself while driving it.

### But the camera is not free, and that decides the running order

`RgbControllerViewModel.kt:2782` already says why only the latency runs open it: "binding it costs
frames, heat and battery for nothing they measure." Those costs land on exactly the quantity the
rate-shaped phases exist to measure, so the photometer must **not** be open during them.

So the phases split in two, and this is the one place the Pixel 9 stops being optional:

- **Light phases** — the ramp, the CCT probe, Mode Capture. Photometer open, no external camera
  needed, nothing to tap.
- **Rate phases** — `rate_ceiling`, `capture_all`, `sustained_load`. Photometer **shut**. The wire
  log carries the whole answer for `rate_ceiling` and `sustained_load` on its own. `capture_all`
  wants light as well as timing, and that is what the Pixel 9 films.

**The Pixel 9 therefore films the whole session at one fixed setup** — recording started once at the
beginning and stopped once at the end, never touched between. For the light phases that video is
insurance the run does not depend on. For `capture_all` it is the measurement. Either way it is one
setup and no mid-session taps, which was the failure mode worth designing out.

---

## Part 1 — what has to be built first

**Built, 2026-09-02.** 308 tests pass and `assembleDebug` succeeds. What follows is the design and
why; the one thing not yet verified against hardware is step 5's guard, which is noted again at the
end of this file.

One thing changed while building it: **the camera phone is driven over adb too**, so it is not just
recording start and stop that Joe does not do — it is nothing at all beyond positioning. See step 6.

All of it was bench work. Only step 5 needs a strip to verify.

### 1. `CalibrationPhotometer` — widen the probe

Extend `LatencyCameraProbe` (rename it; it is no longer only about latency) so that:

- **exposure and sensitivity are parameters, not constants.** They are `EXPOSURE_NS = 5_000_000`
  and `SENSITIVITY = 1600` today. A sequence must be able to say "hold this byte at each of three
  exposures", and every row must carry the pair actually in force, because an exposure that was
  locked but not recorded is barely better than one that drifted.
- **it logs a grid, not a single mean.** An 8×8 (or 16×16) grid of R, G and B means per frame. That
  one change is what gives per-LED uniformity, strip-position work, and the chase-direction check
  below, without a second framing or a telephoto.
- **it keeps the clock discipline exactly as it is.** `SENSOR_INFO_TIMESTAMP_SOURCE` in the header,
  every write stamped on both clocks. That part is right and must not be touched.

Worth knowing what it cannot do: the frames are 8-bit and gamma-encoded, so grid values need
linearising before they are averaged or compared — the same trap `analyse_photometry.py` already
handles for video, and the same fix applies. Logging the camera's `TONEMAP_CURVE` or forcing
`TONEMAP_MODE_GAMMA_VALUE` would remove the guesswork entirely and is worth trying.

### 2. Give the calibration path back its pacing bypass

`CalibrationSequences`' KDoc says "writes bypass pacing deliberately". `AdbControlSink` says "with
pacing bypassed". **Neither is true in this tree.** The call site
(`RgbControllerViewModel.kt:2802`) calls `bleGattTransport.writeCommand(address, command)` and lets
`bypassPacing` default to `false`, so every calibration write goes through the 50ms wait. That is
the 15Hz ceiling in §2 of the analysis, and it is why nothing has ever written to the strip fast
enough to find its real limit.

Two changes:

- pass `bypassPacing = true` from the calibration send path, restoring what both doc comments
  already claim;
- add `--ei pacing <ms>` to `run_calibration` so a run can *choose* a pacing value, since the point
  of the rate work is now to sweep it rather than to be rid of it.

### 3. Four new sequences

| sequence | what it is | runs for |
|---|---|---|
| `full_ramp_x3` | every byte 0-255, held at three pinned exposures back to back before advancing | ~11 min |
| `rate_ceiling` | the existing rate stairs, repeated at pacing 0, 10, 25 and 50ms | ~5 min |
| `cct_probe` | candidate CCT command shapes, each held 1.5s, watched for any light at all | ~2 min |
| `chase_probe` | one built-in chase mode, held while the grid records which cell lights when | ~1 min |

`cct_probe` is the one worth being specific about. The current command is
`7E 06 05 02 <warm> <cold> FF 08 EF` and all 25 steps of `cct_sweep` produced **exactly zero light**.
The probe should vary, one axis at a time: the sub-mode byte at index 3, warm/cold scaled 0-100
against 0-255, the `0x08` at index 7, and a preceding mode-switch command. If nothing in the matrix
lights, the answer is that `Fireworks` has no white channel — which is worth knowing definitively,
and is a two-minute answer instead of an open question.

### 4. Make Mode Capture reachable without taps

This is the biggest single item still open and the only one that currently forces a human at the
screen. It needs a `PreviewView`, tapped strip endpoints, a camera lock, a reference capture, then
`startCycle`. Add a `run_mode_capture` command that opens the screen and drives those phases in
order, with **the endpoints derived rather than tapped**: light the strip full white, take one
frame, and take the extreme lit cells as the endpoints. That is more repeatable than a fingertip and
it removes the last manual step.

Run `chase_probe` before it in the same script. `docs/positions.md` *assumes* the long vertical
strand is consecutive LEDs on the wire, and every Mode Capture direction result rests on that
assumption. The grid answers it in a minute: if the lit cell walks monotonically along the strand,
it holds.

### 5. An abort that fires when the strip is lying

The trap recorded on 2026-09-02: a strip can report CONNECTED, ack every write with status 0, and
emit nothing, and only looking at it tells you. Unattended, that turns a 90-minute run into 90
minutes of nothing. **The photometer can see it.** After the opening sync flashes, if the writes
acked and the frame means did not move, abort the whole battery, log it loudly, and put the strip
into the orange attention breath. This is the one piece of the build that should be verified against
real hardware before the run, because it is the guard everything else leans on.

### 6. Drive the camera phone over adb as well, instead of tapping a camera app

Every previous session filmed with Open Camera and drove it by simulated taps at stored
coordinates. That has the worst failure mode in the rig: a tap that misses produces a recording
that looks perfectly fine and is void, and nobody finds out until the analysis. It has already
happened once — picking the phone up rotated the UI and a whole burst of shutter taps produced no
photos. It also cannot record what it did, because Open Camera's ISO and shutter live in its own
SharedPreferences and adb cannot read them without root.

`CalibrationRecorder` binds `VideoCapture` inside Fuse on the camera phone, with auto-exposure off,
white balance fixed and focus fixed, and returns those settings in the log line and the filename.
`start_recording` / `stop_recording` on the same adb surface as everything else. The file lands in
the app's own external files dir, so the same `adb pull` that collects the CSVs collects the video.

The consequence is that **the script starts and stops the recording**, and the camera phone needs
nothing but to be pointed at the strip and plugged in. It does need USB debugging authorised to
this laptop, which as of writing it does not have — `adb devices` shows only the Pixel 11 and the
moto. That is the one prerequisite left.

---

## Part 2 — the run

One shell script on the laptop, one broadcast per phase, `stop_calibration` between. Files pulled
after each phase rather than at the end, as the old runbook insists — long runs heat and drain, and
a phase that dies takes only its own output with it.

| # | phase | mins | photometer | settles |
|---|---|---|---|---|
| 1 | `chase_probe` | 1 | open | whether the vertical strand really is consecutive LEDs |
| 2 | `full_ramp_x3` | 11 | open | the bottom of the response curve, and per-LED uniformity, at once |
| 3 | `cct_probe` | 2 | open | whether CCT is a wrong command or an absent channel |
| — | cool-down, camera released, strip black | 5 | shut | see below |
| 4 | `rate_ceiling` | 5 | **shut** | the strip's actual speed limit, finally unthrottled |
| 5 | `capture_all` | 10 | **shut** | the whole battery again — light from the Pixel 9's video |
| 6 | `sustained_load` | 15 | **shut** | endurance at the *delivered* rate, with the ack log this time |
| 7 | `run_mode_capture` | 35 | open | ~200 modes: real names, categories and directions |
| | **total** | **~80** | | plus margin, call it two hours |

Order is deliberate on three counts. The cheap probes run first, so a build mistake surfaces in the
first two minutes rather than the seventieth. The photometer-shut phases are contiguous, so the
camera binds once and releases once rather than cycling. And the **cool-down is not padding**:
thirteen minutes of bound camera puts real heat into the phone, a hot phone throttles, and
throttling would land directly on the write ceiling this run exists to find. Five idle minutes with
the camera released and the strip black costs nothing in an unattended run and removes the one
confound that would be invisible in the results. The script should log the phone's thermal status
either side of it, so the assumption is checked rather than trusted.

**One consequence to accept:** the lying-strip guard from Part 1 step 5 needs the photometer, so it
cannot watch the camera-shut phases. Run it as a short opening check, and again in the cool-down
before `capture_all` — a brief bind, sync flashes, verify light, release. That covers the entry to
the longest unwatched stretch, which is the exposure that matters.

## Part 3 — what Joe actually does

1. Prop the **Pixel 11** facing the strip, plugged in. This is the framing decision the session
   turns on: the strip should fill the frame with a little margin, and the long vertical strand
   should run roughly down the middle, because that is where Mode Capture's default endpoints
   assume it is.
2. Prop the **Pixel 9** facing the strip too, plugged in, USB debugging authorised. Nothing to open,
   nothing to set — the script starts and stops its recording and sets its exposure.
3. Blackout, after dark, curtains closed.
4. Run it:

```bash
bash tools/capture/run-session.sh --camera <pixel-9-serial>
```

That is the whole of it. No taps, no exposure changes, no reframing, no second setup, and nothing
to do while it runs. Without `--camera` it still runs and still answers everything except
`capture_all`, which is skipped rather than shot blind.

The script starts recording, runs each phase, pulls the files between phases and counts what
arrived, holds the cool-down, sweeps the pacing values, polls Mode Capture rather than sleeping
blind, stops the recording, waits for the mp4 to finalise before pulling it, and prints an
inventory. Everything it does is logged to `captures/session-<timestamp>/session.log`.

## Part 4 — what this run still will not answer

Named so nobody expects it.

- **The PWM smear at 5-10%.** It needs the camera swept across the strip by hand, and the strip's
  cable would not reach far enough last time. The stationary `pwm_probe` already answered the
  flicker question this was meant to ask, so it is a loose end and not a gap.
- **Absolute photometry.** Everything stays relative because nothing in frame has a known output.
  Fixing it needs a reference light in shot, not a re-analysis, and nothing asked of this data has
  needed it yet.
- **The moto contrast run.** Optional, last, clearly labelled, and skippable — a slow-hardware bound
  is nice to have and nothing depends on it.
- **`write_type_probe` — plain against music colour commands.** Nothing missing here: it was
  captured and is sitting in `derived/steps_measured.csv` and the wire logs. It needs an analysis
  pass, not a run.

## Part 5 — analysis

The scripts in `tools/calibration/` take this without much change. `analyse_probe.py` already reads
the probe's CSV shape and would need to learn the grid columns; `analyse_photometry.py`'s stitching
becomes unnecessary, because three known exposures per byte is a better answer than two unknown ones
joined by a fitted scale factor — keep its linearisation, drop its stitch. `build_model.py` gets a
higher-confidence response curve and, for the first time, a real number for the strip's rate limit.

## The risk worth stating plainly

This plan trades a proven instrument for a better one. The external camera app worked; the in-app
photometer and recorder are new code, and new code has bugs. Everything here compiles and the
suite passes, but **none of it has met a strip**, and a rig that has only ever been exercised
against a compiler is not a rig anyone should walk away from for two hours.

So the first thing to do when there is a strip and a dark room is not the session — it is fifteen
minutes of proving the parts:

1. `chase_probe` end to end. Shortest phase, uses the photometer, and its grid rows either contain
   a moving bright cell or the grid is wrong.
2. `start_recording` on the camera phone, then `stop_recording`, then pull and open the mp4. It
   either decodes at the exposure the log line claims or it does not.
3. The liveness guard, provoked: run a photometer sequence with the strip powered down at the wall.
   It should abort in the first ten seconds saying the strip acks but is dark. This is the guard
   everything else leans on, and it is the one piece whose whole value is in firing correctly on a
   day nobody is watching.
4. `run_mode_capture`, killed after two or three modes. Enough to see it open the screen, lock, take
   the reference and start cycling.

If those four pass, the session is a script and a walk away. If the photometer turns out to be
wrong afterwards, the Pixel 9's video is still there and the session is recoverable as an ordinary
filmed run — which is the other reason it records throughout.
