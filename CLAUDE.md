# Expressive RGB

Android Bluetooth LE RGB LED controller app targeting DuoCo hardware.

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

Two "god files" are mid-refactor into this structure:
- `RgbControllerViewModel` — originally 5,440 lines, 12 mixed responsibilities (BLE GATT, DSP/beat detection, audio capture, DB, prefs, scene orchestration)
- `MainActivity` — originally 2,476 lines (nav/permissions/dialogs/UI)

### Refactor phases
1. **Phase 1 — COMPLETE.** Extracted pure/stateless logic into `core/`: `ColorConverter`, `CalibrationMatrixSolver`, `DuoCoProtocol`, `ProceduralSceneParams`.
2. **Phase 2 — COMPLETE.** Removed Hilt DI entirely (manual DI: `AppContainer`, `ViewModelFactory`). Fixed `AppPreferencesRepository` domain/UI layering violation.
3. **Phase 3a — COMPLETE.** `DuoCoProtocol.overrides` encapsulated (private + accessors). Connection state centralized via `ConnectionManager` singleton (kills the old static-field hazard where `manuallyDisconnected` was written by the ViewModel and read directly by `MainActivity`, bypassing `StateFlow`).
4. **Phase 3b — IN PROGRESS.** Consolidating state into `RgbUiState` + intent reducer pattern. See "RgbUiState shape" and "Current reducer status" below.
5. **Phase 4 — NOT STARTED.** Hardware (BLE/audio) isolated behind interfaces with fake implementations for testing. `startAudioEngine` (~408 lines) and `audioThread` (~455 lines) are flagged as the hardest part — they mix DSP math, hardware I/O, and direct UI state mutation.

### RgbUiState shape (locked)

```
RgbUiState:
  connectivity: ConnectivityState
  coreControl: CoreControlState       // includes errorMessage, protocolBytes
  audioSettings: AudioSettingsState   // includes musicMode
  ambianceSettings: AmbianceSettingsState  // ambiancePreset non-null, "Balanced" default
  calibrationFlow: CalibrationFlowState
```

- `RgbIntent` lives inside `RgbUiState.kt` — there is **no separate `RgbIntent.kt` file**.
- `colorCalibrations`, `cctCalibrations`, `byteOverrides` stay as **separate StateFlows**, NOT part of `RgbUiState`.
- `TelemetryState` (`musicAmplitudes`, `visualizerHue`, `logMessages`, `deviceAchievedFps`) is separate from `RgbUiState` due to high-frequency updates.

### Current reducer status

`CoreControlsReducer.kt` (`app/src/main/java/com/example/presentation/CoreControlsReducer.kt`) handles the Connectivity & Scanning / Device management / Core Controls intent groups. It has been **fixed, tested, and merged into `refactor-checkpoint`** at commit `8aef9dd` (PR not yet opened against main). Test coverage: 43 JUnit tests in `CoreControlsReducerTest.kt`, covering all 21 intents plus regression tests for both fixes described below.

**How this works:**

1. `SetPower`/`SetColor`/`SetBrightness`/`SetMode`/`SetWarmth` update `deviceStatesMap` via `applyControlledDeviceUpdates()`, which mirrors `RgbControllerViewModel.updateControlledDevicesInMap()`: it creates missing entries for every target address rather than only touching keys that already exist, so newly-connected auto-saved devices correctly get a `deviceStatesMap` entry from these five intents.
2. `SetDemoMode(true)` and `DisconnectAll` key off `activeConnections` (a live-only registry), mirroring the source ViewModel, rather than `deviceConnectionStates` (a map that never shrinks) — so the "already disconnected" fast path continues to fire correctly after the first device connects, avoiding repeated disconnect-effect emission for stale/already-disconnected addresses.

If touching this code further, reference the real `updateControlledDevicesInMap()` and `activeConnections` implementations in `RgbControllerViewModel.kt` directly — don't infer intended behavior from the reducer alone.

A pre-existing bug in `setWarmth` (writes `"CCT"` to a pref key, then immediately overwrites it with `"Colour"`) has been **intentionally preserved** in the reducer as behavior parity — this is not a bug to fix during the refactor.

