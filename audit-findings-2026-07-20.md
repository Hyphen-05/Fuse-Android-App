# Expressive RGB — Post-Phase-4 Cleanup Audit

Date: 2026-07-20. Scope: code-first audit of what's left beyond the completed Phase 1–4 refactor. All findings below were read from the actual source (and several re-verified independently after the research pass) — no claim here is relayed from CLAUDE.md or an AI self-report without a file:line check.

## 0. Git/GitHub state (check first, as requested)

- Working tree clean, `phase4-hardware-isolation` matches `origin/phase4-hardware-isolation`.
- **PR #12 (Phase 4) is already merged** into `wire-phase3b-reducers` (merged 2026-07-20T15:11:58Z, merge commit `6c06151`). CLAUDE.md's "PR open" language is stale — should be updated.
- `refactor-checkpoint` has PR #11 (`wire-phase3b-reducers`) merged in, but is **one PR behind**: it does not yet contain Phase 4 (PR #12).
- `main` is **far behind** everything else — it only has through PR #3 (`add-reducer-tests`); none of the RgbUiState migration, reducer wiring, or Phase 4 work is on it.
- No open PRs. Four long-lived branches (`main`, `refactor-checkpoint`, `wire-phase3b-reducers`, `phase4-hardware-isolation`) are diverging further with each phase — worth a housekeeping pass (fast-forward `refactor-checkpoint`, decide whether `main` is meant to ever catch up, delete branches merged into all downstream targets) before starting a new phase.

---

## 1. RgbControllerViewModel.kt — 3,925 lines (confirmed)

Down from 5,440 → 4,778 → 3,925. Phase 4 removed direct `AudioRecord`/`Visualizer` API calls (genuine, verified win — `startAudioEngine` really is now a ~50-line orchestrator at lines 3501–3551, confirmed by direct read) but did not touch the other ~12 responsibilities still living here directly:

1. **`BeatDetector`** (lines 153–659, ~500 lines) — full autocorrelation/phase-flux beat-detection algorithm. Widened to `internal` in Phase 4 so `AudioDspProcessor` could reuse it, but the class body itself is still in the ViewModel file, not `core/audio/`.
2. **BLE GATT lifecycle** — `DeviceWriteManager` (1123–1304), `gattCallback`/`handleConnectionStateChange` incl. backoff retry (2354–2449), service/characteristic discovery (2451–2539), connect/disconnect hardware (2249–2348). CLAUDE.md documents this as deliberately out of scope for Phase 4 — confirmed still true, still raw `BluetoothGatt` code inline.
3. **BLE scanning** — `scanCallback`/`handleScanResult`/scan start-stop (2087–2216).
4. **Command transmission/queueing** — `queueCommand`, `sendCommandToDeviceDirect`, `broadcastCommand(Direct)` (2550–2666).
5. **Scene orchestration** — `applyScene`, `applyDeviceState`, `updateDeviceStateInMap`, scene chaining (2844–3323) — substantial business logic, fully inline, not in `domain/`.
6. **Calibration matrix math** — `getCalibrationMatrices`/`interpolateMatrices` (1028–1105) do 3×3 interpolation inline; only `fit3x3Matrix` (3357–3362) delegates to `core/calibration/CalibrationMatrixSolver`. Inconsistent extraction — the solver was pulled out in Phase 1 but its caller-side matrix math wasn't.
7. **Demo audio simulation** — `runAudioSimulationEngine` (3553–3856, ~300 lines) — a second, hand-duplicated DSP pipeline for demo mode that shares no code with `core/audio/AudioDspProcessor`.
8. **Bluetooth audio-route detection** — direct `AudioManager`/`BroadcastReceiver` usage (1385–1410, 1981–2055).
9. **`MetronomePlayer`** (3883–3925) — raw `AudioTrack` synthesis for calibration clicks, not moved into `hardware/audio/` despite that being exactly Phase 4's focus area.
10. **MVI dispatch/effect bridge** — `dispatch()` + four `execute*SideEffect()` (759–1026, ~270 lines) — this one is *correctly* here per the documented architecture, not a finding.
11. **Hardcoded seed data** — ~215 lines (1546–1759) of default `CustomMode` definitions inline in `init`, not in `data/`.
12. **`CctCorrectionProfile`** — data class + hand-rolled `JSONObject` (de)serialization (84–132) at file scope, domain-shaped but not in `domain/`.

