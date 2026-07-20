package com.example.presentation

import com.example.RgbUiState
import com.example.RgbIntent
import com.example.ActiveDeviceState
import com.example.RgbControllerViewModel
import com.example.core.protocol.DuoCoProtocol

// ============================================================================
// AUDIO SIDE EFFECTS — side effects triggered by audio settings transitions
// ============================================================================

sealed interface AudioSideEffect {
    data class SaveAudioPrefFloat(val key: String, val value: Float) : AudioSideEffect
    data class SaveAudioPrefInt(val key: String, val value: Int) : AudioSideEffect
    data class SaveAudioPrefLong(val key: String, val value: Long) : AudioSideEffect
    data class SaveAudioPrefString(val key: String, val value: String) : AudioSideEffect
    data class SaveAudioPrefBoolean(val key: String, val value: Boolean) : AudioSideEffect
    data class BroadcastCommand(val command: ByteArray, val logMessage: String, val cancelRunningScenes: Boolean = true) : AudioSideEffect {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BroadcastCommand) return false
            return command.contentEquals(other.command) && logMessage == other.logMessage && cancelRunningScenes == other.cancelRunningScenes
        }
        override fun hashCode(): Int {
            var result = command.contentHashCode()
            result = 31 * result + logMessage.hashCode()
            result = 31 * result + cancelRunningScenes.hashCode()
            return result
        }
    }
    object CancelSceneChain : AudioSideEffect
    object ClearExclusionsIfNotApplyingScene : AudioSideEffect
    data class CancelSceneRunner(val address: String) : AudioSideEffect
    data class SaveDeviceState(val address: String, val automationType: RgbControllerViewModel.AutomationType) : AudioSideEffect
    data class RestoreDeviceState(val address: String, val automationType: RgbControllerViewModel.AutomationType) : AudioSideEffect
    data class ClearDeviceAutomationMode(val address: String) : AudioSideEffect
    data class StopMusicSyncInternal(val keepServiceRunning: Boolean) : AudioSideEffect
    data class StartAudioEngine(val mode: String) : AudioSideEffect
}

// Mirrors RgbControllerViewModel's BeatDetector default (private class, RgbControllerViewModel.kt:217-220):
// `BeatDetector().lookaheadMs` is always constructed with no args, so the effective value is always
// this default. The class itself is private to the ViewModel file and can't be referenced here.
private const val BEAT_DETECTOR_DEFAULT_LOOKAHEAD_MS = 180L

private data class VisualizerConfig(
    val attack: Float, val decay: Float, val flash: Float, val gamma: Float, val idleDelay: Long,
    val noiseGate: Float, val bassGain: Float,
    val midGain: Float, val highGain: Float, val paletteCycling: Boolean, val beatMult: Float,
    val minBrightness: Float, val colorSpeed: Float,
    val beatFlashDecayMs: Float, val ambientCapFraction: Float, val midFluxWeight: Float
)

// Mirrors RgbControllerViewModel.setVisualizerPreset()'s per-preset value table (lines 2685-2693) verbatim.
private fun visualizerConfigFor(preset: String): VisualizerConfig = when (preset) {
    "Punchy" -> VisualizerConfig(0.95f, 0.35f, 0.6f, 0.35f, 2000L, 8.0f, 1.2f, 1.0f, 1.0f, true, 1.3f, 0.15f, 1.5f, 140f, 0.40f, 0.20f)
    "Smooth Flow" -> VisualizerConfig(0.25f, 0.08f, 0.00f, 0.45f, 2800L, 4.0f, 1.0f, 1.1f, 1.0f, true, 1.8f, 0.18f, 1.2f, 300f, 0.45f, 0.30f)
    "Strobe Blast" -> VisualizerConfig(1.0f, 0.85f, 1.0f, 0.2f, 1500L, 10.0f, 1.0f, 1.0f, 1.5f, true, 1.2f, 0.10f, 2.0f, 90f, 0.25f, 0.15f)
    "Ambient Chill" -> VisualizerConfig(0.10f, 0.02f, 0.00f, 0.65f, 5000L, 3.0f, 1.0f, 0.7f, 0.4f, true, 2.5f, 0.35f, 0.15f, 600f, 0.75f, 0.20f)
    "Bass Thump" -> VisualizerConfig(0.90f, 0.20f, 0.5f, 0.4f, 2500L, 6.0f, 2.0f, 0.6f, 0.4f, true, 1.4f, 0.15f, 1.0f, 170f, 0.45f, 0.05f)
    "Laser Sharp" -> VisualizerConfig(1.0f, 0.95f, 0.8f, 0.3f, 1500L, 12.0f, 1.0f, 1.0f, 1.0f, true, 1.1f, 0.10f, 3.0f, 70f, 0.20f, 0.10f)
    "Beat Only" -> VisualizerConfig(1.0f, 0.9f, 1.0f, 0.3f, 1500L, 10.0f, 1.0f, 1.0f, 1.0f, false, 1.2f, 0.05f, 2.0f, 90f, 0.0f, 0.15f)
    else -> VisualizerConfig(0.85f, 0.12f, 0.3f, 0.45f, 2500L, 5.0f, 1.0f, 1.0f, 1.0f, true, 1.6f, 0.15f, 1.0f, 200f, 0.40f, 0.25f)
}

