package com.example.presentation

import com.example.RgbUiState
import com.example.RgbIntent
import com.example.core.protocol.DuoCoProtocol

// ============================================================================
// AMBIANCE SIDE EFFECTS — side effects triggered by ambiance settings transitions
// ============================================================================

sealed interface AmbianceSideEffect {
    data class SaveAmbiancePrefFloat(val key: String, val value: Float) : AmbianceSideEffect
    data class SaveAmbiancePrefInt(val key: String, val value: Int) : AmbianceSideEffect
    data class SaveAmbiancePrefString(val key: String, val value: String) : AmbianceSideEffect
    object CancelSceneChain : AmbianceSideEffect
    // Pure BLE passthrough for the captured-frame color (mirrors the real writeAmbianceColor()'s
    // AmbianceCaptureState.isActive guard + broadcastCommandDirect call) — no RgbUiState field touched.
    data class WriteColor(val r: Int, val g: Int, val b: Int) : AmbianceSideEffect
    // Pure BLE passthrough for the capture-session phone-mic toggle (mirrors the real
    // broadcastCommand() call previously made directly by AmbianceCaptureService).
    data class BroadcastCommand(val command: ByteArray) : AmbianceSideEffect {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BroadcastCommand) return false
            return command.contentEquals(other.command)
        }
        override fun hashCode(): Int = command.contentHashCode()
    }
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

        is RgbIntent.WriteAmbianceColor -> {
            state to listOf(AmbianceSideEffect.WriteColor(intent.r, intent.g, intent.b))
        }

        is RgbIntent.SetAmbianceCaptureActive -> {
            state to listOf(AmbianceSideEffect.BroadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(intent.active)))
        }

        else -> state to emptyList()
    }
}