**Dead code, verified directly:**
- `private fun delayMs(ms: Long)` at **line 2662** — zero call sites anywhere in the file (confirmed by grep: every other `delayMs` hit is an unrelated local `val`). Launches a no-op coroutine. Safe to delete.
- No `ControllerUiState` stragglers (only an explanatory comment at line 145 — clean migration).
- No TODO/FIXME markers in the file.

## 2. MainActivity.kt — 2,489 lines (confirmed; grew from the documented 2,476, not shrank — no refactor phase targeted this file)

- **`MainScreen()` is one ~1,880-line composable** (lines 180–2061, >75% of the file): permission flow, tab nav, Home tab, the entire Devices tab (~450 lines, 1376–1786), calibration dialog, scene CRUD dialogs, connection overlay — all inline. Notably **inconsistent** with the rest of the codebase: Modes/Music/Ambiance/Settings tabs *are* extracted to `ui/components/*`, but Home and Devices tabs never were.
- Permission handling (216–264), FPS-estimation heuristic with hardcoded per-feature constants (285–308), and `getSceneDescription()` (529–541, pure string formatting) are all local functions/state inside `MainScreen()` rather than extracted.
- Reflection-based `HapticFeedbackType` lookup via `getMethod`/`invoke` is **duplicated verbatim** in two places (2079–2093 and 2381–2387) — should be a single shared utility.
- **`validateHex()`** at line 2066 — confirmed by repo-wide grep to have **zero call sites anywhere in the codebase**, not just this file. Dead code, safe to delete (or wire up if it was meant to validate hex input somewhere).
- Odd double-closing-brace structure at lines 2060–2064 (compiles fine, but reads like leftover AI-edit residue) — cosmetic, low priority.
- No `ControllerUiState` stragglers, no TODOs.

## 3. Other god-file / hardware-coupling patterns found

Ranked by how directly they touch hardware/persistence outside an interface boundary:

1. **`ui/components/PacingAutoTuneDialog.kt:295–370+`** — a Compose dialog embeds a full BLE pacing auto-tune algorithm (binary-search-style `runBurst`/`runStress`, FPS-threshold heuristics) inside a `LaunchedEffect`, driving `viewModel.queueCommand`/`setDevicePacing`/`broadcastCommand` directly. This is exactly the shape of thing `AudioCaptureSource` was extracted to fix — candidate for a `core/`-level `PacingAutoTuner` class the dialog only observes.
2. **`getActiveInstance()` static singleton** — confirmed live: `RgbControllerViewModel.kt:676` exposes a static instance getter, used directly (bypassing `dispatch(RgbIntent)` entirely) by `ambiance/AmbianceCaptureService.kt:76,164` and `ambiance/AmbianceOutputInterpolator.kt:111`, plus 5 internal uses at `RgbControllerViewModel.kt:2356–2376`. This is the same static-singleton hazard class CLAUDE.md says Phase 3a fixed for `manuallyDisconnected`/`ConnectionManager` — but the escape hatch itself is still open for the ambiance capture path. CLAUDE.md correctly scoped ambiance capture *lifecycle* as out of Phase 4, but this specific coupling mechanism is a separate, addressable issue.
3. **`ui/components/AmbianceScreen.kt`** mixes three concerns: UI composition, direct unabstracted `SharedPreferences` access (`"ble_pacing_prefs"` at line 119, `"ambiance_presets_prefs"` at 120/620/639, with hand-rolled JSON persistence at 619–665), and pure color-math business logic (`derivePaletteFromParameters`, 64–108) that belongs beside `ColorConverter` in `core/`. Also has a confirmed-unused `import android.media.projection.MediaProjectionManager` (line 6, verified — no other reference in the file).
4. **`ui/components/SettingsTabContent.kt:347`** — an independent second raw handle to the same `"ble_pacing_prefs"` SharedPreferences store as `AmbianceScreen.kt:119`, with no shared repository between them. Two UI files each own their own copy of the same persistence logic.
5. **`ui/components/MusicScreen.kt:36`** — same confirmed-unused `MediaProjectionManager` import as `AmbianceScreen.kt`.
6. **`ui/components/AiSceneGeneratorScreen.kt:60–181`** — a full `ViewModel` subclass with its own `StateFlow`/`viewModelScope` logic is defined inside a file named `...Screen.kt`. Lower severity (the AI-call logic itself is properly separated into `AiSceneGenerator.kt`), but the state holder belongs in `presentation/` proper.

