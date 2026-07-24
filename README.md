# Fuse

An Android app for controlling Bluetooth LE RGB LED hardware — power, color, brightness, modes, and warmth, plus some more ambitious stuff:

- **Ambiance mode** — syncs LED color to on-screen content in real time via screen capture, with smoothing, scene-cut detection, and gamma-corrected fades to avoid flicker
- **Audio visualizer** — reactive lighting driven by on-device audio capture, with beat detection (spectral flux onset analysis) and multiple presets (Smooth Flow, Ambient Chill, Vocal Floor, and more)
- **Scene chaining** — sequence multiple scenes with configurable delay, looping, and auto-cancel on manual input
- **Multi-device support** — save, alias, and control multiple devices independently

No account, no ads, no tracking, no internet permission at all — it just talks to your lights over Bluetooth.

## Compatible hardware

Fuse was built and tested against **MELK-branded DuoCo strips** (the ones that ship with the duoCo Strip / duoCo StripX app). Its wire protocol turns out to be a byte-for-byte match for the widely-cloned **ELK-BLEDOM** family — a generic BLE LED-controller chipset sold under a lot of different names. So the app's scanner also picks up devices advertising these name prefixes:

**Confirmed working:**
- MELK-branded DuoCo strips

**Likely to work (same protocol, not personally verified — try it and let us know!):**
- Anything named `ELK-*`, `ELK-BLEDOM`, `BLEDOM`, `LEDBLE`, `LED-*`, `JACKYLED`, `XROCKER`, or `DMRRBA-007`
- Anything that shipped with the **duoCo Strip**, **Lotus Lantern**, **Lotus Lamp X**, or **Happy Lighting** companion app (though "Happy Lighting" in particular gets reused across a few different underlying chipsets, so no promises there)

**Won't work:** hardware speaking a genuinely different protocol under the hood, even if it looks like the same kind of product from the outside. If your strip shows up in the scan list but nothing happens when you try to control it, that's most likely what's going on.

If you've got one of these (or something not listed here) and want to try it — **please do, and open an issue or PR either way.** Reports of "this works" are just as useful as "this doesn't," and if you've reverse-engineered a protocol variant we don't handle yet, a PR is very welcome.

## Architecture

MVI (unidirectional data flow), chosen to handle concurrent async streams (BLE, audio capture, UI events) without the race conditions plain MVVM ran into. Package structure:

- `core/` — pure logic (color math, protocol serialization, calibration)
- `data/` — persistence (database, preferences)
- `domain/` — models and repository interfaces
- `hardware/` — BLE and audio I/O
- `presentation/` — Compose UI and ViewModels/reducers

## Hardware note

These devices flash briefly at the firmware level whenever the pixel count changes — that's a hardware constraint, not a bug, and animations are designed around it rather than fighting it.

## Contributing

This is a hobby project and still finding its feet, so feedback is genuinely welcome — hardware compatibility reports, bug reports, feature ideas, or just "this part of the UI is confusing." Issues and PRs are both fine ways to reach out; there's no formal process, just say what you found.

## License

MIT — see [LICENSE](LICENSE).
