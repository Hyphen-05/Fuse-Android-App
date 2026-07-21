package com.example

import com.example.BleConnectionState
import com.example.ScannedRgbDevice
import com.example.CctCorrectionProfile
import com.example.ActiveDeviceState
import com.example.db.ColorCalibration
import com.example.db.RgbPreset
import com.example.db.CustomMode
import com.example.domain.model.AppScene
import com.example.core.animation.ProceduralSceneParams

// ============================================================================
// UI STATE — reducer-driven, updates in response to intents/system events
// ============================================================================

data class ConnectivityState(
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDeviceAddress: String? = null,
    val connectedDeviceName: String? = null,
    val isScanning: Boolean = false,
    val scannedDevices: List<ScannedRgbDevice> = emptyList(),
    val deviceConnectionStates: Map<String, BleConnectionState> = emptyMap(),
    val devicePacingMs: Map<String, Int> = emptyMap(),
    val isTestPatternRunning: Map<String, Boolean> = emptyMap(),
    val deviceStatesMap: Map<String, ActiveDeviceState> = emptyMap()
)

data class CoreControlState(
    val isDbLoaded: Boolean = false,
    val isPowerOn: Boolean = true,
    val red: Int = 255,
    val green: Int = 0,
    val blue: Int = 128,
    val brightness: Int = 80,
    val modeIndex: Int = 0,
    val modeSpeed: Int = 50,
    val warmth: Int = 50,
    val activeFeatureName: String = "Colour",
    val isDemoMode: Boolean = true,
    val errorMessage: String? = null,
    val showFpsTracker: Boolean = false,
    // In-memory only (updateProtocolByte never touches prefs/DB) — category (a)
    val protocolBytes: ByteArray = byteArrayOf(
        0x7E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xEF.toByte()
    )
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CoreControlState
        if (isDbLoaded != other.isDbLoaded) return false
        if (isPowerOn != other.isPowerOn) return false
        if (red != other.red) return false
        if (green != other.green) return false
        if (blue != other.blue) return false
        if (brightness != other.brightness) return false
        if (modeIndex != other.modeIndex) return false
        if (modeSpeed != other.modeSpeed) return false
        if (warmth != other.warmth) return false
        if (activeFeatureName != other.activeFeatureName) return false
        if (isDemoMode != other.isDemoMode) return false
        if (errorMessage != other.errorMessage) return false
        if (showFpsTracker != other.showFpsTracker) return false
        if (!protocolBytes.contentEquals(other.protocolBytes)) return false
        return true
    }
    override fun hashCode(): Int {
        var result = isDbLoaded.hashCode()
        result = 31 * result + isPowerOn.hashCode()
        result = 31 * result + red
        result = 31 * result + green
        result = 31 * result + blue
        result = 31 * result + brightness
        result = 31 * result + modeIndex
        result = 31 * result + modeSpeed
        result = 31 * result + warmth
        result = 31 * result + activeFeatureName.hashCode()
        result = 31 * result + isDemoMode.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        result = 31 * result + showFpsTracker.hashCode()
        result = 31 * result + protocolBytes.contentHashCode()
        return result
    }
}