Checked and genuinely clean (not flagged): `CoreControlsReducer.kt`, `AudioSettingsReducer.kt`, `SceneAnimationRunner.kt` (hardware access properly abstracted behind injected closures), `AmbianceProcessor.kt`, `DeviceStateStore.kt`, `AudioCaptureService.kt`, `DiagnosticLogger.kt`, `ModesScreen.kt`, `SceneCreationDialog.kt`, `AiSceneGenerator.kt`.

## 4. Dead code / stale scaffolding — summary

- `ControllerUiState`: clean, no stragglers, only a comment noting the migration.
- Two confirmed dead/unused items: `RgbControllerViewModel.kt:2662` (`delayMs` function, unused), `MainActivity.kt:2066` (`validateHex` function, unused).
- Two confirmed unused imports: `AmbianceScreen.kt:6`, `MusicScreen.kt:36` (`MediaProjectionManager`).
- One TODO in the whole tree: `core/protocol/DuoCoProtocol.kt:9`, self-documented as forward-looking, not urgent.
- No commented-out code blocks, no orphaned duplicate reducer logic — the reducer migration itself is clean.
- Legacy `app/src/main/java/com/example/DuoCoProtocol.kt` is **not** dead — it's a thin shim still imported by `PacingAutoTuneDialog.kt:22` and `AmbianceCaptureService.kt:24`, delegating to `core/protocol/DuoCoProtocol`. Leave it unless those two call sites are updated first.

## 5. Test coverage gaps, ranked by risk

Full suite: 150 tests (Phase 4). All reducers well-covered. Gaps are concentrated in `core/` and `hardware/audio/` — the parts of the codebase that are *supposed* to be the most testable:

1. **`core/protocol/DuoCoProtocol.kt`** — highest risk. Pure logic that directly encodes real BLE hardware commands, and **14 of ~20 public functions are completely untested** (`createMusicColorCommand`, `createCctCommand`, `createSceneCommand`, `createRgbPinSequenceCommand`, etc.). The override get/set/clear state (lines 13–23) that several command builders branch on — the same mutable global state the file's own TODO at line 9 flags as a risk — is untested.
2. **`hardware/audio/Fft.kt`** — pure math moved "verbatim" from the ViewModel in Phase 4, **zero tests**, and unlike its sibling capture-source files it has no Android-hardware excuse for that (Robolectric's inability to model real audio input doesn't apply to plain FFT math).
3. **`core/audio/AudioDspProcessor.kt`** — 403 lines, the largest file in `core/`, only 5 tests. The beat-detection branch, the palette-cycling/white-flash fork, the `"Beat Only"` preset's separate brightness formula, and auto-gain branching are all untested — real-time LED color output logic with false confidence from a thin test file.
4. **`core/calibration/CalibrationMatrixSolver.kt`** — both degenerate-input guards (`n < 3`, near-singular `det`) silently return an identity fallback and are completely untested; a calibration bug here would silently no-op rather than fail loudly.
5. **`domain/ConnectionManager.kt`** — zero tests on the exact class CLAUDE.md says was introduced to fix a documented past static-field bug. Only 40 lines — cheap to close.
6. **`RgbDatabaseRepositoryImplTest.kt`/`AppPreferencesRepositoryImplTest.kt`** — both leave most of their interface surface (calibration CRUD, device-alias, pacing-pref, CCT groups) as untested delegation.
7. **`domain/model/AppScene.kt`** — no dedicated test; only incidentally exercised (a handful of ~32 `DeviceSceneState` fields) via a repository persistence test.
8. **`core/color/ColorConverter.kt`** — partial hue-branch coverage (2 of 6 branches), lower risk since it's simple, symmetric math.
9. `core/animation/ProceduralSceneParams.kt` — near-full coverage, lowest risk in `core/`.
10. `AudioRecordCaptureSource.kt`/`VisualizerCaptureSource.kt` — untested paths are genuine Android hardware I/O, correctly and explicitly deferred to the outstanding manual on-device smoke test per CLAUDE.md. Not a hidden gap.

