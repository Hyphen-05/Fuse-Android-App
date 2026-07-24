# Expressive RGB

Android Bluetooth LE RGB LED controller app targeting DuoCo hardware.

> Full phase-by-phase implementation history, bug investigations, PR-by-PR merge history, and per-phase test-count breakdown live in [HISTORY.md](HISTORY.md). If a decision below seems under-explained, check there first.

## Development workflow

This project is developed across three tools with different roles:
- **Claude (chat)** — architecture decisions, planning, prioritization. This is where design tradeoffs get decided.
- **Gemini AI Studio** — some code generation, prompted with complete self-contained instructions and explicit file-scope constraints.
- **Claude Code (this environment)** — direct-repo execution and verification. Your job is to check claims against the real source, not trust prior self-reports (from Gemini or from Claude chat) at face value.

**Verification standard: treat any single AI's self-report as unverified until checked against real source, a build, or git history.** Full-file dumps from an AI chat can be fabricated convincingly even when they look detailed and specific. The trustworthy channels are: the actual files in this repo, builds that are run and pass/fail for real, and git history inspected directly. If you're asked to confirm something works, verify it against the actual code — don't just repeat back what you were told.

Joe (the developer) considers himself a git novice — explain git concepts (staged vs. committed, what a diff/branch represents) rather than just running commands silently when it's relevant to what he asked.

## Architecture

Target architecture is **MVI / unidirectional data flow**, chosen over MVVM+UseCases because concurrent async streams (BLE, audio, UI events) were causing race conditions under plain MVVM.

Target package structure:
- `core/` — pure logic (color math, calibration, protocol encoding)
- `data/` — DB, prefs
- `domain/` — models, repository interfaces
- `hardware/` — BLE, audio, camera
- `presentation/` — Compose UI, ViewModels

Two "god files" were mid-refactor into this structure:
- `RgbControllerViewModel` — originally 5,440 lines, 12 mixed responsibilities (BLE GATT, DSP/beat detection, audio capture, DB, prefs, scene orchestration)
- `MainActivity` — originally 2,476 lines (nav/permissions/dialogs/UI)

God-file refactor (phases 1–6) and the mapping-layer/audio rewrites are complete — see HISTORY.md for the full history.

### RgbUiState shape (locked)

```
RgbUiState:
  connectivity: ConnectivityState
  coreControl: CoreControlState       // includes errorMessage, protocolBytes
  audioSettings: AudioSettingsState   // 40 fields (27 original + 13 from the mapping-layer stages 1-2, items 19-20), includes musicMode
  ambianceSettings: AmbianceSettingsState  // ambiancePreset non-null, "Balanced" default
  calibrationFlow: CalibrationFlowState
```

- `RgbIntent` lives inside `RgbUiState.kt` — there is **no separate `RgbIntent.kt` file**.
- `colorCalibrations`, `cctCalibrations`, `byteOverrides` stay as **separate StateFlows**, NOT part of `RgbUiState`.
- `TelemetryState` (`musicAmplitudes`, `visualizerHue`, `logMessages`, `deviceAchievedFps`) is separate from `RgbUiState` due to high-frequency updates.

### Current reducer status

All four reducers below are **wired live into `RgbControllerViewModel`**. `RgbControllerViewModel._uiState` is a real `MutableStateFlow<RgbUiState>`, not the old flat `ControllerUiState`.

**Wiring shape:** a single `private fun dispatch(intent: RgbIntent)` in `RgbControllerViewModel.kt` runs every intent through all four reducers in sequence — `coreControlsReducer` → `ambianceSettingsReducer` → `audioSettingsReducer` → `calibrationFlowReducer` — threading the state through each call. This is safe because each reducer's `when` has an `else -> state to emptyList()` fallthrough, so an intent outside a reducer's scope is a no-op there; only the one reducer that actually owns the intent produces a state change or effects. Each reducer's side-effect list is executed immediately after by one dedicated executor function (`executeCoreSideEffect`, `executeAmbianceSideEffect`, `executeAudioSideEffect`, `executeCalibrationSideEffect`), which is where the real hardware/IO work (GATT calls, prefs, Room, coroutine loops) actually happens — the reducers themselves stay pure. Every public ViewModel method that used to mutate `_uiState` directly (e.g. `setPower`, `startScanning`, `toggleActiveControl`) is now a one-line `dispatch(RgbIntent.X(...))` call; hardware/IO logic that isn't a pure state transition was split into private `*Hardware()` helpers invoked from the executors (e.g. `connectDeviceHardware`, `startBleScanHardware`, `checkActiveAudioRouteHardware`).

