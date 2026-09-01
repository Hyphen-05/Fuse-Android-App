# Capture session runbook — the commands, in order

The plan is [docs/capture-session-plan.md](../../docs/capture-session-plan.md); this is the sheet you
work from with a phone in each hand. Written 2026-09-01.

- **Driver: Pixel 11 Pro XL `65271FDDV001AB`** — representative BLE hardware. Rate, spacing and
  latency are properties of the *driving* phone, so a run driven from the moto characterises a phone
  nobody will use.
- **Camera: Pixel 9 Pro XL** — needs USB debugging authorised to this laptop. Confirm this first;
  it is the single most likely thing to be discovered too late.
- The moto is **not needed**, so its lock stays free. Its contrast runs are last, if at all.

```bash
ADB=C:/Users/attgm/AppData/Local/Android/Sdk/platform-tools/adb.exe
DRIVER=65271FDDV001AB
```

## 0. Before anything

```bash
C:/Users/attgm/AppData/Local/Android/Sdk/platform-tools/adb.exe devices -l
```

Three devices, or the camera phone is not authorised yet. Then install the branch build on the
driver and provision the camera phone:

```bash
tools/capture/provision-camera.sh <camera-serial>
```

Claim the pixel lock for a **realistic window** — this needs hours, not a burst, and it is SMSRelay's
Primary. Negotiate one honest long claim rather than re-taking short ones.

## 1. Sequences

Every run is one broadcast at the driver. Nothing here is reachable from the app itself.

```bash
adb -s $DRIVER shell "am broadcast -a com.example.debug.ACTION_CONTROL -p com.github.hyphen05.fuse --es cmd run_calibration --es sequence <name>"
```

| `<name>` | Runs for | Camera | Light |
|---|---|---|---|
| `hold_white` | 3 min | set exposure against it | — |
| `brightness_ramp` | ~4 min | 1080p, locked | curtains closed |
| `dark_ramp` | ~3 min | 4K, locked, P1 then P2 macro | **blackout, after dark** |
| `spacing_staircase` | ~2 min | 1080p, locked | dim room, daytime fine |
| `rate_ramp` | ~2 min | none needed — CSV alone | any |
| `latency_pulse` | ~4 min | **stock camera, 240fps**, screen + strip in frame | daytime fine |
| `sustained_load` | 15 min, or `--ei minutes 60` | none | any |
| `attention` | until stopped | never during a capture | — |

Read the **ten control bursts first** on any staircase run: they must settle on red. Any reading blue
or black means the classifier is misaligned and every number from that run is suspect.

`latency_pulse` now flashes the driver's own screen white on the same pulse as the strip. Both must
be in frame or the run measures jitter again, which is what every previous attempt measured. The CSV
carries `screen_presented` rows (marked `-2`) with the compositor's share already subtracted; the
panel's own response, about one refresh interval, is a systematic offset to quote alongside the
result, not to fold into it.

## 2. Stopping

```bash
adb -s $DRIVER shell "am broadcast -a com.example.debug.ACTION_CONTROL -p com.github.hyphen05.fuse --es cmd stop_calibration"
```

Aborts a run without killing the app, which would take the CSV with it. It is also how the attention
signal is acknowledged.

## 3. The attention signal

When a run finishes, the strip breathes fiery orange until acknowledged — the monitor is off during a
session, so the strip is the only channel. Suppress it with `--ez attention false` when someone is
watching the phone anyway. Starting the next sequence acknowledges it too.

**Any recording containing the orange breath should be treated as ending there.** It only runs when
nothing is being captured, so its presence in a frame means the measurement was already over.

## 4. Pulling files

```bash
adb -s $DRIVER shell "ls -l /sdcard/Android/data/com.github.hyphen05.fuse/files/"
adb -s $DRIVER pull /sdcard/Android/data/com.github.hyphen05.fuse/files/ ./captures/
```

Pull **between** runs, not at the end. 4K and slow-mo eat gigabytes, and long runs heat and drain
both phones.

**Before the Pixel 9 goes for trade-in: pull every file and open it.** Videos, CSVs, the Mode
Capture JSON. Check sizes and play a frame of each. A trade-in wipe is final, and a half-pulled video
is indistinguishable from a good one until someone tries to read it.

## 5. Analysis

`tools/calibration/` has the six scripts — `analyse_ramp.py`, `analyse_staircase.py`,
`analyse_rate.py`, `analyse_rate_light.py`, `analyse_latency.py`, `analyse_sustained.py` — and its
own README for what each one expects.

## Mode Capture is not in this list

It is a screen in the app, not an adb sequence, so it is driven by taps on the driver. It is priority
1 in the plan: ~200 built-in modes, 10s each, about 35 minutes unattended, and its names, categories
and directions are all still *guessed*. Film it while it runs — its own sampling has never been
verified, so if the on-device JSON is wrong the video still holds the raw truth.