## 6. What would block or complicate the deferred CLAUDE.md items

- **LED-freezing bug**: the BLE GATT lifecycle (finding 1.2 above) and `DeviceWriteManager` remain fully inline in the ViewModel with no unit-test seam — any investigation work will be debugging live hardware-coupled code, not something isolatable in tests. Extracting `DeviceWriteManager`/GATT callback handling behind an interface (mirroring `AudioCaptureSource`) before debugging the freeze would make it much easier to write a reproducing test.
- **Sample-rate mismatch fix** (`AudioDspProcessor` backend-specific constants): directly blocked by test gap #3 above — fixing that mismatch without beat-detection/preset-branch test coverage first means the fix would be unverifiable except by manual on-device testing.
- **Smart Scene polish**: scene orchestration (finding 1.5) is ~500 lines of inline, untested business logic in the ViewModel. Any UI polish work here will be touching code with zero unit-test safety net; consider extracting `applyScene`/`applyDeviceState`/scene-chaining into `domain/` before or alongside that work, matching the "isolate behind an interface" pattern the project already used successfully for audio.

---

## Ranked punch list (impact vs. effort)

**High impact, low effort — do first:**
- Delete confirmed dead code: `RgbControllerViewModel.kt:2662` (`delayMs`), `MainActivity.kt:2066` (`validateHex`), unused `MediaProjectionManager` imports in `AmbianceScreen.kt`/`MusicScreen.kt`.
- Fast-forward `refactor-checkpoint` to include Phase 4 (PR #12); decide `main`'s fate; prune merged branches.
- Add unit tests for `domain/ConnectionManager.kt` (40 lines, zero tests, cheap).
- Add tests for `core/calibration/CalibrationMatrixSolver.kt`'s two silent-fallback guards.

**High impact, medium effort:**
- Close the `core/protocol/DuoCoProtocol.kt` test gap (14 untested functions encoding real hardware commands) — highest-risk untested code in the repo.
- Expand `AudioDspProcessor` tests to cover beat-detection/preset branches before touching the sample-rate-mismatch fix.
- Add tests for `hardware/audio/Fft.kt` (pure math, no excuse for the gap).
- Consolidate the duplicated `"ble_pacing_prefs"` SharedPreferences access (`AmbianceScreen.kt:119`, `SettingsTabContent.kt:347`) behind one repository method.

**Medium impact, higher effort (candidates for a "Phase 5"):**
- Extract `MainActivity.kt`'s Home/Devices tab UI into `ui/components/*`, matching how Modes/Music/Ambiance/Settings were already done — closes the one clear inconsistency in the presentation layer.
- Extract `PacingAutoTuneDialog.kt`'s auto-tune algorithm into a `core/`-level class the dialog only observes.
- Close the `getActiveInstance()` escape hatch for the ambiance capture path — route `AmbianceCaptureService`/`AmbianceOutputInterpolator` through `dispatch(RgbIntent)` or an injected interface instead of the static singleton.
- Move `BeatDetector`, `MetronomePlayer`, and the demo `runAudioSimulationEngine` DSP duplicate out of `RgbControllerViewModel.kt` into `core/audio/`/`hardware/audio/` — same pattern Phase 4 already proved out for the real capture backends.

**Lower priority / house-keeping:**
- Deduplicate the reflection-based haptics lookup in `MainActivity.kt` (2 copies).
- Move `derivePaletteFromParameters` out of `AmbianceScreen.kt` into `core/`.
- Clean up the double-closing-brace formatting artifact at `MainActivity.kt:2060–2064`.
