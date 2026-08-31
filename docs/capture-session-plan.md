# One-off capture session — Pixel 9 Pro XL as a dedicated camera

Written 2026-08-29. The old **Pixel 9 Pro XL** goes for trade-in tomorrow. Until then there are three
phones instead of two, and one of them can be tied to a tripod for hours without anyone missing it.

## What this actually buys

A separate camera phone is **not** new — the phone cannot film itself (Android freezes Fuse behind the
camera app), so every previous session already drove from one phone and filmed with another. What is
new is that **both ends can be good at once**, and previously only one could.

**Reason one, and the bigger one: the driver.** The moto's BLE is far slower than a Pixel's, and the
app will never be used on hardware like that. Any run driven from the moto measures the moto, not the
product — its write throughput, its spacing floor, its latency. With two Pixels, the strip is driven
by hardware that represents real use *while* being filmed properly. Every rate-shaped measurement in
this plan is only worth capturing under that condition.

**Reason two: the camera.** Every capture so far was filmed on the **moto at 1080p30**, and the camera
was the limiting instrument:

| Measurement | Why the moto's camera limited it |
|---|---|
| PWM carrier | needs a short, *known* exposure and the resolution to count dashes in a smear |
| Absolute latency | 30fps is a 33ms quantisation on the quantity being measured |
| Spacing staircase | fine for the settled colour, but transients between bursts were invisible |
| Dark end (byte 0-32) | sensor noise dominates exactly where the fit is out by 2x |

So the session is a first: **representative BLE hardware and a good instrument at the same time.**
Previous sessions always had to give up one to get the other.

**After tomorrow this is still possible** but strictly worse — filming would mean the moto's camera
again, or tying up the only good driver as the camera and driving from the moto. Either way one end
degrades. That is what is actually being lost tomorrow.

## Prerequisites — tonight, not tomorrow

1. **Enable developer options + USB debugging on the Pixel 9** and authorise this laptop. Nothing
   below works without it, and it is the step most likely to be found out too late.
2. **Recover the calibration rig.** Deleted by the 2026-08-26 rollback; it is on the tag
   `visualiser-work-2026-08-26` — `CalibrationSequences.kt`, `CalibrationForegroundService.kt`, the
   `run_calibration` command in `AdbControlReceiver`, and `tools/calibration/` with six analysis
   scripts. Without it there is no scripted sequence, no CSV and no sync marker, so the videos would
   be unalignable and largely worthless. **Needs Joe's go-ahead** — it is rolled-back code coming back.
3. **Keep the foreground service.** `CalibrationForegroundService` stops Android freezing Fuse mid-run
   behind the camera app. Do **not** disable `cached_apps_freezer` again; that surgery was done once
   and had to be remembered and undone.
4. **A manual camera app on the Pixel 9** (Open Camera or similar). The stock app cannot lock ISO and
   shutter to known values, and locked exposure is what makes two frames comparable. Every
   photometric run below is void without it.
5. **Storage and power.** 4K and slow-mo eat gigabytes; long runs heat and drain phones. Pull files
   between runs, not at the end.
6. **Cables and ports** for driver and camera at once.

## Roles

- **Driver: the Pixel 11 Pro XL (`65271FDDV001AB`)** — representative hardware. Rate, spacing and
  latency numbers are properties of *the driving phone*, so a run from the moto would characterise a
  phone nobody will use.
- **Camera: Pixel 9 Pro XL**, on a tripod or a stack of books, taped in place so a position can be
  returned to.
- **The moto is optional and only as a contrast run.** One `rate_ramp` and one `spacing_staircase`
  from it are worth having as a slow-hardware bound — label them clearly and never mix them into the
  Pixel numbers. Skip entirely if time is short.
- The driver stays **in the foreground and untouched** during a run. Backgrounding costs ~57% of write
  throughput (36.8ms/write against 4.6ms). That invalidates rate measurements; it does not affect
  response-curve or spacing runs.

**Scheduling conflict to resolve first:** the Pixel is SMSRelay's Primary and is driven by another
session. This plan needs it for hours, not a short burst. Claim the pixel lock for a realistic window
and expect to negotiate it — a 20-minute claim repeatedly re-taken is worse for the other session than
one honest long one. The moto is **not** needed, so its lock stays free throughout.

## Room and camera discipline

This is what makes the data reinterpretable later. Skipping it produces video that looks fine and
answers nothing.

- Dark room, no other light source, nothing else in frame that emits or reflects.
- **Exposure, ISO, focus and white balance locked**, and written down per run.
- Never let the strip clip. A saturated pixel has no intensity structure left to measure.
- **Start recording before the sequence, stop after.** The moto's camera app once exited to the
  launcher while idle and cost an entire run. A recording camera stays put.
- Every sequence opens with a **sync marker** — three white flashes, 120ms on, 280ms gap. That is what
  aligns video frames to CSV timestamps. Do not trim it out.