// Mirrors RgbControllerViewModel.getVisualizerPresetName() (lines 2652-2663).
private fun visualizerPresetDisplayName(id: String): String = when (id) {
    "Default" -> "Balanced"
    "Punchy" -> "Punchy"
    "Smooth Flow" -> "Smooth Flow"
    "Strobe Blast" -> "Strobe Blast"
    "Ambient Chill" -> "Ambient Chill"
    "Bass Thump" -> "Bass Thump"
    "Laser Sharp" -> "Laser Sharp"
    else -> id
}

// Mirrors RgbControllerViewModel.getLedPresetName() (lines 2638-2650).
private fun ledPresetDisplayName(id: String): String = when (id) {
    "energetic_1" -> "Energetic 1"
    "energetic_2" -> "Energetic 2"
    "rhythm_1" -> "Rhythm 1"
    "rhythm_2" -> "Rhythm 2"
    "spectrum_1" -> "Spectrum 1"
    "spectrum_2" -> "Spectrum 2"
    "rolling_1" -> "Rolling 1"
    "rolling_2" -> "Rolling 2"
    else -> "Unknown"
}

// Mirrors the onboard-BLE preset index table used in startMusicSync() (lines 3929-3939).
private fun onboardPresetIndexFor(mode: String): Int = when (mode) {
    "energetic_1" -> 0
    "energetic_2" -> 1
    "rhythm_1" -> 2
    "rhythm_2" -> 3
    "spectrum_1" -> 4
    "spectrum_2" -> 5
    "rolling_1" -> 6
    "rolling_2" -> 7
    else -> -1
}

