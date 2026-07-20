package com.example.presentation

import com.example.RgbUiState
import com.example.RgbIntent

// ============================================================================
// AMBIANCE SIDE EFFECTS — side effects triggered by ambiance settings transitions
// ============================================================================

sealed interface AmbianceSideEffect {
    data class SaveAmbiancePrefFloat(val key: String, val value: Float) : AmbianceSideEffect
    data class SaveAmbiancePrefInt(val key: String, val value: Int) : AmbianceSideEffect
    data class SaveAmbiancePrefString(val key: String, val value: String) : AmbianceSideEffect
    object CancelSceneChain : AmbianceSideEffect
}

// Mirrors RgbControllerViewModel's setAmbiance*() family (lines 2775-2845): each of the seven
// single-value setters forces ambiancePreset to "Custom" and persists both the changed value
// and the "Custom" preset override. ApplyAmbiancePreset is the one exception — it sets
// ambiancePreset to the applied presetId (not "Custom") and additionally cancels any running
// scene chain, since applying a preset is treated as a user-directed feature switch the same
// way SetPower/SetColor/etc. are in CoreControlsReducer.
fun ambianceSettingsReducer(
    state: RgbUiState,
    intent: RgbIntent
): Pair<RgbUiState, List<AmbianceSideEffect>> {
    return when (intent) {
        is RgbIntent.SetAmbianceResponseSpeed -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceResponseSpeed = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefFloat("response_speed", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAmbianceSmoothnessMs -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceSmoothnessMs = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefInt("smoothness_ms", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAmbianceSaturationBoost -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceSaturationBoost = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefFloat("saturation_boost", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAmbianceBrightnessCompensation -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceBrightnessCompensation = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefFloat("brightness_compensation", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAmbianceUpdateRateCapFps -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceUpdateRateCapFps = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefInt("update_rate_cap_fps", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAmbianceSceneCutSensitivity -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceSceneCutSensitivity = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefFloat("scene_cut_sensitivity", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAmbianceNoiseDeadband -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceNoiseDeadband = intent.value,
                    ambiancePreset = "Custom"
                )
            )
            val effects = listOf(
                AmbianceSideEffect.SaveAmbiancePrefFloat("noise_deadband", intent.value),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.ApplyAmbiancePreset -> {
            val newState = state.copy(
                ambianceSettings = state.ambianceSettings.copy(
                    ambianceResponseSpeed = intent.responseSpeed,
                    ambianceSmoothnessMs = intent.smoothnessMs,
                    ambianceSaturationBoost = intent.saturationBoost,
                    ambianceBrightnessCompensation = intent.brightnessCompensation,
                    ambianceSceneCutSensitivity = intent.sceneCutSensitivity,
                    ambianceNoiseDeadband = intent.noiseDeadband,
                    ambiancePreset = intent.presetId
                )
            )
            val effects = listOf(
                AmbianceSideEffect.CancelSceneChain,
                AmbianceSideEffect.SaveAmbiancePrefFloat("response_speed", intent.responseSpeed),
                AmbianceSideEffect.SaveAmbiancePrefInt("smoothness_ms", intent.smoothnessMs),
                AmbianceSideEffect.SaveAmbiancePrefFloat("saturation_boost", intent.saturationBoost),
                AmbianceSideEffect.SaveAmbiancePrefFloat("brightness_compensation", intent.brightnessCompensation),
                AmbianceSideEffect.SaveAmbiancePrefFloat("scene_cut_sensitivity", intent.sceneCutSensitivity),
                AmbianceSideEffect.SaveAmbiancePrefFloat("noise_deadband", intent.noiseDeadband),
                AmbianceSideEffect.SaveAmbiancePrefString("ambiance_preset", intent.presetId)
            )
            newState to effects
        }

        else -> state to emptyList()
    }
}
