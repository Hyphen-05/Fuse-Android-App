# Fuse

An Android app for controlling Bluetooth LE RGB LED hardware — power, color, brightness, modes, and warmth, plus some more ambitious stuff:

- **Ambiance mode** — syncs LED color to on-screen content in real time via screen capture, with smoothing, scene-cut detection, and gamma-corrected fades to avoid flicker
- **Audio visualizer** — reactive lighting driven by on-device audio capture, with beat detection (spectral flux onset analysis) and multiple presets (Smooth Flow, Ambient Chill, Vocal Floor, and more)
- **Scene chaining** — sequence multiple scenes with configurable delay, looping, and auto-cancel on manual input
- **Multi-device support** — save, alias, and control multiple devices independently

No account, no ads, no tracking, no internet permission at all — it just talks to your lights over Bluetooth.

## Installing

Grab the latest APK from the [Releases page](../../releases) and sideload it (you'll need to allow installs from your browser/file manager in Android's settings). These are debug-signed builds, not Play Store releases.

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

## Building from source

Requires JDK 21 and the Android SDK (compileSdk 36). The Gradle wrapper is committed, so no local Gradle install is needed.

```
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are signed with `debug.keystore` at the repo root, which is gitignored (not meant to be shared) and isn't auto-generated since the build points at that fixed path rather than Android's default per-user location. Create one once, before your first build:

```
keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

To run the unit test suite:

```
./gradlew testDebugUnitTest
```

A release build (`./gradlew assembleRelease`) also works without any keystore configured — `app/build.gradle.kts` falls back to an unsigned release APK when no release keystore is present (useful for reproducible-build pipelines like F-Droid's), but an unsigned APK can't be installed directly. Use the debug build, or the prebuilt APKs on the [Releases page](../../releases), to actually install the app.

Every push to `main` runs the test suite and builds a debug APK in CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)); pushing a `v*` tag builds and attaches a debug APK to a new GitHub Release ([`.github/workflows/release.yml`](.github/workflows/release.yml)).

## Contributing

This is a hobby project and still finding its feet, so feedback is genuinely welcome — hardware compatibility reports, bug reports, feature ideas, or just "this part of the UI is confusing." Issues and PRs are both fine ways to reach out; there's no formal process, just say what you found.

## License

MIT — see [LICENSE](LICENSE).
