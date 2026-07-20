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
4. **Phase 3b — COMPLETE.** State migrated to `RgbUiState` + intent reducer pattern, all four reducers written/tested/merged, and — as of the `wire-phase3b-reducers` branch — all four are wired live into `RgbControllerViewModel` via `dispatch(RgbIntent)`. See "RgbUiState shape" and "Current reducer status" below.
5. **Phase 4 — NOT STARTED, up next.** Hardware (BLE/audio) isolated behind interfaces with fake implementations for testing. `startAudioEngine` (~408 lines) and `audioThread` (~455 lines) are flagged as the hardest part — they mix DSP math, hardware I/O, and direct UI state mutation. These two functions were deliberately left untouched by the Phase 3b wiring (only their entry/exit points route through the reducer's side effects); they're the natural starting point for Phase 4.

### RgbUiState shape (locked)

```
RgbUiState:
  connectivity: ConnectivityState
  coreControl: CoreControlState       // includes errorMessage, protocolBytes
  audioSettings: AudioSettingsState   // 27 fields, includes musicMode
  ambianceSettings: AmbianceSettingsState  // ambiancePreset non-null, "Balanced" default
  calibrationFlow: CalibrationFlowState
```

- `RgbIntent` lives inside `RgbUiState.kt` — there is **no separate `RgbIntent.kt` file**.
- `colorCalibrations`, `cctCalibrations`, `byteOverrides` stay as **separate StateFlows**, NOT part of `RgbUiState`.
- `TelemetryState` (`musicAmplitudes`, `visualizerHue`, `logMessages`, `deviceAchievedFps`) is separate from `RgbUiState` due to high-frequency updates.

### Current reducer status

All four reducers below are **wired live into `RgbControllerViewModel`** (branch `wire-phase3b-reducers`, based on `phase3b-complete`; not yet merged into `refactor-checkpoint` — check `git log` before assuming merge state). `RgbControllerViewModel._uiState` is a real `MutableStateFlow<RgbUiState>` (migrated in Stage 2/3, commits `24b60f3`/`8d39aac`), not the old flat `ControllerUiState`.

**Wiring shape:** a single `private fun dispatch(intent: RgbIntent)` in `RgbControllerViewModel.kt` runs every intent through all four reducers in sequence — `coreControlsReducer` → `ambianceSettingsReducer` → `audioSettingsReducer` → `calibrationFlowReducer` — threading the state through each call. This is safe because each reducer's `when` has an `else -> state to emptyList()` fallthrough, so an intent outside a reducer's scope is a no-op there; only the one reducer that actually owns the intent produces a state change or effects. Each reducer's side-effect list is executed immediately after by one dedicated executor function (`executeCoreSideEffect`, `executeAmbianceSideEffect`, `executeAudioSideEffect`, `executeCalibrationSideEffect`), which is where the real hardware/IO work (GATT calls, prefs, Room, coroutine loops) actually happens — the reducers themselves stay pure. Every public ViewModel method that used to mutate `_uiState` directly (e.g. `setPower`, `startScanning`, `toggleActiveControl`) is now a one-line `dispatch(RgbIntent.X(...))` call; hardware/IO logic that isn't a pure state transition was split into private `*Hardware()` helpers invoked from the executors (e.g. `connectDeviceHardware`, `startBleScanHardware`, `checkActiveAudioRouteHardware`).

`handleConnectionStateChange` (the real GATT callback) is **not** fully routed through the reducer — only its two state-mutation points now call `dispatch(RgbIntent.InternalConnectionStateChanged(...))`. The exponential-backoff retry logic, GATT connection-priority/MTU requests, and `discoverServices()` calls stay as direct ViewModel code, since the reducer's `InternalConnectionStateChanged` case doesn't (and shouldn't) model those — they're genuine hardware operations, not state transitions.

**The `StartMetronome` contract is now enforced, not just documented.** The metronome coroutine (`executeCalibrationSideEffect`'s `StartMetronome` case) calls `dispatch(RgbIntent.SendCalibrationFlash)` on every tick — the exact same call the public `sendCalibrationFlash()` makes — so `SendFlashPulse` is structurally producible from exactly one code path.

`CoreControlsReducer.kt` (`app/src/main/java/com/example/presentation/CoreControlsReducer.kt`) handles the Connectivity & Scanning / Device management / Core Controls intent groups. It has been **fixed, tested, merged, and wired**. Test coverage: 43 JUnit tests in `CoreControlsReducerTest.kt`, covering all 21 intents plus regression tests for both fixes described below.

**How this works:**

1. `SetPower`/`SetColor`/`SetBrightness`/`SetMode`/`SetWarmth` update `deviceStatesMap` via `applyControlledDeviceUpdates()`, which mirrors `RgbControllerViewModel.updateControlledDevicesInMap()`: it creates missing entries for every target address rather than only touching keys that already exist, so newly-connected auto-saved devices correctly get a `deviceStatesMap` entry from these five intents.
2. `SetDemoMode(true)` and `DisconnectAll` key off `activeConnections` (a live-only registry), mirroring the source ViewModel, rather than `deviceConnectionStates` (a map that never shrinks) — so the "already disconnected" fast path continues to fire correctly after the first device connects, avoiding repeated disconnect-effect emission for stale/already-disconnected addresses.

If touching this code further, reference the real `updateControlledDevicesInMap()` and `activeConnections` implementations in `RgbControllerViewModel.kt` directly — don't infer intended behavior from the reducer alone.

A pre-existing bug in `setWarmth` (writes `"CCT"` to a pref key, then immediately overwrites it with `"Colour"`) has been **intentionally preserved** in the reducer as behavior parity — this is not a bug to fix during the refactor.

`AmbianceSettingsReducer.kt` (`app/src/main/java/com/example/presentation/AmbianceSettingsReducer.kt`) handles the Ambiance & Camera Sync intent group. **Merged and wired.** Test coverage: 13 JUnit tests in `AmbianceSettingsReducerTest.kt`, covering all 8 intents. Source-diffed against the real `setAmbiance*()` / `applyAmbiancePreset()` handlers in `RgbControllerViewModel.kt` (lines 2775-2845) — no discrepancies found, unlike `CoreControlsReducer`.

**How this works:** 7 single-value setters (`SetAmbianceResponseSpeed`, `SetAmbianceSmoothnessMs`, `SetAmbianceSaturationBoost`, `SetAmbianceBrightnessCompensation`, `SetAmbianceUpdateRateCapFps`, `SetAmbianceSceneCutSensitivity`, `SetAmbianceNoiseDeadband`) each update one field and force `ambiancePreset` to `"Custom"`. `ApplyAmbiancePreset` is the exception: it sets `ambiancePreset` to the applied preset ID (not `"Custom"`), does **not** touch `ambianceUpdateRateCapFps`, and additionally cancels any running scene chain — modeled as `AmbianceSideEffect.CancelSceneChain`, mirroring `CoreSideEffect.CancelSceneChain`.

Deliberately **out of scope** for this reducer — verified, not overlooked:
- Ambiance capture start/stop has no ViewModel intent; `MainActivity.kt` drives `AmbianceCaptureService` directly via an `ActivityResultLauncher` (needs the Activity for the `MediaProjection` permission result).
- `WriteAmbianceColor` is a pure BLE passthrough (no `RgbUiState` field touched, fires per captured frame) — belongs with Commands/Protocol, not settings.
- `CoreSideEffect.StopAmbiance` already exists and is already wired into `CoreControlsReducer`, fired from `SetPower`/`SetColor`/etc. — not new work.

`AudioSettingsReducer.kt` (`app/src/main/java/com/example/presentation/AudioSettingsReducer.kt`) handles the Audio Settings intent group. **Merged and wired.** Ported against the real `setVisualizerPreset()`, `startMusicSync()`/`stopMusicSync()`, and the sensitivity/threshold/gain setters in `RgbControllerViewModel.kt` (lines 2677-2962, 3901-3975, 4009-4013). Source-diffed line-by-line against all 23 intent handlers, including the 8-preset × 16-field value table (byte-identical) — no discrepancies found *at the time*, but see the wiring-stage fix below. Test coverage: 39 JUnit tests in `AudioSettingsReducerTest.kt`, covering all 23 intents.

**How this works:**

1. `SetVisualizerPreset` writes the 16-field-per-preset value table (`visualizerConfigFor()`) verbatim, plus `activeFeatureName` — recomputed from `getVisualizerPresetName()`/current mode, mirroring source, even though the recompute is a no-op unless `musicMode` is `phone_mic`/`on_device`.
2. `StartMusicSync`/`StopMusicSync` model the real hardware/scene-runner work (`stopMusicSyncInternal`, `startAudioEngine`, per-address `saveDeviceState`/scene-runner release, `deviceAutomationMode` restore-or-clear) as `AudioSideEffect` cases for the ViewModel to execute — same pattern as `CoreSideEffect`. `startMusicSync` branches on `mode`: phone-mic/on-device modes route to `AudioSideEffect.StartAudioEngine`; the eight onboard BLE modes (`energetic_1/2`, `rhythm_1/2`, `spectrum_1/2`, `rolling_1/2`) route to three direct `BroadcastCommand` effects (toggle, style, sensitivity) instead.
3. Preserved faithfully, not "fixed": `setBeatThresholdMultiplier` and the smoothing/gamma/flash/brightness/speed/idle-delay group reset `visualizerPreset` to `"Custom"`; `setBeatCooldownMs`, the gain setters, `setAutoGainEnabled`, `setPaletteCyclingEnabled`, and `setLogarithmicScalingEnabled` do **not** — this inconsistency exists in the source and is reproduced exactly. `setBluetoothDelayMs` derives `totalVisualDelayMs = bluetoothDelayMs + BeatDetector().lookaheadMs` inline; since `BeatDetector` is a private class in `RgbControllerViewModel.kt` always constructed with no args, the reducer replicates it as a local constant (`BEAT_DETECTOR_DEFAULT_LOOKAHEAD_MS = 180L`) rather than referencing the class directly.
4. **Bug found and fixed during wiring (not present in the original merge):** `ResetAudioPipelineSettings` was missing the `mid_flux_weight` prefs write that the real `resetAudioPipelineSettings()` (line 2938) performs — the in-memory field was reset but never persisted, so it would silently revert on the next app restart. The existing test had actually documented the *missing* write as an intentional "preserved quirk," which turned out to be wrong against real source. Both the reducer and `AudioSettingsReducerTest.kt` (`resetAudioPipelineSettings_persistsMidFluxWeight`) were corrected. Lesson: a prior source-diff pass missed this — don't assume "no discrepancies found" from an earlier session is still true without re-checking against the real ViewModel when you touch this code again.

`CalibrationFlowReducer.kt` (`app/src/main/java/com/example/presentation/CalibrationFlowReducer.kt`) handles the Calibration & Tuning intent group. **Merged and wired**, including the `StartMetronome` contract (see above — no longer doc-only). Ported against the real `startCalibrationMode()`/`stopCalibrationMode()`/`saveCalibrationAndExit()`/`sendCalibrationFlash()`/`resetCalibrationSettings()`/`toggleTestPattern()`/`setDevicePacing()`/`saveCctCorrectionProfile()`/`deleteCctCorrectionProfile()`/`saveColorCalibration()`/`deleteColorCalibration()` handlers in `RgbControllerViewModel.kt` (lines 1104-1149, 1775-1783, 1880-1965, 2964-2985, 3645-3657). Source-diffed line-by-line, branch-by-branch against all 13 intent handlers — one ordering mismatch found and fixed during verification (see below). Test coverage: 22 JUnit tests in `CalibrationFlowReducerTest.kt`, covering all 13 intents.

**How this works:**

1. `CalibrationFlowState` only has 3 fields (`showCalibrationPrompt`, `isCalibrationModeActive`, `calibrationDelayOffsetMs`), but the Calibration & Tuning intent group is broader than that shape — several intents write to `ConnectivityState` (`isTestPatternRunning`, `devicePacingMs`) and `AudioSettingsState` (`bluetoothDelayMs`, `totalVisualDelayMs`) instead of or in addition to `CalibrationFlowState`. `calibrationFlowReducer` touches `connectivity`, `audioSettings`, and `calibrationFlow` directly in the same function — same rationale as `AudioSettingsReducer` reaching into `deviceStatesMap`: the reducer is scoped by intent group, not by which state slice each intent happens to touch.
2. Almost every intent in this group is IO/hardware-bound (prefs, Room DB, live `DeviceWriteManager` mutation, or a long-running coroutine loop), so it leans on `CalibrationSideEffect` more heavily than the other reducers. `SaveColorCalibration`/`DeleteColorCalibration` are Room-only (no `RgbUiState` field touched, same pattern as `WriteAmbianceColor`); `SaveCctCorrectionProfile`/`DeleteCctCorrectionProfile` write prefs and reload the separate `cctCalibrations` StateFlow; `StartCalibrationMode`/`StopCalibrationMode`/`ToggleTestPattern` start or cancel long-running coroutines (metronome loop, test-pattern color cycle) that a pure reducer can't own directly.
3. Two extra parameters beyond `state`/`intent`, because a pure reducer can't do the IO or see live hardware state itself: `savedCalibrationDelayMs: Int` (pre-fetched pref read, seeds `calibrationDelayOffsetMs` when `StartCalibrationMode` fires) and `connectedManagerAddresses: Set<String>` (reproduces the `deviceWriteManagers[address] != null` guard in the source `setDevicePacing()` — when absent, the *entire* block including the state update is skipped, not just the hardware write).
4. Preserved faithfully, not "fixed": `StartCalibrationMode` unconditionally calls `stopCalibrationMode()` first, so restarting while already active silently resets and re-logs "stopped" then "started"; `SaveCalibrationAndExit` writes the same delay to two different pref keys via two different mechanisms; `ResetCalibrationSettings` writes `bluetooth_delay_ms` to 0 as an explicit separate pref call (none of its three `clear*Prefs()` calls cover that key) and resets pacing via two independent paths (live `DeviceWriteManager` mutation for connected devices, `devicePacingMs.mapValues { 100 }` in state for all recorded addresses) that are kept in sync manually, not derived from one another — both preserved, not unified.
5. The one mismatch caught during source-diff verification: `StartCalibrationMode`'s effect order initially had the metronome-start effect before the "Calibration Mode started..." log; source calls `addLog()` before launching the coroutine. Fixed to log-then-start, with a regression test (`startCalibrationMode_emitsStopThenStartEffectsInSourceOrder`) locking the order in.

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
- `AudioSettingsReducerTest.kt` covers the Audio Settings reducer (`AudioSettingsReducer.kt`). 39 tests, all 23 intents, including regression tests for the selective `"Custom"`-preset-reset quirk and the `totalVisualDelayMs` derivation.
- `CalibrationFlowReducerTest.kt` covers the Calibration & Tuning reducer (`CalibrationFlowReducer.kt`). 23 tests, all 13 intents, including regression tests for the unconditional-restart quirk, the two-pref-key write in `SaveCalibrationAndExit`, the standalone `bluetooth_delay_ms` reset write, the dual pacing-reset paths, the no-live-manager no-op in `SetDevicePacing`, and the SendFlashPulse single-producer contract (see the `StartMetronome` contract in `CalibrationFlowReducer.kt`).
- Run via `./gradlew :app:testDebugUnitTest`. Reducer logic and its tests are opened as **separate PRs** (reducer first, tests based on the reducer branch) — follow this precedent for future reducer slices.
- Full suite is 140 tests as of the reducer-wiring work. These tests exercise the reducers in isolation only — they do **not** cover `RgbControllerViewModel`'s `dispatch()`/`execute*SideEffect()` wiring (effect ordering, hardware/coroutine interaction, demo-vs-real-hardware branching). That wiring was verified by build + source-diff against the pre-wiring ViewModel methods, not by new automated tests. A manual on-device smoke test of each control group is still outstanding — do that before trusting the wiring in production, especially around BLE connect/disconnect and the calibration metronome.

## Git/GitHub notes

- Working branch: `wire-phase3b-reducers` (based on `phase3b-complete`; carries the full Stage 4-6 reducer-wiring work described above). Neither `phase3b-complete` nor `wire-phase3b-reducers` is merged into `refactor-checkpoint` yet — check `git log --oneline --graph` / `git merge-base --is-ancestor` before assuming merge state rather than trusting this file, since branch pointers here can go stale. `refactor-checkpoint` (tracking `origin/refactor-checkpoint`) predates the RgbUiState migration entirely; don't branch new reducer work off it without checking it's still the right base.
- The Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`, `gradle/gradle-daemon-jvm.properties`) is committed to the repo.
- If using AI Studio's own GitHub panel elsewhere in this workflow: it silently overwrites custom `LICENSE`/`README` with its own template on repo (re-)init — always check/restore both files after any AI-Studio-driven git re-init. This does not apply to Claude Code's own git operations, just noting it in case AI Studio touches the same repo.
- `.gitignore` covers `debug.keystore` and `debug.keystore.base64` — keep both covered if keystore handling changes.