`handleConnectionStateChange` (the real GATT callback) is **not** fully routed through the reducer — only its two state-mutation points now call `dispatch(RgbIntent.InternalConnectionStateChanged(...))`. The exponential-backoff retry logic, GATT connection-priority/MTU requests, and `discoverServices()` calls stay as direct ViewModel code, since the reducer's `InternalConnectionStateChanged` case doesn't (and shouldn't) model those — they're genuine hardware operations, not state transitions.

**The `StartMetronome` contract is now enforced, not just documented.** The metronome coroutine (`executeCalibrationSideEffect`'s `StartMetronome` case) calls `dispatch(RgbIntent.SendCalibrationFlash)` on every tick — the exact same call the public `sendCalibrationFlash()` makes — so `SendFlashPulse` is structurally producible from exactly one code path.

`CoreControlsReducer.kt` (`app/src/main/java/com/example/presentation/CoreControlsReducer.kt`) handles the Connectivity & Scanning / Device management / Core Controls intent groups. It has been **fixed, tested, merged, and wired**. Test coverage: 43 JUnit tests in `CoreControlsReducerTest.kt`, covering all 21 intents plus regression tests for both fixes described below.

**How this works:**

1. `SetPower`/`SetColor`/`SetBrightness`/`SetMode`/`SetWarmth` update `deviceStatesMap` via `applyControlledDeviceUpdates()`, which mirrors `RgbControllerViewModel.updateControlledDevicesInMap()`: it creates missing entries for every target address rather than only touching keys that already exist, so newly-connected auto-saved devices correctly get a `deviceStatesMap` entry from these five intents.
2. `SetDemoMode(true)` and `DisconnectAll` key off `activeConnections` (a live-only registry), mirroring the source ViewModel, rather than `deviceConnectionStates` (a map that never shrinks) — so the "already disconnected" fast path continues to fire correctly after the first device connects, avoiding repeated disconnect-effect emission for stale/already-disconnected addresses.

If touching this code further, reference the real `updateControlledDevicesInMap()` and `activeConnections` implementations in `RgbControllerViewModel.kt` directly — don't infer intended behavior from the reducer alone.

A pre-existing bug in `setWarmth` (writes `"CCT"` to a pref key, then immediately overwrites it with `"Colour"`) has been **intentionally preserved** in the reducer as behavior parity — this is not a bug to fix during the refactor.

`AmbianceSettingsReducer.kt` (`app/src/main/java/com/example/presentation/AmbianceSettingsReducer.kt`) handles the Ambiance & Camera Sync intent group. **Merged and wired.** Test coverage: 13 JUnit tests in `AmbianceSettingsReducerTest.kt`, covering all 8 intents.

**How this works:** 7 single-value setters (`SetAmbianceResponseSpeed`, `SetAmbianceSmoothnessMs`, `SetAmbianceSaturationBoost`, `SetAmbianceBrightnessCompensation`, `SetAmbianceUpdateRateCapFps`, `SetAmbianceSceneCutSensitivity`, `SetAmbianceNoiseDeadband`) each update one field and force `ambiancePreset` to `"Custom"`. `ApplyAmbiancePreset` is the exception: it sets `ambiancePreset` to the applied preset ID (not `"Custom"`), does **not** touch `ambianceUpdateRateCapFps`, and additionally cancels any running scene chain — modeled as `AmbianceSideEffect.CancelSceneChain`, mirroring `CoreSideEffect.CancelSceneChain`.

Deliberately **out of scope** for this reducer — verified, not overlooked:
- Ambiance capture start/stop has no ViewModel intent; `MainActivity.kt` drives `AmbianceCaptureService` directly via an `ActivityResultLauncher` (needs the Activity for the `MediaProjection` permission result).
- `WriteAmbianceColor` is a pure BLE passthrough (no `RgbUiState` field touched, fires per captured frame) — belongs with Commands/Protocol, not settings.
- `CoreSideEffect.StopAmbiance` already exists and is already wired into `CoreControlsReducer`, fired from `SetPower`/`SetColor`/etc. — not new work.

`AudioSettingsReducer.kt` (`app/src/main/java/com/example/presentation/AudioSettingsReducer.kt`) handles the Audio Settings intent group. **Merged and wired.** Test coverage: 39 JUnit tests in `AudioSettingsReducerTest.kt`, covering all 23 intents.

**How this works:**

1. `SetVisualizerPreset` writes the 16-field-per-preset value table (`visualizerConfigFor()`) verbatim, plus `activeFeatureName` — recomputed from `getVisualizerPresetName()`/current mode, mirroring source, even though the recompute is a no-op unless `musicMode` is `phone_mic`/`on_device`.
2. `StartMusicSync`/`StopMusicSync` model the real hardware/scene-runner work (`stopMusicSyncInternal`, `startAudioEngine`, per-address `saveDeviceState`/scene-runner release, `deviceAutomationMode` restore-or-clear) as `AudioSideEffect` cases for the ViewModel to execute — same pattern as `CoreSideEffect`. `startMusicSync` branches on `mode`: phone-mic/on-device modes route to `AudioSideEffect.StartAudioEngine`; the eight onboard BLE modes (`energetic_1/2`, `rhythm_1/2`, `spectrum_1/2`, `rolling_1/2`) route to three direct `BroadcastCommand` effects (toggle, style, sensitivity) instead.
3. Preserved faithfully, not "fixed": `setBeatThresholdMultiplier` and the smoothing/gamma/flash/brightness/speed/idle-delay group reset `visualizerPreset` to `"Custom"`; `setBeatCooldownMs`, the gain setters, `setAutoGainEnabled`, `setPaletteCyclingEnabled`, and `setLogarithmicScalingEnabled` do **not** — this inconsistency exists in the source and is reproduced exactly. `setBluetoothDelayMs` derives `totalVisualDelayMs = bluetoothDelayMs + BeatDetector().lookaheadMs` inline; `BeatDetector` (now `com.example.core.audio.BeatDetector`) is always constructed with no args, so the reducer replicates the default as a local constant (`BEAT_DETECTOR_DEFAULT_LOOKAHEAD_MS = 180L`) rather than importing the class, to avoid this reducer depending on audio-pipeline internals for a single derived value.

`CalibrationFlowReducer.kt` (`app/src/main/java/com/example/presentation/CalibrationFlowReducer.kt`) handles the Calibration & Tuning intent group. **Merged and wired**, including the `StartMetronome` contract (see above — no longer doc-only). Test coverage: 22 JUnit tests in `CalibrationFlowReducerTest.kt`, covering all 13 intents.

**How this works:**

1. `CalibrationFlowState` only has 3 fields (`showCalibrationPrompt`, `isCalibrationModeActive`, `calibrationDelayOffsetMs`), but the Calibration & Tuning intent group is broader than that shape — several intents write to `ConnectivityState` (`isTestPatternRunning`, `devicePacingMs`) and `AudioSettingsState` (`bluetoothDelayMs`, `totalVisualDelayMs`) instead of or in addition to `CalibrationFlowState`. `calibrationFlowReducer` touches `connectivity`, `audioSettings`, and `calibrationFlow` directly in the same function — same rationale as `AudioSettingsReducer` reaching into `deviceStatesMap`: the reducer is scoped by intent group, not by which state slice each intent happens to touch.
2. Almost every intent in this group is IO/hardware-bound (prefs, Room DB, live `DeviceWriteManager` mutation, or a long-running coroutine loop), so it leans on `CalibrationSideEffect` more heavily than the other reducers. `SaveColorCalibration`/`DeleteColorCalibration` are Room-only (no `RgbUiState` field touched, same pattern as `WriteAmbianceColor`); `SaveCctCorrectionProfile`/`DeleteCctCorrectionProfile` write prefs and reload the separate `cctCalibrations` StateFlow; `StartCalibrationMode`/`StopCalibrationMode`/`ToggleTestPattern` start or cancel long-running coroutines (metronome loop, test-pattern color cycle) that a pure reducer can't own directly.
3. Two extra parameters beyond `state`/`intent`, because a pure reducer can't do the IO or see live hardware state itself: `savedCalibrationDelayMs: Int` (pre-fetched pref read, seeds `calibrationDelayOffsetMs` when `StartCalibrationMode` fires) and `connectedManagerAddresses: Set<String>` (reproduces the `deviceWriteManagers[address] != null` guard in the source `setDevicePacing()` — when absent, the *entire* block including the state update is skipped, not just the hardware write).
4. Preserved faithfully, not "fixed": `StartCalibrationMode` unconditionally calls `stopCalibrationMode()` first, so restarting while already active silently resets and re-logs "stopped" then "started"; `SaveCalibrationAndExit` writes the same delay to two different pref keys via two different mechanisms; `ResetCalibrationSettings` writes `bluetooth_delay_ms` to 0 as an explicit separate pref call (none of its three `clear*Prefs()` calls cover that key) and resets pacing via two independent paths (live `DeviceWriteManager` mutation for connected devices, `devicePacingMs.mapValues { 100 }` in state for all recorded addresses) that are kept in sync manually, not derived from one another — both preserved, not unified.

## Hardware constraint (permanent, confirmed)

Every pixel-count change on a DuoCo device causes a visible firmware-level LED flash. This is unavoidable at the firmware level — design animations around it (e.g. deliberate stepped changes) rather than trying to eliminate it.

## Known open bug

LEDs freezing intermittently — unresolved. Experimental start/stop diagnostic logging (exportable to file) exists to help diagnose; if you touch BLE write paths or the watchdog, be aware this issue is still open.

## Ambiance mode

Uses screen capture (`MediaProjection`), **not** the physical camera. Don't assume camera code is load-bearing for ambiance — the physical camera is used only by the separate Mode Capture feature below.

## Mode Capture (experimental)

One-time diagnostic tool under Settings > Experimental (`ui/components/ModeCaptureScreen.kt`) that auto-cycles every mode except Auto Play (`byteValue = 0`), holding each for 10s while the phone camera samples color at multiple positions along the strip over time, then exports the raw data as JSON (share sheet) for offline analysis of `ModesScreen.kt`'s currently-guessed mode metadata (name/category/direction/colors). Built 2026-07-23 — see HISTORY.md item 29 for the full design writeup, including why it's kept deliberately outside `RgbUiState`/`RgbIntent`/the reducer chain (`presentation/modecapture/ModeCaptureViewModel.kt` is a small self-contained `AndroidViewModel`, same precedent as Ambiance capture's Activity-driven `MediaProjection` flow) and why the reference-color correction is exported raw rather than computed on-device. **Not yet verified on real hardware** — capture-quality (tap-endpoint accuracy, sampling density, the fractional-coordinate preview-to-bitmap mapping) is unconfirmed pending an actual run on DuoCo hardware.

## Deferred / known-not-urgent

- DSP/animation timing constants (`fluxHistorySize`, `noiseHistory`, `bassHistory`, `energyWindowSize`, `shortWindowSize`, per-frame offsets) are sample-count-based rather than time-based, causing behavior differences between `AudioRecord` (~43Hz) and the Visualizer API (~20Hz). Audited, fix scoped, not urgent. As of Phase 4, these constants live in `core/audio/AudioDspProcessor.kt` (branched by `AudioBackend`) rather than inline in the ViewModel, but the mismatch itself is unchanged.
- Firework splash experimental animation (two-device) — tuning ongoing, lives in Settings > Experimental.
- Smart Scene: chip dropdowns for in-place editing, animation timing consistency, chip label formatting, manual per-step editing — all deferred until scene editors are reconciled.
- Audio visualizer: `paletteCycling` on Beat Only pending hardware test decision; fire-effect flicker/brightness modulation deferred.

## Testing

- Full suite: **271 tests**, run via `./gradlew :app:testDebugUnitTest`.
- `CoreControlsReducerTest.kt` — Core Controls + Connectivity + Device management reducer (`CoreControlsReducer.kt`). 43 tests, all 21 intents.
- `AmbianceSettingsReducerTest.kt` — Ambiance & Camera Sync reducer (`AmbianceSettingsReducer.kt`). 13 tests, all 8 intents.
- `AudioSettingsReducerTest.kt` — Audio Settings reducer (`AudioSettingsReducer.kt`). 39 tests, all 23 intents.
- `CalibrationFlowReducerTest.kt` — Calibration & Tuning reducer (`CalibrationFlowReducer.kt`). 22 tests, all 13 intents.
- `AudioCaptureSourceTest.kt` (`hardware/audio/`), `AudioDspProcessorTest.kt` (`core/audio/`), `BeatDetectorTest.kt` (`core/audio/`) — audio capture/DSP pipeline.
- `ConnectionManagerTest.kt` (`domain/`), `CalibrationMatrixSolverTest.kt`/`CalibrationMatrixOpsTest.kt` (`core/calibration/`), `DuoCoProtocolTest.kt`/`CoreExtractionTest.kt` (`core/protocol/`), `FftTest.kt` (`hardware/audio/`), `PacingAutoTuneEngineTest.kt` (`core/pacing/`), `DemoAudioDspSimulatorTest.kt` (`core/audio/`), `BleTransportTest.kt` (`hardware/ble/`).
- `ModeCaptureModelsTest.kt` (`core/modecapture/`), `CameraCalibrationLogicTest.kt` (`ui/components/`) — Mode Capture experimental feature (see below). Both Robolectric-based (`org.json`/`Bitmap` throw under plain JUnit).
- Reducer logic and its tests are opened as **separate PRs** (reducer first, tests based on the reducer branch) — follow this precedent for future reducer slices.
- **Standing caveat:** timing/hardware-dependent changes (real BLE, real mic/Visualizer input, elapsed-wall-clock behavior) go untested by design — Robolectric can't model them. Verify those on-device instead of expecting unit coverage. See HISTORY.md for the full per-phase test-count history.

## Local tooling notes

- **`adb` is installed but not on PATH in this Claude Code environment (Windows/Git Bash).** Binary lives at `/c/Users/attgm/AppData/Local/Android/Sdk/platform-tools/adb.exe` (standard Android Studio SDK location under `%LOCALAPPDATA%`) — `where adb`/`which adb` both fail, so it has to be invoked by full path.
  - **`adb devices`** works fine by full path with no special handling.
  - **Pulling files (`adb pull <remote> <local>`) needs `MSYS_NO_PATHCONV=1`** set first — otherwise Git Bash rewrites the leading `/` in the *remote* device path (e.g. `/sdcard/Android/data/...`) into a bogus Windows path (`C:/Program Files/Git/sdcard/...`) before adb ever sees it, and the pull fails with "No such file or directory" for a path that's real on the device.
  - **Installing an APK (`adb install`) needs the OPPOSITE — do NOT set `MSYS_NO_PATHCONV`** (or explicitly `unset` it first) — `adb.exe` is a native Windows binary and needs the *local* APK path (e.g. `app/build/outputs/apk/debug/app-debug.apk`) converted to a real Windows path (`C:\Users\...`), which is exactly the conversion `MSYS_NO_PATHCONV` disables. With it set, `install` fails the same "No such file or directory" way, just for the opposite reason (local path unconverted instead of remote path over-converted).
  - Net effect: toggle `MSYS_NO_PATHCONV` per-command depending on whether the path that matters is on the device (`pull`, `shell ls`, etc. → set it) or on this machine (`install`, `push`'s local source → unset/don't set it). `push` needs both handled correctly since it has one path of each kind.
  - The app's saved diagnostics live at `/sdcard/Android/data/<applicationId>/files/diagnostics/diagnostics_log_<epochMs>.txt` — `applicationId` is `com.aistudio.expressivergb.xkzqp` (`app/build.gradle.kts`), **not** `com.example` (the Kotlin package name) — `adb shell run-as com.example` or browsing under that name will fail with "unknown package." List/pull directly from the `Android/data/<applicationId>/files/diagnostics/` path instead of trying `run-as`.
  - Log line timestamps in diagnostics exports are `SystemClock.elapsedRealtime()` (ms since boot, monotonic — see `DiagnosticLogger.kt`'s `logInternal`), **not** wall-clock. Several tags (e.g. `FlashSchedule`'s `atMs`/`targetMs`) separately embed `System.currentTimeMillis()` wall-clock values in their own message text. These two clocks are NOT the same number space — cross-referencing a bracket timestamp against an embedded `atMs`-style value requires first computing the constant per-session offset (`bracket_ts - embedded_wallclock_ts` from any line that has both) and applying it, not comparing the raw numbers directly. Confirmed stable to ~2ms across a 30-60s capture (no deep-sleep drift during active foreground use), so one offset sample per session is enough.

## Git/GitHub notes

- `main` is the sole active branch — clean, in sync with `origin/main`. `refactor-checkpoint` was deleted (local + `origin`) after being confirmed a strict ancestor of `main` (every commit on it was already reachable from `main`); see HISTORY.md for the deletion writeup and the `git merge-base --is-ancestor` check used to verify it.
- The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`, `gradle/gradle-daemon-jvm.properties`) is committed to the repo.
- If using AI Studio's own GitHub panel elsewhere in this workflow: it silently overwrites custom `LICENSE`/`README` with its own template on repo (re-)init — always check/restore both files after any AI-Studio-driven git re-init. This does not apply to Claude Code's own git operations, just noting it in case AI Studio touches the same repo.
- `.gitignore` covers `debug.keystore` and `debug.keystore.base64` — keep both covered if keystore handling changes.
- **Standing conventions:** Claude Code (this environment) owns routine git/GitHub mechanics — merging approved work, keeping this file current — without checking in for each individual step. Merge commits (never squash) are the convention; branches are kept, not deleted, after merge except where explicitly approved otherwise. Still flag before acting on anything destructive, anything where a merge isn't a clean fast-forward/no-conflict case, or anything that bundles an actual app-behavior change rather than pure restructuring.
- Full PR-by-PR merge history, branch provenance, and past conflict-resolution notes are in HISTORY.md.