fun audioSettingsReducer(
    state: RgbUiState,
    intent: RgbIntent,
    targetAddresses: List<String>,
    deviceAutomationMode: Map<String, RgbControllerViewModel.AutomationType>
): Pair<RgbUiState, List<AudioSideEffect>> {
    return when (intent) {
        is RgbIntent.SetVisualizerPreset -> {
            val currentMusicMode = state.audioSettings.musicMode
            val featureName = if (currentMusicMode == "phone_mic" || currentMusicMode == "on_device") {
                "Audio Visualiser (${visualizerPresetDisplayName(intent.preset)})"
            } else {
                state.coreControl.activeFeatureName
            }
            val config = visualizerConfigFor(intent.preset)

            val newState = state.copy(
                audioSettings = state.audioSettings.copy(
                    visualizerPreset = intent.preset,
                    audioSmoothingAttack = config.attack,
                    audioSmoothingDecay = config.decay,
                    audioFlashStrength = config.flash,
                    audioGammaExponent = config.gamma,
                    idleTriggerDelayMs = config.idleDelay,
                    noiseGateThreshold = config.noiseGate,
                    bassGain = config.bassGain,
                    midGain = config.midGain,
                    highGain = config.highGain,
                    isPaletteCyclingEnabled = config.paletteCycling,
                    beatThresholdMultiplier = config.beatMult,
                    visualizerMinBrightness = config.minBrightness,
                    visualizerColorSpeed = config.colorSpeed,
                    beatFlashDecayMs = config.beatFlashDecayMs,
                    ambientCapFraction = config.ambientCapFraction,
                    midFluxWeight = config.midFluxWeight
                ),
                coreControl = state.coreControl.copy(activeFeatureName = featureName)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", intent.preset),
                AudioSideEffect.SaveAudioPrefString("active_feature_name", featureName),
                AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_attack", config.attack),
                AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_decay", config.decay),
                AudioSideEffect.SaveAudioPrefFloat("audio_flash_strength", config.flash),
                AudioSideEffect.SaveAudioPrefFloat("audio_gamma_exponent", config.gamma),
                AudioSideEffect.SaveAudioPrefLong("idle_trigger_delay_ms", config.idleDelay),
                AudioSideEffect.SaveAudioPrefFloat("noise_gate_threshold", config.noiseGate),
                AudioSideEffect.SaveAudioPrefFloat("bass_gain", config.bassGain),
                AudioSideEffect.SaveAudioPrefFloat("mid_gain", config.midGain),
                AudioSideEffect.SaveAudioPrefFloat("high_gain", config.highGain),
                AudioSideEffect.SaveAudioPrefBoolean("is_palette_cycling_enabled", config.paletteCycling),
                AudioSideEffect.SaveAudioPrefFloat("beat_threshold_multiplier", config.beatMult),
                AudioSideEffect.SaveAudioPrefFloat("visualizer_min_brightness", config.minBrightness),
                AudioSideEffect.SaveAudioPrefFloat("visualizer_color_speed", config.colorSpeed),
                AudioSideEffect.SaveAudioPrefFloat("beat_flash_decay_ms", config.beatFlashDecayMs),
                AudioSideEffect.SaveAudioPrefFloat("ambient_cap_fraction", config.ambientCapFraction),
                AudioSideEffect.SaveAudioPrefFloat("mid_flux_weight", config.midFluxWeight)
            )
            newState to effects
        }

        // Mirrors RgbControllerViewModel.startMusicSync() (lines 3901-3951). Per-target-address
        // scene-runner teardown and saveDeviceState() calls, plus the hardware engine start/stop
        // (stopMusicSyncInternal / startAudioRecording), are modeled as effects the ViewModel executes —
        // they are not pure RgbUiState transitions.
        is RgbIntent.StartMusicSync -> {
            val mode = intent.mode
            val effects = mutableListOf<AudioSideEffect>()
            targetAddresses.forEach { address ->
                effects.add(AudioSideEffect.SaveDeviceState(address, RgbControllerViewModel.AutomationType.AUDIO))
                effects.add(AudioSideEffect.CancelSceneRunner(address))
            }
            effects.add(AudioSideEffect.CancelSceneChain)

            val featureName = if (mode == "phone_mic" || mode == "on_device") {
                "Audio Visualiser (${visualizerPresetDisplayName(state.audioSettings.visualizerPreset)})"
            } else {
                "LED Visualiser (${ledPresetDisplayName(mode)})"
            }

            val newDeviceStatesMap = state.connectivity.deviceStatesMap.toMutableMap()
            targetAddresses.forEach { address ->
                val existing = newDeviceStatesMap[address] ?: ActiveDeviceState()
                newDeviceStatesMap[address] = existing.copy(activeFeatureName = featureName)
            }

            val newState = state.copy(
                audioSettings = state.audioSettings.copy(musicMode = mode),
                coreControl = state.coreControl.copy(activeFeatureName = featureName),
                connectivity = state.connectivity.copy(deviceStatesMap = newDeviceStatesMap)
            )

            effects.add(AudioSideEffect.SaveAudioPrefString("active_feature_name", featureName))

            val keepServiceRunning = mode == "phone_mic" || mode == "on_device"
            effects.add(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning))

            if (keepServiceRunning) {
                effects.add(AudioSideEffect.StartAudioEngine(mode))
            } else {
                val presetIndex = onboardPresetIndexFor(mode)
                if (presetIndex != -1) {
                    effects.add(AudioSideEffect.BroadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(true), "Phone Mic Toggle ON"))
                    effects.add(AudioSideEffect.BroadcastCommand(DuoCoProtocol.createMicVisualizerStyleCommand(presetIndex), "Mic Visualizer Style Preset $presetIndex"))
                    effects.add(AudioSideEffect.BroadcastCommand(DuoCoProtocol.createMusicSensitivityCommand(state.audioSettings.musicSensitivity), "Phone Mic sensitivity ${state.audioSettings.musicSensitivity}"))
                }
            }

            newState to effects
        }

        // Mirrors RgbControllerViewModel.stopMusicSync() (lines 3953-3975). The per-address
        // deviceAutomationMode restore/clear loop is modeled as one effect per matching address.
        is RgbIntent.StopMusicSync -> {
            val previousMode = state.audioSettings.musicMode
            val effects = mutableListOf<AudioSideEffect>(AudioSideEffect.ClearExclusionsIfNotApplyingScene)

            val newState = state.copy(
                audioSettings = state.audioSettings.copy(musicMode = null)
            )

            if (previousMode != null) {
                effects.add(AudioSideEffect.BroadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(false), "Phone Mic Toggle OFF"))
            }

            effects.add(AudioSideEffect.StopMusicSyncInternal(keepServiceRunning = false))

            deviceAutomationMode.forEach { (address, mode) ->
                if (mode == RgbControllerViewModel.AutomationType.AUDIO) {
                    if (intent.restoreState) {
                        effects.add(AudioSideEffect.RestoreDeviceState(address, RgbControllerViewModel.AutomationType.AUDIO))
                    } else {
                        effects.add(AudioSideEffect.ClearDeviceAutomationMode(address))
                    }
                }
            }

            newState to effects
        }

        is RgbIntent.SetMusicSensitivity -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(musicSensitivity = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.BroadcastCommand(DuoCoProtocol.createMusicSensitivityCommand(intent.value), "Mic Sensitivity ${intent.value}")
            )
            newState to effects
        }

        is RgbIntent.SetAudioSmoothingAttack -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(audioSmoothingAttack = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_attack", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAudioSmoothingDecay -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(audioSmoothingDecay = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_decay", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAudioGammaExponent -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(audioGammaExponent = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("audio_gamma_exponent", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetAudioFlashStrength -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(audioFlashStrength = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("audio_flash_strength", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetVisualizerMinBrightness -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(visualizerMinBrightness = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("visualizer_min_brightness", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetVisualizerColorSpeed -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(visualizerColorSpeed = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("visualizer_color_speed", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetIdleTriggerDelayMs -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(idleTriggerDelayMs = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefLong("idle_trigger_delay_ms", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetTransmissionDelayMs -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(transmissionDelayMs = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefInt("transmission_delay_ms", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetNoiseGateThreshold -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(noiseGateThreshold = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("noise_gate_threshold", intent.value)
            )
            newState to effects
        }

        // Unlike the rest of the sensitivity/threshold group, this one also resets the preset to
        // "Custom" — preserved faithfully per RgbControllerViewModel.setBeatThresholdMultiplier() (2863-2867).
        is RgbIntent.SetBeatThresholdMultiplier -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(beatThresholdMultiplier = intent.value, visualizerPreset = "Custom")
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("beat_threshold_multiplier", intent.value),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Custom")
            )
            newState to effects
        }

        is RgbIntent.SetBeatCooldownMs -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(beatCooldownMs = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefInt("beat_cooldown_ms", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetBassGain -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(bassGain = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("bass_gain", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetMidGain -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(midGain = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("mid_gain", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetHighGain -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(highGain = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("high_gain", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetAutoGainEnabled -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(isAutoGainEnabled = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefBoolean("is_auto_gain_enabled", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetPaletteCyclingEnabled -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(isPaletteCyclingEnabled = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefBoolean("is_palette_cycling_enabled", intent.value)
            )
            newState to effects
        }

        is RgbIntent.SetLogarithmicScalingEnabled -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(isLogarithmicScalingEnabled = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefBoolean("is_logarithmic_scaling_enabled", intent.value)
            )
            newState to effects
        }

        // Derives totalVisualDelayMs = bluetoothDelayMs + BeatDetector().lookaheadMs inline, mirroring
        // RgbControllerViewModel.setBluetoothDelayMs() (2904-2910). Only bluetooth_delay_ms is persisted —
        // totalVisualDelayMs is not written to prefs in the source either.
        is RgbIntent.SetBluetoothDelayMs -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(
                    bluetoothDelayMs = intent.value,
                    totalVisualDelayMs = intent.value + BEAT_DETECTOR_DEFAULT_LOOKAHEAD_MS.toInt()
                )
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefInt("bluetooth_delay_ms", intent.value)
            )
            newState to effects
        }

        // Mirrors RgbControllerViewModel.resetAudioPipelineSettings() (2912-2962).
        is RgbIntent.ResetAudioPipelineSettings -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(
                    audioSmoothingAttack = 0.85f,
                    audioSmoothingDecay = 0.12f,
                    transmissionDelayMs = 16,
                    noiseGateThreshold = 5.0f,
                    beatThresholdMultiplier = 1.3f,
                    beatCooldownMs = 250,
                    bassGain = 1.0f,
                    midGain = 1.0f,
                    highGain = 1.0f,
                    isAutoGainEnabled = true,
                    isPaletteCyclingEnabled = true,
                    isLogarithmicScalingEnabled = true,
                    bluetoothDelayMs = 0,
                    totalVisualDelayMs = BEAT_DETECTOR_DEFAULT_LOOKAHEAD_MS.toInt(),
                    visualizerPreset = "Default",
                    audioGammaExponent = 0.45f,
                    audioFlashStrength = 0.3f,
                    visualizerMinBrightness = 0.15f,
                    visualizerColorSpeed = 1.0f,
                    beatFlashDecayMs = 200f,
                    ambientCapFraction = 0.40f,
                    midFluxWeight = 0.25f,
                    idleTriggerDelayMs = 2500L
                )
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_attack", 0.85f),
                AudioSideEffect.SaveAudioPrefFloat("audio_smoothing_decay", 0.12f),
                AudioSideEffect.SaveAudioPrefInt("transmission_delay_ms", 16),
                AudioSideEffect.SaveAudioPrefFloat("noise_gate_threshold", 5.0f),
                AudioSideEffect.SaveAudioPrefFloat("beat_threshold_multiplier", 1.3f),
                AudioSideEffect.SaveAudioPrefInt("beat_cooldown_ms", 250),
                AudioSideEffect.SaveAudioPrefFloat("bass_gain", 1.0f),
                AudioSideEffect.SaveAudioPrefFloat("mid_gain", 1.0f),
                AudioSideEffect.SaveAudioPrefFloat("high_gain", 1.0f),
                AudioSideEffect.SaveAudioPrefBoolean("is_auto_gain_enabled", true),
                AudioSideEffect.SaveAudioPrefBoolean("is_palette_cycling_enabled", true),
                AudioSideEffect.SaveAudioPrefBoolean("is_logarithmic_scaling_enabled", true),
                AudioSideEffect.SaveAudioPrefInt("bluetooth_delay_ms", 0),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Default"),
                AudioSideEffect.SaveAudioPrefFloat("audio_gamma_exponent", 0.45f),
                AudioSideEffect.SaveAudioPrefFloat("audio_flash_strength", 0.3f),
                AudioSideEffect.SaveAudioPrefFloat("visualizer_min_brightness", 0.15f),
                AudioSideEffect.SaveAudioPrefFloat("visualizer_color_speed", 1.0f),
                AudioSideEffect.SaveAudioPrefFloat("beat_flash_decay_ms", 200f),
                AudioSideEffect.SaveAudioPrefFloat("ambient_cap_fraction", 0.40f),
                AudioSideEffect.SaveAudioPrefFloat("mid_flux_weight", 0.25f),
                AudioSideEffect.SaveAudioPrefLong("idle_trigger_delay_ms", 2500L)
            )
            newState to effects
        }

        else -> state to emptyList()
    }
}
