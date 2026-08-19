# Hardware calibration

Scripted light sequences plus a video of the strips, turned into the constants the feel harness
needs. Without this, `VirtualStrip`'s limits are guesses and its renders can only be compared to
each other — not to what the hardware will really do.

## Results: spacing staircase, 2026-08-16 (Pixel driving, moto filming at 1080p30)

Two findings, and the second is the bigger one.

**1. The strip renders whatever reaches it.** 110 bursts out of 110 settled on the second colour, on
both wall patches independently, from 6ms of separation up to 66ms. No strip-side dropping anywhere
in the reachable range.

**2. Only 32% of the *first* writes ever reached the strip.** In 75 of 110 bursts the red phase
never appeared at all — the strip went straight to blue. Where red did render it was visible for a
median of **68ms**, far too long for a 30fps camera to have missed it in the other cases, and far
longer than the 4-60ms it was commanded to last. The rate is flat across gap sizes, so it is not the
commanded spacing deciding this.

The mechanism this points to is `DeviceWriteManager.updateCommand`'s latest-wins queue: a queued
command of the same type is *removed* when a newer one arrives, so red survives only if it had
already been dequeued and written when blue was enqueued. That window is the in-flight time, which
the latency pulses showed varies wildly (sd 36ms) — which explains both the ~32% survival and its
indifference to a 4-60ms commanded gap. **The queue, not the strip, is what drops colours.** This is
inference from the code plus the timing, not direct observation; the write-with-response ack probe
would confirm it.

Consequence: the pipeline cannot show two colours closer together than ~68ms, and usually shows only
the later one. That is a visible-update rate near **15Hz**, against 57-80Hz of wire writes — the gap
between "frames sent" and "frames seen" that every FPS readout in the app currently hides.

(Credit where due: Joe spotted this by eye during the run — "some flashes I only saw blue" — before
the analysis had noticed it.)

The floor we can probe is **~6-8ms, not the 2ms asked for**: each `emit` costs time to issue, so the
achieved gaps ran ~5ms above the requested ones — which matches the separately-measured 4.6ms
per-write cost exactly. Below that the app physically cannot send, so "no drops anywhere reachable"
is the operational answer even though the strip's true limit (if any) is somewhere underneath it.

Consequence for the harness: **`StripLimits.minWriteSpacingMs = 20` is wrong and actively
misleading.** It drops a quarter of the writes a fast preset makes, in simulation, that the hardware
demonstrably renders. Nothing in this run justifies a drop mechanism at all in the reachable range.

Two cautions on the method, both worth knowing before running it again:

- **Do not trust `find_sync` for alignment here.** On this footage it latched onto the *second* of
  the three flashes, putting every prediction 10 frames (337ms — the flash spacing) late. That read
  5% of bursts as black and silently read the blue hold whenever it meant to read the red window.
  Each burst is now anchored on its own black-to-lit transition, which is immune to that and to
  frame-rate drift; `find_sync` only locates the run.
- **The settled-colour read alone cannot see a lost *first* write**, and very nearly buried the more
  important finding. A dropped second write leaves red, which is what the run was built to test. A
  first write lost leaves *blue* — identical to success. Reading only the settled colour scored that
  110/110 and called it a clean sweep. Measuring the red phase's *duration* is what separated the
  two cases, and it needed no extra footage. **Add a control subset next time** — bursts that send
  red and no second write at all — so "red held" versus "blue" validates the classifier end to end
  rather than leaving it to be inferred.

## Capturing the spacing staircase (the run to do next)

Settles `minWriteSpacingMs`, which the 2026-08-16 recording could not. **Drive from the Pixel** — the
phone whose behaviour we care about — and film with anything, including the moto at 30 or 60fps. No
slow-mo: each burst holds its answer for 700ms, so the colour the strip settles on is readable at any
frame rate.

1. Connect the strips in Fuse on the Pixel, one strip for the first run.
2. Point the camera at the lit wall, both patches in frame, **exposure and white balance locked**,
   room dark. Start recording.
