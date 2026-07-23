package com.example.presentation

import com.example.RgbUiState
import com.example.RgbIntent
import com.example.CctCorrectionProfile
import com.example.db.ColorCalibration

// ============================================================================
// CALIBRATION SIDE EFFECTS — side effects triggered by calibration/tuning
// intent transitions. Nearly every intent in this group is IO/hardware-bound
// (prefs, Room, live DeviceWriteManager mutation, or a long-running coroutine
// loop), so this reducer leans on side effects more heavily than its siblings.
// ============================================================================

sealed interface CalibrationSideEffect {
    // Mirrors prefsRepo.putCalibrationDelayPrefInt(deviceKey, value) — deviceKey is
    // either the active audio device identifier or the literal "default_delay".
    data class SaveCalibrationDelayPrefInt(val deviceKey: String, val value: Int) : CalibrationSideEffect
    object ClearCalibrationDelayPrefs : CalibrationSideEffect

    // Mirrors prefsRepo.putAppStatePrefInt(key, value) — generic app-state pref,
    // same underlying method the other reducers' SavePrefInt/SaveAudioPrefInt use.
    data class SaveCalibrationPrefInt(val key: String, val value: Int) : CalibrationSideEffect

    // Mirrors prefsRepo.putPacingPrefInt(address, value) — distinct from the
    // generic app-state prefs above, keyed per device address.
    data class SavePacingPrefInt(val address: String, val value: Int) : CalibrationSideEffect
    object ClearPacingPrefs : CalibrationSideEffect

    // CCT correction profiles: prefsRepo write + reload of the separate
    // `cctCalibrations` StateFlow (not part of RgbUiState).
    data class SaveCctCorrectionProfile(val profile: CctCorrectionProfile) : CalibrationSideEffect
    data class DeleteCctCorrectionProfile(val address: String) : CalibrationSideEffect
    // Used by ResetCalibrationSettings: clears the prefs AND resets the separate
    // `cctCalibrations` StateFlow to emptyMap (source folds this into the same
    // _uiState.update as the other reset fields; here it's out-of-band since
    // cctCalibrations isn't part of RgbUiState).
    object ClearCctCalibrationPrefs : CalibrationSideEffect

    // Room DB — color calibration profiles. Source does the DB write and the
    // matching addLog() inside the same IO coroutine, so the log is emitted by
    // whatever executes this effect, not as a separate reducer-emitted Log.
    data class SaveColorCalibration(val calibration: ColorCalibration) : CalibrationSideEffect
    data class DeleteColorCalibration(val macAddress: String) : CalibrationSideEffect

    // Live DeviceWriteManager mutation — not part of RgbUiState.
    data class SetDeviceManagerPacing(val address: String, val ms: Int) : CalibrationSideEffect
    object ResetAllDeviceManagerPacing : CalibrationSideEffect

    // Metronome coroutine (StartCalibrationMode / StopCalibrationMode).
    // CONTRACT: on each tick (after delaying calibrationDelayOffsetMs, mirroring
    // source's `launch { delay(currentDelay.toLong()); sendCalibrationFlash() }`),
    // the executor of this effect MUST re-dispatch RgbIntent.SendCalibrationFlash
    // through the same intent-processing entry point used for direct dispatch —
    // NOT construct SendFlashPulse (or any equivalent BLE pulse) inline. This
    // keeps SendFlashPulse producible from exactly one place: the
    // RgbIntent.SendCalibrationFlash branch below. Do not special-case the
    // metronome tick with its own pulse logic; there must be nothing to drift.
    object StartMetronome : CalibrationSideEffect
    object CancelMetronome : CalibrationSideEffect

    // BLE pulse (white full-brightness -> 120ms -> black). No RgbUiState change.
    // Produced ONLY by the RgbIntent.SendCalibrationFlash branch below — see the
    // StartMetronome contract above for why the metronome tick must not produce
    // this independently.
    object SendFlashPulse : CalibrationSideEffect

    // Test pattern coroutine loop (cycles RED/GREEN/BLUE/WHITE every 30ms).
    data class StartTestPatternLoop(val address: String) : CalibrationSideEffect
    data class CancelTestPatternLoop(val address: String) : CalibrationSideEffect

    data class Log(val message: String) : CalibrationSideEffect
}

