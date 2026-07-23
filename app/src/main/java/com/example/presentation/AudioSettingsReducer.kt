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

private data class VisualizerConfig(
    val attack: Float, val decay: Float, val flash: Float, val gamma: Float, val idleDelay: Long,
    val noiseGate: Float, val bassGain: Float,
    val midGain: Float, val highGain: Float, val paletteCycling: Boolean, val beatMult: Float,
    val minBrightness: Float, val colorSpeed: Float,
    val beatFlashDecayMs: Float, val ambientCapFraction: Float, val midFluxWeight: Float,
    // Mapping-layer stage 1 (see mapping-proposal-audio-to-led-2026-07-21.md §6): flashFloor/
    // flashRange replace AudioDspProcessor's previously hard-coded 0.6f/0.4f.
    val flashFloor: Float = 0.6f, val flashRange: Float = 0.4f,
    // Mapping-layer stage 2 — anchor+breath hue model (§4/§5). See the per-field doc comments on
    // the matching AudioSettingsState properties for what each one means; values below implement
    // the §5 per-preset table.
    val anchorBeatsPerAdvance: Int = 1,
    val anchorTimerMs: Long = 0L,
    val hueAnchorJumpDeg: Float = 60f,
    val hueJumpConfidenceGate: Float = 0.35f,
    val hueBreathRangeDeg: Float = 25f,
    val breathUsesBassRatio: Boolean = false,
    val hueDriftDegPerSec: Float = 4f,
    val hueDegreesPerBeat: Float = 0f,
    val sustainResponse: String = "HUE_SHIFT",
    val sustainRampMs: Float = 2000f,
    val whiteFlashRecoveryMs: Float = 1000f
)

