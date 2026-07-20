package com.example.presentation

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
class AmbianceSettingsReducerTest {

    private fun reduce(
        state: RgbUiState = RgbUiState(),
        intent: RgbIntent
    ): Pair<RgbUiState, List<AmbianceSideEffect>> = ambianceSettingsReducer(state, intent)

    // ========================================================================
    // Single-value setters — each forces ambiancePreset to "Custom" and
    // persists both the changed value and the "Custom" preset override.
    // ========================================================================

    @Test
    fun setAmbianceResponseSpeed_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceResponseSpeed(0.75f))

        assertEquals(0.75f, newState.ambianceSettings.ambianceResponseSpeed)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("response_speed", 0.75f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
        assertFalse(effects.any { it is AmbianceSideEffect.CancelSceneChain })
    }

    @Test
    fun setAmbianceSmoothnessMs_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceSmoothnessMs(250))

        assertEquals(250, newState.ambianceSettings.ambianceSmoothnessMs)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefInt("smoothness_ms", 250)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
    }

    @Test
    fun setAmbianceSaturationBoost_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceSaturationBoost(2.0f))

        assertEquals(2.0f, newState.ambianceSettings.ambianceSaturationBoost)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("saturation_boost", 2.0f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
    }

    @Test
    fun setAmbianceBrightnessCompensation_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceBrightnessCompensation(1.5f))

        assertEquals(1.5f, newState.ambianceSettings.ambianceBrightnessCompensation)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("brightness_compensation", 1.5f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
    }

    @Test
    fun setAmbianceUpdateRateCapFps_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceUpdateRateCapFps(30))

        assertEquals(30, newState.ambianceSettings.ambianceUpdateRateCapFps)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefInt("update_rate_cap_fps", 30)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
    }

    @Test
    fun setAmbianceSceneCutSensitivity_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceSceneCutSensitivity(75.0f))

        assertEquals(75.0f, newState.ambianceSettings.ambianceSceneCutSensitivity)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("scene_cut_sensitivity", 75.0f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
    }

    @Test
    fun setAmbianceNoiseDeadband_updatesStateAndPersistsCustomPreset() {
        val (newState, effects) = reduce(intent = RgbIntent.SetAmbianceNoiseDeadband(0.25f))

        assertEquals(0.25f, newState.ambianceSettings.ambianceNoiseDeadband)
        assertEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("noise_deadband", 0.25f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")))
    }

    @Test
    fun setAmbianceResponseSpeed_leavesOtherAmbianceFieldsUntouched() {
        val initial = RgbUiState().let {
            it.copy(ambianceSettings = it.ambianceSettings.copy(
                ambianceSmoothnessMs = 999,
                ambianceUpdateRateCapFps = 5
            ))
        }
        val (newState, _) = reduce(state = initial, intent = RgbIntent.SetAmbianceResponseSpeed(0.1f))

        assertEquals(999, newState.ambianceSettings.ambianceSmoothnessMs)
        assertEquals(5, newState.ambianceSettings.ambianceUpdateRateCapFps)
    }

    // ========================================================================
    // ApplyAmbiancePreset — the one intent that does NOT force "Custom" and
    // additionally cancels any running scene chain. Does not touch
    // ambianceUpdateRateCapFps (verified absent from both state update and
    // pref writes in RgbControllerViewModel.applyAmbiancePreset()).
    // ========================================================================

    @Test
    fun applyAmbiancePreset_updatesAllSevenFieldsAndSetsPresetId() {
        val (newState, _) = reduce(
            intent = RgbIntent.ApplyAmbiancePreset(
                presetId = "Cinema",
                responseSpeed = 0.3f,
                smoothnessMs = 400,
                saturationBoost = 1.8f,
                brightnessCompensation = 0.9f,
                sceneCutSensitivity = 60.0f,
                noiseDeadband = 0.05f
            )
        )

        assertEquals("Cinema", newState.ambianceSettings.ambiancePreset)
        assertEquals(0.3f, newState.ambianceSettings.ambianceResponseSpeed)
        assertEquals(400, newState.ambianceSettings.ambianceSmoothnessMs)
        assertEquals(1.8f, newState.ambianceSettings.ambianceSaturationBoost)
        assertEquals(0.9f, newState.ambianceSettings.ambianceBrightnessCompensation)
        assertEquals(60.0f, newState.ambianceSettings.ambianceSceneCutSensitivity)
        assertEquals(0.05f, newState.ambianceSettings.ambianceNoiseDeadband)
    }

    @Test
    fun applyAmbiancePreset_doesNotTouchUpdateRateCapFps() {
        val initial = RgbUiState().let {
            it.copy(ambianceSettings = it.ambianceSettings.copy(ambianceUpdateRateCapFps = 12))
        }
        val (newState, effects) = reduce(
            state = initial,
            intent = RgbIntent.ApplyAmbiancePreset(
                presetId = "Balanced", responseSpeed = 0.5f, smoothnessMs = 150,
                saturationBoost = 1.4f, brightnessCompensation = 1.0f,
                sceneCutSensitivity = 110.0f, noiseDeadband = 0.10f
            )
        )

        assertEquals(12, newState.ambianceSettings.ambianceUpdateRateCapFps)
        assertFalse(effects.any { it is AmbianceSideEffect.SaveAmbiancePrefInt && it.key == "update_rate_cap_fps" })
    }

    @Test
    fun applyAmbiancePreset_cancelsSceneChainAndPersistsAllSevenPrefs() {
        val (_, effects) = reduce(
            intent = RgbIntent.ApplyAmbiancePreset(
                presetId = "Party", responseSpeed = 0.9f, smoothnessMs = 50,
                saturationBoost = 2.0f, brightnessCompensation = 1.2f,
                sceneCutSensitivity = 30.0f, noiseDeadband = 0.02f
            )
        )

        assertTrue(effects.contains(AmbianceSideEffect.CancelSceneChain))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("response_speed", 0.9f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefInt("smoothness_ms", 50)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("saturation_boost", 2.0f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("brightness_compensation", 1.2f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("scene_cut_sensitivity", 30.0f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefFloat("noise_deadband", 0.02f)))
        assertTrue(effects.contains(AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Party")))
        assertEquals(8, effects.size)
    }

    @Test
    fun applyAmbiancePreset_doesNotForceCustomPreset() {
        val (newState, _) = reduce(
            intent = RgbIntent.ApplyAmbiancePreset(
                presetId = "Balanced", responseSpeed = 0.5f, smoothnessMs = 150,
                saturationBoost = 1.4f, brightnessCompensation = 1.0f,
                sceneCutSensitivity = 110.0f, noiseDeadband = 0.10f
            )
        )

        assertNotEquals("Custom", newState.ambianceSettings.ambiancePreset)
        assertEquals("Balanced", newState.ambianceSettings.ambiancePreset)
    }

    // ========================================================================
    // WriteAmbianceColor / SetAmbianceCaptureActive — pure BLE passthroughs
    // routed through dispatch() instead of the getActiveInstance() escape
    // hatch (AmbianceCaptureService/AmbianceOutputInterpolator). No
    // RgbUiState field is touched by either intent.
    // ========================================================================

    @Test
    fun writeAmbianceColor_producesWriteColorEffectAndLeavesStateUnchanged() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.WriteAmbianceColor(10, 20, 30))

        assertEquals(initial, newState)
        assertEquals(listOf(AmbianceSideEffect.WriteColor(10, 20, 30)), effects)
    }

    @Test
    fun setAmbianceCaptureActive_true_producesPhoneMicToggleOnBroadcast() {
        val initial = RgbUiState()
        val (newState, effects) = reduce(state = initial, intent = RgbIntent.SetAmbianceCaptureActive(true))

        assertEquals(initial, newState)
        assertEquals(
            listOf(AmbianceSideEffect.BroadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(true))),
            effects
        )
    }

    @Test
    fun setAmbianceCaptureActive_false_producesPhoneMicToggleOffBroadcast() {
        val (_, effects) = reduce(intent = RgbIntent.SetAmbianceCaptureActive(false))

        assertEquals(
            listOf(AmbianceSideEffect.BroadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(false))),
            effects
        )
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
