# Fuse

Android app for controlling Bluetooth LE RGB LED strips. Power, color, brightness, modes, warmth, plus a few bigger features:

- Ambiance mode: syncs LED color to whatever's on screen, using screen capture with smoothing and scene-cut detection so it doesn't flicker on cuts
- Audio visualizer: reacts to audio picked up by the phone mic or on-device audio, with beat detection and a handful of presets (Smooth Flow, Ambient Chill, Vocal Floor, etc.)
- Scene chaining: queue up multiple scenes with delays and looping, cancels automatically if you touch the controls manually
- Multiple devices at once, each saved and aliased separately

No accounts, no ads, no analytics, no internet permission. It only talks to your lights over Bluetooth.

## Installing

Grab an APK from the [Releases page](../../releases) and sideload it. These are debug-signed builds, not something from the Play Store.

## Compatible hardware

Built and tested against MELK-branded DuoCo strips (the ones that pair with the duoCo Strip / duoCo StripX app). Turns out that protocol is identical to the ELK-BLEDOM chipset, which gets sold under a lot of names, so the scanner also picks up:

**Confirmed working:**
- MELK-branded DuoCo strips

**Probably works, not personally tested:**
- Anything advertising as `ELK-*`, `ELK-BLEDOM`, `BLEDOM`, `LEDBLE`, `LED-*`, `JACKYLED`, `XROCKER`, or `DMRRBA-007`
- Anything that came with the duoCo Strip, Lotus Lantern, Lotus Lamp X, or Happy Lighting app (Happy Lighting in particular covers a few different chipsets under one app, so no guarantees there)

**Won't work:** strips using a genuinely different protocol, even if the product looks similar from the outside. If a device shows up in the scan but doesn't respond to controls, that's usually why.

If you try it on hardware not listed here, open an issue either way — works or doesn't, both are useful to know. If you've worked out a different protocol variant, a PR adding it is welcome.

## Architecture

MVI / unidirectional data flow — picked this over MVVM to handle BLE, audio capture, and UI events running concurrently.

- `core/` — color math, protocol encoding, calibration
- `data/` — database, preferences
- `domain/` — models, repository interfaces
- `hardware/` — BLE and audio I/O
- `presentation/` — Compose UI, ViewModels/reducers

## Hardware note

These strips flash briefly at the firmware level any time the pixel count changes. That's a hardware limitation, not a bug — animations are built around it instead of trying to hide it.

## Building from source

Needs JDK 21 and the Android SDK (compileSdk 36).

```
./gradlew assembleDebug
```

APK ends up at `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are signed with `debug.keystore` at the repo root — it's gitignored and not auto-generated, since the build points at that fixed path instead of Android's default per-user keystore location. Create one before your first build:

```
keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

`./gradlew testDebugUnitTest` runs the unit test suite. `./gradlew assembleRelease` also works with no keystore configured — `app/build.gradle.kts` falls back to an unsigned release APK in that case (useful for reproducible-build pipelines like F-Droid's), but you can't install an unsigned APK directly. Use the debug build or a Releases-page APK if you actually want to install it.

CI runs the test suite and builds a debug APK on every push to `main` ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)); pushing a `v*` tag builds a debug APK and attaches it to a new GitHub Release ([`.github/workflows/release.yml`](.github/workflows/release.yml)).

## Contributing

Hobby project, still rough in places. Bug reports, hardware compatibility reports, and "this part of the UI doesn't make sense" are all fine to open as issues. No formal process.

## License

MIT — see [LICENSE](LICENSE).
