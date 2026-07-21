package com.example.presentation

import com.example.CctCorrectionProfile
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.db.ColorCalibration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalibrationFlowReducerTest {

    private fun reduce(
        state: RgbUiState = RgbUiState(),
        intent: RgbIntent,
        savedCalibrationDelayMs: Int = 0,
        connectedManagerAddresses: Set<String> = emptySet()
    ): Pair<RgbUiState, List<CalibrationSideEffect>> =
        calibrationFlowReducer(state, intent, savedCalibrationDelayMs, connectedManagerAddresses)

    // ========================================================================
    // DismissCalibrationPrompt
    // ========================================================================

    @Test
    fun dismissCalibrationPrompt_setsShowCalibrationPromptFalse() {
        val initial = RgbUiState().let {
            it.copy(calibrationFlow = it.calibrationFlow.copy(showCalibrationPrompt = true))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.DismissCalibrationPrompt)

        assertFalse(newState.calibrationFlow.showCalibrationPrompt)
        assertTrue(effects.isEmpty())
    }

    // ========================================================================
    // StartCalibrationMode — quirk: unconditionally stops first, so the
    // "stopped" log/cancel are always present even if nothing was running.
    // Order matters: cancel, "stopped" log, "started" log, THEN start job
    // (source calls addLog() before launching the coroutine).
    // ========================================================================

    @Test
    fun startCalibrationMode_setsActiveAndSeedsDelayFromParam() {
        val (newState, _) = reduce(intent = RgbIntent.StartCalibrationMode, savedCalibrationDelayMs = 42)

        assertTrue(newState.calibrationFlow.isCalibrationModeActive)
        assertEquals(42, newState.calibrationFlow.calibrationDelayOffsetMs)
    }

    @Test
    fun startCalibrationMode_emitsStopThenStartEffectsInSourceOrder() {
        val (_, effects) = reduce(intent = RgbIntent.StartCalibrationMode, savedCalibrationDelayMs = 42)

        assertEquals(
            listOf(
                CalibrationSideEffect.CancelMetronome,
                CalibrationSideEffect.Log("Calibration Mode stopped."),
                CalibrationSideEffect.Log("Calibration Mode started. Click plays every 1000ms. Pre-populated delay: 42 ms"),
                CalibrationSideEffect.StartMetronome
            ),
            effects
        )
    }

    @Test
    fun startCalibrationMode_emitsStopEffectsEvenWhenNotPreviouslyActive() {
        val initial = RgbUiState().let {
            it.copy(calibrationFlow = it.calibrationFlow.copy(isCalibrationModeActive = false))
        }
        val (_, effects) = reduce(state = initial, intent = RgbIntent.StartCalibrationMode, savedCalibrationDelayMs = 0)

        assertTrue(effects.contains(CalibrationSideEffect.CancelMetronome))
        assertTrue(effects.contains(CalibrationSideEffect.Log("Calibration Mode stopped.")))
    }

    // ========================================================================
    // UpdateCalibrationSliderValue — pure, no effects
    // ========================================================================

    @Test
    fun updateCalibrationSliderValue_updatesOffsetWithNoEffects() {
        val (newState, effects) = reduce(intent = RgbIntent.UpdateCalibrationSliderValue(88))

        assertEquals(88, newState.calibrationFlow.calibrationDelayOffsetMs)
        assertTrue(effects.isEmpty())
    }

    // ========================================================================
    // SaveCalibrationAndExit — quirk: writes the SAME delay to two different
    // pref keys via two different mechanisms, and tail-calls stopCalibrationMode().
    // ========================================================================

    @Test
    fun saveCalibrationAndExit_withKnownDevice_savesUnderDeviceIdentifier() {
        val initial = RgbUiState().let {
            it.copy(
                calibrationFlow = it.calibrationFlow.copy(calibrationDelayOffsetMs = 55, isCalibrationModeActive = true),
                audioSettings = it.audioSettings.copy(
                    activeAudioDeviceIdentifier = "AA:BB:CC",
                    detectedAudioDeviceName = "My Earbuds"
                )
            )
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SaveCalibrationAndExit)

        assertEquals(
            listOf(
                CalibrationSideEffect.SaveCalibrationDelayPrefInt("AA:BB:CC", 55),
                CalibrationSideEffect.Log("Saved calibrated delay of 55 ms for device: My Earbuds"),
                CalibrationSideEffect.SaveCalibrationPrefInt("bluetooth_delay_ms", 55),
                CalibrationSideEffect.CancelMetronome,
                CalibrationSideEffect.Log("Calibration Mode stopped.")
            ),
            effects
        )
        assertEquals(55, newState.audioSettings.bluetoothDelayMs)
        assertEquals(55, newState.audioSettings.totalVisualDelayMs)
        assertFalse(newState.calibrationFlow.showCalibrationPrompt)
        assertFalse(newState.calibrationFlow.isCalibrationModeActive)
    }

    @Test
    fun saveCalibrationAndExit_withNoActiveDevice_savesUnderDefaultDelayKey() {
        val initial = RgbUiState().let {
            it.copy(
                calibrationFlow = it.calibrationFlow.copy(calibrationDelayOffsetMs = 20),
                audioSettings = it.audioSettings.copy(activeAudioDeviceIdentifier = null)
            )
        }
        val (_, effects) = reduce(state = initial, intent = RgbIntent.SaveCalibrationAndExit)

        assertTrue(effects.contains(CalibrationSideEffect.SaveCalibrationDelayPrefInt("default_delay", 20)))
        assertTrue(effects.contains(CalibrationSideEffect.Log("Saved default calibrated delay of 20 ms")))
        assertTrue(effects.contains(CalibrationSideEffect.SaveCalibrationPrefInt("bluetooth_delay_ms", 20)))
    }

    // ========================================================================
    // StopCalibrationMode
    // ========================================================================

    @Test
    fun stopCalibrationMode_cancelsAndLogsAndClearsActiveFlag() {
        val initial = RgbUiState().let {
            it.copy(calibrationFlow = it.calibrationFlow.copy(isCalibrationModeActive = true))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.StopCalibrationMode)

        assertFalse(newState.calibrationFlow.isCalibrationModeActive)
        assertEquals(
            listOf(CalibrationSideEffect.CancelMetronome, CalibrationSideEffect.Log("Calibration Mode stopped.")),
            effects
        )
    }

    // ========================================================================
    // SendCalibrationFlash — pure IO/BLE pulse, no state change at all
    // ========================================================================

    @Test
    fun sendCalibrationFlash_emitsPulseEffectWithNoStateChange() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SendCalibrationFlash)

        assertEquals(initial, newState)
        assertEquals(listOf(CalibrationSideEffect.SendFlashPulse), effects)
    }

    // StartMetronome's contract (see CalibrationSideEffect docs in CalibrationFlowReducer.kt)
    // requires the ViewModel to re-dispatch RgbIntent.SendCalibrationFlash on every metronome
    // tick through the same intent-processing entry point used for direct dispatch, rather
    // than building the pulse effect inline inside StartMetronome's implementation — so
    // SendFlashPulse has exactly one producer. The metronome's own timing/looping can't be
    // exercised here without the ViewModel wiring (not yet built), but this locks in the half
    // of the guarantee that lives in the reducer: SendCalibrationFlash's output is pure,
    // state-independent, and identical on every dispatch — so however many times / whatever
    // context (direct call, or redispatched from inside a metronome tick) it's invoked from,
    // there is nothing for it to drift from.
    @Test
    fun sendCalibrationFlash_isPureAndIdenticalOnRepeatedDispatch_singleSourceForMetronomeTicks() {
        val stateA = RgbUiState()
        val stateB = RgbUiState().let {
            it.copy(calibrationFlow = it.calibrationFlow.copy(isCalibrationModeActive = true, calibrationDelayOffsetMs = 300))
        }

        val results = listOf(
            reduce(state = stateA, intent = RgbIntent.SendCalibrationFlash),
            reduce(state = stateA, intent = RgbIntent.SendCalibrationFlash),
            reduce(state = stateB, intent = RgbIntent.SendCalibrationFlash)
        )

        results.forEach { (newState, effects) ->
            assertEquals(listOf(CalibrationSideEffect.SendFlashPulse), effects)
        }
        // No state dependency at all — identical effect regardless of calibration state,
        // consistent with source's sendCalibrationFlash() reading no _uiState fields.
        assertEquals(stateA, results[0].first)
        assertEquals(stateB, results[2].first)
    }

    // ========================================================================
    // ResetCalibrationSettings — quirks: (a) bluetooth_delay_ms pref write is
    // separate from the three clear() calls, (b) two independent pacing-reset
    // paths (live manager mutation + devicePacingMs map) both preserved.
    // ========================================================================

    @Test
    fun resetCalibrationSettings_resetsStateFieldsAcrossSlices() {
        val initial = RgbUiState().let {
            it.copy(
                audioSettings = it.audioSettings.copy(bluetoothDelayMs = 99, totalVisualDelayMs = 500),
                calibrationFlow = it.calibrationFlow.copy(calibrationDelayOffsetMs = 77),
                connectivity = it.connectivity.copy(devicePacingMs = mapOf("A1" to 20, "A2" to 30))
            )
        }
        val (newState, _) = reduce(state = initial, intent = RgbIntent.ResetCalibrationSettings)

        assertEquals(0, newState.audioSettings.bluetoothDelayMs)
        assertEquals(0, newState.audioSettings.totalVisualDelayMs)
        assertEquals(0, newState.calibrationFlow.calibrationDelayOffsetMs)
        assertEquals(mapOf("A1" to 100, "A2" to 100), newState.connectivity.devicePacingMs)
    }

    @Test
    fun resetCalibrationSettings_emitsClearsManagerResetPrefWriteAndLogInSourceOrder() {
        val (_, effects) = reduce(intent = RgbIntent.ResetCalibrationSettings)

        assertEquals(
            listOf(
                CalibrationSideEffect.ClearCalibrationDelayPrefs,
                CalibrationSideEffect.ClearCctCalibrationPrefs,
                CalibrationSideEffect.ClearPacingPrefs,
                CalibrationSideEffect.ResetAllDeviceManagerPacing,
                CalibrationSideEffect.SaveCalibrationPrefInt("bluetooth_delay_ms", 0),
                CalibrationSideEffect.Log(
                    "Reset Calibration Defaults: bluetooth audio delay compensation set to 0, " +
                        "per-device pacing reset to 100ms, and custom CCT/audio calibration profiles cleared."
                )
            ),
            effects
        )
    }

    @Test
    fun resetCalibrationSettings_emitsBothIndependentPacingResetPaths() {
        val initial = RgbUiState().let {
            it.copy(connectivity = it.connectivity.copy(devicePacingMs = mapOf("A1" to 20)))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.ResetCalibrationSettings)

        // Path 1: live DeviceWriteManager mutation, fired as a side effect.
        assertTrue(effects.contains(CalibrationSideEffect.ResetAllDeviceManagerPacing))
        // Path 2: the UI-facing devicePacingMs map, updated independently in state.
        assertEquals(mapOf("A1" to 100), newState.connectivity.devicePacingMs)
    }

    // ========================================================================
    // SaveColorCalibration / DeleteColorCalibration — Room DB only, no
    // RgbUiState field touched in source.
    // ========================================================================

    @Test
    fun saveColorCalibration_emitsEffectWithNoStateChange() {
        val calibration = ColorCalibration(
            macAddress = "AA:BB",
            timestamp = 0L,
            version = 1,
            m11 = 1f, m12 = 0f, m13 = 0f,
            m21 = 0f, m22 = 1f, m23 = 0f,
            m31 = 0f, m32 = 0f, m33 = 1f,
            samplesJson = "[]",
            exposureTimeNs = 0L,
            sensitivityIso = 100,
            whiteBalanceLocked = false
        )
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SaveColorCalibration(calibration))

        assertEquals(initial, newState)
        assertEquals(listOf(CalibrationSideEffect.SaveColorCalibration(calibration)), effects)
    }

    @Test
    fun deleteColorCalibration_emitsEffectWithNoStateChange() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.DeleteColorCalibration("AA:BB"))

        assertEquals(initial, newState)
        assertEquals(listOf(CalibrationSideEffect.DeleteColorCalibration("AA:BB")), effects)
    }

    // ========================================================================
    // SaveCctCorrectionProfile / DeleteCctCorrectionProfile — pref write +
    // reload of the separate cctCalibrations StateFlow, no log in source.
    // ========================================================================

    @Test
    fun saveCctCorrectionProfile_emitsEffectWithNoStateChange() {
        val profile = CctCorrectionProfile(
            macAddress = "AA:BB",
            timestamp = 0L,
            scaleR = 1.1f, offsetR = 0.0f,
            scaleG = 1.0f, offsetG = 0.0f,
            scaleB = 0.9f, offsetB = 0.0f,
            iso = 100,
            exposureNs = 0L
        )
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SaveCctCorrectionProfile(profile))

        assertEquals(initial, newState)
        assertEquals(listOf(CalibrationSideEffect.SaveCctCorrectionProfile(profile)), effects)
    }

    @Test
    fun deleteCctCorrectionProfile_emitsEffectWithNoStateChange() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.DeleteCctCorrectionProfile("AA:BB"))

        assertEquals(initial, newState)
        assertEquals(listOf(CalibrationSideEffect.DeleteCctCorrectionProfile("AA:BB")), effects)
    }

    // ========================================================================
    // ToggleTestPattern — flips the flag and picks Start/Cancel accordingly
    // ========================================================================

    @Test
    fun toggleTestPattern_whenNotRunning_startsLoopAndSetsFlagTrue() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.ToggleTestPattern("AA:BB"))

        assertEquals(true, newState.connectivity.isTestPatternRunning["AA:BB"])
        assertEquals(listOf(CalibrationSideEffect.StartTestPatternLoop("AA:BB")), effects)
    }

    @Test
    fun toggleTestPattern_whenRunning_cancelsLoopAndSetsFlagFalse() {
        val initial = RgbUiState().let {
            it.copy(connectivity = it.connectivity.copy(isTestPatternRunning = mapOf("AA:BB" to true)))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.ToggleTestPattern("AA:BB"))

        assertEquals(false, newState.connectivity.isTestPatternRunning["AA:BB"])
        assertEquals(listOf(CalibrationSideEffect.CancelTestPatternLoop("AA:BB")), effects)
    }

    @Test
    fun toggleTestPattern_onlyAffectsTargetAddress() {
        val initial = RgbUiState().let {
            it.copy(connectivity = it.connectivity.copy(isTestPatternRunning = mapOf("OTHER" to true)))
        }
        val (newState, _) = reduce(state = initial, intent = RgbIntent.ToggleTestPattern("AA:BB"))

        assertEquals(true, newState.connectivity.isTestPatternRunning["OTHER"])
        assertEquals(true, newState.connectivity.isTestPatternRunning["AA:BB"])
    }

    // ========================================================================
    // SetDevicePacing — quirk: entire block (state + pref write + manager
    // mutation) is guarded by the live DeviceWriteManager existing; silently
    // no-ops, including the state update, when it doesn't.
    // ========================================================================

    @Test
    fun setDevicePacing_withLiveManager_updatesStateAndEmitsEffects() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(
            state = initial,
            intent = RgbIntent.SetDevicePacing("AA:BB", 45),
            connectedManagerAddresses = setOf("AA:BB")
        )

        assertEquals(45, newState.connectivity.devicePacingMs["AA:BB"])
        assertEquals(
            listOf(
                CalibrationSideEffect.SetDeviceManagerPacing("AA:BB", 45),
                CalibrationSideEffect.SavePacingPrefInt("AA:BB", 45)
            ),
            effects
        )
    }

    @Test
    fun setDevicePacing_withoutLiveManager_isANoOp() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(
            state = initial,
            intent = RgbIntent.SetDevicePacing("AA:BB", 45),
            connectedManagerAddresses = emptySet()
        )

        assertEquals(initial, newState)
        assertTrue(effects.isEmpty())
    }

    // ========================================================================
    // Unrelated intents pass through unchanged
    // ========================================================================

    @Test
    fun unrelatedIntent_returnsStateUnchangedWithNoEffects() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetPower(true))

        assertEquals(initial, newState)
        assertTrue(effects.isEmpty())
    }
}