3. `adb -s 47201FDAS00BHH shell am broadcast -a com.example.debug.ACTION_CONTROL -p com.github.hyphen05.fuse --es cmd run_calibration --es sequence spacing_staircase`
4. Leave the phone alone and in the foreground for ~2½ minutes (110 bursts). Stop recording.

   **The moto's camera app exits to the launcher on its own when left idle**, and it is not the
   screen timeout — that was already 30 minutes. It cost one whole unrecorded run on 2026-08-16.
   Start the recording *before* any long wait, not after: a recording camera stays put.
5. Repeat with two strips connected — the per-write cost doubles, so the floor may too.
6. Pull the CSV, put it next to the video, and run `analyse_staircase.py` (set `VIDEO` first).

Reads red/blue/black per burst: blue = both writes rendered, red = the second was dropped, black =
both were. The drop rate against achieved spacing is the answer.

**The run now ends with ten control bursts** (`control_*`), which send the first colour and no
second write at all. They must settle on **red**. Any that read blue or black mean the classifier is
reading the wrong window and every number from that run is suspect — which is exactly what
`find_sync` latching onto the wrong flash did to the first analysis, undetected. Check these before
reading anything else.

## Absolute latency: why "flash the phone's screen" is not ready either

The standing note says the constant needs the phone's own screen in frame, flashed on the same
millisecond as the write. That is the right *shape* — a reference event that is not the strips — but
it does not work as stated, and the reason is the same one that sank the first attempt.

**The screen has its own latency, and nobody has measured it.** A View toggle reaches photons a
compositor frame or two plus panel response later — order 20-30ms on a modern phone. The quantity
being measured is guessed at 35ms. So the method returns `BLE latency − screen latency` and presents
it as absolute: the same class of error as aligning on the strips, just smaller and harder to spot.

It needs one of: the screen path characterised independently; a reference that is not the display
(an LED on a wired GPIO, whose delay is microseconds); or an explicit, stated acceptance of the
bias. Do not plumb the flash overlay until that is decided — and when it is, note that `RgbUiState`'s
shape is locked, so it wants the `AdbControlReceiver` / Mode Capture precedent (a registered
listener outside the reducer chain) rather than a new state field.

## Sustained load — the run that needs nothing but time

`sustained_load` writes flat out for an hour with no delay between writes, which is precisely the
configuration removing the pacing wait would ship. **No camera, no dark room, nobody watching** — the
CSV is the whole result, and a link that degrades shows up as an achieved rate that sags over time.
A marker row every 30s segments the file.

`analyse_sustained.py` reads the result. It reports disconnection, degradation and stalls
**separately and on purpose**: a healthy-looking mean rate can sit on top of a trace that stopped
dead for four seconds every minute, and averaging is exactly what would hide that.

### Result, 2026-08-18 (moto, two strips, 15 min) - PARTIAL, do not treat as a pass

Ran the full 15 minutes it was asked for. **273,039 writes offered, zero stalls over 250ms**, and
the rate *rose* from 221 to 325 writes/s across the run (+47%) - warm-up, not degradation. Nothing
in the trace suggests the app-side loop struggles.

**But it does not clear Phase 3 step 2, for two reasons.**

1. **The CSV cannot see the link.** It records offered writes, and the offer loop runs happily
   against a dead connection. A mid-run disconnect would look identical to a clean run.
2. **The telemetry that *would* have shown it was not captured.** Diagnostics were not started
   before the run, and by the time that was noticed the logcat buffer had rolled past the window.
   That is a process error, not a property of the sequence: `start_diagnostics` before
   `run_calibration`, and the 1Hz DeviceWriteManager line answers it directly.

Also note the run was 15 minutes rather than 60 (Joe's call - an hour is an hour of strobing his
room and of the shared phone). Anything that only appears after ~20 minutes of thermal load remains
unmeasured either way.

**To actually clear it:** re-run with `start_diagnostics` first, then check the exported log for
`Disconnected from GATT` and for `fps` staying non-zero throughout.

Unrelated but worth recording, from a drop observed later the same night: both strips dropped
together at 01:26 and **both reconnected within two seconds** through the backoff-retry path - which
is the path the GATT-close fix touches. Reconnection works on real hardware after that change.