- The ramps re-measure **descending** as an exposure-drift check: the same level reading differently
  on the way down means the camera drifted and the run is unusable. Check that before trusting it.
- Film a **white or grey card** at the start of each position, at the same settings.

## Does it need full darkness? Only some of it

Not one answer — it depends on how small the signal being measured is. What actually matters is that
ambient light is **low and constant**, and daylight fails the *constant* half: it drifts over minutes,
which silently breaks the descending re-measure check that is supposed to catch exposure drift.

| Run | Light needed | Why |
|---|---|---|
| `dark_ramp`, P2 macro | **Blackout, night** | Bytes 0-32 are the smallest signal there is. Ambient adds an offset that swamps it, and this is the run whose fit is out by 2x — the whole reason for doing it |
| PWM at P3 | **Blackout, night** | The strip is dimmed to 10-20% and deliberately underexposed. Any ambient forces a different exposure and washes out the smear structure |
| `brightness_ramp`, `colour_primaries`, `cct_sweep`, `brightness_x_colour` | **Curtains closed, lights off** | Photometric, but at levels well above the noise floor. A dim, *unchanging* room is enough; a sunbeam moving across the wall is not |
| `spacing_staircase`, `control_bursts` | **Dim room, daytime fine** | Classifies red / blue / black patches. Only needs the patches distinguishable, with exposure locked |
| Mode Capture at P6 | **Dim room, daytime fine** | Hue accuracy suffers with ambient, so darker is better, but it is not measuring absolute level |
| `latency_pulse` at P4 | **Daytime fine** | Timing, not photometry. It needs the flash edge detectable, nothing more |
| `sustained_load` | **Anything** | No camera at all |

**So: start in daylight with the staircase, latency and Mode Capture, and hold the dark ramp and PWM
for after dark.** That ordering also matches the priority list — Mode Capture is the single biggest
item and needs no darkness, so the session is not blocked waiting for sunset.

## Self-provisioning: the session sets up its own tooling

Joe's requirement, 2026-08-29: when the test runs it should download and configure whatever apps it
needs on its own, rather than depending on him having prepared anything.

**What is genuinely automatable:** downloading an APK (Open Camera is on F-Droid, direct download, no
account), `adb install`-ing it, granting its runtime permissions with `adb shell pm grant`, and
launching it with an intent.

**What is not:** *configuring* a third-party camera app. Its settings live in its own
SharedPreferences, which cannot be written from adb without root. Manual ISO and shutter would have to
be set by simulated taps (`adb shell input tap`), which is brittle across versions and silently wrong
if a tap misses — the worst failure mode here, because the run looks fine and the data is void.

**The honest recommendation is to put the capture in Fuse instead.** The app already has Camera2 code
(`ModeCaptureCameraSource`) and already runs on the camera phone. A small capture screen driven by the
existing `AdbControlReceiver` gives locked ISO, locked shutter, a known frame rate and a file written
where we choose — all set programmatically, with no taps and nothing to misconfigure. It is more work
up front than installing Open Camera, and it is the only route that actually satisfies "configures
itself" rather than "installs itself and hopes".

Decide which before the rig is set up, not during.

## The attention signal — the strip asks for Joe

The monitor is off during runs to avoid light leakage, so there is no way to tell him anything on
screen. **The strip itself is the notification.** Joe's spec, 2026-08-29: *smooth but not slow, fiery
orange fades.*

Concrete reading of that, to be built into the rig:

- **Colour:** fiery orange, fading between roughly `(255, 70, 0)` and `(255, 150, 30)` — never through
  white or yellow, which would read as a test pattern.
- **Motion:** a smooth sinusoidal fade, one full breath in about **1.2-1.6s**. Fast enough to read as
  "come here", slow enough not to look like a strobe or like any visualiser preset.
- **Persistence:** repeats until acknowledged or the next sequence starts. A single pulse is missable
  from another room, which defeats the point.
- **When it fires:** the run finished; the run aborted or errored; or a step needs Joe (reposition the
  camera, change brightness, start a recording).
- **It must not be confusable with a measurement.** It only ever runs when nothing is being captured,
  and any recording that contains it should be treated as ending there.

## Positions

Each is a rig setup, and setup is the expensive part. Batch everything at a position before moving.

### P1 — Wall patch, perpendicular, ~1.5m (the workhorse)

Strip aimed at a plain wall, camera perpendicular to the lit patch, whole patch in frame, nothing
clipping.

- `brightness_ramp` — white 0 to 255 in 15 steps, 2s each. The response curve.
- `dark_ramp` — every byte 0 to 32 at 1.5s, then descending, then firmware brightness 1-20%. Settles
  the curve where the fit is out 2x, **and** whether firmware brightness is a finer dimmer than the
  colour bytes — the whole gate on headroom scaling.