fun calibrationFlowReducer(
    state: RgbUiState,
    intent: RgbIntent,
    // Pre-fetched by the ViewModel via prefsRepo.getCalibrationDelayPrefInt(deviceKey, 0)
    // before the reducer runs, since a pure reducer can't perform the pref read itself.
    savedCalibrationDelayMs: Int,
    // Addresses with a live DeviceWriteManager. Mirrors the `deviceWriteManagers[address] != null`
    // guard in the source setDevicePacing() — the entire state+pref update is skipped when absent.
    connectedManagerAddresses: Set<String>
): Pair<RgbUiState, List<CalibrationSideEffect>> {
    return when (intent) {

        is RgbIntent.DismissCalibrationPrompt -> {
            state.copy(
                calibrationFlow = state.calibrationFlow.copy(showCalibrationPrompt = false)
            ) to emptyList()
        }

        is RgbIntent.StartCalibrationMode -> {
            // Source unconditionally calls stopCalibrationMode() first — preserved exactly,
            // including its "stopped" log even if no metronome was actually running.
            val newState = state.copy(
                calibrationFlow = state.calibrationFlow.copy(
                    isCalibrationModeActive = true,
                    calibrationDelayOffsetMs = savedCalibrationDelayMs
                )
            )
            val effects = listOf(
                CalibrationSideEffect.CancelMetronome,
                CalibrationSideEffect.Log("Calibration Mode stopped."),
                CalibrationSideEffect.Log(
                    "Calibration Mode started. Click plays every 1000ms. Pre-populated delay: $savedCalibrationDelayMs ms"
                ),
                CalibrationSideEffect.StartMetronome
            )
            newState to effects
        }

        is RgbIntent.UpdateCalibrationSliderValue -> {
            state.copy(
                calibrationFlow = state.calibrationFlow.copy(calibrationDelayOffsetMs = intent.value)
            ) to emptyList()
        }

        is RgbIntent.SaveCalibrationAndExit -> {
            val currentDelay = state.calibrationFlow.calibrationDelayOffsetMs
            val identifier = state.audioSettings.activeAudioDeviceIdentifier
            val deviceKey = identifier ?: "default_delay"
            val saveLogMessage = if (identifier != null) {
                "Saved calibrated delay of $currentDelay ms for device: ${state.audioSettings.detectedAudioDeviceName}"
            } else {
                "Saved default calibrated delay of $currentDelay ms"
            }

            // totalVisualDelayMs mirrors bluetoothDelayMs directly here, not
            // bluetoothDelayMs + BeatDetector's lookaheadMs: the calibration flash
            // (SendFlashPulse) is fired via broadcastCommandDirect, bypassing
            // queueCommand's totalVisualDelayMs delay entirely, so currentDelay already
            // IS the full delay the user tuned against the metronome click — adding the
            // 180ms lookahead on top would double-count detection latency that has
            // already elapsed by the time a beat is ever reported.
            // visualizer-review-2026-07-22.md C4/B3: this is now the ONE guided measurement that
            // sets both delay knobs -- the existing tap-to-sync metronome flow this action exits
            // already produces the single number that matters (audio onset -> photon), so
            // flashTimingOffsetMs is derived from it too instead of needing its own separate
            // calibration. See AudioSettingsReducer.SetBluetoothDelayMs's doc comment for the
            // derivation.
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(
                    bluetoothDelayMs = currentDelay,
                    totalVisualDelayMs = currentDelay,
                    flashTimingOffsetMs = currentDelay
                ),
                calibrationFlow = state.calibrationFlow.copy(
                    showCalibrationPrompt = false,
                    isCalibrationModeActive = false
                )
            )
            val effects = listOf(
                CalibrationSideEffect.SaveCalibrationDelayPrefInt(deviceKey, currentDelay),
                CalibrationSideEffect.Log(saveLogMessage),
                CalibrationSideEffect.SaveCalibrationPrefInt("bluetooth_delay_ms", currentDelay),
                CalibrationSideEffect.SaveCalibrationPrefInt("flash_timing_offset_ms", currentDelay),
                // stopCalibrationMode() tail call, preserved exactly
                CalibrationSideEffect.CancelMetronome,
                CalibrationSideEffect.Log("Calibration Mode stopped.")
            )
            newState to effects
        }

        is RgbIntent.StopCalibrationMode -> {
            val newState = state.copy(
                calibrationFlow = state.calibrationFlow.copy(isCalibrationModeActive = false)
            )
            val effects = listOf(
                CalibrationSideEffect.CancelMetronome,
                CalibrationSideEffect.Log("Calibration Mode stopped.")
            )
            newState to effects
        }

        is RgbIntent.SendCalibrationFlash -> {
            // Pure IO/BLE pulse — no RgbUiState field is touched in source. This is
            // the sole branch that may emit SendFlashPulse (see contract on
            // CalibrationSideEffect.StartMetronome) — the metronome tick must
            // re-dispatch this intent rather than duplicating its effect.
            state to listOf(CalibrationSideEffect.SendFlashPulse)
        }

        is RgbIntent.ResetCalibrationSettings -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(
                    bluetoothDelayMs = 0,
                    totalVisualDelayMs = 0,
                    // visualizer-review-2026-07-22.md C4/B3: unified with bluetoothDelayMs.
                    flashTimingOffsetMs = 0
                ),
                calibrationFlow = state.calibrationFlow.copy(calibrationDelayOffsetMs = 0),
                connectivity = state.connectivity.copy(
                    devicePacingMs = state.connectivity.devicePacingMs.mapValues { 100 }
                )
            )
            val effects = listOf(
                CalibrationSideEffect.ClearCalibrationDelayPrefs,
                CalibrationSideEffect.ClearCctCalibrationPrefs,
                CalibrationSideEffect.ClearPacingPrefs,
                // Live DeviceWriteManager pacing reset and the devicePacingMs state map above
                // are two independent paths kept in sync manually in source — preserve both,
                // do not unify into one.
                CalibrationSideEffect.ResetAllDeviceManagerPacing,
                CalibrationSideEffect.SaveCalibrationPrefInt("bluetooth_delay_ms", 0),
                CalibrationSideEffect.SaveCalibrationPrefInt("flash_timing_offset_ms", 0),
                CalibrationSideEffect.Log(
                    "Reset Calibration Defaults: bluetooth audio delay compensation set to 0, " +
                        "per-device pacing reset to 100ms, and custom CCT/audio calibration profiles cleared."
                )
            )
            newState to effects
        }

        is RgbIntent.SaveColorCalibration -> {
            state to listOf(CalibrationSideEffect.SaveColorCalibration(intent.calibration))
        }

        is RgbIntent.DeleteColorCalibration -> {
            state to listOf(CalibrationSideEffect.DeleteColorCalibration(intent.macAddress))
        }

        is RgbIntent.SaveCctCorrectionProfile -> {
            state to listOf(CalibrationSideEffect.SaveCctCorrectionProfile(intent.profile))
        }

        is RgbIntent.DeleteCctCorrectionProfile -> {
            state to listOf(CalibrationSideEffect.DeleteCctCorrectionProfile(intent.address))
        }

        is RgbIntent.ToggleTestPattern -> {
            val isRunning = state.connectivity.isTestPatternRunning[intent.address] == true
            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    isTestPatternRunning = state.connectivity.isTestPatternRunning + (intent.address to !isRunning)
                )
            )
            val effects = if (isRunning) {
                listOf(CalibrationSideEffect.CancelTestPatternLoop(intent.address))
            } else {
                listOf(CalibrationSideEffect.StartTestPatternLoop(intent.address))
            }
            newState to effects
        }

        is RgbIntent.SetDevicePacing -> {
            // Source guards the ENTIRE block (state update included) on the live
            // DeviceWriteManager existing — preserved exactly, including the
            // silent no-op when it doesn't.
            if (intent.address in connectedManagerAddresses) {
                val newState = state.copy(
                    connectivity = state.connectivity.copy(
                        devicePacingMs = state.connectivity.devicePacingMs + (intent.address to intent.ms)
                    )
                )
                val effects = listOf(
                    CalibrationSideEffect.SetDeviceManagerPacing(intent.address, intent.ms),
                    CalibrationSideEffect.SavePacingPrefInt(intent.address, intent.ms)
                )
                newState to effects
            } else {
                state to emptyList()
            }
        }

        else -> state to emptyList()
    }
}