This is the last measurement standing between the pacing work and shipping it: every ramp so far ran
flat out for about fifteen seconds, so "does an hour of this disconnect, back the queue up, or
degrade" is genuinely unmeasured. Run it on a day when the strips are not otherwise wanted; it does
not need to happen at night.

## Running a session

Requires: strips connected in Fuse, a camera that is not the phone driving them (see the caveat
below), a dark room, and locked exposure.

```
adb shell am broadcast -a com.example.debug.ACTION_CONTROL -p com.github.hyphen05.fuse \
    --es cmd run_calibration \
    --es sequence <hold_white|brightness_ramp|dark_ramp|latency_pulse|rate_ramp|spacing_staircase|sustained_load>
```

`hold_white` parks the strips at the brightest state any run produces, so the camera's exposure can
be locked against the worst case — lock it against anything dimmer and the top of the ramp clips
and is unrecoverable. Every measuring sequence opens with three fast white flashes: that is the
alignment point between video time and the CSV timestamps.

Each run writes `fuse_calibration_<sequence>_<epoch>.csv` to the app's external files dir
(`/sdcard/Android/data/com.github.hyphen05.fuse/files/`) recording exactly what was sent when.

Sequences pin strip brightness to 100% first, because the strip applies its own dimming on top of
whatever RGB it receives and a run taken at some other level cannot be untangled afterwards. The
app's slider still shows the old value afterwards — nudge it to resync.

## The phone cannot film itself (2026-08-16)

With the camera app in front, Android froze Fuse and then killed it — *"Async binder space running
out while frozen"* — because BLE callbacks kept arriving at a frozen process. That session worked
around it by disabling `cached_apps_freezer` globally on Joe's own phone, which then had to be
remembered and put back.

**Fixed properly 2026-08-18: every run is now wrapped in a foreground service**
(`CalibrationForegroundService`), so the process is unfreezable for its duration and no device
surgery is needed. It is best-effort — if the service will not start, the run still goes ahead and
says so in the log, it is just freezable again. **Do not disable the freezer any more.**

Backgrounding still costs ~57% of write throughput (36.8ms per write against 4.6ms in the
foreground) even when the process survives. That is scheduling and radio priority, not freezing, and
no service type changes it — so a filmed run still is not measuring the same system as normal use.
It matters for any *rate* measurement and not at all for a response-curve or spacing one.

## Analysis