`AmbianceSettingsReducer.kt` (`app/src/main/java/com/example/presentation/AmbianceSettingsReducer.kt`) handles the Ambiance & Camera Sync intent group. **Merged into `refactor-checkpoint`.** Test coverage: 13 JUnit tests in `AmbianceSettingsReducerTest.kt`, covering all 8 intents. Source-diffed against the real `setAmbiance*()` / `applyAmbiancePreset()` handlers in `RgbControllerViewModel.kt` (lines 2775-2845) — no discrepancies found, unlike `CoreControlsReducer`.

**How this works:** 7 single-value setters (`SetAmbianceResponseSpeed`, `SetAmbianceSmoothnessMs`, `SetAmbianceSaturationBoost`, `SetAmbianceBrightnessCompensation`, `SetAmbianceUpdateRateCapFps`, `SetAmbianceSceneCutSensitivity`, `SetAmbianceNoiseDeadband`) each update one field and force `ambiancePreset` to `"Custom"`. `ApplyAmbiancePreset` is the exception: it sets `ambiancePreset` to the applied preset ID (not `"Custom"`), does **not** touch `ambianceUpdateRateCapFps`, and additionally cancels any running scene chain — modeled as `AmbianceSideEffect.CancelSceneChain`, mirroring `CoreSideEffect.CancelSceneChain`.

Deliberately **out of scope** for this reducer — verified, not overlooked:
- Ambiance capture start/stop has no ViewModel intent; `MainActivity.kt` drives `AmbianceCaptureService` directly via an `ActivityResultLauncher` (needs the Activity for the `MediaProjection` permission result).
- `WriteAmbianceColor` is a pure BLE passthrough (no `RgbUiState` field touched, fires per captured frame) — belongs with Commands/Protocol, not settings.
- `CoreSideEffect.StopAmbiance` already exists and is already wired into `CoreControlsReducer`, fired from `SetPower`/`SetColor`/etc. — not new work.

## Hardware constraint (permanent, confirmed)

Every pixel-count change on a DuoCo device causes a visible firmware-level LED flash. This is unavoidable at the firmware level — design animations around it (e.g. deliberate stepped changes) rather than trying to eliminate it.

## Known open bug

LEDs freezing intermittently — unresolved. Experimental start/stop diagnostic logging (exportable to file) exists to help diagnose; if you touch BLE write paths or the watchdog, be aware this issue is still open.

## Ambiance mode

Uses screen capture (`MediaProjection`), **not** the physical camera. The camera exists in the codebase only as an unused calibration feature — don't assume camera code is load-bearing for ambiance.

## Deferred / known-not-urgent

- DSP/animation timing constants (`fluxHistorySize`, `noiseHistory`, `bassHistory`, `energyWindowSize`, `shortWindowSize`, per-frame offsets) are sample-count-based rather than time-based, causing behavior differences between `AudioRecord` (~43Hz) and the Visualizer API (~20Hz). Audited, fix scoped, not urgent.
- Firework splash experimental animation (two-device) — tuning ongoing, lives in Settings > Experimental.
- Smart Scene: chip dropdowns for in-place editing, animation timing consistency, chip label formatting, manual per-step editing — all deferred until scene editors are reconciled.
- Audio visualizer: `paletteCycling` on Beat Only pending hardware test decision; fire-effect flicker/brightness modulation deferred.

## Testing

- `CoreControlsReducerTest.kt` covers the Core Controls + Connectivity + Device management reducer (`CoreControlsReducer.kt`). 43 tests, all 21 intents.
- `AmbianceSettingsReducerTest.kt` covers the Ambiance & Camera Sync reducer (`AmbianceSettingsReducer.kt`). 13 tests, all 8 intents.
- Run either via `./gradlew :app:testDebugUnitTest`. Reducer logic and its tests are opened as **separate PRs** (reducer first, tests based on the reducer branch) — follow this precedent for future reducer slices (audio settings, calibration flow).

## Git/GitHub notes

- Working branch: `refactor-checkpoint` (tracking `origin/refactor-checkpoint`).
- The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`, `gradle/gradle-daemon-jvm.properties`) is committed to the repo.
- If using AI Studio's own GitHub panel elsewhere in this workflow: it silently overwrites custom `LICENSE`/`README` with its own template on repo (re-)init — always check/restore both files after any AI-Studio-driven git re-init. This does not apply to Claude Code's own git operations, just noting it in case AI Studio touches the same repo.
- `.gitignore` covers `debug.keystore` and `debug.keystore.base64` — keep both covered if keystore handling changes.