data class AudioSettingsState(
    val musicMode: String? = null,
    val musicSensitivity: Int = 50,
    val isAudioSyncRunning: Boolean = false,
    val isVisualizerIdle: Boolean = false,
    val audioSmoothingAttack: Float = 0.85f,
    val audioSmoothingDecay: Float = 0.12f,
    val beatFlashDecayMs: Float = 200f,
    val ambientCapFraction: Float = 0.40f,
    val midFluxWeight: Float = 0.25f,
    val transmissionDelayMs: Int = 16,
    val noiseGateThreshold: Float = 5.0f,
    val beatThresholdMultiplier: Float = 1.3f,
    val beatCooldownMs: Int = 250,
    val bassGain: Float = 1.0f,
    val midGain: Float = 1.0f,
    val highGain: Float = 1.0f,
    val isAutoGainEnabled: Boolean = true,
    val isPaletteCyclingEnabled: Boolean = true,
    val isLogarithmicScalingEnabled: Boolean = true,
    val bluetoothDelayMs: Int = 0,
    val totalVisualDelayMs: Int = 0,
    val visualizerPreset: String = "Default",
    val audioGammaExponent: Float = 0.45f,
    val audioFlashStrength: Float = 0.3f,
    val visualizerMinBrightness: Float = 0.15f,
    val visualizerColorSpeed: Float = 1.0f,
    // Beat flash peak = flashFloor + flashRange * strength * confidence. Split out of the
    // previously hard-coded 0.6f/0.4f (see item 16 in CLAUDE.md's phase log) so presets can
    // choose their own flash dynamic range — same formula shape, now parameterized.
    val flashFloor: Float = 0.6f,
    val flashRange: Float = 0.4f,
    // --- Mapping-layer stage 2: anchor+breath hue model (see mapping-proposal-audio-to-led-
    // 2026-07-21.md §4/§5). Replaces the old five-source additive hue pile (continuousHueOffset's
    // per-beat jump + drift, beatHueOffset's 180° decaying impulse, spectral tilt, binary sustain
    // shift, palette-index offset) with two bounded components: a discrete `anchor` that only
    // moves on qualifying events, and a bounded `breath` tilt around it.
    // Matches the "Default"/Balanced preset's table value (§5) so a fresh install with no
    // SetVisualizerPreset ever dispatched still starts consistent with that preset.
    val anchorBeatsPerAdvance: Int = 2,
    // Fallback anchor-advance timer (ms), used only when a preset wants color variety even
    // without confident beats (Ambient Chill's "16 beats, or 30s timer if no BPM lock"). 0 =
    // disabled. Not named in the proposal's own per-preset param table (§5) but required to
    // implement that one preset's documented fallback behavior.
    val anchorTimerMs: Long = 0L,
    val hueAnchorJumpDeg: Float = 60f,
    val hueJumpConfidenceGate: Float = 0.35f,
    val hueBreathRangeDeg: Float = 25f,
    // Bass Thump's breath is keyed to bassRatio instead of the default (midRatio - highRatio)
    // spectral tilt, per §5's per-preset reasoning ("keeps the color leaning with the low end its
    // identity is built on"). Not a named §5 param table column — a structural flag needed to
    // implement that one preset's documented divergence.
    val breathUsesBassRatio: Boolean = false,
    val hueDriftDegPerSec: Float = 4f,
    val hueDegreesPerBeat: Float = 0f,
    // One of "NONE" / "HUE_SHIFT" / "SAT_BOOST" / "BRIGHTNESS_SWELL" — see §4's sustain-response
    // list. String-typed to match the existing visualizerPreset/musicMode string-enum convention
    // in this state class rather than introducing a new sealed-type pattern here.
    val sustainResponse: String = "HUE_SHIFT",
    val sustainRampMs: Float = 2000f,
    val whiteFlashRecoveryMs: Float = 1000f,
    val idleTriggerDelayMs: Long = 2500L,
    val detectedAudioDeviceName: String? = null,
    val activeAudioDeviceIdentifier: String? = null
)

data class AmbianceSettingsState(
    val ambianceResponseSpeed: Float = 0.5f,
    val ambianceSmoothnessMs: Int = 150,
    val ambianceSaturationBoost: Float = 1.4f,
    val ambianceBrightnessCompensation: Float = 1.0f,
    val ambianceUpdateRateCapFps: Int = 20,
    val ambianceSceneCutSensitivity: Float = 50.0f, // verified line 210 of original VM
    val ambianceNoiseDeadband: Float = 0.10f,
    val ambiancePreset: String = "Balanced"
)

// colorCalibrations/cctCalibrations deliberately NOT here — they are category (b),
// DB/prefs-backed, and stay as separate StateFlows (see below).
data class CalibrationFlowState(
    val showCalibrationPrompt: Boolean = false,
    val isCalibrationModeActive: Boolean = false,
    val calibrationDelayOffsetMs: Int = 0
)

data class RgbUiState(
    val connectivity: ConnectivityState = ConnectivityState(),
    val coreControl: CoreControlState = CoreControlState(),
    val audioSettings: AudioSettingsState = AudioSettingsState(),
    val ambianceSettings: AmbianceSettingsState = AmbianceSettingsState(),
    val calibrationFlow: CalibrationFlowState = CalibrationFlowState()
)

// ============================================================================
// SEPARATE FLOWS — not part of the reducer loop
// ============================================================================

// High-frequency telemetry (musicAmplitudes ~60Hz, visualizerHue per-frame,
// deviceAchievedFps on a continuous tick) — kept out of RgbUiState to avoid
// forcing whole-state recomposition on every audio frame.
data class TelemetryState(
    val musicAmplitudes: List<Float> = emptyList(),
    val visualizerHue: Float = 0.0f,
    val logMessages: List<String> = emptyList(),
    val deviceAchievedFps: Map<String, Int> = emptyMap()
)

// DB/prefs-backed reference data — category (b), each its own StateFlow,
// combined at the point of consumption rather than folded into RgbUiState:
//   scenes: StateFlow<List<AppScene>>
//   savedPresets: StateFlow<List<RgbPreset>>
//   savedAliases: StateFlow<List<RgbDeviceAlias>>
//   savedDevices: StateFlow<List<SavedDevice>>
//   customModes: StateFlow<List<CustomMode>>
//   colorCalibrations: StateFlow<Map<String, ColorCalibration>>
//   cctCalibrations: StateFlow<Map<String, CctCorrectionProfile>>
//   byteOverrides: StateFlow<Map<String, String>>  (persisted via prefsRepo.putProtocolOverrideString)

