# Hardware calibration

Scripted light sequences plus a video of the strips, turned into the constants the feel harness
needs. Without this, `VirtualStrip`'s limits are guesses and its renders can only be compared to
each other — not to what the hardware will really do.

## Running a session

Requires: strips connected in Fuse, a camera that is not the phone driving them (see the caveat
below), a dark room, and locked exposure.

```
adb shell am broadcast -a com.example.debug.ACTION_CONTROL -p com.github.hyphen05.fuse \
    --es cmd run_calibration --es sequence <hold_white|brightness_ramp|latency_pulse|rate_ramp>
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
out while frozen"* — because BLE callbacks kept arriving at a frozen process. Disabling
`cached_apps_freezer` works around it for a test device but is a global setting that should be put
back. The proper fix is a foreground service for the duration of a run. Backgrounding also costs
~57% of write throughput even when the process survives, so a filmed run is not measuring the same
system as normal use.

## Analysis

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