// Mirrors RgbControllerViewModel.setVisualizerPreset()'s per-preset value table (lines 2685-2693) verbatim
// for the original 16 fields. flashFloor/flashRange/anchor.../hueBreath.../sustain.../whiteFlashRecoveryMs
// are new (mapping-proposal-audio-to-led-2026-07-21.md §5/§6) — there is no ViewModel counterpart for
// those yet, this reducer is their first home.
private fun visualizerConfigFor(preset: String): VisualizerConfig = when (preset) {
    "Punchy" -> VisualizerConfig(
        attack = 0.95f, decay = 0.35f, flash = 0.6f, gamma = 0.35f, idleDelay = 2000L,
        noiseGate = 8.0f, bassGain = 1.2f, midGain = 1.0f, highGain = 1.0f, paletteCycling = true,
        beatMult = 1.3f, minBrightness = 0.15f, colorSpeed = 1.5f, beatFlashDecayMs = 140f,
        ambientCapFraction = 0.40f, midFluxWeight = 0.20f,
        flashFloor = 0.5f, flashRange = 0.5f,
        anchorBeatsPerAdvance = 1, hueAnchorJumpDeg = 90f, hueJumpConfidenceGate = 0.45f,
        hueBreathRangeDeg = 10f, hueDriftDegPerSec = 0f,
        sustainResponse = "SAT_BOOST", sustainRampMs = 800f
    )
    "Smooth Flow" -> VisualizerConfig(
        attack = 0.25f, decay = 0.08f, flash = 0.00f, gamma = 0.45f, idleDelay = 2800L,
        noiseGate = 4.0f, bassGain = 1.0f, midGain = 1.1f, highGain = 1.0f, paletteCycling = true,
        beatMult = 1.8f, minBrightness = 0.35f, colorSpeed = 1.2f, beatFlashDecayMs = 300f,
        // Brightness tuning pass (2026-07-21): still read as too dim after the first pass raised
        // ambientCapFraction/minBrightness from 0.45f/0.18f to 0.65f/0.30f — confirmed on-device.
        // Pushed to match Ambient Chill's 0.75f/0.35f exactly, since Smooth Flow has no beat flash
        // (flash = 0.00f, same as Ambient Chill) and these two fields are the only brightness
        // levers available to a no-flash preset. Smooth Flow's identity is preserved by its other
        // fields (drift/breath hue motion, beatMult = 1.8f vs Ambient Chill's 2.5f), not by
        // holding these two lower.
        ambientCapFraction = 0.75f, midFluxWeight = 0.30f,
        // Never advances the anchor on beats — the tempo-lock showcase: all color motion is
        // continuous, clocked to hueDegreesPerBeat, with hueDriftDegPerSec as the fallback rate
        // before/without a BPM lock.
        anchorBeatsPerAdvance = 0, hueAnchorJumpDeg = 0f, hueJumpConfidenceGate = 1.0f,
        hueBreathRangeDeg = 40f, hueDriftDegPerSec = 6f, hueDegreesPerBeat = 1.5f,
        sustainResponse = "HUE_SHIFT", sustainRampMs = 3000f
    )
    "Strobe Blast" -> VisualizerConfig(
        attack = 1.0f, decay = 0.85f, flash = 1.0f, gamma = 0.2f, idleDelay = 1500L,
        noiseGate = 10.0f, bassGain = 1.0f, midGain = 1.0f, highGain = 1.5f, paletteCycling = true,
        beatMult = 1.2f, minBrightness = 0.10f, colorSpeed = 2.0f, beatFlashDecayMs = 90f,
        ambientCapFraction = 0.25f, midFluxWeight = 0.15f,
        flashFloor = 0.7f, flashRange = 0.3f,
        anchorBeatsPerAdvance = 1, hueAnchorJumpDeg = 120f, hueJumpConfidenceGate = 0.50f,
        hueBreathRangeDeg = 0f, hueDriftDegPerSec = 0f,
        sustainResponse = "NONE", sustainRampMs = 0f, whiteFlashRecoveryMs = 250f
    )
    "Ambient Chill" -> VisualizerConfig(
        attack = 0.10f, decay = 0.02f, flash = 0.00f, gamma = 0.65f, idleDelay = 5000L,
        noiseGate = 3.0f, bassGain = 1.0f, midGain = 0.7f, highGain = 0.4f, paletteCycling = true,
        beatMult = 2.5f, minBrightness = 0.35f, colorSpeed = 0.15f, beatFlashDecayMs = 600f,
        ambientCapFraction = 0.75f, midFluxWeight = 0.20f,
        // The anti-transient preset: long-arc anchor advance (every 16 confident beats, or a 30s
        // timer if the detector never locks a tempo) instead of recoloring on every beat.
        anchorBeatsPerAdvance = 16, anchorTimerMs = 30_000L, hueAnchorJumpDeg = 60f,
        hueJumpConfidenceGate = 0.50f, hueBreathRangeDeg = 45f, hueDriftDegPerSec = 1.5f,
        sustainResponse = "BRIGHTNESS_SWELL", sustainRampMs = 3000f
    )
    "Bass Thump" -> VisualizerConfig(
        attack = 0.90f, decay = 0.20f, flash = 0.5f, gamma = 0.4f, idleDelay = 2500L,
        noiseGate = 6.0f, bassGain = 2.0f, midGain = 0.6f, highGain = 0.4f, paletteCycling = true,
        beatMult = 1.4f, minBrightness = 0.15f, colorSpeed = 1.0f, beatFlashDecayMs = 170f,
        ambientCapFraction = 0.45f, midFluxWeight = 0.05f,
        flashFloor = 0.4f, flashRange = 0.6f,
        anchorBeatsPerAdvance = 1, hueAnchorJumpDeg = 60f, hueJumpConfidenceGate = 0.40f,
        hueBreathRangeDeg = 15f, breathUsesBassRatio = true, hueDriftDegPerSec = 2f,
        sustainResponse = "SAT_BOOST", sustainRampMs = 800f
    )
    "Laser Sharp" -> VisualizerConfig(
        attack = 1.0f, decay = 0.95f, flash = 0.8f, gamma = 0.3f, idleDelay = 1500L,
        noiseGate = 12.0f, bassGain = 1.0f, midGain = 1.0f, highGain = 1.0f, paletteCycling = true,
        beatMult = 1.1f, minBrightness = 0.10f, colorSpeed = 3.0f, beatFlashDecayMs = 70f,
        ambientCapFraction = 0.20f, midFluxWeight = 0.10f,
        flashFloor = 0.7f, flashRange = 0.3f,
        anchorBeatsPerAdvance = 1, hueAnchorJumpDeg = 180f, hueJumpConfidenceGate = 0.60f,
        hueBreathRangeDeg = 0f, hueDriftDegPerSec = 0f,
        sustainResponse = "NONE", sustainRampMs = 0f, whiteFlashRecoveryMs = 120f
    )
    "Beat Only" -> VisualizerConfig(
        attack = 1.0f, decay = 0.9f, flash = 1.0f, gamma = 0.3f, idleDelay = 1500L,
        noiseGate = 10.0f, bassGain = 1.0f, midGain = 1.0f, highGain = 1.0f, paletteCycling = false,
        beatMult = 1.2f, minBrightness = 0.05f, colorSpeed = 2.0f, beatFlashDecayMs = 90f,
        ambientCapFraction = 0.0f, midFluxWeight = 0.15f,
        anchorBeatsPerAdvance = 1,
        // Golden angle (~360/phi^2): consecutive anchor jumps never repeat or cycle visibly in
        // short order, unlike a plain fraction of 360 (e.g. 60°'s 6-beat repeat) — replaces
        // palette cycling (already off for this preset) as the source of per-beat color variety.
        hueAnchorJumpDeg = 137.5f, hueJumpConfidenceGate = 0.40f,
        hueBreathRangeDeg = 0f, hueDriftDegPerSec = 0f,
        sustainResponse = "NONE", sustainRampMs = 0f, whiteFlashRecoveryMs = 250f
    )
    else -> VisualizerConfig(
        attack = 0.85f, decay = 0.12f, flash = 0.3f, gamma = 0.45f, idleDelay = 2500L,
        noiseGate = 5.0f, bassGain = 1.0f, midGain = 1.0f, highGain = 1.0f, paletteCycling = true,
        beatMult = 1.6f, minBrightness = 0.15f, colorSpeed = 1.0f, beatFlashDecayMs = 200f,
        ambientCapFraction = 0.40f, midFluxWeight = 0.25f,
        anchorBeatsPerAdvance = 2, hueAnchorJumpDeg = 60f, hueJumpConfidenceGate = 0.35f,
        hueBreathRangeDeg = 25f, hueDriftDegPerSec = 4f,
        sustainResponse = "HUE_SHIFT", sustainRampMs = 2000f
    )
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
                    midFluxWeight = config.midFluxWeight,
                    flashFloor = config.flashFloor,
                    flashRange = config.flashRange,
                    anchorBeatsPerAdvance = config.anchorBeatsPerAdvance,
                    anchorTimerMs = config.anchorTimerMs,
                    hueAnchorJumpDeg = config.hueAnchorJumpDeg,
                    hueJumpConfidenceGate = config.hueJumpConfidenceGate,
                    hueBreathRangeDeg = config.hueBreathRangeDeg,
                    breathUsesBassRatio = config.breathUsesBassRatio,
                    hueDriftDegPerSec = config.hueDriftDegPerSec,
                    hueDegreesPerBeat = config.hueDegreesPerBeat,
                    sustainResponse = config.sustainResponse,
                    sustainRampMs = config.sustainRampMs,
                    whiteFlashRecoveryMs = config.whiteFlashRecoveryMs
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
                AudioSideEffect.SaveAudioPrefFloat("mid_flux_weight", config.midFluxWeight),
                AudioSideEffect.SaveAudioPrefFloat("flash_floor", config.flashFloor),
                AudioSideEffect.SaveAudioPrefFloat("flash_range", config.flashRange),
                AudioSideEffect.SaveAudioPrefInt("anchor_beats_per_advance", config.anchorBeatsPerAdvance),
                AudioSideEffect.SaveAudioPrefLong("anchor_timer_ms", config.anchorTimerMs),
                AudioSideEffect.SaveAudioPrefFloat("hue_anchor_jump_deg", config.hueAnchorJumpDeg),
                AudioSideEffect.SaveAudioPrefFloat("hue_jump_confidence_gate", config.hueJumpConfidenceGate),
                AudioSideEffect.SaveAudioPrefFloat("hue_breath_range_deg", config.hueBreathRangeDeg),
                AudioSideEffect.SaveAudioPrefBoolean("breath_uses_bass_ratio", config.breathUsesBassRatio),
                AudioSideEffect.SaveAudioPrefFloat("hue_drift_deg_per_sec", config.hueDriftDegPerSec),
                AudioSideEffect.SaveAudioPrefFloat("hue_degrees_per_beat", config.hueDegreesPerBeat),
                AudioSideEffect.SaveAudioPrefString("sustain_response", config.sustainResponse),
                AudioSideEffect.SaveAudioPrefFloat("sustain_ramp_ms", config.sustainRampMs),
                AudioSideEffect.SaveAudioPrefFloat("white_flash_recovery_ms", config.whiteFlashRecoveryMs)
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

        // totalVisualDelayMs mirrors bluetoothDelayMs directly, not bluetoothDelayMs +
        // BeatDetector's lookaheadMs: the calibration flash that produces this value is sent
        // via broadcastCommandDirect, bypassing queueCommand's totalVisualDelayMs delay
        // entirely, so the calibrated value already IS the full delay to apply. Only
        // bluetooth_delay_ms is persisted — totalVisualDelayMs is not written to prefs
        // in the source either.
        //
        // visualizer-review-2026-07-22.md C4/B3: flashTimingOffsetMs is now derived from this
        // same value too, not tuned independently. The math: AudioDspProcessor schedules a
        // predictive flash's calculation at `nextPredictedBeatMs - flashTimingOffsetMs`, and
        // queueAudioResult then delays that frame's broadcast by `totalVisualDelayMs`
        // (== bluetoothDelayMs) before it ever reaches the wire. The photon therefore actually
        // appears at `nextPredictedBeatMs - flashTimingOffsetMs + bluetoothDelayMs` -- which only
        // lands exactly on the predicted true beat when flashTimingOffsetMs == bluetoothDelayMs.
        // Previously these were two independently by-ear-tuned sliders (bluetoothDelayMs via the
        // existing tap-to-sync calibration flow, flashTimingOffsetMs via its own raw 0-300ms
        // Settings slider) that had no reason to ever land on the same value by accident -- tuning
        // one without retuning the other by exactly the same amount reintroduced a timing offset.
        // They're now the same measured number by construction; the standalone
        // SetFlashTimingOffsetMs intent below is left in place (unused by UI as of this fix) as a
        // programmatic override, not removed outright.
        is RgbIntent.SetBluetoothDelayMs -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(
                    bluetoothDelayMs = intent.value,
                    totalVisualDelayMs = intent.value,
                    flashTimingOffsetMs = intent.value
                )
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefInt("bluetooth_delay_ms", intent.value),
                AudioSideEffect.SaveAudioPrefInt("flash_timing_offset_ms", intent.value)
            )
            newState to effects
        }

        // Same by-ear calibration pattern as SetBluetoothDelayMs above — see the field's doc
        // comment on AudioSettingsState for what it drives. Unused by UI as of C4/B3 (see
        // SetBluetoothDelayMs above) -- kept as a programmatic override, not removed.
        is RgbIntent.SetFlashTimingOffsetMs -> {
            val newState = state.copy(
                audioSettings = state.audioSettings.copy(flashTimingOffsetMs = intent.value)
            )
            val effects = listOf(
                AudioSideEffect.SaveAudioPrefInt("flash_timing_offset_ms", intent.value)
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
                    totalVisualDelayMs = 0,
                    // visualizer-review-2026-07-22.md C4/B3: unified with bluetoothDelayMs (was
                    // 100, its own independent default) — see SetBluetoothDelayMs's doc comment.
                    flashTimingOffsetMs = 0,
                    visualizerPreset = "Default",
                    audioGammaExponent = 0.45f,
                    audioFlashStrength = 0.3f,
                    visualizerMinBrightness = 0.15f,
                    visualizerColorSpeed = 1.0f,
                    beatFlashDecayMs = 200f,
                    ambientCapFraction = 0.40f,
                    midFluxWeight = 0.25f,
                    flashFloor = 0.6f,
                    flashRange = 0.4f,
                    // Resets to "Default"/Balanced's anchor+breath values, matching the reset of
                    // visualizerPreset itself below.
                    anchorBeatsPerAdvance = 2,
                    anchorTimerMs = 0L,
                    hueAnchorJumpDeg = 60f,
                    hueJumpConfidenceGate = 0.35f,
                    hueBreathRangeDeg = 25f,
                    breathUsesBassRatio = false,
                    hueDriftDegPerSec = 4f,
                    hueDegreesPerBeat = 0f,
                    sustainResponse = "HUE_SHIFT",
                    sustainRampMs = 2000f,
                    whiteFlashRecoveryMs = 1000f,
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
                // visualizer-review-2026-07-22.md C4/B3: unified with bluetoothDelayMs (was 100).
                AudioSideEffect.SaveAudioPrefInt("flash_timing_offset_ms", 0),
                AudioSideEffect.SaveAudioPrefString("visualizer_preset", "Default"),
                AudioSideEffect.SaveAudioPrefFloat("audio_gamma_exponent", 0.45f),
                AudioSideEffect.SaveAudioPrefFloat("audio_flash_strength", 0.3f),
                AudioSideEffect.SaveAudioPrefFloat("visualizer_min_brightness", 0.15f),
                AudioSideEffect.SaveAudioPrefFloat("visualizer_color_speed", 1.0f),
                AudioSideEffect.SaveAudioPrefFloat("beat_flash_decay_ms", 200f),
                AudioSideEffect.SaveAudioPrefFloat("ambient_cap_fraction", 0.40f),
                AudioSideEffect.SaveAudioPrefFloat("mid_flux_weight", 0.25f),
                AudioSideEffect.SaveAudioPrefFloat("flash_floor", 0.6f),
                AudioSideEffect.SaveAudioPrefFloat("flash_range", 0.4f),
                AudioSideEffect.SaveAudioPrefInt("anchor_beats_per_advance", 2),
                AudioSideEffect.SaveAudioPrefLong("anchor_timer_ms", 0L),
                AudioSideEffect.SaveAudioPrefFloat("hue_anchor_jump_deg", 60f),
                AudioSideEffect.SaveAudioPrefFloat("hue_jump_confidence_gate", 0.35f),
                AudioSideEffect.SaveAudioPrefFloat("hue_breath_range_deg", 25f),
                AudioSideEffect.SaveAudioPrefBoolean("breath_uses_bass_ratio", false),
                AudioSideEffect.SaveAudioPrefFloat("hue_drift_deg_per_sec", 4f),
                AudioSideEffect.SaveAudioPrefFloat("hue_degrees_per_beat", 0f),
                AudioSideEffect.SaveAudioPrefString("sustain_response", "HUE_SHIFT"),
                AudioSideEffect.SaveAudioPrefFloat("sustain_ramp_ms", 2000f),
                AudioSideEffect.SaveAudioPrefFloat("white_flash_recovery_ms", 1000f),
                AudioSideEffect.SaveAudioPrefLong("idle_trigger_delay_ms", 2500L)
            )
            newState to effects
        }

        else -> state to emptyList()
    }
}