// ============================================================================
// INTENTS
// ============================================================================

sealed interface RgbIntent {

    // --- Connectivity & Scanning ---
    object StartScanning : RgbIntent
    object StopScanning : RgbIntent
    data class ConnectDevice(val address: String) : RgbIntent
    data class DisconnectDevice(val address: String) : RgbIntent
    object DisconnectAll : RgbIntent
    data class SetDemoMode(val isDemo: Boolean) : RgbIntent
    data class SetActiveFeatureName(val name: String) : RgbIntent
    object CheckActiveAudioRoute : RgbIntent
    data class InternalConnectionStateChanged(val address: String, val newState: BleConnectionState) : RgbIntent

    // --- Device management ---
    data class ToggleAutoConnect(val address: String, val name: String, val enabled: Boolean) : RgbIntent
    data class ToggleActiveControl(val address: String, val name: String, val enabled: Boolean) : RgbIntent
    data class DeleteSavedDevice(val address: String) : RgbIntent
    data class SaveDeviceAlias(val address: String, val customName: String) : RgbIntent
    data class DeleteDeviceAlias(val address: String) : RgbIntent

    // --- Core Controls ---
    data class SetPower(val isOn: Boolean) : RgbIntent
    data class SetColor(val r: Int, val g: Int, val b: Int) : RgbIntent
    data class SetBrightness(val percent: Int) : RgbIntent
    data class SetMode(val modeIndex: Int) : RgbIntent
    data class SetModeSpeed(val speed: Int) : RgbIntent
    data class SetWarmth(val percent: Int) : RgbIntent
    data class SetShowFpsTracker(val enabled: Boolean) : RgbIntent

    // --- Commands & Protocol (verified signatures) ---
    data class QueueCommand(val command: ByteArray) : RgbIntent {
        override fun equals(other: Any?) = this === other ||
            (other is QueueCommand && command.contentEquals(other.command))
        override fun hashCode() = command.contentHashCode()
    }
    data class BroadcastCommand(val command: ByteArray, val cancelRunningScenes: Boolean = true) : RgbIntent {
        override fun equals(other: Any?) = this === other ||
            (other is BroadcastCommand && command.contentEquals(other.command) && cancelRunningScenes == other.cancelRunningScenes)
        override fun hashCode() = 31 * command.contentHashCode() + cancelRunningScenes.hashCode()
    }
    data class SendCommandToDeviceDirect(val address: String, val command: ByteArray) : RgbIntent {
        override fun equals(other: Any?) = this === other ||
            (other is SendCommandToDeviceDirect && address == other.address && command.contentEquals(other.command))
        override fun hashCode() = 31 * address.hashCode() + command.contentHashCode()
    }
    data class UpdateProtocolByte(val index: Int, val value: Int) : RgbIntent
    data class UpdateOverride(val key: String, val hexString: String) : RgbIntent
    // verified signature: fun writeAmbianceColor(r: Int, g: Int, b: Int) — no address param
    data class WriteAmbianceColor(val r: Int, val g: Int, val b: Int) : RgbIntent
    // Fires the phone-mic-toggle BLE broadcast tied to ambiance capture start/stop.
    // AmbianceCaptureState.isActive itself stays outside RgbUiState (see AmbianceSettingsReducer) —
    // this intent only carries the BLE side effect through dispatch(), same as WriteAmbianceColor.
    data class SetAmbianceCaptureActive(val active: Boolean) : RgbIntent

    // --- Scenes & Presets ---
    data class ApplyScene(val scene: AppScene, val isReversing: Boolean = false) : RgbIntent
    data class SaveScene(
        val name: String, val groupA: String?, val includeBrightness: Boolean,
        val includeModeSpeed: Boolean, val targetScope: String, val selectedDeviceMacs: List<String>?,
        val includeAmbianceSettings: Boolean = false, val includeCalibrationSettings: Boolean = false,
        val includeAudioSettings: Boolean = false, val chainedSceneId: String? = null,
        val chainedSceneDelaySeconds: Int? = null, val chainedSceneReverseId: String? = null
    ) : RgbIntent
    data class UpdateScene(
        val sceneId: String, val name: String, val groupA: String?, val includeBrightness: Boolean,
        val includeModeSpeed: Boolean, val targetScope: String, val selectedDeviceMacs: List<String>?,
        val includeAmbianceSettings: Boolean = false, val includeCalibrationSettings: Boolean = false,
        val includeAudioSettings: Boolean = false, val chainedSceneId: String? = null,
        val chainedSceneDelaySeconds: Int? = null, val chainedSceneReverseId: String? = null
    ) : RgbIntent
    data class DeleteScene(val sceneId: String) : RgbIntent
    data class RenameScene(val sceneId: String, val newName: String) : RgbIntent
    data class SaveAiSceneSequence(val params: ProceduralSceneParams, val sceneName: String, val explanation: String) : RgbIntent
    data class UpdateAiSceneSequence(val sceneId: String, val params: ProceduralSceneParams, val sceneName: String) : RgbIntent
    data class ApplyPreset(val preset: RgbPreset) : RgbIntent
    data class SavePreset(val name: String) : RgbIntent
    data class DeletePreset(val id: Int) : RgbIntent
    data class UpdateCustomMode(val customMode: CustomMode) : RgbIntent
    data class RenameCategory(val oldName: String, val newName: String) : RgbIntent