- `colour_primaries` *(new)* — R, G, B swept alone, then the secondaries. Per-channel response and
  channel crosstalk; never captured.
- `cct_sweep` *(new)* — the warm/cold command across its range, versus what the app models.
- `brightness_x_colour` *(new)* — brightness {10,25,50,75,100}% crossed with several colours. Does
  firmware brightness scale cleanly, and independently of hue?
- `spacing_staircase` + `control_bursts` — `minWriteSpacingMs`. **Read the ten control bursts first**:
  they must settle on red. Any reading blue or black means the classifier is misaligned and every
  number from that run is suspect.
- `rate_ramp` — wire-side throughput.
- `transition_probe` *(new)* — abrupt jumps between distant colours: does the firmware interpolate or
  step? Never measured, and it changes what "a flash" physically is.

### P2 — Macro, 3 to 5 LEDs filling the frame

- `dark_ramp` again. Per-LED quantisation and channel imbalance show at this scale and average away
  at P1.
- Slow-mo across a `transition_probe`.

### P3 — Sweep across the strip, low brightness (PWM carrier)

The 2026-08-17 attempt failed all three conditions. Corrected method:

- Dim to **10-20%** — at full white the duty cycle is ~100% and there is nothing to chop.
- Expose so the strip is **grey, not white**.
- Sweep the camera **across** the strip, not along it. Along its own axis, LED pitch and PWM chopping
  fall on the same axis and cannot be told apart.
- Count dashes, divide by the EXIF exposure time.
- **Repeat at 5, 10, 20 and 40%.** Whether the carrier is constant or scales with duty is itself
  unmeasured, and it decides whether deep dimming can flicker.
- The 2026-08-19 result found no chopping at 15%. A known shutter either confirms that properly or
  overturns it.

### P4 — Driver's screen and the strip both in frame (absolute latency)

**Needs an app change first**: flash the phone's own screen white on the same millisecond as the BLE
write. Without it the alignment subtracts out the quantity being measured, which is why this has never
been captured. Cheap to add while the rig is being recovered.

- `latency_pulse` at the highest frame rate available. At 240fps a frame is ~4ms against 33ms at 30 —
  the measurement that most needs the better camera.

### P5 — Both strips in frame

- Any sequence with two strips connected. Per-write cost doubles, so `spacing_staircase` may have a
  different floor.
- Do the two strips stay in step or drift? Never measured.

### P6 — Full strip length, along its axis (built-in modes)

The one place the strip has a **spatial** dimension. The protocol has no per-LED addressing, but the
~200 built-in modes are animated by the strip's own firmware.

- **Run Mode Capture.** Built 2026-07-23, never run on hardware, and the biggest genuinely-unblocked
  item there is: the ~200 modes still have *guessed* names, categories, directions and colours. It
  cycles every mode holding each 10s — about 35 minutes, unattended.
- **Film it at the same time.** Mode Capture's own sampling has never been verified, so if its
  tap-endpoint mapping or sampling density is wrong the on-device JSON is wrong — but the video still
  holds the raw truth and can be re-analysed. The clearest case in the session of capture raw,
  interpret later.
- Then sweep the **mode speed** command at a few settings on 3 or 4 representative modes.

## Unattended, no camera

- `sustained_load --ei minutes 60`. The full hour has never been run; 15 minutes was a compromise.
  Needs a powered strip and a phone left alone. The CSV is the entire result.

  The existing 3- and 15-minute results were **driven from the moto**, so they say whether the moto's
  link survives an hour, not whether a Pixel's does. Run this from the Pixel driver and treat the old
  results as a different measurement rather than a baseline. It needs no camera, so it can run while
  the Pixel 9 is being repositioned — but it does occupy the driver, so slot it at the end or
  overnight.

## Priority if time runs out

Ordered by *what stops being capturable tomorrow*, not by general interest.

1. **Mode Capture at P6** — biggest unblocked item, camera-shaped, 35 minutes, and it wants a second
   camera as ground truth precisely because its own sampling is unverified.
2. **`spacing_staircase` at P1** — the named "run to do next", and the first time it can be driven by
   representative hardware *and* filmed by a good camera at once.
3. **`dark_ramp` at P1** — gates headroom scaling, the leading fix for dark scenes.
4. **PWM at P3** — cheap, and decides whether deep dimming is safe.
5. **`colour_primaries` / `brightness_x_colour` at P1** — pure new data, never captured.
6. **Latency at P4** — highest value per frame, but needs the app change first, so it is the first to
   drop if the rig recovery runs late.
7. Everything else, then the moto contrast runs last.

## Before the phone goes

**Pull every file and verify it opens before the factory reset.** Videos, CSVs, the Mode Capture JSON.
A trade-in wipe is final, and a half-pulled video is indistinguishable from a good one until someone
tries to read it. Check sizes, and play a frame of each.