Run everything from the folder holding the CSVs and the video
(`C:\Users\attgm\FuseCalibration\2026-08-16\`).

| Script | Answers |
|---|---|
| `decode_full.py` | decodes the whole 210s once into `cache_full_<region>.bin`; every other script reads that |
| `analyse_ramp.py` | brightness response (needs only the first 105s, has its own caches) |
| `analyse_rate.py` | wire side: what send rate the phone achieved — CSV only, no video |
| `analyse_latency.py` | delivery jitter (and why absolute latency is not in this recording) |
| `analyse_rate_light.py` | light side: which writes the strip actually rendered |
| `analyse_staircase.py` | `minWriteSpacingMs`, from the staircase run above |
| `analyse_sustained.py` | whether an hour flat out disconnects, degrades or stalls — CSV only |

`analyse_ramp.py` measures the brightness response: it crops a wall patch (never the emitters —
those clip), averages it per frame via ffmpeg, aligns on the sync flashes, and fits the exponent.

Measure reflected light off a wall, not the LEDs. And linearise: video is gamma-encoded, so raw
frame values are not proportional to light, and skipping that step makes any response look
dramatically more compressive than it is.

## Results, 2026-08-16 (Pixel 9 Pro XL, "Fireworks" + "Ambiance Bars")

**light ≈ (byte/255) ^ 0.4** — measured independently on both devices (k = 0.39 and 0.41).

Byte 8 → 11% light · 32 → 42% · 64 → 53% · 128 → 84% · 255 → 100%.

The whole top half of the byte range buys the last 16% of light. Consequences are written up in
`IMPROVEMENT_PLAN.md`'s Tier E; the short version is that the cubic curve in
`ColorConverter.hsvToRgb` roughly compensates for this and should not be removed without new
measurements.

### The fit is not trustworthy below byte ~32 (noticed 2026-08-17)

Check the fit against its own points. From byte 32 up it is good to within a few percent. At **byte
8, the lowest point measured, it is out by more than 2×**: the rig read 11% of full light, the curve
says 25%. The exponent was fitted across the whole ramp, so the bright end — where most of the
points are — decided it.

That matters because everything the ambiance and dithering work is about sits at **bytes 4-24**,
inside that gap, and **nothing below byte 8 has ever been measured at all**. Two readings, and this
recording cannot separate them: a real toe in the response (a minimum usable duty cycle, or PWM
resolution running out at the bottom), or measurement error exactly where it is most likely, since
the dimmest wall patch is the one nearest the camera's noise floor and the most sensitive to an
error in the black level.

Both readings push the same way, so conclusions drawn from the curve stay directionally right: the
local slope between the *measured* bytes 8 and 32 is **1.7× steeper** than the fitted curve's, so
near-black steps are if anything larger than the model says. What the curve must not be used for is
a light value at a specific byte below 8 — including the tempting claim that byte 1 emits ~11%.

`dark_ramp` exists to settle this, and to answer the question it raises: **is the brightness command
a finer dimmer than the colour bytes are?** If brightness is a PWM duty cycle held at more than
8-bit precision, dim colours should be commanded as large bytes scaled down rather than as small
bytes — one extra command, and it buys back the resolution the byte grid does not have down there.
See `DitheringSimulation` for the argument and the numbers.

## Finding the PWM carrier with one photo (method corrected 2026-08-17)

Whether the strips' own dimming carrier is high (invisible, ignore it) or low (100-250Hz, flickers
on its own under deep dimming and would beat against anything the app adds) is settled by a single
motion-smeared still. No rig, no dark room, no lock.

The first attempt came back unreadable, and each of its three problems is worth stating as a rule:

| Do | Because |
|---|---|
| **Dim the strip to ~10-20% first** | At full white the duty cycle is ~100% and there is nothing to chop. A continuous streak proves nothing. |
| **Expose so the strip is *not* blown out** | PWM shows as intensity structure along the smear; a saturated pixel has none. Tap the strip to focus/expose, then drag the exposure slider down until it is grey rather than white. |
| **Sweep the camera *across* the strip, not along it** | Along its own axis, LED pitch and PWM chopping fall on the same axis and are indistinguishable — regular dark bands between LEDs could be either. Across, each LED draws a line that PWM breaks into countable dashes. |

Then: shutter speed comes out of EXIF (`ExposureTime`), count the dashes across one LED's streak,
and the carrier is `dashes / exposure_seconds`. At 6ms exposure a 1kHz carrier gives ~6 dashes —
easy to count — and anything that shows *no* dashes at a legible exposure is fast enough not to care
about.

### Result, 2026-08-19: no PWM chopping at 15% brightness

Four stills, Pixel, strip dimmed to ~15%, camera swept across the strip. Exposure 8.27ms, ISO 22.
Three of the four caught a streak; all three agree.

Measured on the longest smear (525px across an 8.27ms exposure = **63 px/ms**):

| | |
|---|---|
| samples inside the streak below 50% of peak | 38 of 525 |
| contiguous dark runs | 2, both at the streak's ends |
| mean level inside the streak | 230/255 |

The two dark runs are the ramp at each end, at sample 0 and the last sample. There is **no periodic
structure anywhere along the streak**, on any of the three photos.

At 63 px/ms a 1kHz carrier would chop the streak into eight ~63px dashes, and 5kHz into ~13px
dashes. Both would be unmissable. So the carrier is **above ~5kHz, or the driver dims by current
rather than by PWM** — this method cannot separate those, and for our purposes it does not need to.

**What it settles:** dimming via the brightness command is flicker-free at 15%, so there is no
low-frequency carrier to beat against anything the app does, and no self-flicker under dimming.
That was the open risk against headroom scaling, and it is now closed. What remains open for
headroom scaling is *resolution*, not flicker — `dark_ramp` phase 2.

**Caveat worth keeping:** this tested 15%. Some drivers change strategy at very low duty, so 1-5%
is not covered. `dark_ramp` phase 2 sweeps 1-20% and would show a change of behaviour as a kink.

Note the clipping: the LEDs still read 255 at the streak's core. That does **not** undermine the
result — a PWM off-period collects zero light, and clipping cannot fill in a genuine zero. Solid
streaks mean no off-periods, however bright the on-periods were.

## Capturing the dark ramp (the offline-analysis run to do next)

Two phases in one recording, ~2½ minutes. Same rig as any other sequence — dark room, locked
exposure, wall patch not the emitters — but with one change that matters: **lock exposure against
`hold_white` as usual, then check the dimmest levels are still above the noise floor** on a test
frame before committing to the run. The whole point is the bottom of the range, and that is the part
an exposure lock chosen for the bright end throws away.

1. Phase 1 steps every byte from 0 to 32 (1.5s each), then descends in fours as a drift check.
2. Phase 2 holds colour at byte 96 and steps brightness 1-20%, restoring 100% at the end.

Phase 2's read is the interesting one: smooth, even steps mean the fine dimmer exists and headroom
scaling is the right fix for dark scenes. A staircase landing on the same handful of levels as phase
1 means brightness is just a byte multiply, and it is not.

## Throughput, 2026-08-16 (wire side, all three rate ramps)

Nothing saturates. Achieved rate climbs all the way to the top of the ramp, so the runs never found
a ceiling — what they found is a **fixed cost per write** on top of whatever interval was asked for:

| Run | Overhead per write | Fastest achieved |
|---|---|---|
| foreground, 1 strip | **4.6 ms** | 80.8 Hz |
| foreground, 2 strips | **8.7 ms** | 57.1 Hz |
| backgrounded, 2 strips | **36.8 ms** | 27.5 Hz |

Two strips cost almost exactly twice one strip's overhead, which says the cost is per strip per
write and the strips are being written sequentially — not that there is a shared radio budget being
divided. Modelling it as a per-write cost predicts every row of all three runs; modelling it as a
throughput ceiling with a `multiDeviceThroughputFactor` does not, because there is no ceiling in
range. At the top of the ramp the two-strip run reaches 71% of the one-strip run, which is the
number that factor would want, but it is an artefact of where the ramp stopped.

Backgrounding costs far more than the extra strip does: 37ms per write against 4.6ms.

## Delivery jitter, 2026-08-16

**sd ≈ 36 ms, peak-to-peak ≈ 108 ms**, consistent on both devices, from the 12 latency pulses.
Writes do not land evenly spaced even when they are sent evenly spaced.

**Absolute latency is not measurable from a recording of this shape**, and this is not a flaw in the
analysis — it is missing information. Video time can only be pinned to log time using something
visible, the only visible things are the strips, and the strips arrive already delayed by the
latency in question. Aligning on the sync flashes subtracts the latency out and leaves a mean of
zero by construction. (An earlier pass of this analysis reported *negative* latency, which is how
the problem announced itself.)

To get the constant, a future run needs a second thing in frame whose time the phone knows: flash
the **phone's own screen** white on the same millisecond the write goes out, log both, and film
screen and strip together. That measures (BLE → light) − (draw → photons); the screen path is tens
of ms in its own right, so it bounds the answer rather than pinning it, but a bound is more than
this recording can give.

## What the strip rendered, 2026-08-16

Only the backgrounded run is on video — it is the one that was running while the camera was in
front. Per-write, at the spacings 60fps can resolve:

| Achieved rate | Spacing | Writes rendered |
|---|---|---|
| 1.8 Hz | 543 ms | 8/8 both devices |
| 4.2 Hz | 236 ms | 18/20 and 17/20 |

**Superseded by the staircase run below** for the drop question — this section stands only as the
record of why 60fps edge-counting does not work.

**Above ~5 Hz this footage cannot answer the question at all.** With ±50ms of delivery jitter and a
17ms frame, a write's edge can land either side of where the log says it should, so "was this write
rendered" and "did it arrive late" become the same observation. Three different edge detectors gave
three different answers at 10 Hz, which is the tell. `minWriteSpacingMs` is therefore **still
unmeasured** — a 120/240fps run is the way to get it.