    // --- Audio Sync & Configuration ---
    data class StartMusicSync(val mode: String) : RgbIntent
    data class StopMusicSync(val restoreState: Boolean = true) : RgbIntent
    data class SetMusicSensitivity(val value: Int) : RgbIntent
    data class SetVisualizerPreset(val preset: String) : RgbIntent
    data class SetAudioSmoothingAttack(val value: Float) : RgbIntent
    data class SetAudioSmoothingDecay(val value: Float) : RgbIntent
    data class SetAudioGammaExponent(val value: Float) : RgbIntent
    data class SetAudioFlashStrength(val value: Float) : RgbIntent
    data class SetVisualizerMinBrightness(val value: Float) : RgbIntent
    data class SetVisualizerColorSpeed(val value: Float) : RgbIntent
    data class SetIdleTriggerDelayMs(val value: Long) : RgbIntent
    data class SetTransmissionDelayMs(val value: Int) : RgbIntent
    data class SetNoiseGateThreshold(val value: Float) : RgbIntent
    data class SetBeatThresholdMultiplier(val value: Float) : RgbIntent
    data class SetBeatCooldownMs(val value: Int) : RgbIntent
    data class SetBassGain(val value: Float) : RgbIntent
    data class SetMidGain(val value: Float) : RgbIntent
    data class SetHighGain(val value: Float) : RgbIntent
    data class SetAutoGainEnabled(val value: Boolean) : RgbIntent
    data class SetPaletteCyclingEnabled(val value: Boolean) : RgbIntent
    data class SetLogarithmicScalingEnabled(val value: Boolean) : RgbIntent
    data class SetBluetoothDelayMs(val value: Int) : RgbIntent
    object ResetAudioPipelineSettings : RgbIntent
    // beatFlashDecayMs, ambientCapFraction, midFluxWeight, totalVisualDelayMs have
    // no dedicated setter in RgbControllerViewModel (verified) — read-only fields,
    // no mutating intent exists for them. They remain in AudioSettingsState for
    // display only.

    // --- Ambiance & Camera Sync ---
    data class ApplyAmbiancePreset(
        val presetId: String, val responseSpeed: Float, val smoothnessMs: Int,
        val saturationBoost: Float, val brightnessCompensation: Float,
        val sceneCutSensitivity: Float, val noiseDeadband: Float
    ) : RgbIntent // verified signature, lines 2817-2825
    data class SetAmbianceResponseSpeed(val value: Float) : RgbIntent
    data class SetAmbianceSmoothnessMs(val value: Int) : RgbIntent
    data class SetAmbianceSaturationBoost(val value: Float) : RgbIntent
    data class SetAmbianceBrightnessCompensation(val value: Float) : RgbIntent
    data class SetAmbianceUpdateRateCapFps(val value: Int) : RgbIntent
    data class SetAmbianceSceneCutSensitivity(val value: Float) : RgbIntent
    data class SetAmbianceNoiseDeadband(val value: Float) : RgbIntent

    // --- Calibration & Tuning ---
    object StartCalibrationMode : RgbIntent
    object StopCalibrationMode : RgbIntent
    object SaveCalibrationAndExit : RgbIntent
    object SendCalibrationFlash : RgbIntent
    object DismissCalibrationPrompt : RgbIntent
    data class UpdateCalibrationSliderValue(val value: Int) : RgbIntent
    object ResetCalibrationSettings : RgbIntent
    data class SaveColorCalibration(val calibration: ColorCalibration) : RgbIntent
    data class DeleteColorCalibration(val macAddress: String) : RgbIntent
    data class SaveCctCorrectionProfile(val profile: CctCorrectionProfile) : RgbIntent
    data class DeleteCctCorrectionProfile(val address: String) : RgbIntent
    data class ToggleTestPattern(val address: String) : RgbIntent
    data class SetDevicePacing(val address: String, val ms: Int) : RgbIntent

    // --- Utility ---
    object PlayClick : RgbIntent // verified: fun playClick() (line 5284)
    object ClearLogs : RgbIntent
}
