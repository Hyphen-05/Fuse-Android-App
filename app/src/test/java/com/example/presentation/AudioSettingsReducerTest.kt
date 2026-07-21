package com.example.presentation

import com.example.ActiveDeviceState
import com.example.RgbControllerViewModel
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.protocol.DuoCoProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioSettingsReducerTest {

    private fun reduce(
        state: RgbUiState = RgbUiState(),
        intent: RgbIntent,
        targetAddresses: List<String> = emptyList(),
        deviceAutomationMode: Map<String, RgbControllerViewModel.AutomationType> = emptyMap()
    ): Pair<RgbUiState, List<AudioSideEffect>> = audioSettingsReducer(
        state, intent, targetAddresses, deviceAutomationMode
    )

    // ========================================================================
    // SetVisualizerPreset — 16-field value table + activeFeatureName recompute
    // ========================================================================

    @Test
    fun setVisualizerPreset_punchy_appliesAllSixteenFields() {
        val (newState, effects) = reduce(intent = RgbIntent.SetVisualizerPreset("Punchy"))

        val audio = newState.audioSettings
        assertEquals("Punchy", audio.visualizerPreset)
        assertEquals(0.95f, audio.audioSmoothingAttack)
        assertEquals(0.35f, audio.audioSmoothingDecay)
        assertEquals(0.6f, audio.audioFlashStrength)
        assertEquals(0.35f, audio.audioGammaExponent)
        assertEquals(2000L, audio.idleTriggerDelayMs)
        assertEquals(8.0f, audio.noiseGateThreshold)
        assertEquals(1.2f, audio.bassGain)
        assertEquals(1.0f, audio.midGain)
        assertEquals(1.0f, audio.highGain)
        assertTrue(audio.isPaletteCyclingEnabled)
        assertEquals(1.3f, audio.beatThresholdMultiplier)
        assertEquals(0.15f, audio.visualizerMinBrightness)
        assertEquals(1.5f, audio.visualizerColorSpeed)
        assertEquals(140f, audio.beatFlashDecayMs)
        assertEquals(0.40f, audio.ambientCapFraction)
        assertEquals(0.20f, audio.midFluxWeight)
        // Stage 1 of the mapping-layer proposal: flashFloor/flashRange are wired into every
        // preset at the same default values as before (0.6f/0.4f) — no per-preset table yet.
        assertEquals(0.6f, audio.flashFloor)
        assertEquals(0.4f, audio.flashRange)

        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Punchy")))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("mid_flux_weight", 0.20f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("flash_floor", 0.6f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("flash_range", 0.4f)))
        assertEquals(20, effects.size)
    }

    @Test
    fun setVisualizerPreset_beatOnly_disablesPaletteCyclingAndZeroesAmbientCapFraction() {
        val (newState, _) = reduce(intent = RgbIntent.SetVisualizerPreset("Beat Only"))

        assertFalse(newState.audioSettings.isPaletteCyclingEnabled)
        assertEquals(0.0f, newState.audioSettings.ambientCapFraction)
        assertEquals(0.15f, newState.audioSettings.midFluxWeight)
    }

    @Test
    fun setVisualizerPreset_unknownId_fallsBackToDefaultTable() {
        val (newState, _) = reduce(intent = RgbIntent.SetVisualizerPreset("NotARealPreset"))

        val audio = newState.audioSettings
        assertEquals("NotARealPreset", audio.visualizerPreset)
        assertEquals(0.85f, audio.audioSmoothingAttack)
        assertEquals(0.12f, audio.audioSmoothingDecay)
        assertEquals(1.6f, audio.beatThresholdMultiplier)
    }

    @Test
    fun setVisualizerPreset_whenMusicModeIsPhoneMic_recomputesActiveFeatureName() {
        val initial = RgbUiState().let {
            it.copy(audioSettings = it.audioSettings.copy(musicMode = "phone_mic"))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetVisualizerPreset("Bass Thump"))

        assertEquals("Audio Visualiser (Bass Thump)", newState.coreControl.activeFeatureName)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("active_feature_name", "Audio Visualiser (Bass Thump)")))
    }

    @Test
    fun setVisualizerPreset_whenMusicModeIsOnboardBle_leavesActiveFeatureNameUnchanged() {
        val initial = RgbUiState().let {
            it.copy(
                audioSettings = it.audioSettings.copy(musicMode = "rhythm_1"),
                coreControl = it.coreControl.copy(activeFeatureName = "LED Visualiser (Rhythm 1)")
            )
        }
        val (newState, _) = reduce(state = initial, intent = RgbIntent.SetVisualizerPreset("Laser Sharp"))

        assertEquals("LED Visualiser (Rhythm 1)", newState.coreControl.activeFeatureName)
    }

    @Test
    fun setVisualizerPreset_whenMusicModeIsNull_leavesActiveFeatureNameUnchanged() {
        val initial = RgbUiState().let {
            it.copy(coreControl = it.coreControl.copy(activeFeatureName = "Colour"))
        }
        val (newState, _) = reduce(state = initial, intent = RgbIntent.SetVisualizerPreset("Smooth Flow"))

        assertEquals("Colour", newState.coreControl.activeFeatureName)
    }

    // ========================================================================
    // StartMusicSync — phone-mic/on-device vs. the 8 onboard-BLE modes
    // ========================================================================

    @Test
    fun startMusicSync_phoneMic_savesDeviceStateCancelsRunnersAndStartsAudioEngine() {
        val (newState, effects) = reduce(
            intent = RgbIntent.StartMusicSync("phone_mic"),
            targetAddresses = listOf("AA:BB", "CC:DD")
        )

        assertEquals("phone_mic", newState.audioSettings.musicMode)
        assertEquals("Audio Visualiser (Balanced)", newState.coreControl.activeFeatureName)

        assertTrue(effects.contains(AudioSideEffect.SaveDeviceState("AA:BB", RgbControllerViewModel.AutomationType.AUDIO)))
        assertTrue(effects.contains(AudioSideEffect.CancelSceneRunner("AA:BB")))
        assertTrue(effects.contains(AudioSideEffect.SaveDeviceState("CC:DD", RgbControllerViewModel.AutomationType.AUDIO)))
        assertTrue(effects.contains(AudioSideEffect.CancelSceneRunner("CC:DD")))
        assertTrue(effects.contains(AudioSideEffect.CancelSceneChain))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("active_feature_name", "Audio Visualiser (Balanced)")))
        assertTrue(effects.contains(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning = true)))
        assertTrue(effects.contains(AudioSideEffect.StartAudioEngine("phone_mic")))
        assertFalse(effects.any { it is AudioSideEffect.BroadcastCommand })
    }

    @Test
    fun startMusicSync_onDevice_alsoStartsAudioEngine() {
        val (newState, effects) = reduce(intent = RgbIntent.StartMusicSync("on_device"))

        assertEquals("on_device", newState.audioSettings.musicMode)
        assertTrue(effects.contains(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning = true)))
        assertTrue(effects.contains(AudioSideEffect.StartAudioEngine("on_device")))
    }

    @Test
    fun startMusicSync_onboardBleMode_broadcastsToggleStyleAndSensitivityInsteadOfAudioEngine() {
        val initial = RgbUiState().let {
            it.copy(audioSettings = it.audioSettings.copy(musicSensitivity = 42))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.StartMusicSync("rhythm_2"))

        assertEquals("rhythm_2", newState.audioSettings.musicMode)
        assertEquals("LED Visualiser (Rhythm 2)", newState.coreControl.activeFeatureName)

        assertTrue(effects.contains(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning = false)))
        assertFalse(effects.any { it is AudioSideEffect.StartAudioEngine })
        assertTrue(effects.any {
            it is AudioSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createPhoneMicToggleCommand(true)) &&
                it.logMessage == "Phone Mic Toggle ON"
        })
        assertTrue(effects.any {
            it is AudioSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createMicVisualizerStyleCommand(3)) // rhythm_2 -> index 3
        })
        assertTrue(effects.any {
            it is AudioSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createMusicSensitivityCommand(42))
        })
    }

    @Test
    fun startMusicSync_unrecognizedMode_skipsBroadcastCommandsEntirely() {
        val (_, effects) = reduce(intent = RgbIntent.StartMusicSync("not_a_real_mode"))

        assertFalse(effects.any { it is AudioSideEffect.BroadcastCommand })
        assertFalse(effects.any { it is AudioSideEffect.StartAudioEngine })
    }

    @Test
    fun startMusicSync_updatesDeviceStatesMapActiveFeatureNameForEachTarget() {
        val initial = RgbUiState().let {
            it.copy(connectivity = it.connectivity.copy(
                deviceStatesMap = mapOf("AA:BB" to ActiveDeviceState(red = 9))
            ))
        }
        val (newState, _) = reduce(
            state = initial,
            intent = RgbIntent.StartMusicSync("spectrum_1"),
            targetAddresses = listOf("AA:BB", "EE:FF")
        )

        val map = newState.connectivity.deviceStatesMap
        assertEquals("LED Visualiser (Spectrum 1)", map["AA:BB"]?.activeFeatureName)
        assertEquals(9, map["AA:BB"]?.red) // pre-existing fields preserved
        assertEquals("LED Visualiser (Spectrum 1)", map["EE:FF"]?.activeFeatureName)
    }

    // ========================================================================
    // StopMusicSync
    // ========================================================================

    @Test
    fun stopMusicSync_whenModeWasActive_broadcastsToggleOffAndClearsMode() {
        val initial = RgbUiState().let {
            it.copy(audioSettings = it.audioSettings.copy(musicMode = "phone_mic"))
        }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.StopMusicSync(restoreState = true))

        assertNull(newState.audioSettings.musicMode)
        assertEquals(AudioSideEffect.ClearExclusionsIfNotApplyingScene, effects.first())
        assertTrue(effects.any {
            it is AudioSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createPhoneMicToggleCommand(false)) &&
                it.logMessage == "Phone Mic Toggle OFF"
        })
        assertTrue(effects.contains(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning = false)))
    }

    @Test
    fun stopMusicSync_whenModeWasAlreadyNull_skipsToggleOffCommand() {
        val (_, effects) = reduce(intent = RgbIntent.StopMusicSync(restoreState = true))

        assertFalse(effects.any { it is AudioSideEffect.BroadcastCommand })
        assertTrue(effects.contains(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning = false)))
    }

    @Test
    fun stopMusicSync_restoreStateTrue_emitsRestoreOnlyForAudioAutomationAddresses() {
        val (_, effects) = reduce(
            intent = RgbIntent.StopMusicSync(restoreState = true),
            deviceAutomationMode = mapOf(
                "AA:BB" to RgbControllerViewModel.AutomationType.AUDIO,
                "CC:DD" to RgbControllerViewModel.AutomationType.AMBIANCE
            )
        )

        assertTrue(effects.contains(AudioSideEffect.RestoreDeviceState("AA:BB", RgbControllerViewModel.AutomationType.AUDIO)))
        assertFalse(effects.any { it is AudioSideEffect.RestoreDeviceState && it.address == "CC:DD" })
        assertFalse(effects.any { it is AudioSideEffect.ClearDeviceAutomationMode })
    }

    @Test
    fun stopMusicSync_restoreStateFalse_clearsAutomationModeInsteadOfRestoring() {
        val (_, effects) = reduce(
            intent = RgbIntent.StopMusicSync(restoreState = false),
            deviceAutomationMode = mapOf("AA:BB" to RgbControllerViewModel.AutomationType.AUDIO)
        )

        assertTrue(effects.contains(AudioSideEffect.ClearDeviceAutomationMode("AA:BB")))
        assertFalse(effects.any { it is AudioSideEffect.RestoreDeviceState })
    }

    // ========================================================================
    // SetMusicSensitivity
    // ========================================================================

    @Test
    fun setMusicSensitivity_updatesStateAndBroadcastsCommandWithNoPrefWrite() {
        val (newState, effects) = reduce(intent = RgbIntent.SetMusicSensitivity(77))

        assertEquals(77, newState.audioSettings.musicSensitivity)
        assertTrue(effects.any {
            it is AudioSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createMusicSensitivityCommand(77)) &&
                it.logMessage == "Mic Sensitivity 77"
        })
        assertFalse(effects.any { it is AudioSideEffect.SaveAudioPrefInt })
    }

    // ========================================================================
    // Selective "Custom" preset reset — the preserved quirk. These six setters
    // force visualizerPreset back to "Custom"; the sibling group below does not.
    // ========================================================================

    @Test
    fun setAudioSmoothingAttack_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAudioSmoothingAttack(0.5f))

        assertEquals(0.5f, newState.audioSettings.audioSmoothingAttack)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_attack", 0.5f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setAudioSmoothingDecay_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAudioSmoothingDecay(0.3f))

        assertEquals(0.3f, newState.audioSettings.audioSmoothingDecay)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_decay", 0.3f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setAudioGammaExponent_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAudioGammaExponent(0.6f))

        assertEquals(0.6f, newState.audioSettings.audioGammaExponent)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("audio_gamma_exponent", 0.6f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setAudioFlashStrength_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAudioFlashStrength(0.7f))

        assertEquals(0.7f, newState.audioSettings.audioFlashStrength)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("audio_flash_strength", 0.7f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setVisualizerMinBrightness_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetVisualizerMinBrightness(0.25f))

        assertEquals(0.25f, newState.audioSettings.visualizerMinBrightness)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("visualizer_min_brightness", 0.25f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setVisualizerColorSpeed_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetVisualizerColorSpeed(2.5f))

        assertEquals(2.5f, newState.audioSettings.visualizerColorSpeed)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("visualizer_color_speed", 2.5f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setIdleTriggerDelayMs_updatesValueAndForcesCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetIdleTriggerDelayMs(3200L))

        assertEquals(3200L, newState.audioSettings.idleTriggerDelayMs)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefLong("idle_trigger_delay_ms", 3200L)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    @Test
    fun setBeatThresholdMultiplier_updatesValueAndForcesCustomPreset() {
        // The one member of the "sensitivity/threshold" group that DOES reset the preset —
        // preserved as an intentional inconsistency vs. setBeatCooldownMs and the gain setters below.
        val (newState, effects) = reduce(intent = RgbIntent.SetBeatThresholdMultiplier(1.9f))

        assertEquals(1.9f, newState.audioSettings.beatThresholdMultiplier)
        assertEquals("Custom", newState.audioSettings.visualizerPreset)
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("beat_threshold_multiplier", 1.9f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")))
    }

    // ========================================================================
    // Setters that do NOT reset the preset — the other half of the preserved
    // inconsistency. Each asserts visualizerPreset is left untouched.
    // ========================================================================

    @Test
    fun setTransmissionDelayMs_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetTransmissionDelayMs(24))

        assertEquals(24, newState.audioSettings.transmissionDelayMs)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefInt("transmission_delay_ms", 24)), effects)
    }

    @Test
    fun setNoiseGateThreshold_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetNoiseGateThreshold(9.5f))

        assertEquals(9.5f, newState.audioSettings.noiseGateThreshold)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefFloat("noise_gate_threshold", 9.5f)), effects)
    }

    @Test
    fun setBeatCooldownMs_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetBeatCooldownMs(400))

        assertEquals(400, newState.audioSettings.beatCooldownMs)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefInt("beat_cooldown_ms", 400)), effects)
    }

    @Test
    fun setBassGain_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetBassGain(1.8f))

        assertEquals(1.8f, newState.audioSettings.bassGain)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefFloat("bass_gain", 1.8f)), effects)
    }

    @Test
    fun setMidGain_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetMidGain(1.4f))

        assertEquals(1.4f, newState.audioSettings.midGain)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefFloat("mid_gain", 1.4f)), effects)
    }

    @Test
    fun setHighGain_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetHighGain(0.9f))

        assertEquals(0.9f, newState.audioSettings.highGain)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefFloat("high_gain", 0.9f)), effects)
    }

    @Test
    fun setAutoGainEnabled_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetAutoGainEnabled(false))

        assertFalse(newState.audioSettings.isAutoGainEnabled)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefBoolean("is_auto_gain_enabled", false)), effects)
    }

    @Test
    fun setPaletteCyclingEnabled_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetPaletteCyclingEnabled(false))

        assertFalse(newState.audioSettings.isPaletteCyclingEnabled)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefBoolean("is_palette_cycling_enabled", false)), effects)
    }

    @Test
    fun setLogarithmicScalingEnabled_updatesValueAndLeavesPresetUntouched() {
        val initial = RgbUiState().let { it.copy(audioSettings = it.audioSettings.copy(visualizerPreset = "Punchy")) }
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetLogarithmicScalingEnabled(false))

        assertFalse(newState.audioSettings.isLogarithmicScalingEnabled)
        assertEquals("Punchy", newState.audioSettings.visualizerPreset)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefBoolean("is_logarithmic_scaling_enabled", false)), effects)
    }

    // ========================================================================
    // SetBluetoothDelayMs — totalVisualDelayMs derivation regression tests.
    // totalVisualDelayMs mirrors bluetoothDelayMs directly (no added lookahead):
    // the calibration flash bypasses queueCommand's totalVisualDelayMs delay
    // entirely, so the calibrated value already IS the full delay to apply —
    // adding BeatDetector's lookaheadMs on top double-counted detection latency
    // that has already elapsed by the time a beat is ever reported.
    // ========================================================================

    @Test
    fun setBluetoothDelayMs_derivesTotalVisualDelayAsValueDirectly() {
        val (newState, effects) = reduce(intent = RgbIntent.SetBluetoothDelayMs(50))

        assertEquals(50, newState.audioSettings.bluetoothDelayMs)
        assertEquals(50, newState.audioSettings.totalVisualDelayMs)
        assertEquals(listOf(AudioSideEffect.SaveAudioPrefInt("bluetooth_delay_ms", 50)), effects)
    }

    @Test
    fun setBluetoothDelayMs_zero_totalVisualDelayIsZero() {
        val (newState, _) = reduce(intent = RgbIntent.SetBluetoothDelayMs(0))

        assertEquals(0, newState.audioSettings.totalVisualDelayMs)
    }

    @Test
    fun setBluetoothDelayMs_doesNotPersistTotalVisualDelayMs() {
        val (_, effects) = reduce(intent = RgbIntent.SetBluetoothDelayMs(75))

        assertFalse(effects.any { it is AudioSideEffect.SaveAudioPrefInt && it.key == "total_visual_delay_ms" })
    }

    // ========================================================================
    // ResetAudioPipelineSettings
    // ========================================================================

    @Test
    fun resetAudioPipelineSettings_restoresAllDefaultsIncludingTotalVisualDelayIsZero() {
        val dirty = RgbUiState().let {
            it.copy(audioSettings = it.audioSettings.copy(
                audioSmoothingAttack = 0.1f,
                bluetoothDelayMs = 999,
                totalVisualDelayMs = 9999,
                visualizerPreset = "Custom",
                bassGain = 5f
            ))
        }
        val (newState, effects) = reduce(state = dirty, intent = RgbIntent.ResetAudioPipelineSettings)

        val audio = newState.audioSettings
        assertEquals(0.85f, audio.audioSmoothingAttack)
        assertEquals(0.12f, audio.audioSmoothingDecay)
        assertEquals(16, audio.transmissionDelayMs)
        assertEquals(5.0f, audio.noiseGateThreshold)
        assertEquals(1.3f, audio.beatThresholdMultiplier)
        assertEquals(250, audio.beatCooldownMs)
        assertEquals(1.0f, audio.bassGain)
        assertEquals(1.0f, audio.midGain)
        assertEquals(1.0f, audio.highGain)
        assertTrue(audio.isAutoGainEnabled)
        assertTrue(audio.isPaletteCyclingEnabled)
        assertTrue(audio.isLogarithmicScalingEnabled)
        assertEquals(0, audio.bluetoothDelayMs)
        assertEquals(0, audio.totalVisualDelayMs)
        assertEquals("Default", audio.visualizerPreset)
        assertEquals(0.45f, audio.audioGammaExponent)
        assertEquals(0.3f, audio.audioFlashStrength)
        assertEquals(0.15f, audio.visualizerMinBrightness)
        assertEquals(1.0f, audio.visualizerColorSpeed)
        assertEquals(200f, audio.beatFlashDecayMs)
        assertEquals(0.40f, audio.ambientCapFraction)
        assertEquals(0.25f, audio.midFluxWeight)
        assertEquals(0.6f, audio.flashFloor)
        assertEquals(0.4f, audio.flashRange)
        assertEquals(2500L, audio.idleTriggerDelayMs)

        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefInt("bluetooth_delay_ms", 0)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Default")))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("flash_floor", 0.6f)))
        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("flash_range", 0.4f)))
    }

    @Test
    fun resetAudioPipelineSettings_persistsMidFluxWeight() {
        // RgbControllerViewModel.resetAudioPipelineSettings() (line 2938) does persist
        // mid_flux_weight to prefs, unlike several other reset fields modeled elsewhere as
        // state-only. A prior source-diff pass missed this write; verified directly against
        // the real ViewModel method during Stage 4 wiring.
        val (_, effects) = reduce(intent = RgbIntent.ResetAudioPipelineSettings)

        assertTrue(effects.contains(AudioSideEffect.SaveAudioPrefFloat("mid_flux_weight", 0.25f)))
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
