package com.example

import com.example.core.protocol.DuoCoProtocol
import com.example.core.color.ColorConverter
import com.example.core.calibration.CalibrationMatrixSolver
import com.example.core.animation.ProceduralSceneParams
import com.example.core.audio.BeatDetector
import com.example.hardware.audio.MetronomePlayer
import com.example.domain.repository.AppPreferencesRepository
import com.example.domain.repository.RgbDatabaseRepository

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.RgbDeviceAlias
import com.example.db.RgbPreset
import com.example.db.SavedDevice
import com.example.db.CustomMode
import com.example.db.ColorCalibration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjectionManager
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import com.example.domain.model.AppScene
import com.example.domain.model.DeviceSceneState
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.UUID

private const val TAG = "BleRgbController"

// ============================================================================

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class ScannedRgbDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isDuoCoSuspected: Boolean,
    val alias: String? = null
)

data class CctCorrectionProfile(
    val macAddress: String,
    val timestamp: Long,
    val scaleR: Float,
    val offsetR: Float,
    val scaleG: Float,
    val offsetG: Float,
    val scaleB: Float,
    val offsetB: Float,
    val iso: Int,
    val exposureNs: Long
) {
    fun toJson(): String {
        val obj = org.json.JSONObject()
        obj.put("macAddress", macAddress)
        obj.put("timestamp", timestamp)
        obj.put("scaleR", scaleR.toDouble())
        obj.put("offsetR", offsetR.toDouble())
        obj.put("scaleG", scaleG.toDouble())
        obj.put("offsetG", offsetG.toDouble())
        obj.put("scaleB", scaleB.toDouble())
        obj.put("offsetB", offsetB.toDouble())
        obj.put("iso", iso)
        obj.put("exposureNs", exposureNs)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): CctCorrectionProfile? {
            return try {
                val obj = org.json.JSONObject(jsonStr)
                CctCorrectionProfile(
                    macAddress = obj.getString("macAddress"),
                    timestamp = obj.getLong("timestamp"),
                    scaleR = obj.getDouble("scaleR").toFloat(),
                    offsetR = obj.getDouble("offsetR").toFloat(),
                    scaleG = obj.getDouble("scaleG").toFloat(),
                    offsetG = obj.getDouble("offsetG").toFloat(),
                    scaleB = obj.getDouble("scaleB").toFloat(),
                    offsetB = obj.getDouble("offsetB").toFloat(),
                    iso = obj.getInt("iso"),
                    exposureNs = obj.getLong("exposureNs")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class ActiveDeviceState(
    val activeFeatureName: String = "Colour",
    val red: Int = 255,
    val green: Int = 0,
    val blue: Int = 128,
    val warmth: Int = 50,
    val modeIndex: Int = 0,
    val brightness: Int = 80,
    val isPowerOn: Boolean = true
)

// ControllerUiState (flat, 60-field) has been migrated to RgbUiState (RgbUiState.kt) —
// see CLAUDE.md "RgbUiState shape (locked)" for the sub-state mapping.


@SuppressLint("MissingPermission")

class RgbControllerViewModel(
    private val application: android.content.Context,
    private val prefsRepo: com.example.domain.repository.AppPreferencesRepository,
    private val repository: com.example.domain.repository.RgbDatabaseRepository,
    private val connectionManager: com.example.domain.ConnectionManager
) : androidx.lifecycle.ViewModel() {

    private fun getApplication(): android.app.Application = application as android.app.Application

    companion object {
        @Volatile
        private var instance: RgbControllerViewModel? = null

        fun getActiveInstance(): RgbControllerViewModel? = instance

        // Static maps to survive Activity/ViewModel recreation and prevent GC disconnects
        val activeConnections = ConcurrentHashMap<String, BluetoothGatt>()
        val writeCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
        val retryAttempts = ConcurrentHashMap<String, Int>()
        val activeExcludedMacs = ConcurrentHashMap.newKeySet<String>()
        val deviceWriteManagers = ConcurrentHashMap<String, RgbControllerViewModel.DeviceWriteManager>()
        private val connectionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }

        private val deviceStateStore = DeviceStateStore(application)
    private val deviceAutomationMode = java.util.concurrent.ConcurrentHashMap<String, AutomationType>()
    private val deviceRestoredFeatureName = java.util.concurrent.ConcurrentHashMap<String, String>()
    enum class AutomationType { AUDIO, AMBIANCE }
    
        
    private val _uiState = MutableStateFlow(
        RgbUiState(
            coreControl = CoreControlState(
                isPowerOn = prefsRepo.getAppStatePrefBoolean("power_on", true),
                activeFeatureName = prefsRepo.getAppStatePrefString("active_feature_name", "Colour") ?: "Colour",
                showFpsTracker = prefsRepo.getAppStatePrefBoolean("show_fps_tracker", false),
                red = prefsRepo.getAppStatePrefInt("red", 255),
                green = prefsRepo.getAppStatePrefInt("green", 0),
                blue = prefsRepo.getAppStatePrefInt("blue", 128),
                brightness = prefsRepo.getAppStatePrefInt("brightness", 80),
                modeIndex = prefsRepo.getAppStatePrefInt("mode_index", 0),
                modeSpeed = prefsRepo.getAppStatePrefInt("mode_speed", 50),
                warmth = prefsRepo.getAppStatePrefInt("warmth", 50)
            ),
            audioSettings = AudioSettingsState(
                audioSmoothingAttack = prefsRepo.getAppStatePrefFloat("audio_smoothing_attack", 0.85f),
                audioSmoothingDecay = prefsRepo.getAppStatePrefFloat("audio_smoothing_decay", 0.12f),
                beatFlashDecayMs = prefsRepo.getAppStatePrefFloat("beat_flash_decay_ms", 200f),
                ambientCapFraction = prefsRepo.getAppStatePrefFloat("ambient_cap_fraction", 0.40f),
                midFluxWeight = prefsRepo.getAppStatePrefFloat("mid_flux_weight", 0.25f),
                transmissionDelayMs = prefsRepo.getAppStatePrefInt("transmission_delay_ms", 16),
                noiseGateThreshold = prefsRepo.getAppStatePrefFloat("noise_gate_threshold", 5.0f),
                beatThresholdMultiplier = prefsRepo.getAppStatePrefFloat("beat_threshold_multiplier", 1.3f),
                beatCooldownMs = prefsRepo.getAppStatePrefInt("beat_cooldown_ms", 250),
                bassGain = prefsRepo.getAppStatePrefFloat("bass_gain", 1.0f),
                midGain = prefsRepo.getAppStatePrefFloat("mid_gain", 1.0f),
                highGain = prefsRepo.getAppStatePrefFloat("high_gain", 1.0f),
                isAutoGainEnabled = prefsRepo.getAppStatePrefBoolean("is_auto_gain_enabled", true),
                isPaletteCyclingEnabled = prefsRepo.getAppStatePrefBoolean("is_palette_cycling_enabled", true),
                isLogarithmicScalingEnabled = prefsRepo.getAppStatePrefBoolean("is_logarithmic_scaling_enabled", true),
                bluetoothDelayMs = prefsRepo.getAppStatePrefInt("bluetooth_delay_ms", 0),
                totalVisualDelayMs = prefsRepo.getAppStatePrefInt("bluetooth_delay_ms", 0) + BeatDetector().lookaheadMs.toInt(),
                visualizerPreset = prefsRepo.getAppStatePrefString("visualizer_preset", "Default") ?: "Default",
                audioGammaExponent = prefsRepo.getAppStatePrefFloat("audio_gamma_exponent", 0.45f),
                audioFlashStrength = prefsRepo.getAppStatePrefFloat("audio_flash_strength", 0.3f),
                visualizerMinBrightness = prefsRepo.getAppStatePrefFloat("visualizer_min_brightness", 0.15f),
                visualizerColorSpeed = prefsRepo.getAppStatePrefFloat("visualizer_color_speed", 1.0f),
                idleTriggerDelayMs = prefsRepo.getAppStatePrefLong("idle_trigger_delay_ms", 2500L)
            ),
            ambianceSettings = AmbianceSettingsState(
                ambianceResponseSpeed = prefsRepo.getAmbiancePrefFloat("response_speed", 0.5f),
                ambianceSmoothnessMs = prefsRepo.getAmbiancePrefInt("smoothness_ms", 150),
                ambianceSaturationBoost = prefsRepo.getAmbiancePrefFloat("saturation_boost", 1.4f),
                ambianceBrightnessCompensation = prefsRepo.getAmbiancePrefFloat("brightness_compensation", 1.0f),
                ambianceUpdateRateCapFps = prefsRepo.getAmbiancePrefInt("update_rate_cap_fps", 20),
                ambianceSceneCutSensitivity = prefsRepo.getAmbiancePrefFloat("scene_cut_sensitivity", 110.0f), // Balanced default is 110
                ambianceNoiseDeadband = prefsRepo.getAmbiancePrefFloat("noise_deadband", 0.10f),
                ambiancePreset = prefsRepo.getAmbiancePrefString("ambiance_preset", "Balanced") ?: "Balanced"
            )
        )
    )
    val uiState: StateFlow<RgbUiState> = _uiState.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryState())
    val telemetry: StateFlow<TelemetryState> = _telemetry.asStateFlow()

    private val _colorCalibrations = MutableStateFlow<Map<String, ColorCalibration>>(emptyMap())
    val colorCalibrations: StateFlow<Map<String, ColorCalibration>> = _colorCalibrations.asStateFlow()

    private val _cctCalibrations = MutableStateFlow<Map<String, CctCorrectionProfile>>(emptyMap())
    val cctCalibrations: StateFlow<Map<String, CctCorrectionProfile>> = _cctCalibrations.asStateFlow()

    // ============================================================================
    // MVI DISPATCH — Core Controls + Connectivity & Scanning + Device management
    // ============================================================================

    private fun dispatch(intent: RgbIntent) {
        val (coreState, coreEffects) = com.example.presentation.coreControlsReducer(
            state = _uiState.value,
            intent = intent,
            customModes = customModes.value,
            savedDevices = savedDevices.value,
            cctCalibrations = _cctCalibrations.value,
            isAmbianceActive = com.example.ambiance.AmbianceCaptureState.isActive.value,
            targetAddresses = getCurrentlyControlledDeviceAddresses(),
            deviceAutomationMode = deviceAutomationMode
        )
        val (ambianceState, ambianceEffects) = com.example.presentation.ambianceSettingsReducer(
            state = coreState,
            intent = intent
        )
        val (audioState, audioEffects) = com.example.presentation.audioSettingsReducer(
            state = ambianceState,
            intent = intent,
            targetAddresses = getCurrentlyControlledDeviceAddresses(),
            deviceAutomationMode = deviceAutomationMode
        )
        val identifier = audioState.audioSettings.activeAudioDeviceIdentifier
        val savedCalibrationDelayMs = if (identifier != null) {
            prefsRepo.getCalibrationDelayPrefInt(identifier, 0)
        } else {
            prefsRepo.getCalibrationDelayPrefInt("default_delay", 0)
        }
        val (newState, calibrationEffects) = com.example.presentation.calibrationFlowReducer(
            state = audioState,
            intent = intent,
            savedCalibrationDelayMs = savedCalibrationDelayMs,
            connectedManagerAddresses = deviceWriteManagers.keys
        )
        _uiState.value = newState
        coreEffects.forEach { executeCoreSideEffect(it) }
        ambianceEffects.forEach { executeAmbianceSideEffect(it) }
        audioEffects.forEach { executeAudioSideEffect(it) }
        calibrationEffects.forEach { executeCalibrationSideEffect(it) }
    }

    private fun executeCalibrationSideEffect(effect: com.example.presentation.CalibrationSideEffect) {
        when (effect) {
            is com.example.presentation.CalibrationSideEffect.SaveCalibrationDelayPrefInt -> prefsRepo.putCalibrationDelayPrefInt(effect.deviceKey, effect.value)
            com.example.presentation.CalibrationSideEffect.ClearCalibrationDelayPrefs -> prefsRepo.clearCalibrationDelayPrefs()
            is com.example.presentation.CalibrationSideEffect.SaveCalibrationPrefInt -> prefsRepo.putAppStatePrefInt(effect.key, effect.value)
            is com.example.presentation.CalibrationSideEffect.SavePacingPrefInt -> prefsRepo.putPacingPrefInt(effect.address, effect.value)
            com.example.presentation.CalibrationSideEffect.ClearPacingPrefs -> prefsRepo.clearPacingPrefs()
            is com.example.presentation.CalibrationSideEffect.SaveCctCorrectionProfile -> {
                prefsRepo.putCctCalibrationString(effect.profile.macAddress, effect.profile.toJson())
                loadCctCalibrations()
            }
            is com.example.presentation.CalibrationSideEffect.DeleteCctCorrectionProfile -> {
                prefsRepo.removeCctCalibration(effect.address)
                loadCctCalibrations()
            }
            com.example.presentation.CalibrationSideEffect.ClearCctCalibrationPrefs -> {
                prefsRepo.clearCctCalibrationPrefs()
                _cctCalibrations.value = emptyMap()
            }
            is com.example.presentation.CalibrationSideEffect.SaveColorCalibration -> {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.insertColorCalibration(effect.calibration)
                    addLog("Saved Color Calibration Profile for ${effect.calibration.macAddress}")
                }
            }
            is com.example.presentation.CalibrationSideEffect.DeleteColorCalibration -> {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.deleteColorCalibration(effect.macAddress)
                    addLog("Deleted Color Calibration Profile for ${effect.macAddress}")
                }
            }
            is com.example.presentation.CalibrationSideEffect.SetDeviceManagerPacing -> {
                deviceWriteManagers[effect.address]?.currentPacingMs = effect.ms
            }
            com.example.presentation.CalibrationSideEffect.ResetAllDeviceManagerPacing -> {
                deviceWriteManagers.forEach { (_, manager) -> manager.currentPacingMs = 100 }
            }
            // CONTRACT: the metronome tick re-dispatches RgbIntent.SendCalibrationFlash rather
            // than constructing the pulse itself — see CalibrationSideEffect.StartMetronome.
            com.example.presentation.CalibrationSideEffect.StartMetronome -> {
                metronomeJob = viewModelScope.launch(Dispatchers.Default) {
                    while (true) {
                        metronomePlayer.playClick()
                        val currentDelay = _uiState.value.calibrationFlow.calibrationDelayOffsetMs
                        launch {
                            delay(currentDelay.toLong())
                            dispatch(RgbIntent.SendCalibrationFlash)
                        }
                        delay(1000L)
                    }
                }
            }
            com.example.presentation.CalibrationSideEffect.CancelMetronome -> {
                metronomeJob?.cancel()
                metronomeJob = null
            }
            com.example.presentation.CalibrationSideEffect.SendFlashPulse -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val whiteColor = DuoCoProtocol.createColorCommand(255, 255, 255)
                    val fullBrightness = DuoCoProtocol.createBrightnessCommand(100)
                    val pulseOn = fullBrightness + whiteColor
                    broadcastCommandDirect(pulseOn)

                    delay(120) // pulse duration

                    val blackColor = DuoCoProtocol.createColorCommand(0, 0, 0)
                    broadcastCommandDirect(blackColor)
                }
            }
            is com.example.presentation.CalibrationSideEffect.StartTestPatternLoop -> {
                testPatternJobs[effect.address] = viewModelScope.launch {
                    val colors = listOf(
                        android.graphics.Color.RED,
                        android.graphics.Color.GREEN,
                        android.graphics.Color.BLUE,
                        android.graphics.Color.WHITE
                    )
                    var idx = 0
                    while (isActive) {
                        val c = colors[idx % colors.size]
                        val cmd = DuoCoProtocol.createColorCommand(
                            android.graphics.Color.red(c),
                            android.graphics.Color.green(c),
                            android.graphics.Color.blue(c)
                        )
                        deviceWriteManagers[effect.address]?.updateCommand(cmd)
                        idx++
                        delay(30)
                    }
                }
            }
            is com.example.presentation.CalibrationSideEffect.CancelTestPatternLoop -> {
                testPatternJobs[effect.address]?.cancel()
                testPatternJobs.remove(effect.address)
            }
            is com.example.presentation.CalibrationSideEffect.Log -> addLog(effect.message)
        }
    }

    private fun executeAudioSideEffect(effect: com.example.presentation.AudioSideEffect) {
        when (effect) {
            is com.example.presentation.AudioSideEffect.SaveAudioPrefFloat -> prefsRepo.putAppStatePrefFloat(effect.key, effect.value)
            is com.example.presentation.AudioSideEffect.SaveAudioPrefInt -> prefsRepo.putAppStatePrefInt(effect.key, effect.value)
            is com.example.presentation.AudioSideEffect.SaveAudioPrefLong -> prefsRepo.putAppStatePrefLong(effect.key, effect.value)
            is com.example.presentation.AudioSideEffect.SaveAudioPrefString -> prefsRepo.putAppStatePrefString(effect.key, effect.value)
            is com.example.presentation.AudioSideEffect.SaveAudioPrefBoolean -> prefsRepo.putAppStatePrefBoolean(effect.key, effect.value)
            is com.example.presentation.AudioSideEffect.BroadcastCommand -> sendCommand(effect.command, effect.logMessage, effect.cancelRunningScenes)
            com.example.presentation.AudioSideEffect.CancelSceneChain -> cancelSceneChain()
            com.example.presentation.AudioSideEffect.ClearExclusionsIfNotApplyingScene -> clearExclusionsIfNotApplyingScene()
            is com.example.presentation.AudioSideEffect.CancelSceneRunner -> {
                sceneRunners[effect.address]?.release()
                sceneRunners.remove(effect.address)
            }
            is com.example.presentation.AudioSideEffect.SaveDeviceState -> saveDeviceState(effect.address, effect.automationType)
            is com.example.presentation.AudioSideEffect.RestoreDeviceState -> restoreDeviceState(effect.address, effect.automationType)
            is com.example.presentation.AudioSideEffect.ClearDeviceAutomationMode -> deviceAutomationMode.remove(effect.address)
            is com.example.presentation.AudioSideEffect.StopMusicSyncInternal -> stopMusicSyncInternal(effect.keepServiceRunning)
            is com.example.presentation.AudioSideEffect.StartAudioEngine -> startAudioRecording(effect.mode)
        }
    }

    private fun executeAmbianceSideEffect(effect: com.example.presentation.AmbianceSideEffect) {
        when (effect) {
            is com.example.presentation.AmbianceSideEffect.SaveAmbiancePrefFloat -> prefsRepo.putAmbiancePrefFloat(effect.key, effect.value)
            is com.example.presentation.AmbianceSideEffect.SaveAmbiancePrefInt -> prefsRepo.putAmbiancePrefInt(effect.key, effect.value)
            is com.example.presentation.AmbianceSideEffect.SaveAmbiancePrefString -> prefsRepo.putAmbiancePrefString(effect.key, effect.value)
            com.example.presentation.AmbianceSideEffect.CancelSceneChain -> cancelSceneChain()
            is com.example.presentation.AmbianceSideEffect.WriteColor -> {
                if (com.example.ambiance.AmbianceCaptureState.isActive.value) {
                    broadcastCommandDirect(DuoCoProtocol.createColorCommand(effect.r, effect.g, effect.b))
                }
            }
            is com.example.presentation.AmbianceSideEffect.BroadcastCommand -> broadcastCommand(effect.command)
        }
    }

    private fun executeCoreSideEffect(effect: com.example.presentation.CoreSideEffect) {
        when (effect) {
            is com.example.presentation.CoreSideEffect.SavePrefBoolean -> prefsRepo.putAppStatePrefBoolean(effect.key, effect.value)
            is com.example.presentation.CoreSideEffect.SavePrefInt -> prefsRepo.putAppStatePrefInt(effect.key, effect.value)
            is com.example.presentation.CoreSideEffect.SavePrefString -> prefsRepo.putAppStatePrefString(effect.key, effect.value)
            is com.example.presentation.CoreSideEffect.Log -> addLog(effect.message)
            is com.example.presentation.CoreSideEffect.BroadcastCommand -> sendCommand(effect.command, effect.logMessage, effect.cancelRunningScenes)
            is com.example.presentation.CoreSideEffect.SendCommandToDeviceDirect -> sendCommandToDeviceDirect(effect.address, effect.command)
            is com.example.presentation.CoreSideEffect.ConnectDevice -> connectDeviceHardware(effect.address)
            is com.example.presentation.CoreSideEffect.DisconnectDevice -> {
                _uiState.update { s ->
                    s.copy(connectivity = s.connectivity.copy(
                        deviceConnectionStates = s.connectivity.deviceConnectionStates + (effect.address to BleConnectionState.DISCONNECTING)
                    ))
                }
                disconnectDeviceHardware(effect.address)
            }
            is com.example.presentation.CoreSideEffect.StopMusicSync -> stopMusicSync(effect.restoreState)
            is com.example.presentation.CoreSideEffect.StopAmbiance -> stopAmbianceIfActive(effect.restoreState)
            com.example.presentation.CoreSideEffect.CancelSceneChain -> cancelSceneChain()
            com.example.presentation.CoreSideEffect.ClearExclusionsIfNotApplyingScene -> clearExclusionsIfNotApplyingScene()
            com.example.presentation.CoreSideEffect.StartBleScan -> startBleScanHardware()
            com.example.presentation.CoreSideEffect.StopBleScan -> stopBleScanHardware()
            com.example.presentation.CoreSideEffect.SimulateScan -> simulateScanHardware()
            com.example.presentation.CoreSideEffect.CheckActiveAudioRoute -> checkActiveAudioRouteHardware()
            is com.example.presentation.CoreSideEffect.AutoSaveDeviceIfNew -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val existing = savedDevices.value.find { it.macAddress == effect.address }
                    if (existing == null) {
                        repository.insertSavedDevice(
                            SavedDevice(
                                macAddress = effect.address,
                                customName = effect.name,
                                isAutoConnectEnabled = effect.autoConnect,
                                isActiveControlEnabled = effect.activeControl
                            )
                        )
                        addLog("Saved connected device ${effect.name} to database.")
                    }
                }
            }
            is com.example.presentation.CoreSideEffect.ToggleAutoConnect -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val existing = savedDevices.value.find { it.macAddress == effect.address }
                    if (existing != null) {
                        repository.updateAutoConnect(effect.address, effect.enabled)
                    } else {
                        repository.insertSavedDevice(
                            SavedDevice(
                                macAddress = effect.address,
                                customName = effect.name,
                                isAutoConnectEnabled = effect.enabled,
                                isActiveControlEnabled = true
                            )
                        )
                    }
                }
            }
            is com.example.presentation.CoreSideEffect.UpdateActiveControl -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val existing = savedDevices.value.find { it.macAddress == effect.address }
                    if (existing != null) {
                        repository.updateActiveControl(effect.address, effect.enabled)
                    } else {
                        repository.insertSavedDevice(
                            SavedDevice(
                                macAddress = effect.address,
                                customName = effect.name,
                                isAutoConnectEnabled = true,
                                isActiveControlEnabled = effect.enabled
                            )
                        )
                    }
                }
            }
            is com.example.presentation.CoreSideEffect.SaveDeviceState -> saveDeviceState(effect.address, effect.automationType)
            is com.example.presentation.CoreSideEffect.RestoreDeviceState -> restoreDeviceState(effect.address, effect.automationType)
            is com.example.presentation.CoreSideEffect.DeleteSavedDevice -> {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.deleteSavedDevice(effect.address)
                }
            }
            is com.example.presentation.CoreSideEffect.SaveDeviceAlias -> {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.saveDeviceAlias(effect.address, effect.customName)
                    val existingSaved = savedDevices.value.find { it.macAddress == effect.address }
                    if (existingSaved != null) {
                        repository.insertSavedDevice(existingSaved.copy(customName = effect.customName))
                    }
                }
            }
            is com.example.presentation.CoreSideEffect.DeleteDeviceAlias -> {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.deleteDeviceAlias(effect.address)
                }
            }
        }
    }

    fun getCalibrationMatrices(calibration: ColorCalibration): Map<Float, FloatArray> {
        val map = mutableMapOf<Float, FloatArray>()
        try {
            val json = org.json.JSONObject(calibration.samplesJson)
            if (json.optBoolean("is_multi_brightness", false)) {
                val matricesObj = json.getJSONObject("matrices")
                val keys = matricesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val keyFloat = key.toFloatOrNull() ?: continue
                    val arr = matricesObj.getJSONArray(key)
                    val matrix = FloatArray(9)
                    for (i in 0..8) {
                        matrix[i] = arr.getDouble(i).toFloat()
                    }
                    map[keyFloat] = matrix
                }
            }
        } catch (e: Exception) {
            // Error parsing multi-brightness matrices
        }
        
        // If empty, populate with the single matrix from ColorCalibration fields
        if (map.isEmpty()) {
            map[100f] = floatArrayOf(
                calibration.m11, calibration.m12, calibration.m13,
                calibration.m21, calibration.m22, calibration.m23,
                calibration.m31, calibration.m32, calibration.m33
            )
        }
        return map
    }

    fun interpolateMatrices(brightnessPercent: Float, matrices: Map<Float, FloatArray>): FloatArray {
        if (matrices.isEmpty()) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }
        if (matrices.size == 1) {
            return matrices.values.first()
        }
        
        val sortedLevels = matrices.keys.sorted()
        val minLevel = sortedLevels.first()
        val maxLevel = sortedLevels.last()
        
        if (brightnessPercent <= minLevel) {
            return matrices[minLevel] ?: matrices.values.first()
        }
        if (brightnessPercent >= maxLevel) {
            return matrices[maxLevel] ?: matrices.values.first()
        }
        
        var lower = minLevel
        var upper = maxLevel
        for (i in 0 until sortedLevels.size - 1) {
            val l1 = sortedLevels[i]
            val l2 = sortedLevels[i+1]
            if (brightnessPercent >= l1 && brightnessPercent <= l2) {
                lower = l1
                upper = l2
                break
            }
        }
        
        val m1 = matrices[lower] ?: return matrices.values.first()
        val m2 = matrices[upper] ?: return m1
        
        val t = (brightnessPercent - lower) / (upper - lower)
        val result = FloatArray(9)
        for (i in 0..8) {
            result[i] = m1[i] * (1f - t) + m2[i] * t
        }
        return result
    }

    fun applyCalibrationIfRequired(address: String, cmd: ByteArray): ByteArray {
        // Old full-spectrum RGB calibration is entirely disabled per scope change
        return cmd
    }

    fun processCommandWithCalibration(address: String, command: ByteArray): ByteArray {
        if (command.size % 9 != 0) return command
        val result = ByteArray(command.size)
        for (i in 0 until command.size step 9) {
            val chunk = command.copyOfRange(i, i + 9)
            val processed = applyCalibrationIfRequired(address, chunk)
            System.arraycopy(processed, 0, result, i, 9)
        }
        return result
    }

    inner class DeviceWriteManager(
        val address: String,
        val gatt: BluetoothGatt,
        val charac: BluetoothGattCharacteristic
    ) {
        private val commandQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        @Volatile var isWriting = false
        @Volatile var lastWriteTime = 0L
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        var currentPacingMs = prefsRepo.getPacingPrefInt(address, 50)
        
        private var framesSent = 0
        private var lastFpsTime = System.currentTimeMillis()
        @Volatile private var pendingJob: kotlinx.coroutines.Job? = null
        
        private var consecutiveWatchdogTriggers = 0
        private var lastQueueLogTime = 0L

        fun updateCommand(command: ByteArray) {
            val processed = processCommandWithCalibration(address, command)
            
            val type = if (processed.size >= 3) processed[2] else null
            if (type != null) {
                val iterator = commandQueue.iterator()
                while (iterator.hasNext()) {
                    val existing = iterator.next()
                    if (existing.size >= 3 && existing[2] == type) {
                        iterator.remove()
                    }
                }
            }
            
            val qSizeBefore = commandQueue.size
            // Fallback limit just in case
            if (commandQueue.size > 20) {
                commandQueue.poll()
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "Backpressure triggered (Queue size > 20)! Polled/dropped command. address=$address. (${getDiagAttribution(address)})"
                )
            }
            commandQueue.offer(processed)
            val qSizeAfter = commandQueue.size
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "Write enqueued: address=$address, cmdHex=${processed.joinToString("") { String.format("%02X", it) }}, queueSizeBefore=$qSizeBefore, queueSizeAfter=$qSizeAfter. (${getDiagAttribution(address)})"
            )
            
            val now = System.currentTimeMillis()
            if (now - lastQueueLogTime >= 1000L) {
                Log.d("BleWriteQueue", "Queue size for $address: ${commandQueue.size}")
                lastQueueLogTime = now
            }

            tryWrite()
        }

        fun onWriteCompleted() {
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "onWriteCompleted callback received for $address. (${getDiagAttribution(address)})"
            )
            consecutiveWatchdogTriggers = 0
            lastWriteTime = System.currentTimeMillis()
            isWriting = false
            framesSent++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000L) {
                val fps = framesSent
                framesSent = 0
                lastFpsTime = now
                _telemetry.update { s ->
                    s.copy(deviceAchievedFps = s.deviceAchievedFps + (address to fps))
                }
            }
            tryWrite()
        }

        @Synchronized
        private fun tryWrite() {
            if (isWriting) return
            val cmd = commandQueue.peek() ?: return
            
            val now = System.currentTimeMillis()
            val elapsed = now - lastWriteTime
            
            if (currentPacingMs > 0) {
                if (elapsed < currentPacingMs) {
                    if (pendingJob == null || pendingJob?.isActive != true) {
                        pendingJob = connectionScope.launch(Dispatchers.IO) {
                            delay(currentPacingMs - elapsed)
                            pendingJob = null
                            tryWrite()
                        }
                    }
                    return
                }
            }
            
            isWriting = true
            val cmdToWrite = commandQueue.poll()
            if (cmdToWrite == null) {
                isWriting = false
                return
            }
            val currentWriteTime = System.currentTimeMillis()
            lastWriteTime = currentWriteTime
            
            charac.writeType = writeType
            try {
                val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(charac, cmdToWrite, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    charac.value = cmdToWrite
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(charac)
                }
                
                if (!success) {
                    Log.w("BleWriteQueue", "writeCharacteristic() returned false for $address")
                    com.example.DiagnosticLogger.log(
                        "DeviceWriteManager",
                        "writeCharacteristic() returned false (write failure) for $address. (${getDiagAttribution(address)})"
                    )
                } else {
                    com.example.DiagnosticLogger.log(
                        "DeviceWriteManager",
                        "writeCharacteristic() initiated (write success) for $address. (${getDiagAttribution(address)})"
                    )
                }
            } catch (e: Exception) {
                isWriting = false
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() Exception for $address: ${android.util.Log.getStackTraceString(e)}. (${getDiagAttribution(address)})"
                )
                return
            }
            
            connectionScope.launch(Dispatchers.IO) {
                com.example.DiagnosticLogger.log(
                    "BleWriteWatchdog",
                    "Watchdog check tick scheduled for device $address (currentWriteTime=$currentWriteTime). (${getDiagAttribution(address)})"
                )
                delay(2000)
                com.example.DiagnosticLogger.log(
                    "BleWriteWatchdog",
                    "Watchdog check tick running for device $address: isWriting=$isWriting, lastWriteTime=$lastWriteTime, expectedWriteTime=$currentWriteTime, consecutiveWatchdogTriggers=$consecutiveWatchdogTriggers. (${getDiagAttribution(address)})"
                )
                if (isWriting && lastWriteTime == currentWriteTime) {
                    Log.w("BleWriteWatchdog", "Watchdog fired for device $address at timestamp $currentWriteTime — forcing reset")
                    com.example.DiagnosticLogger.log(
                        "BleWriteWatchdog",
                        "Watchdog FIRED for device $address at timestamp $currentWriteTime — forcing reset. (${getDiagAttribution(address)})"
                    )
                    isWriting = false
                    consecutiveWatchdogTriggers++
                    
                    if (consecutiveWatchdogTriggers >= 3) {
                        Log.e("BleWriteWatchdog", "device $address appears frozen — forcing reconnect")
                        com.example.DiagnosticLogger.log(
                            "BleWriteWatchdog",
                            "device $address appears frozen (consecutiveTriggers=$consecutiveWatchdogTriggers) — forcing reconnect. (${getDiagAttribution(address)})"
                        )
                        consecutiveWatchdogTriggers = 0
                        try {
                            gatt.disconnect()
                        } catch (e: Exception) {
                            Log.e("BleWriteWatchdog", "Exception forcing disconnect on frozen device", e)
                            com.example.DiagnosticLogger.log(
                                "BleWriteWatchdog",
                                "Exception forcing disconnect on frozen device $address: ${android.util.Log.getStackTraceString(e)}"
                            )
                        }
                    } else {
                        tryWrite()
                    }
                }
            }
        }
    }

    private val sceneRunners = ConcurrentHashMap<String, SceneAnimationRunner>()

    private val testPatternJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun toggleTestPattern(address: String) {
        dispatch(RgbIntent.ToggleTestPattern(address))
    }

    fun setDevicePacing(address: String, ms: Int) {
        dispatch(RgbIntent.SetDevicePacing(address, ms))
    }

    private val _byteOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val byteOverrides: StateFlow<Map<String, String>> = _byteOverrides.asStateFlow()

    private fun loadOverridesFromPrefs() {
        val allPrefs = prefsRepo.getProtocolOverrideAll()
        val overridesMap = mutableMapOf<String, String>()
        for ((key, value) in allPrefs) {
            if (value is String) {
                val bytes = DuoCoProtocol.parseHex(value)
                if (bytes != null) {
                    DuoCoProtocol.setOverride(key, bytes)
                    overridesMap[key] = value
                }
            }
        }
        _byteOverrides.value = overridesMap
    }

    fun updateOverride(key: String, hexString: String) {
        val cleanHex = hexString.trim().uppercase()
        if (cleanHex.isEmpty()) {
            prefsRepo.removeProtocolOverride(key)
            DuoCoProtocol.clearOverride(key)
            _byteOverrides.update { it - key }
        } else {
            val bytes = DuoCoProtocol.parseHex(cleanHex)
            if (bytes != null) {
                val formatted = DuoCoProtocol.formatHex(bytes)
                prefsRepo.putProtocolOverrideString(key, formatted)
                DuoCoProtocol.setOverride(key, bytes)
                _byteOverrides.update { it + (key to formatted) }
            }
        }
    }

    private var lastWriteTime = 0L
    private var pendingWriteJob: kotlinx.coroutines.Job? = null





    fun updateProtocolByte(index: Int, value: Int) {
        if (index in 1..7) {
            val current = _uiState.value.coreControl.protocolBytes.clone()
            current[index] = value.toByte()
            _uiState.update { it.copy(coreControl = it.coreControl.copy(protocolBytes = current)) }

            pendingWriteJob?.cancel()
            pendingWriteJob = viewModelScope.launch {
                val now = System.currentTimeMillis()
                val elapsed = now - lastWriteTime
                if (elapsed < 40) {
                    delay(40 - elapsed)
                }
                sendCommand(current, "Protocol Explorer")
                lastWriteTime = System.currentTimeMillis()
            }
        }
    }

    private var bluetoothAdapter: BluetoothAdapter? = null

            private val metronomePlayer = MetronomePlayer()
    private var metronomeJob: kotlinx.coroutines.Job? = null
    @Volatile private var activeBluetoothAudioDevice: BluetoothDevice? = null

    private val routingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val action = intent?.action
            if (action == "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED") {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as? BluetoothDevice
                }
                activeBluetoothAudioDevice = device
                addLog("RoutingReceiver: active device changed to ${device?.name ?: "none"} (${device?.address ?: "none"})")
                checkActiveAudioRoute()
            }
        }
    }

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            checkActiveAudioRoute()
        }
        
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            checkActiveAudioRoute()
        }
    }

    // Handler to run scan timeouts
    
    private val _scenes = MutableStateFlow<List<AppScene>>(emptyList())
    val scenes: StateFlow<List<AppScene>> = _scenes.asStateFlow()

    private var sceneChainJob: kotlinx.coroutines.Job? = null

    private fun cancelSceneChain() {
        sceneChainJob?.cancel()
        sceneChainJob = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { dispatch(RgbIntent.StopScanning) }

    private fun saveDeviceState(macAddress: String, mode: AutomationType) {
        if (deviceAutomationMode[macAddress] == null) {
            val s = _uiState.value
            val devState = s.connectivity.deviceStatesMap[macAddress]
            val red = devState?.red ?: s.coreControl.red
            val green = devState?.green ?: s.coreControl.green
            val blue = devState?.blue ?: s.coreControl.blue
            val warmth = devState?.warmth ?: s.coreControl.warmth
            val brightness = devState?.brightness ?: s.coreControl.brightness
            val power = devState?.isPowerOn ?: s.coreControl.isPowerOn

            val featureName = devState?.activeFeatureName ?: s.coreControl.activeFeatureName
            deviceRestoredFeatureName[macAddress] = if (featureName == "Colour" || featureName == "CCT") featureName else "Colour"
            
            deviceAutomationMode[macAddress] = mode
            
            viewModelScope.launch(Dispatchers.IO) {
                deviceStateStore.saveState(
                    macAddress = macAddress,
                    power = power,
                    red = red,
                    green = green,
                    blue = blue,
                    warmth = warmth,
                    brightness = brightness
                )
            }
        }
    }

    private fun restoreDeviceState(macAddress: String, mode: AutomationType) {
        if (deviceAutomationMode[macAddress] == mode) {
            deviceAutomationMode.remove(macAddress)
            viewModelScope.launch(Dispatchers.IO) {
                val state = deviceStateStore.getState(macAddress)
                if (state != null) {
                    val powerCmd = DuoCoProtocol.createPowerCommand(state.power)
                    val brightnessCmd = DuoCoProtocol.createBrightnessCommand(state.brightness)
                    val colorCmd = DuoCoProtocol.createColorCommand(state.red, state.green, state.blue)
                    
                    sendCommandToDeviceDirect(macAddress, powerCmd)
                    sendCommandToDeviceDirect(macAddress, brightnessCmd)
                    sendCommandToDeviceDirect(macAddress, colorCmd)
                    
                    val memoryFeatureName = deviceRestoredFeatureName.remove(macAddress)
                    val restoredFeatureName = if (memoryFeatureName != null) {
                        memoryFeatureName
                    } else {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(state.warmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        val isCct = rgb[0] == state.red && rgb[1] == state.green && rgb[2] == state.blue
                        if (isCct) "CCT" else "Colour"
                    }
                    
                    _uiState.update { current ->
                        val newMap = current.connectivity.deviceStatesMap.toMutableMap()
                        val existing = newMap[macAddress] ?: ActiveDeviceState()
                        newMap[macAddress] = existing.copy(
                            activeFeatureName = restoredFeatureName,
                            red = state.red,
                            green = state.green,
                            blue = state.blue,
                            warmth = state.warmth,
                            brightness = state.brightness,
                            isPowerOn = state.power
                        )
                        current.copy(connectivity = current.connectivity.copy(deviceStatesMap = newMap))
                    }
                }
            }
        }
    }

    // Preset list, Device alias list, and Saved devices list from Room
    val savedPresets: StateFlow<List<RgbPreset>>
    val savedAliases: StateFlow<List<RgbDeviceAlias>>
    val savedDevices: StateFlow<List<SavedDevice>>
    val customModes: StateFlow<List<CustomMode>>

    init {
        instance = this
        loadOverridesFromPrefs()
        _scenes.value = prefsRepo.loadScenes()
        
        savedPresets = repository.allPresets.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        savedAliases = repository.allDeviceAliases.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        savedDevices = repository.allSavedDevices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        customModes = repository.allCustomModes.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Initialize custom modes with default values if empty
        viewModelScope.launch {
            customModes.collect { list ->
                val currentVersion = prefsRepo.getAppStatePrefInt("custom_modes_version", 1)
                val isOldDb = list.isNotEmpty() && (list.size < 200 || currentVersion < 3)
                if (list.isEmpty() || isOldDb) {
                    if (isOldDb) {
                        repository.deleteAllCustomModes()
                    }
                    prefsRepo.putAppStatePrefInt("custom_modes_version", 3)
                    val defaultModes = mutableListOf<CustomMode>()
                    defaultModes.add(CustomMode(0, "Auto Play", "Classic Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(1, "Magic Forward", "Classic Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(2, "Magic Back", "Classic Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(212, "7-Color Energy", "Classic Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(193, "7-Color Jump", "Jump Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(194, "R-G-B Jump", "Jump Effects", "none", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(195, "Y-C-P Jump", "Jump Effects", "none", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(196, "7-Color Strobe", "Strobe Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(197, "R-G-B Strobe", "Strobe Effects", "none", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(198, "Y-C-P Strobe", "Strobe Effects", "none", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(199, "7-Color Gradual", "Gradual Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(200, "R-Y Gradual", "Gradual Effects", "none", "Red,Yellow"))
                    defaultModes.add(CustomMode(201, "R-P Gradual", "Gradual Effects", "none", "Red,Purple"))
                    defaultModes.add(CustomMode(202, "G-C Gradual", "Gradual Effects", "none", "Green,Cyan"))
                    defaultModes.add(CustomMode(203, "G-Y Gradual", "Gradual Effects", "none", "Green,Yellow"))
                    defaultModes.add(CustomMode(204, "B-P Gradual", "Gradual Effects", "none", "Blue,Purple"))
                    defaultModes.add(CustomMode(205, "Red Marquee", "Marquee Effects", "up", "Red"))
                    defaultModes.add(CustomMode(206, "Green Marquee", "Marquee Effects", "up", "Green"))
                    defaultModes.add(CustomMode(207, "Blue Marquee", "Marquee Effects", "up", "Blue"))
                    defaultModes.add(CustomMode(208, "Yellow Marquee", "Marquee Effects", "up", "Yellow"))
                    defaultModes.add(CustomMode(209, "Cyan Marquee", "Marquee Effects", "up", "Cyan"))
                    defaultModes.add(CustomMode(210, "Purple Marquee", "Marquee Effects", "up", "Purple"))
                    defaultModes.add(CustomMode(211, "White Marquee", "Marquee Effects", "up", "White"))
                    defaultModes.add(CustomMode(77, "7-Color Race", "Race Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(78, "7-Color Race Back", "Race Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(79, "R-G-B Race", "Race Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(80, "R-G-B Race Back", "Race Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(81, "Y-C-P Race", "Race Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(82, "Y-C-P Race Back", "Race Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(83, "7-Color Wave", "Wave Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(84, "7-Color Wave Back", "Wave Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(85, "R-G-B Wave", "Wave Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(86, "R-G-B Wave Back", "Wave Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(87, "Y-C-P Wave", "Wave Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(88, "Y-C-P Wave Back", "Wave Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(181, "7-Color Flush", "Flush Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(182, "7-Color Flush Back", "Flush Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(183, "R-G-B Flush", "Flush Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(184, "R-G-B Flush Back", "Flush Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(185, "Y-C-P Flush", "Flush Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(186, "Y-C-P Flush Back", "Flush Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(187, "7-Color Flush Close", "Flush Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(188, "7-Color Flush Open", "Flush Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(189, "R-G-B Flush Close", "Flush Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(190, "R-G-B Flush Open", "Flush Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(191, "Y-C-P Flush Close", "Flush Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(192, "Y-C-P Flush Open", "Flush Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(57, "7-Color Close", "Curtain Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(58, "7-Color Open", "Curtain Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(59, "R-G-B Close", "Curtain Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(60, "R-G-B Open", "Curtain Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(61, "Y-C-P Close", "Curtain Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(62, "Y-C-P Open", "Curtain Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(63, "Red Close", "Curtain Effects", "down", "Red"))
                    defaultModes.add(CustomMode(64, "Red Open", "Curtain Effects", "up", "Red"))
                    defaultModes.add(CustomMode(65, "Green Close", "Curtain Effects", "down", "Green"))
                    defaultModes.add(CustomMode(66, "Green Open", "Curtain Effects", "up", "Green"))
                    defaultModes.add(CustomMode(67, "Blue Close", "Curtain Effects", "down", "Blue"))
                    defaultModes.add(CustomMode(68, "Blue Open", "Curtain Effects", "up", "Blue"))
                    defaultModes.add(CustomMode(69, "Yellow Close", "Curtain Effects", "down", "Yellow"))
                    defaultModes.add(CustomMode(70, "Yellow Open", "Curtain Effects", "up", "Yellow"))
                    defaultModes.add(CustomMode(71, "Cyan Close", "Curtain Effects", "down", "Cyan"))
                    defaultModes.add(CustomMode(72, "Cyan Open", "Curtain Effects", "up", "Cyan"))
                    defaultModes.add(CustomMode(73, "Purple Close", "Curtain Effects", "down", "Purple"))
                    defaultModes.add(CustomMode(74, "Purple Open", "Curtain Effects", "up", "Purple"))
                    defaultModes.add(CustomMode(75, "White Close", "Curtain Effects", "down", "White"))
                    defaultModes.add(CustomMode(76, "White Open", "Curtain Effects", "up", "White"))
                    defaultModes.add(CustomMode(3, "7-Color Trans", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(4, "7-Color Trans Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(5, "R-G-B Trans", "Transition Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(6, "R-G-B Trans Back", "Transition Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(7, "Y-C-P Trans", "Transition Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(8, "Y-C-P Trans Back", "Transition Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(9, "6-Color to Red", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(10, "6-Color to Red Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(11, "6-Color to Green", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(12, "6-Color to Green Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(13, "6-Color to Blue", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(14, "6-Color to Blue Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(15, "6-Color to Cyan", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(16, "6-Color to Cyan Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(17, "6-Color to Yellow", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(18, "6-Color to Yellow Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(19, "6-Color to Purple", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(20, "6-Color to Purple Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(21, "6-Color to White", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(22, "6-Color to White Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(39, "7-Color Water", "Water Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(40, "7-Color Water Back", "Water Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(41, "R-G-B Water", "Water Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(42, "R-G-B Water Back", "Water Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(43, "Y-C-P Water", "Water Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(44, "Y-C-P Water Back", "Water Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(45, "R-G Water", "Water Effects", "up", "Red,Green"))
                    defaultModes.add(CustomMode(46, "R-G Water Back", "Water Effects", "down", "Red,Green"))
                    defaultModes.add(CustomMode(47, "G-B Water", "Water Effects", "up", "Green,Blue"))
                    defaultModes.add(CustomMode(48, "G-B Water Back", "Water Effects", "down", "Green,Blue"))
                    defaultModes.add(CustomMode(49, "Y-B Water", "Water Effects", "up", "Yellow,Blue"))
                    defaultModes.add(CustomMode(50, "Y-B Water Back", "Water Effects", "down", "Yellow,Blue"))
                    defaultModes.add(CustomMode(51, "Y-C Water", "Water Effects", "up", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(52, "Y-C Water Back", "Water Effects", "down", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(53, "C-P Water", "Water Effects", "up", "Cyan,Purple"))
                    defaultModes.add(CustomMode(54, "C-P Water Back", "Water Effects", "down", "Cyan,Purple"))
                    defaultModes.add(CustomMode(55, "White Water", "Water Effects", "up", "White"))
                    defaultModes.add(CustomMode(56, "White Water Back", "Water Effects", "down", "White"))
                    defaultModes.add(CustomMode(143, "W-R-W Flow", "Flow Effects", "up", "Red,White"))
                    defaultModes.add(CustomMode(144, "W-R-W Flow Back", "Flow Effects", "down", "Red,White"))
                    defaultModes.add(CustomMode(145, "W-G-W Flow", "Flow Effects", "up", "Green,White"))
                    defaultModes.add(CustomMode(146, "W-G-W Flow Back", "Flow Effects", "down", "Green,White"))
                    defaultModes.add(CustomMode(147, "W-B-W Flow", "Flow Effects", "up", "Blue,White"))
                    defaultModes.add(CustomMode(148, "W-B-W Flow Back", "Flow Effects", "down", "Blue,White"))
                    defaultModes.add(CustomMode(149, "W-Y-W Flow", "Flow Effects", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(150, "W-Y-W Flow Back", "Flow Effects", "down", "Yellow,White"))
                    defaultModes.add(CustomMode(151, "W-C-W Flow", "Flow Effects", "up", "Cyan,White"))
                    defaultModes.add(CustomMode(152, "W-C-W Flow Back", "Flow Effects", "down", "Cyan,White"))
                    defaultModes.add(CustomMode(153, "W-P-W Flow", "Flow Effects", "up", "Purple,White"))
                    defaultModes.add(CustomMode(154, "W-P-W Flow Back", "Flow Effects", "down", "Purple,White"))
                    defaultModes.add(CustomMode(155, "R-W-R Flow", "Flow Effects", "up", "Red,White"))
                    defaultModes.add(CustomMode(156, "R-W-R Flow Back", "Flow Effects", "down", "Red,White"))
                    defaultModes.add(CustomMode(157, "G-W-G Flow", "Flow Effects", "up", "Green,White"))
                    defaultModes.add(CustomMode(158, "G-W-G Flow Back", "Flow Effects", "down", "Green,White"))
                    defaultModes.add(CustomMode(159, "B-W-B Flow", "Flow Effects", "up", "Blue,White"))
                    defaultModes.add(CustomMode(160, "B-W-B Flow Back", "Flow Effects", "down", "Blue,White"))
                    defaultModes.add(CustomMode(161, "Y-W-Y Flow", "Flow Effects", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(162, "Y-W-Y Flow Back", "Flow Effects", "down", "Yellow,White"))
                    defaultModes.add(CustomMode(163, "C-W-C Flow", "Flow Effects", "up", "Cyan,White"))
                    defaultModes.add(CustomMode(164, "C-W-C Flow Back", "Flow Effects", "down", "Cyan,White"))
                    defaultModes.add(CustomMode(165, "P-W-P Flow", "Flow Effects", "up", "Purple,White"))
                    defaultModes.add(CustomMode(166, "P-W-P Flow Back", "Flow Effects", "down", "Purple,White"))
                    defaultModes.add(CustomMode(23, "7-Color Tail", "Tail Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(24, "7-Color Tail Back", "Tail Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(25, "Red Tail", "Tail Effects", "up", "Red"))
                    defaultModes.add(CustomMode(26, "Red Tail Back", "Tail Effects", "down", "Red"))
                    defaultModes.add(CustomMode(27, "Green Tail", "Tail Effects", "up", "Green"))
                    defaultModes.add(CustomMode(28, "Green Tail Back", "Tail Effects", "down", "Green"))
                    defaultModes.add(CustomMode(29, "Blue Tail", "Tail Effects", "up", "Blue"))
                    defaultModes.add(CustomMode(30, "Blue Tail Back", "Tail Effects", "down", "Blue"))
                    defaultModes.add(CustomMode(31, "Yellow Tail", "Tail Effects", "up", "Yellow"))
                    defaultModes.add(CustomMode(32, "Yellow Tail Back", "Tail Effects", "down", "Yellow"))
                    defaultModes.add(CustomMode(33, "Cyan Tail", "Tail Effects", "up", "Cyan"))
                    defaultModes.add(CustomMode(34, "Cyan Tail Back", "Tail Effects", "down", "Cyan"))
                    defaultModes.add(CustomMode(35, "Purple Tail", "Tail Effects", "up", "Purple"))
                    defaultModes.add(CustomMode(36, "Purple Tail Back", "Tail Effects", "down", "Purple"))
                    defaultModes.add(CustomMode(37, "White Tail", "Tail Effects", "up", "White"))
                    defaultModes.add(CustomMode(38, "White Tail Back", "Tail Effects", "down", "White"))
                    defaultModes.add(CustomMode(89, "Red Running", "Forward Chase", "up", "Red"))
                    defaultModes.add(CustomMode(91, "Green Running", "Forward Chase", "up", "Green"))
                    defaultModes.add(CustomMode(93, "Blue Running", "Forward Chase", "up", "Blue"))
                    defaultModes.add(CustomMode(95, "Yellow Running", "Forward Chase", "up", "Yellow"))
                    defaultModes.add(CustomMode(97, "Cyan Running", "Forward Chase", "up", "Cyan"))
                    defaultModes.add(CustomMode(99, "Purple Running", "Forward Chase", "up", "Purple"))
                    defaultModes.add(CustomMode(101, "White Running", "Forward Chase", "up", "White"))
                    defaultModes.add(CustomMode(103, "7-Color Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(105, "R-G-B Running", "Forward Chase", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(107, "Y-C-P Running", "Forward Chase", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(109, "B-P-C-Y Running", "Forward Chase", "up", "Blue,Purple,Cyan,Yellow"))
                    defaultModes.add(CustomMode(111, "B-G-C-Y Running", "Forward Chase", "up", "Blue,Green,Cyan,Yellow"))
                    defaultModes.add(CustomMode(113, "Red-Dot in White Running", "Forward Chase", "up", "Red,White"))
                    defaultModes.add(CustomMode(115, "Green-Dot in Red Running", "Forward Chase", "up", "Red,Green"))
                    defaultModes.add(CustomMode(117, "Blue-Dot in Green Running", "Forward Chase", "up", "Green,Blue"))
                    defaultModes.add(CustomMode(119, "Yellow-Dot in Blue Running", "Forward Chase", "up", "Blue,Yellow,White"))
                    defaultModes.add(CustomMode(121, "Cyan-Dot in Yellow Running", "Forward Chase", "up", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(123, "Purple-Dot in Cyan Running", "Forward Chase", "up", "Cyan,Purple"))
                    defaultModes.add(CustomMode(125, "White-Dot in Purple Running", "Forward Chase", "up", "Purple,White"))
                    defaultModes.add(CustomMode(127, "White-Dot in Red Running", "Forward Chase", "up", "Red,White"))
                    defaultModes.add(CustomMode(129, "7-Color in Red Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(131, "7-Color in Green Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(133, "7-Color in Blue Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(135, "7-Color in Yellow Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(137, "7-Color in Cyan Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(139, "7-Color in Purple Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(141, "7-Color in White Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(167, "Green-Dot in Blue Running", "Forward Chase", "up", "Green,Blue"))
                    defaultModes.add(CustomMode(169, "Green-Dot in Red Running", "Forward Chase", "up", "Red,Green"))
                    defaultModes.add(CustomMode(171, "Red-Dot in Blue Running", "Forward Chase", "up", "Red,Blue"))
                    defaultModes.add(CustomMode(173, "Cyan-Dot in Yellow Running", "Forward Chase", "up", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(175, "Yellow-Dot in Purple Running", "Forward Chase", "up", "Yellow,Purple,White"))
                    defaultModes.add(CustomMode(177, "White-Dot in Yellow Running", "Forward Chase", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(179, "Yellow-Dot in White Running", "Forward Chase", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(90, "Red Run Back", "Reverse Chase", "down", "Red"))
                    defaultModes.add(CustomMode(92, "Green Run Back", "Reverse Chase", "down", "Green"))
                    defaultModes.add(CustomMode(94, "Blue Run Back", "Reverse Chase", "down", "Blue"))
                    defaultModes.add(CustomMode(96, "Yellow Run Back", "Reverse Chase", "down", "Yellow"))
                    defaultModes.add(CustomMode(98, "Cyan Run Back", "Reverse Chase", "down", "Cyan"))
                    defaultModes.add(CustomMode(100, "Purple Run Back", "Reverse Chase", "down", "Purple"))
                    defaultModes.add(CustomMode(102, "White Run Back", "Reverse Chase", "down", "White"))
                    defaultModes.add(CustomMode(104, "7-Color Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(106, "R-G-B Run Back", "Reverse Chase", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(108, "Y-C-P Run Back", "Reverse Chase", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(110, "B-P-C-Y Run Back", "Reverse Chase", "down", "Blue,Purple,Cyan,Yellow"))
                    defaultModes.add(CustomMode(112, "B-G-C-Y Run Back", "Reverse Chase", "down", "Blue,Green,Cyan,Yellow"))
                    defaultModes.add(CustomMode(114, "Red-Dot in White Run Back", "Reverse Chase", "down", "Red,White"))
                    defaultModes.add(CustomMode(116, "Green-Dot in Red Run Back", "Reverse Chase", "down", "Red,Green"))
                    defaultModes.add(CustomMode(118, "Blue-Dot in Green Run Back", "Reverse Chase", "down", "Green,Blue"))
                    defaultModes.add(CustomMode(120, "Yellow-Dot in Blue Run Back", "Reverse Chase", "down", "Blue,Yellow,White"))
                    defaultModes.add(CustomMode(122, "Cyan-Dot in Yellow Run Back", "Reverse Chase", "down", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(124, "Purple-Dot in Cyan Run Back", "Reverse Chase", "down", "Cyan,Purple"))
                    defaultModes.add(CustomMode(126, "White-Dot in Purple Run Back", "Reverse Chase", "down", "Purple,White"))
                    defaultModes.add(CustomMode(128, "White-Dot in Red Run Back", "Reverse Chase", "down", "Red,White"))
                    defaultModes.add(CustomMode(130, "7-Color in Red Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(132, "7-Color in Green Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(134, "7-Color in Blue Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(136, "7-Color in Yellow Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(138, "7-Color in Cyan Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(140, "7-Color in Purple Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(142, "7-Color in White Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(168, "Green-Dot in Blue Run Back", "Reverse Chase", "down", "Green,Blue"))
                    defaultModes.add(CustomMode(170, "Green-Dot in Red Run Back", "Reverse Chase", "down", "Red,Green"))
                    defaultModes.add(CustomMode(172, "Red-Dot in Blue Run Back", "Reverse Chase", "down", "Red,Blue"))
                    defaultModes.add(CustomMode(174, "Cyan-Dot in Yellow Run Back", "Reverse Chase", "down", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(176, "Yellow-Dot in Purple Run Back", "Reverse Chase", "down", "Yellow,Purple,White"))
                    defaultModes.add(CustomMode(178, "White-Dot in Yellow Run Back", "Reverse Chase", "down", "Yellow,White"))
                    defaultModes.add(CustomMode(180, "Yellow-Dot in White Run Back", "Reverse Chase", "down", "Yellow,White"))

                    repository.insertCustomModes(defaultModes)
                }
            }
        }

        // Initialize BluetoothAdapter
        val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // If no hardware Bluetooth available, enforce Demo Mode automatically
        if (bluetoothAdapter == null) {
            _uiState.update { it.copy(coreControl = it.coreControl.copy(isDemoMode = true)) }
            addLog("No BLE hardware found. Auto-fallback to Demo/Simulation Mode.")
        } else {
            _uiState.update { it.copy(coreControl = it.coreControl.copy(isDemoMode = false)) }
            addLog("BLE adapter loaded. Ready to scan!")
        }

        // Combine DB aliases with scanned devices
        viewModelScope.launch {
            combine(savedAliases, _uiState) { aliases, state ->
                // Map aliases to devices
                state.connectivity.scannedDevices.map { dev ->
                    val foundAlias = aliases.find { it.macAddress == dev.address }
                    dev.copy(alias = foundAlias?.aliasName)
                }
            }.collect { updatedDevices ->
                _uiState.update { it.copy(connectivity = it.connectivity.copy(scannedDevices = updatedDevices)) }
            }
        }

        // Collect and sync color calibrations
        viewModelScope.launch {
            repository.allColorCalibrations.collect { calibrations ->
                val calMap = calibrations.associateBy { it.macAddress }
                _colorCalibrations.value = calMap
            }
        }

        // Initialize with default color preset in database if empty
        viewModelScope.launch {
            savedPresets.collect { list ->
                if (list.isEmpty()) {
                    repository.insertPreset(RgbPreset(name = "Chill Sunset", red = 255, green = 110, blue = 40, brightness = 90))
                    repository.insertPreset(RgbPreset(name = "Cyberpunk Neon", red = 255, green = 0, blue = 255, brightness = 100))
                    repository.insertPreset(RgbPreset(name = "Forest Green", red = 0, green = 255, blue = 80, brightness = 75))
                    repository.insertPreset(RgbPreset(name = "Ocean Breeze", red = 0, green = 150, blue = 255, brightness = 80))
                }
            }
        }

        // Background auto-connection scanner loop
        viewModelScope.launch {
            while (true) {
                delay(10000) // check every 10 seconds
                val hasDisconnectedAutoConnectDevices = savedDevices.value.any { saved ->
                    saved.isAutoConnectEnabled && 
                    !connectionManager.isManuallyDisconnected(saved.macAddress) &&
                    _uiState.value.connectivity.deviceConnectionStates[saved.macAddress] != BleConnectionState.CONNECTED &&
                    _uiState.value.connectivity.deviceConnectionStates[saved.macAddress] != BleConnectionState.CONNECTING
                }

                if (hasDisconnectedAutoConnectDevices && !_uiState.value.connectivity.isScanning) {
                    addLog("Background manager: locating disconnected auto-connect devices...")
                    startScanning()
                }
            }
        }

        // Register routing receiver and audio device callback
        val filter = android.content.IntentFilter().apply {
            addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED")
        }
        application.registerReceiver(routingReceiver, filter)
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
        // Initial run of active audio route check
        checkActiveAudioRoute()
        loadCctCalibrations()

        // Restore/re-register DeviceWriteManagers for existing active connections to survive Activity/ViewModel recreation
        activeConnections.forEach { (address, gatt) ->
            val charac = writeCharacteristics[address]
            if (charac != null) {
                val manager = DeviceWriteManager(address, gatt, charac)
                deviceWriteManagers[address] = manager
                addLog("Restored DeviceWriteManager for existing active connection: $address")
            }
        }

        // Sync initial UI state with existing active BLE connections
        if (activeConnections.isNotEmpty()) {
            val initialStates = activeConnections.keys.associateWith { BleConnectionState.CONNECTED }
            val firstConnectedAddress = activeConnections.keys.first()
            _uiState.update { s ->
                s.copy(
                    connectivity = s.connectivity.copy(
                        deviceConnectionStates = s.connectivity.deviceConnectionStates + initialStates,
                        connectionState = BleConnectionState.CONNECTED,
                        connectedDeviceAddress = firstConnectedAddress,
                        connectedDeviceName = "DuoCo Light"
                    )
                )
            }
            addLog("Synchronized UI state with ${activeConnections.size} active connection(s).")
        }

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.allSavedDevices,
                repository.allCustomModes,
                repository.allPresets
            ) { _, _, _ -> true }
                .collect {
                    _uiState.update { s -> s.copy(coreControl = s.coreControl.copy(isDbLoaded = true)) }
                }
        }

        // Reactively sync slowest connected device's pacing to shared preferences for Ambiance capture service
        viewModelScope.launch {
            _uiState.collect { state ->
                val connected = state.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }
                val slowest = if (connected.isEmpty()) {
                    0
                } else {
                    connected.keys.mapNotNull { addr ->
                        state.connectivity.devicePacingMs[addr] ?: prefsRepo.getPacingPrefInt(addr, 100)
                    }.maxOrNull() ?: 0
                }
                prefsRepo.putPacingPrefInt("slowest_connected_pacing", slowest)
            }
        }

        viewModelScope.launch {
            var lastActive = false
            com.example.ambiance.AmbianceCaptureState.isActive.collect { active ->
                if (active && !lastActive) {
                    val targetAddresses = getCurrentlyControlledDeviceAddresses()
                    targetAddresses.forEach { address ->
                        saveDeviceState(address, AutomationType.AMBIANCE)
                    }
                    val presetName = _uiState.value.ambianceSettings.ambiancePreset ?: "Balanced"
                    val label = "Ambiance - $presetName"
                    _uiState.update { current ->
                        val newMap = current.connectivity.deviceStatesMap.toMutableMap()
                        targetAddresses.forEach { address ->
                            val existing = newMap[address] ?: ActiveDeviceState()
                            newMap[address] = existing.copy(activeFeatureName = label)
                        }
                        current.copy(connectivity = current.connectivity.copy(deviceStatesMap = newMap))
                    }
                } else if (!active && lastActive) {
                    deviceAutomationMode.forEach { (address, mode) ->
                        if (mode == AutomationType.AMBIANCE) {
                            restoreDeviceState(address, AutomationType.AMBIANCE)
                        }
                    }
                }
                 lastActive = active
            }
        }
    }


    private fun loadCctCalibrations() {
        val calMap = mutableMapOf<String, CctCorrectionProfile>()
        try {
            val allPrefs = prefsRepo.getCctCalibrationAll()
            for ((address, jsonStr) in allPrefs) {
                if (jsonStr is String) {
                    val profile = CctCorrectionProfile.fromJson(jsonStr)
                    if (profile != null) {
                        calMap[address] = profile
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ColorCalibration", "Failed to load CCT calibrations", e)
        }
        _cctCalibrations.value = calMap
    }

    fun saveCctCorrectionProfile(profile: CctCorrectionProfile) {
        dispatch(RgbIntent.SaveCctCorrectionProfile(profile))
    }

    fun deleteCctCorrectionProfile(address: String) {
        dispatch(RgbIntent.DeleteCctCorrectionProfile(address))
    }

    fun addLog(message: String) {
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _telemetry.update { state ->
            val list = state.logMessages.toMutableList()
            list.add(0, "[$timeStr] $message")
            if (list.size > 50) list.removeAt(list.size - 1)
            state.copy(logMessages = list)
        }
        Log.d(TAG, message)
    }

    fun getAudioDeviceIdentifier(device: BluetoothDevice): String {
        val address = device.address
        if (address != null && address.isNotEmpty() && address != "02:00:00:00:00:00") {
            return address
        }
        val typeStr = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
            else -> "Unknown"
        }
        return "${device.name ?: "Unknown"}_$typeStr"
    }

    fun checkActiveAudioRoute() {
        dispatch(RgbIntent.CheckActiveAudioRoute)
    }

    private fun checkActiveAudioRouteHardware() {
        val audioManager = getApplication().getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetoothAudio = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (hasBluetoothAudio) {
            val device = activeBluetoothAudioDevice
            if (device != null) {
                val identifier = getAudioDeviceIdentifier(device)
                val name = device.name ?: "Bluetooth Audio Device"
                onBluetoothAudioRouteActive(name, identifier)
            } else {
                val btOutput = devices.find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                val name = btOutput?.productName?.toString() ?: "Bluetooth Audio Device"
                val identifier = "${name}_Classic"
                onBluetoothAudioRouteActive(name, identifier)
            }
        } else {
            onBluetoothAudioRouteInactive()
        }
    }

    private fun onBluetoothAudioRouteActive(name: String, identifier: String) {
        val savedDelay = prefsRepo.getCalibrationDelayPrefInt(identifier, -1)
        _uiState.update { state ->
            if (savedDelay >= 0) {
                if (state.audioSettings.bluetoothDelayMs != savedDelay) {
                    addLog("Detected active Bluetooth audio: $name. Automatically applied saved delay profile of $savedDelay ms.")
                }
                state.copy(
                    audioSettings = state.audioSettings.copy(
                        detectedAudioDeviceName = name,
                        activeAudioDeviceIdentifier = identifier,
                        bluetoothDelayMs = savedDelay,
                        totalVisualDelayMs = savedDelay + BeatDetector().lookaheadMs.toInt()
                    ),
                    calibrationFlow = state.calibrationFlow.copy(showCalibrationPrompt = false)
                )
            } else {
                if (state.audioSettings.detectedAudioDeviceName != name) {
                    addLog("Detected active Bluetooth audio: $name. No delay profile found. Setting 0ms (inviting calibration).")
                }
                state.copy(
                    audioSettings = state.audioSettings.copy(
                        detectedAudioDeviceName = name,
                        activeAudioDeviceIdentifier = identifier,
                        bluetoothDelayMs = 0,
                        totalVisualDelayMs = BeatDetector().lookaheadMs.toInt()
                    ),
                    calibrationFlow = state.calibrationFlow.copy(showCalibrationPrompt = true)
                )
            }
        }
    }

    private fun onBluetoothAudioRouteInactive() {
        _uiState.update { state ->
            if (state.audioSettings.detectedAudioDeviceName != null) {
                addLog("Bluetooth audio inactive. Reverting delay compensation to 0ms.")
            }
            state.copy(
                audioSettings = state.audioSettings.copy(
                    detectedAudioDeviceName = null,
                    activeAudioDeviceIdentifier = null,
                    bluetoothDelayMs = 0,
                    totalVisualDelayMs = BeatDetector().lookaheadMs.toInt()
                ),
                calibrationFlow = state.calibrationFlow.copy(showCalibrationPrompt = false)
            )
        }
    }

    fun dismissCalibrationPrompt() {
        dispatch(RgbIntent.DismissCalibrationPrompt)
    }

    fun startCalibrationMode() {
        dispatch(RgbIntent.StartCalibrationMode)
    }

    fun updateCalibrationSliderValue(value: Int) {
        dispatch(RgbIntent.UpdateCalibrationSliderValue(value))
    }

    fun saveCalibrationAndExit() {
        dispatch(RgbIntent.SaveCalibrationAndExit)
    }

    fun stopCalibrationMode() {
        dispatch(RgbIntent.StopCalibrationMode)
    }

    fun sendCalibrationFlash() {
        dispatch(RgbIntent.SendCalibrationFlash)
    }

    fun setDemoMode(isDemo: Boolean) {
        dispatch(RgbIntent.SetDemoMode(isDemo))
    }

    // --- SCANNING ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("Scan failed with error code: $errorCode")
            _uiState.update {
                it.copy(
                    connectivity = it.connectivity.copy(isScanning = false),
                    coreControl = it.coreControl.copy(errorMessage = "Scan failed: error $errorCode")
                )
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName
            ?: result.device.name
            ?: return

        val supported = listOf(
            "MELK-",
            "ELK-",
            "ELK-BLEDOM",
            "BLEDOM",
            "DuoCo"
        )

        if (!supported.any { name.startsWith(it) }) {
            return
        }

        val address = result.device.address
        val rssi = result.rssi

        // Since it matched our prefix, it is a suspected compatible controller
        val isDuoCo = true

        _uiState.update { state ->
            val existing = state.connectivity.scannedDevices.find { it.address == address }
            val updatedList = if (existing != null) {
                state.connectivity.scannedDevices.map {
                    if (it.address == address) it.copy(rssi = rssi, name = name) else it
                }
            } else {
                val dbAlias = savedAliases.value.find { it.macAddress == address }?.aliasName
                state.connectivity.scannedDevices + ScannedRgbDevice(address, name, rssi, isDuoCo, dbAlias)
            }
            state.copy(connectivity = state.connectivity.copy(scannedDevices = updatedList.sortedByDescending { it.rssi }))
        }

        // Auto connect if saved and enabled
        val saved = savedDevices.value.find { it.macAddress == address }
        if (saved != null && saved.isAutoConnectEnabled && !connectionManager.isManuallyDisconnected(address)) {
            val isConnected = activeConnections.containsKey(address) ||
                              _uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED
            val isConnecting = _uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTING
            if (!isConnected && !isConnecting) {
                addLog("Auto-connecting in background to $address (${saved.customName})")
                connectDevice(address)
            }
        }
    }

    fun startScanning() {
        dispatch(RgbIntent.StartScanning)
    }

    fun stopScanning() {
        dispatch(RgbIntent.StopScanning)
    }

    private fun simulateScanHardware() {
        viewModelScope.launch {
            delay(400)
            addSimulatedDevice("00:1A:7D:DA:71:11", "ELK-BLEDOM", -52, true)
            delay(300)
            addSimulatedDevice("AA:BB:CC:11:22:33", "Smart LED Bulb", -65, true)
            delay(500)
            addSimulatedDevice("FC:45:96:22:88:99", "Generic Light Strips", -80, false)
            delay(300)
            addSimulatedDevice("44:23:AA:55:12:90", "LED DMX Controller", -45, true)

            delay(10000) // scan for 10 seconds in demo
            if (_uiState.value.connectivity.isScanning) {
                dispatch(RgbIntent.StopScanning)
            }
        }
    }

    private fun startBleScanHardware() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update { it.copy(connectivity = it.connectivity.copy(isScanning = false), coreControl = it.coreControl.copy(errorMessage = "Bluetooth is disabled or unavailable")) }
            addLog("Bluetooth must be enabled to scan.")
            return
        }

        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                _uiState.update { it.copy(connectivity = it.connectivity.copy(isScanning = false), coreControl = it.coreControl.copy(errorMessage = "BLE Scanner unavailable")) }
                addLog("BLE scanner failed to initialize.")
                return
            }
            scanner.startScan(scanCallback)
            handler.postDelayed(scanTimeoutRunnable, 12000) // Scan for 12s
        } catch (e: SecurityException) {
            _uiState.update { it.copy(connectivity = it.connectivity.copy(isScanning = false), coreControl = it.coreControl.copy(errorMessage = "Missing Bluetooth Permissions")) }
            addLog("SecurityException: Permissions not granted.")
        }
    }

    private fun stopBleScanHardware() {
        handler.removeCallbacks(scanTimeoutRunnable)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            addLog("SecurityException during stopScan.")
        }
    }

    private fun addSimulatedDevice(address: String, name: String, rssi: Int, isDuoCo: Boolean) {
        _uiState.update { state ->
            if (state.connectivity.scannedDevices.any { it.address == address }) return@update state
            val dbAlias = savedAliases.value.find { it.macAddress == address }?.aliasName
            val list = state.connectivity.scannedDevices + ScannedRgbDevice(address, name, rssi, isDuoCo, dbAlias)
            state.copy(connectivity = state.connectivity.copy(scannedDevices = list.sortedByDescending { it.rssi }))
        }

        // Auto connect if saved and enabled (Demo mode)
        val saved = savedDevices.value.find { it.macAddress == address }
        if (saved != null && saved.isAutoConnectEnabled && !connectionManager.isManuallyDisconnected(address)) {
            val isConnected = activeConnections.containsKey(address) ||
                              _uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED
            val isConnecting = _uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTING
            if (!isConnected && !isConnecting) {
                addLog("Auto-connecting (Simulated) to $address (${saved.customName})")
                connectDevice(address)
            }
        }
    }

    private fun getDiagAttribution(address: String): String {
        val automation = deviceAutomationMode[address]?.name ?: "NONE"
        return "MAC=$address, Automation=$automation"
    }

    // --- CONNECTION ---

    fun connectDevice(address: String) {
        dispatch(RgbIntent.ConnectDevice(address))
    }

    private fun connectDeviceHardware(address: String) {
        connectionManager.connect(address)
        val state = _uiState.value

        com.example.DiagnosticLogger.log(
            "BLE",
            "connectDevice attempt: address=$address (${getDiagAttribution(address)})"
        )

        if (state.coreControl.isDemoMode) {
            val displayName = state.connectivity.connectedDeviceName ?: "Unknown Device"
            viewModelScope.launch {
                delay(1200) // Simulating connection lag
                dispatch(RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.CONNECTED))
                addLog("Connected (Simulated) to $displayName!")
            }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            _uiState.update { s ->
                s.copy(connectivity = s.connectivity.copy(deviceConnectionStates = s.connectivity.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED)))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = adapter.getRemoteDevice(address)
                connectionManager.connect(address)

                val gatt = device.connectGatt(getApplication(), false, gattCallback)
                activeConnections[address] = gatt
            } catch (e: SecurityException) {
                _uiState.update { s ->
                    s.copy(
                        connectivity = s.connectivity.copy(deviceConnectionStates = s.connectivity.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED)),
                        coreControl = s.coreControl.copy(errorMessage = "Permission Denied")
                    )
                }
                addLog("Gatt connect permission failed for $address.")
                com.example.DiagnosticLogger.log(
                    "BLE",
                    "connectDevice SecurityException: address=$address. ${android.util.Log.getStackTraceString(e)}"
                )
            } catch (e: Exception) {
                _uiState.update { s ->
                    s.copy(
                        connectivity = s.connectivity.copy(deviceConnectionStates = s.connectivity.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED)),
                        coreControl = s.coreControl.copy(errorMessage = e.message)
                    )
                }
                addLog("Connect error for $address: ${e.message}")
                com.example.DiagnosticLogger.log(
                    "BLE",
                    "connectDevice Exception: address=$address. ${android.util.Log.getStackTraceString(e)}"
                )
            }
        }
    }

    fun disconnectDevice(address: String) {
        dispatch(RgbIntent.DisconnectDevice(address))
    }

    private fun disconnectDeviceHardware(address: String) {
        com.example.DiagnosticLogger.log(
            "BLE",
            "disconnectDevice called: address=$address (${getDiagAttribution(address)})"
        )
        connectionManager.disconnect(address, manual = true)
        val gatt = activeConnections.remove(address)
        writeCharacteristics.remove(address)
        deviceWriteManagers.remove(address)

        if (_uiState.value.coreControl.isDemoMode) {
            viewModelScope.launch {
                delay(300)
                dispatch(RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.DISCONNECTED))
                addLog("Disconnected (Simulated) from $address.")
            }
            return
        }

        try {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    gatt?.disconnect()
                    gatt?.close()
                } catch (e: SecurityException) {
                    addLog("SecurityException inside IO disconnect coroutine of $address.")
                }
            }
            dispatch(RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.DISCONNECTED))
            addLog("Disconnected $address.")
        } catch (e: SecurityException) {
            addLog("SecurityException during disconnect of $address.")
        }
    }

    fun disconnect() {
        dispatch(RgbIntent.DisconnectAll)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleConnectionStateChange(gatt, status, newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleServicesDiscovered(gatt, status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleCharacteristicRead(gatt, characteristic, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleCharacteristicChanged(gatt, characteristic)
        }
    }

    fun handleConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        val address = gatt?.device?.address ?: return
        
        com.example.DiagnosticLogger.log(
            "BLE",
            "handleConnectionStateChange: address=$address, status=$status, newState=$newState (${getDiagAttribution(address)})"
        )
        
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            addLog("Connected to $address! Discovering services...")
            dispatch(RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.CONNECTING))
            activeConnections[address] = gatt
            retryAttempts.remove(address)
            connectionManager.setConnected(address)

            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(512)
            } catch (e: SecurityException) {
                addLog("SecurityException requesting priority or MTU for $address: ${e.message}")
            } catch (e: Exception) {
                addLog("Error requesting priority or MTU for $address: ${e.message}")
            }

            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                addLog("SecurityException: Service discovery denied for $address.")
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            addLog("Disconnected from GATT ($address).")
            val wasActive = activeConnections.containsKey(address)
            activeConnections.remove(address)
            writeCharacteristics.remove(address)
            deviceWriteManagers.remove(address)

            dispatch(RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.DISCONNECTED))

            // Exponential backoff retry logic if unexpected drop
            if (wasActive && !connectionManager.isManuallyDisconnected(address)) {
                connectionManager.disconnect(address, manual = false)
                val saved = savedDevices.value.find { it.macAddress == address }
                if (saved != null && saved.isAutoConnectEnabled) {
                    val attempt = retryAttempts.getOrDefault(address, 0)
                    if (attempt < 5) {
                        retryAttempts[address] = attempt + 1
                        val delayMs = (1000L * Math.pow(2.0, attempt.toDouble())).toLong()
                        addLog("Unexpected drop for $address. Retrying in ${delayMs / 1000}s (Attempt ${attempt + 1})...")
                        com.example.DiagnosticLogger.log(
                            "BLE",
                            "Initiating reconnect backoff retry for $address: attempt=${attempt + 1}, delayMs=$delayMs (${getDiagAttribution(address)})"
                        )
                        viewModelScope.launch {
                            delay(delayMs)
                            if (!activeConnections.containsKey(address) && savedDevices.value.find { it.macAddress == address }?.isAutoConnectEnabled == true) {
                                connectDevice(address)
                            }
                        }
                    } else {
                        addLog("Max retries reached for $address. Stopping auto-retry.")
                        com.example.DiagnosticLogger.log(
                            "BLE",
                            "Max retries (5) reached for $address. Stopping auto-retry. (${getDiagAttribution(address)})"
                        )
                    }
                }
            }
        }
    }

    fun handleServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        val address = gatt?.device?.address ?: return
        com.example.DiagnosticLogger.log(
            "BLE",
            "handleServicesDiscovered: address=$address, status=$status. (${getDiagAttribution(address)})"
        )
        if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
            addLog("Services discovered for $address.")
            findDuoCoCharacteristicForGatt(gatt)
        } else {
            addLog("Service discovery failed for $address with status $status")
        }
    }

    fun handleCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
        val address = gatt?.device?.address ?: return
        com.example.DiagnosticLogger.log(
            "BLE",
            "handleCharacteristicWrite callback: address=$address, status=$status. (${getDiagAttribution(address)})"
        )
        deviceWriteManagers[address]?.onWriteCompleted()
    }

    fun handleCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
        // Handled or ignored
    }

    fun handleCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
        // Handled or ignored
    }

    private fun registerCharacteristic(gatt: BluetoothGatt, charac: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        writeCharacteristics[address] = charac
        val manager = DeviceWriteManager(address, gatt, charac)
        deviceWriteManagers[address] = manager
        
        addLog("Found write characteristic for $address: ${charac.uuid} (Ack Supported: ${manager.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT})")
        
        _uiState.update { state ->
            state.copy(
                connectivity = state.connectivity.copy(
                    deviceConnectionStates = state.connectivity.deviceConnectionStates + (address to BleConnectionState.CONNECTED),
                    connectionState = BleConnectionState.CONNECTED,
                    connectedDeviceAddress = address,
                    connectedDeviceName = state.connectivity.scannedDevices.find { it.address == address }?.alias ?: state.connectivity.scannedDevices.find { it.address == address }?.name ?: "Unknown Device",
                    devicePacingMs = state.connectivity.devicePacingMs + (address to manager.currentPacingMs)
                )
            )
        }
    }

    private fun findDuoCoCharacteristicForGatt(gatt: BluetoothGatt) {
        val address = gatt.device.address
        var found = false
        for (service in gatt.services) {
            val charac = service.getCharacteristic(DuoCoProtocol.CHARACTERISTIC_UUID)
            if (charac != null) {
                registerCharacteristic(gatt, charac)
                found = true
                break
            }
        }
        if (!found) {
            for (service in gatt.services) {
                for (charac in service.characteristics) {
                    if (charac.uuid == DuoCoProtocol.CHARACTERISTIC_UUID) {
                        registerCharacteristic(gatt, charac)
                        found = true
                        break
                    }
                }
                if (found) break
            }
        }

        if (!found) {
            addLog("Warning: DuoCo characteristic not found for $address. Commands may not work.")
            _uiState.update { state ->
                state.copy(
                    connectivity = state.connectivity.copy(
                        deviceConnectionStates = state.connectivity.deviceConnectionStates + (address to BleConnectionState.CONNECTED),
                        connectionState = BleConnectionState.CONNECTED,
                        connectedDeviceAddress = address
                    )
                )
            }
        }
    }

    private fun syncPhysicalBulbOfDevice(gatt: BluetoothGatt) {
        val state = _uiState.value
        val powerCmd = DuoCoProtocol.createPowerCommand(state.coreControl.isPowerOn)
        val colorCmd = DuoCoProtocol.createColorCommand(state.coreControl.red, state.coreControl.green, state.coreControl.blue)
        val brightnessCmd = DuoCoProtocol.createBrightnessCommand(state.coreControl.brightness)
        val batched = powerCmd + colorCmd + brightnessCmd
        deviceWriteManagers[gatt.device.address]?.updateCommand(batched)
    }

    // --- COMMAND TRANSMISSION & OUTBOUND QUEUE ---

    fun queueCommand(command: ByteArray) {
        if (!_uiState.value.audioSettings.isAudioSyncRunning) return

        val delayMs = _uiState.value.audioSettings.totalVisualDelayMs
        if (delayMs > 0) {
            viewModelScope.launch(Dispatchers.Default) {
                delay(delayMs.toLong())
                if (!_uiState.value.audioSettings.isAudioSyncRunning) return@launch
                broadcastCommandDirect(command)
            }
        } else {
            broadcastCommandDirect(command)
        }
    }

    fun sendCommandToDeviceDirect(address: String, command: ByteArray) {
        if (_uiState.value.coreControl.isDemoMode) {
            val finalCmd = processCommandWithCalibration(address, command)
            val hexStr = finalCmd.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
            Log.d("ColorCalibration", "Simulated transmit to $address: $hexStr")
        } else {
            deviceWriteManagers[address]?.updateCommand(command)
        }
    }

    private fun broadcastCommandDirect(command: ByteArray) {
        val targetAddresses = getCurrentlyControlledDeviceAddresses()
        targetAddresses.forEach { address ->
            sceneRunners[address]?.release()
            sceneRunners.remove(address)
            
            if (activeExcludedMacs.contains(address)) return@forEach
            if (_uiState.value.coreControl.isDemoMode) {
                if (_uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED) {
                    val finalCmd = processCommandWithCalibration(address, command)
                    val hexStr = finalCmd.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
                    addLog("[Simulated Broadcast] Sent to $address: $hexStr")
                }
            } else {
                deviceWriteManagers[address]?.updateCommand(command)
            }
        }
    }

    fun getCurrentlyControlledDeviceAddresses(): List<String> {
        return savedDevices.value
            .filter { it.isActiveControlEnabled }
            .map { it.macAddress }
    }

    private var isApplyingScene = false
    private fun clearExclusionsIfNotApplyingScene() {
        if (!isApplyingScene) {
            activeExcludedMacs.clear()
        }
    }


    fun broadcastCommand(command: ByteArray, cancelRunningScenes: Boolean = true) {
        val targetAddresses = getCurrentlyControlledDeviceAddresses()
        
        targetAddresses.forEach { address ->
            if (cancelRunningScenes) {
                sceneRunners[address]?.release()
                sceneRunners.remove(address)
            }

            if (_uiState.value.coreControl.isDemoMode) {
                if (_uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED) {
                    val finalCmd = processCommandWithCalibration(address, command)
                    val hexStr = finalCmd.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
                    addLog("[Simulated Broadcast] Sent to $address: $hexStr")
                }
            } else {
                deviceWriteManagers[address]?.updateCommand(command)
            }
        }
    }

    fun setActiveFeatureName(name: String) {
        dispatch(RgbIntent.SetActiveFeatureName(name))
    }

    fun setShowFpsTracker(enabled: Boolean) {
        dispatch(RgbIntent.SetShowFpsTracker(enabled))
    }

    fun writeAmbianceColor(r: Int, g: Int, b: Int) {
        dispatch(RgbIntent.WriteAmbianceColor(r, g, b))
    }

    fun setAmbianceCaptureActive(active: Boolean) {
        dispatch(RgbIntent.SetAmbianceCaptureActive(active))
    }

    private fun sendCommand(command: ByteArray, debugName: String, cancelRunningScenes: Boolean = true) {
        val hexStr = command.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
        addLog("Send Command ($debugName): $hexStr")
        broadcastCommand(command, cancelRunningScenes)
    }

    private fun syncPhysicalBulb() {
        val state = _uiState.value
        val powerCmd = DuoCoProtocol.createPowerCommand(state.coreControl.isPowerOn)
        val colorCmd = DuoCoProtocol.createColorCommand(state.coreControl.red, state.coreControl.green, state.coreControl.blue)
        val brightnessCmd = DuoCoProtocol.createBrightnessCommand(state.coreControl.brightness)
        val batched = powerCmd + colorCmd + brightnessCmd
        broadcastCommand(batched)
    }


    // --- CONTROL INTERFACES ---

    fun setVisualizerPreset(preset: String) {
        dispatch(RgbIntent.SetVisualizerPreset(preset))
    }

    fun setAudioSmoothingAttack(value: Float) {
        dispatch(RgbIntent.SetAudioSmoothingAttack(value))
    }

    fun setAudioSmoothingDecay(value: Float) {
        dispatch(RgbIntent.SetAudioSmoothingDecay(value))
    }

    fun setAudioGammaExponent(value: Float) {
        dispatch(RgbIntent.SetAudioGammaExponent(value))
    }

    fun setAudioFlashStrength(value: Float) {
        dispatch(RgbIntent.SetAudioFlashStrength(value))
    }

    fun setVisualizerMinBrightness(value: Float) {
        dispatch(RgbIntent.SetVisualizerMinBrightness(value))
    }

    fun setVisualizerColorSpeed(value: Float) {
        dispatch(RgbIntent.SetVisualizerColorSpeed(value))
    }

    fun setAmbianceResponseSpeed(value: Float) {
        dispatch(RgbIntent.SetAmbianceResponseSpeed(value))
    }

    fun setAmbianceSmoothnessMs(value: Int) {
        dispatch(RgbIntent.SetAmbianceSmoothnessMs(value))
    }

    fun setAmbianceSaturationBoost(value: Float) {
        dispatch(RgbIntent.SetAmbianceSaturationBoost(value))
    }

    fun setAmbianceBrightnessCompensation(value: Float) {
        dispatch(RgbIntent.SetAmbianceBrightnessCompensation(value))
    }

    fun setAmbianceUpdateRateCapFps(value: Int) {
        dispatch(RgbIntent.SetAmbianceUpdateRateCapFps(value))
    }

    fun setAmbianceSceneCutSensitivity(value: Float) {
        dispatch(RgbIntent.SetAmbianceSceneCutSensitivity(value))
    }

    fun setAmbianceNoiseDeadband(value: Float) {
        dispatch(RgbIntent.SetAmbianceNoiseDeadband(value))
    }

    fun applyAmbiancePreset(
        presetId: String,
        responseSpeed: Float,
        smoothnessMs: Int,
        saturationBoost: Float,
        brightnessCompensation: Float,
        sceneCutSensitivity: Float,
        noiseDeadband: Float
    ) {
        dispatch(
            RgbIntent.ApplyAmbiancePreset(
                presetId = presetId,
                responseSpeed = responseSpeed,
                smoothnessMs = smoothnessMs,
                saturationBoost = saturationBoost,
                brightnessCompensation = brightnessCompensation,
                sceneCutSensitivity = sceneCutSensitivity,
                noiseDeadband = noiseDeadband
            )
        )
    }

    fun setIdleTriggerDelayMs(value: Long) {
        dispatch(RgbIntent.SetIdleTriggerDelayMs(value))
    }

    fun setTransmissionDelayMs(value: Int) {
        dispatch(RgbIntent.SetTransmissionDelayMs(value))
    }

    fun setNoiseGateThreshold(value: Float) {
        dispatch(RgbIntent.SetNoiseGateThreshold(value))
    }

    fun setBeatThresholdMultiplier(value: Float) {
        dispatch(RgbIntent.SetBeatThresholdMultiplier(value))
    }

    fun setBeatCooldownMs(value: Int) {
        dispatch(RgbIntent.SetBeatCooldownMs(value))
    }

    fun setBassGain(value: Float) {
        dispatch(RgbIntent.SetBassGain(value))
    }

    fun setMidGain(value: Float) {
        dispatch(RgbIntent.SetMidGain(value))
    }

    fun setHighGain(value: Float) {
        dispatch(RgbIntent.SetHighGain(value))
    }

    fun setAutoGainEnabled(value: Boolean) {
        dispatch(RgbIntent.SetAutoGainEnabled(value))
    }

    fun setPaletteCyclingEnabled(value: Boolean) {
        dispatch(RgbIntent.SetPaletteCyclingEnabled(value))
    }

    fun setLogarithmicScalingEnabled(value: Boolean) {
        dispatch(RgbIntent.SetLogarithmicScalingEnabled(value))
    }

    fun setBluetoothDelayMs(value: Int) {
        dispatch(RgbIntent.SetBluetoothDelayMs(value))
    }

    fun resetAudioPipelineSettings() {
        dispatch(RgbIntent.ResetAudioPipelineSettings)
    }

    fun resetCalibrationSettings() {
        dispatch(RgbIntent.ResetCalibrationSettings)
    }

    private fun stopAmbianceIfActive(restoreState: Boolean = true) {
        if (com.example.ambiance.AmbianceCaptureState.isActive.value) {
            if (!restoreState) {
                deviceAutomationMode.forEach { (address, mode) ->
                    if (mode == AutomationType.AMBIANCE) {
                        deviceAutomationMode.remove(address)
                    }
                }
            }
            com.example.ambiance.AmbianceCaptureService.stop(getApplication())
        }
    }

    fun setPower(isOn: Boolean) {
        dispatch(RgbIntent.SetPower(isOn))
    }

    fun setColor(r: Int, g: Int, b: Int) {
        dispatch(RgbIntent.SetColor(r, g, b))
    }

    fun setBrightness(percent: Int) {
        dispatch(RgbIntent.SetBrightness(percent))
    }

    fun setMode(modeIndex: Int) {
        dispatch(RgbIntent.SetMode(modeIndex))
    }

    fun setModeSpeed(speed: Int) {
        dispatch(RgbIntent.SetModeSpeed(speed))
    }

    fun setWarmth(percent: Int) {
        dispatch(RgbIntent.SetWarmth(percent))
    }


    // --- SCENES LOGIC ---
    fun captureCurrentDeviceState(groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean = false, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false): DeviceSceneState {
        val s = _uiState.value
        val isPowerOffMode = groupA == "Power Off"
        val isAnyMode = groupA != null && groupA != "None" && !isPowerOffMode

        return DeviceSceneState(
            groupASelection = groupA,
            colorR = if (groupA == "Colour") s.coreControl.red else null,
            colorG = if (groupA == "Colour") s.coreControl.green else null,
            colorB = if (groupA == "Colour") s.coreControl.blue else null,
            cctWarmth = if (groupA == "CCT") s.coreControl.warmth else null,
            modeIndex = if (groupA == "HardwareMode") s.coreControl.modeIndex else null,
            modeSpeed = if (groupA == "HardwareMode" && includeModeSpeed) s.coreControl.modeSpeed else null,
            audioPreset = if (groupA == "Audio") s.audioSettings.visualizerPreset else null,
            audioAttack = if (includeAudioSettings || groupA == "Audio") s.audioSettings.audioSmoothingAttack else null,
            audioDecay = if (includeAudioSettings || groupA == "Audio") s.audioSettings.audioSmoothingDecay else null,
            audioFlash = if (includeAudioSettings || groupA == "Audio") s.audioSettings.audioFlashStrength else null,
            musicMode = if (groupA == "Audio") s.audioSettings.musicMode else null,
            ambianceIsOn = if (groupA == "Ambiance") com.example.ambiance.AmbianceCaptureState.isActive.value else null,
            ambiancePreset = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambiancePreset else null,
            ambianceResponseSpeed = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceResponseSpeed else null,
            ambianceSmoothnessMs = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceSmoothnessMs else null,
            ambianceSaturationBoost = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceSaturationBoost else null,
            ambianceBrightnessCompensation = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceBrightnessCompensation else null,
            ambianceUpdateRateCapFps = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceUpdateRateCapFps else null,
            ambianceSceneCutSensitivity = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceSceneCutSensitivity else null,
            ambianceNoiseDeadband = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSettings.ambianceNoiseDeadband else null,
            noiseGateThreshold = if (includeAudioSettings) s.audioSettings.noiseGateThreshold else null,
            bassGain = if (includeAudioSettings) s.audioSettings.bassGain else null,
            midGain = if (includeAudioSettings) s.audioSettings.midGain else null,
            highGain = if (includeAudioSettings) s.audioSettings.highGain else null,
            isAutoGainEnabled = if (includeAudioSettings) s.audioSettings.isAutoGainEnabled else null,
            isPaletteCyclingEnabled = if (includeAudioSettings) s.audioSettings.isPaletteCyclingEnabled else null,
            isLogarithmicScalingEnabled = if (includeAudioSettings) s.audioSettings.isLogarithmicScalingEnabled else null,
            audioGammaExponent = if (includeAudioSettings) s.audioSettings.audioGammaExponent else null,
            visualizerMinBrightness = if (includeAudioSettings) s.audioSettings.visualizerMinBrightness else null,
            visualizerColorSpeed = if (includeAudioSettings) s.audioSettings.visualizerColorSpeed else null,
            bluetoothDelayMs = if (includeCalibrationSettings) s.audioSettings.bluetoothDelayMs else null,
            brightness = if (includeBrightness) s.coreControl.brightness else null,
            isPowerOn = if (isPowerOffMode) false else if (isAnyMode) true else null
        )
    }

    fun saveScene(name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false, chainedSceneId: String? = null, chainedSceneDelaySeconds: Int? = null, chainedSceneReverseId: String? = null): String {
        val stateSnapshot = captureCurrentDeviceState(groupA, includeBrightness, includeModeSpeed, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings)
        val newId = UUID.randomUUID().toString()
        val newScene = AppScene(
            id = newId,
            name = name,
            targetScope = targetScope,
            selectedDeviceMacs = selectedDeviceMacs,
            state = stateSnapshot,
            chainedSceneId = chainedSceneId,
            chainedSceneReverseId = chainedSceneReverseId,
            chainedSceneDelaySeconds = chainedSceneDelaySeconds
        )
        
        val current = _scenes.value.toMutableList()
        current.add(newScene)
        _scenes.value = current
        prefsRepo.saveScenes(current)
        return newId
    }

    fun updateScene(sceneId: String, name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false, chainedSceneId: String? = null, chainedSceneDelaySeconds: Int? = null, chainedSceneReverseId: String? = null) {
        val stateSnapshot = captureCurrentDeviceState(groupA, includeBrightness, includeModeSpeed, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings)
        
        val current = _scenes.value.toMutableList()
        val index = current.indexOfFirst { it.id == sceneId }
        if (index != -1) {
            val updatedScene = current[index].copy(
                name = name,
                targetScope = targetScope,
                selectedDeviceMacs = selectedDeviceMacs,
                state = stateSnapshot,
                chainedSceneId = chainedSceneId,
                chainedSceneReverseId = chainedSceneReverseId,
                chainedSceneDelaySeconds = chainedSceneDelaySeconds
            )
            current[index] = updatedScene
            _scenes.value = current
            prefsRepo.saveScenes(current)
        }
    }

    fun deleteScene(sceneId: String) {
        val current = _scenes.value.filter { it.id != sceneId }
        _scenes.value = current
        prefsRepo.saveScenes(current)
    }

    fun renameScene(sceneId: String, newName: String) {
        val current = _scenes.value.map { if (it.id == sceneId) it.copy(name = newName) else it }
        _scenes.value = current
        prefsRepo.saveScenes(current)
    }

    fun saveAiSceneSequence(params: ProceduralSceneParams, sceneName: String, explanation: String): AppScene? {
        if (params.palette.isEmpty()) return null
        
        val sceneId = UUID.randomUUID().toString()
        
        val state = DeviceSceneState(
            groupASelection = "Colour",
            colorR = params.palette.first().first,
            colorG = params.palette.first().second,
            colorB = params.palette.first().third,
            isPowerOn = true,
            animatedSequence = params
        )
        
        val scene = AppScene(
            id = sceneId,
            name = sceneName,
            targetScope = "ALL_DEVICES", // Default for AI scenes
            state = state
        )
        
        val current = _scenes.value.toMutableList()
        current.add(scene)
        _scenes.value = current
        prefsRepo.saveScenes(current)
        
        return scene
    }

    fun updateAiSceneSequence(sceneId: String, params: ProceduralSceneParams, sceneName: String) {
        if (params.palette.isEmpty()) return
        val current = _scenes.value.toMutableList()
        val index = current.indexOfFirst { it.id == sceneId }
        if (index != -1) {
            val oldScene = current[index]
            val updatedState = oldScene.state.copy(
                animatedSequence = params,
                colorR = params.palette.first().first,
                colorG = params.palette.first().second,
                colorB = params.palette.first().third
            )
            val updatedScene = oldScene.copy(
                name = sceneName,
                state = updatedState
            )
            current[index] = updatedScene
            _scenes.value = current
            prefsRepo.saveScenes(current)
        }
    }

    fun applyScene(scene: AppScene, isReversing: Boolean = false) {
        cancelSceneChain()
        isApplyingScene = true
        activeExcludedMacs.clear()

        val targetMacs = if (scene.targetScope == "SELECT_DEVICES" && scene.selectedDeviceMacs != null) {
            scene.selectedDeviceMacs
        } else {
            getCurrentlyControlledDeviceAddresses()
        }

        if (scene.state.groupASelection == "Audio" || scene.state.groupASelection == "Ambiance") {
            val allConnected = _uiState.value.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }.keys
            activeExcludedMacs.addAll(allConnected.filter { !targetMacs.contains(it) })
            applyDeviceState(scene.state, null)
        } else {
            applyDeviceState(scene.state, targetMacs)
            
            // Fix: Update relevant global UI state fields when applying HardwareMode/Colour/CCT scenes
            _uiState.update { current ->
                var updated = current.coreControl
                when (scene.state.groupASelection) {
                    "Colour" -> {
                        if (scene.state.colorR != null && scene.state.colorG != null && scene.state.colorB != null) {
                            updated = updated.copy(
                                activeFeatureName = "Colour",
                                red = scene.state.colorR,
                                green = scene.state.colorG,
                                blue = scene.state.colorB
                            )
                        }
                    }
                    "CCT" -> {
                        if (scene.state.cctWarmth != null) {
                            val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(scene.state.cctWarmth)
                            val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                            updated = updated.copy(
                                activeFeatureName = "CCT",
                                warmth = scene.state.cctWarmth,
                                red = rgb[0],
                                green = rgb[1],
                                blue = rgb[2]
                            )
                        }
                    }
                    "HardwareMode" -> {
                        if (scene.state.modeIndex != null) {
                            val modeName = customModes.value.find { it.byteValue == scene.state.modeIndex }?.name ?: "Mode"
                            updated = updated.copy(
                                activeFeatureName = modeName,
                                modeIndex = scene.state.modeIndex
                            )
                        }
                        if (scene.state.modeSpeed != null) {
                            updated = updated.copy(modeSpeed = scene.state.modeSpeed)
                        }
                    }
                }
                if (scene.state.brightness != null) {
                    updated = updated.copy(brightness = scene.state.brightness)
                }
                if (scene.state.isPowerOn != null) {
                    updated = updated.copy(isPowerOn = scene.state.isPowerOn)
                }

                                    prefsRepo.putAppStatePrefString("active_feature_name", updated.activeFeatureName)
                    prefsRepo.putAppStatePrefInt("red", updated.red)
                    prefsRepo.putAppStatePrefInt("green", updated.green)
                    prefsRepo.putAppStatePrefInt("blue", updated.blue)
                    prefsRepo.putAppStatePrefInt("mode_index", updated.modeIndex)
                    prefsRepo.putAppStatePrefInt("mode_speed", updated.modeSpeed)
                    prefsRepo.putAppStatePrefInt("warmth", updated.warmth)
                    prefsRepo.putAppStatePrefInt("brightness", updated.brightness)

                current.copy(coreControl = updated)
            }
        }

        var nextIsReversing = isReversing
        val nextSceneId = if (isReversing && scene.chainedSceneReverseId != null) {
            scene.chainedSceneReverseId
        } else if (!isReversing && scene.chainedSceneId != null) {
            scene.chainedSceneId
        } else if (!isReversing && scene.chainedSceneReverseId != null) {
            nextIsReversing = true
            scene.chainedSceneReverseId
        } else if (isReversing && scene.chainedSceneId != null) {
            nextIsReversing = false
            scene.chainedSceneId
        } else {
            null
        }

        if (nextSceneId != null) {
            val targetScene = _scenes.value.find { it.id == nextSceneId }
            if (targetScene != null) {
                sceneChainJob = viewModelScope.launch {
                    val delaySeconds = scene.chainedSceneDelaySeconds ?: 0
                    if (delaySeconds <= 0) {
                        delay(50L)
                    } else {
                        delay(delaySeconds.toLong() * 1000L)
                    }
                    applyScene(targetScene, nextIsReversing)
                }
            }
        }
        isApplyingScene = false
    }

    private fun updateDeviceStateInMap(macAddress: String, state: DeviceSceneState) {
        _uiState.update { current ->
            val existing = current.connectivity.deviceStatesMap[macAddress] ?: ActiveDeviceState(
                activeFeatureName = current.coreControl.activeFeatureName,
                red = current.coreControl.red,
                green = current.coreControl.green,
                blue = current.coreControl.blue,
                warmth = current.coreControl.warmth,
                modeIndex = current.coreControl.modeIndex,
                brightness = current.coreControl.brightness,
                isPowerOn = current.coreControl.isPowerOn
            )
            
            var updated = existing
            
            if (state.brightness != null) {
                updated = updated.copy(brightness = state.brightness)
            }
            if (state.isPowerOn != null) {
                updated = updated.copy(isPowerOn = state.isPowerOn)
            }
            
            when (state.groupASelection) {
                "Colour" -> {
                    if (state.colorR != null && state.colorG != null && state.colorB != null) {
                        updated = updated.copy(
                            activeFeatureName = "Colour",
                            red = state.colorR,
                            green = state.colorG,
                            blue = state.colorB
                        )
                    }
                }
                "CCT" -> {
                    if (state.cctWarmth != null) {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(state.cctWarmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        updated = updated.copy(
                            activeFeatureName = "CCT",
                            warmth = state.cctWarmth,
                            red = rgb[0],
                            green = rgb[1],
                            blue = rgb[2]
                        )
                    }
                }
                "HardwareMode" -> {
                    if (state.modeIndex != null) {
                        val modeName = customModes.value.find { it.byteValue == state.modeIndex }?.name ?: "Mode"
                        updated = updated.copy(
                            activeFeatureName = modeName,
                            modeIndex = state.modeIndex
                        )
                    }
                }
                "Audio" -> {
                    if (state.audioPreset != null) {
                        updated = updated.copy(
                            activeFeatureName = "Audio - ${state.audioPreset}"
                        )
                    }
                }
                "Ambiance" -> {
                    if (state.ambianceIsOn == true) {
                        updated = updated.copy(
                            activeFeatureName = "Ambiance - ${state.ambiancePreset ?: "Balanced"}"
                        )
                    } else if (state.ambianceIsOn == false) {
                        updated = updated.copy(
                            activeFeatureName = "Colour"
                        )
                    }
                }
            }
            
            val newMap = current.connectivity.deviceStatesMap.toMutableMap()
            newMap[macAddress] = updated
            current.copy(connectivity = current.connectivity.copy(deviceStatesMap = newMap))
        }
    }

    private fun applyDeviceState(state: DeviceSceneState, targetMacsList: List<String>?) {
        val macs = targetMacsList ?: getCurrentlyControlledDeviceAddresses()

        if (targetMacsList != null) {
            macs.forEach { mac -> updateDeviceStateInMap(mac, state) }
        }

        macs.forEach { mac ->
            sceneRunners[mac]?.release()
            sceneRunners.remove(mac)
        }
        
        if (state.animatedSequence != null) {
            val runner = SceneAnimationRunner(
                macAddresses = macs,
                sequence = state.animatedSequence,
                sendCommand = { m, cmd -> sendCommandToDeviceDirect(m, cmd) },
                saveState = { m, p, r, g, b, w -> 
                    viewModelScope.launch {
                        val currentB = deviceStateStore.getState(m)?.brightness ?: 100
                        deviceStateStore.saveState(m, p, r, g, b, w, currentB)
                    }
                }
            )
            macs.forEach { mac ->
                sceneRunners[mac] = runner
            }
            runner.start()
        }

        // Apply Power (Group B - also includes power)
        state.isPowerOn?.let { isOn ->
            if (targetMacsList == null) setPower(isOn)
            else macs.forEach { sendCommandToDeviceDirect(it, DuoCoProtocol.createPowerCommand(isOn)) }
        }

        // Group B
        state.brightness?.let { br -> 
            if (targetMacsList == null) setBrightness(br) 
            else macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createBrightnessCommand(br)) }
        }

        // Group A
        if (state.animatedSequence != null) {
            return // Skip applying static colour/mode if an animated sequence is running
        }

        when (state.groupASelection) {
            "Colour" -> {
                if (state.colorR != null && state.colorG != null && state.colorB != null) {
                    if (targetMacsList == null) setColor(state.colorR, state.colorG, state.colorB)
                    else macs.forEach { sendCommandToDeviceDirect(it, DuoCoProtocol.createColorCommand(state.colorR, state.colorG, state.colorB)) }
                }
            }
            "CCT" -> {
                state.cctWarmth?.let { warmth ->
                    if (targetMacsList == null) setWarmth(warmth)
                    // Wait, warmth command for direct is complex (needs applying calibration logic directly? No, setWarmth applies globally)
                    // Let's just create color command for warmth
                    else {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(warmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        val mappedRed = rgb[0]
                        val mappedGreen = rgb[1]
                        val mappedBlue = rgb[2]
                        macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createColorCommand(mappedRed, mappedGreen, mappedBlue)) }
                    }
                }
            }
            "HardwareMode" -> {
                state.modeIndex?.let { mi ->
                    if (targetMacsList == null) setMode(mi)
                    else macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createModeCommand(mi)) }
                }
                state.modeSpeed?.let { ms ->
                    if (targetMacsList == null) setModeSpeed(ms)
                    else macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createModeSpeedCommand(ms)) }
                }
            }
            "Audio" -> {
                if (targetMacsList == null) {
                    state.musicMode?.let { startMusicSync(it) }
                    state.audioPreset?.let { setVisualizerPreset(it) }
                }
            }
            "Ambiance" -> {
                if (targetMacsList == null) {
                    // Start or stop ambiance based on state.ambianceIsOn
                    if (state.ambianceIsOn == true && !com.example.ambiance.AmbianceCaptureState.isActive.value) {
                        // Ambiance starting requires intent, we can't fully trigger it from here without intent
                        // But we can apply the preset
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(getApplication(), "Ambiance settings applied — tap Ambiance to start capture", android.widget.Toast.LENGTH_LONG).show()
                        }
                        applyAmbiancePreset(
                            state.ambiancePreset ?: "Balanced",
                            state.ambianceResponseSpeed ?: 0.5f,
                            state.ambianceSmoothnessMs ?: 150,
                            state.ambianceSaturationBoost ?: 1.4f,
                            state.ambianceBrightnessCompensation ?: 1.0f,
                            state.ambianceSceneCutSensitivity ?: 110.0f,
                            state.ambianceNoiseDeadband ?: 0.10f
                        )
                        // Restoring the Scene's saved FPS value
                        val fps = state.ambianceUpdateRateCapFps ?: 20
                        _uiState.update { it.copy(ambianceSettings = it.ambianceSettings.copy(ambianceUpdateRateCapFps = fps)) }
                        prefsRepo.putAmbiancePrefInt("update_rate_cap_fps", fps)
                    } else if (state.ambianceIsOn == false && com.example.ambiance.AmbianceCaptureState.isActive.value) {
                        com.example.ambiance.AmbianceCaptureService.stop(getApplication())
                    }
                }
            }
        }
    
        // Independent App-level Settings
        // Ambiance Settings
        state.ambianceResponseSpeed?.let { setAmbianceResponseSpeed(it) }
        state.ambianceSmoothnessMs?.let { setAmbianceSmoothnessMs(it) }
        state.ambianceSaturationBoost?.let { setAmbianceSaturationBoost(it) }
        state.ambianceBrightnessCompensation?.let { setAmbianceBrightnessCompensation(it) }
        state.ambianceUpdateRateCapFps?.let { setAmbianceUpdateRateCapFps(it) }
        state.ambianceSceneCutSensitivity?.let { setAmbianceSceneCutSensitivity(it) }

        // Audio Settings
        state.audioAttack?.let { setAudioSmoothingAttack(it) }
        state.audioDecay?.let { setAudioSmoothingDecay(it) }
        state.audioFlash?.let { setAudioFlashStrength(it) }
        state.noiseGateThreshold?.let { setNoiseGateThreshold(it) }
        state.bassGain?.let { setBassGain(it) }
        state.midGain?.let { setMidGain(it) }
        state.highGain?.let { setHighGain(it) }
        state.isAutoGainEnabled?.let { setAutoGainEnabled(it) }
        state.isPaletteCyclingEnabled?.let { setPaletteCyclingEnabled(it) }
        state.isLogarithmicScalingEnabled?.let { setLogarithmicScalingEnabled(it) }
        state.audioGammaExponent?.let { setAudioGammaExponent(it) }
        state.visualizerMinBrightness?.let { setVisualizerMinBrightness(it) }
        state.visualizerColorSpeed?.let { setVisualizerColorSpeed(it) }

        // Calibration Settings
        state.bluetoothDelayMs?.let { setBluetoothDelayMs(it) }
    }
    // --- ROOM DATABASE OPERATIONS ---

    fun savePreset(name: String) {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            val preset = RgbPreset(
                name = name,
                red = state.coreControl.red,
                green = state.coreControl.green,
                blue = state.coreControl.blue,
                brightness = state.coreControl.brightness,
                modeIndex = state.coreControl.modeIndex
            )
            repository.insertPreset(preset)
            addLog("Saved Preset: '$name'")
        }
    }

    fun deletePreset(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePresetById(id)
            addLog("Deleted Preset ID: $id")
        }
    }

    fun saveColorCalibration(calibration: ColorCalibration) {
        dispatch(RgbIntent.SaveColorCalibration(calibration))
    }

    fun deleteColorCalibration(macAddress: String) {
        dispatch(RgbIntent.DeleteColorCalibration(macAddress))
    }

    fun fit3x3Matrix(
        targets: List<IntArray>,
        measured: List<IntArray>
    ): FloatArray {
        return com.example.core.calibration.CalibrationMatrixSolver.fit3x3Matrix(targets, measured)
    }

    fun applyPreset(preset: RgbPreset) {
        _uiState.update {
            it.copy(
                coreControl = it.coreControl.copy(
                    red = preset.red,
                    green = preset.green,
                    blue = preset.blue,
                    brightness = preset.brightness,
                    modeIndex = preset.modeIndex
                )
            )
        }
                    prefsRepo.putAppStatePrefInt("red", preset.red)
            prefsRepo.putAppStatePrefInt("green", preset.green)
            prefsRepo.putAppStatePrefInt("blue", preset.blue)
            prefsRepo.putAppStatePrefInt("brightness", preset.brightness)
            prefsRepo.putAppStatePrefInt("mode_index", preset.modeIndex)
        addLog("Applied Preset: '${preset.name}'")
        
        // Send commands
        syncPhysicalBulb()
    }

    fun saveDeviceAlias(address: String, customName: String) {
        dispatch(RgbIntent.SaveDeviceAlias(address, customName))
    }

    fun deleteDeviceAlias(address: String) {
        dispatch(RgbIntent.DeleteDeviceAlias(address))
    }

    fun toggleAutoConnect(address: String, name: String, enabled: Boolean) {
        dispatch(RgbIntent.ToggleAutoConnect(address, name, enabled))
    }

    fun toggleActiveControl(address: String, name: String, enabled: Boolean) {
        dispatch(RgbIntent.ToggleActiveControl(address, name, enabled))
    }

    fun deleteSavedDevice(address: String) {
        dispatch(RgbIntent.DeleteSavedDevice(address))
    }

    fun updateCustomMode(customMode: CustomMode) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertCustomMode(customMode)
            addLog("Updated Mode #${customMode.byteValue}: '${customMode.name}'")
        }
    }

    fun renameCategory(oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameCategory(oldName, newName)
            addLog("Renamed category '$oldName' to '$newName'")
        }
    }

    fun clearLogs() {
        _telemetry.update { it.copy(logMessages = emptyList()) }
    }

    // --- MUSIC SYNC & AUDIO CAPTURE ACTIONS ---
    // Phase 4: hardware capture (Visualizer / AudioRecord) is isolated behind
    // com.example.hardware.audio.AudioCaptureSource; the DSP pipeline that used to run inline
    // here now lives in com.example.core.audio.AudioDspProcessor. This ViewModel is now the
    // orchestrator: construct the right capture source + a fresh processor, wire callbacks that
    // reproduce the exact _uiState/_telemetry/queueCommand writes the original inline code made.
    private var audioCaptureSource: com.example.hardware.audio.AudioCaptureSource? = null
    private var audioDspProcessor: com.example.core.audio.AudioDspProcessor? = null

    // Thread-safe state for transmission
    @Volatile private var latestR = 0
    @Volatile private var latestG = 0
    @Volatile private var latestB = 0

    private var transmissionThread: Thread? = null

    fun startMusicSync(mode: String) {
        dispatch(RgbIntent.StartMusicSync(mode))
    }

    fun stopMusicSync(restoreState: Boolean = true) {
        dispatch(RgbIntent.StopMusicSync(restoreState))
    }

    private fun stopMusicSyncInternal(keepServiceRunning: Boolean = false) {
        _uiState.update { it.copy(audioSettings = it.audioSettings.copy(isAudioSyncRunning = false)) }

        transmissionThread?.interrupt()
        transmissionThread = null

        try {
            audioCaptureSource?.stop()
        } catch (e: Exception) {}
        audioCaptureSource = null
        audioDspProcessor = null

        if (!keepServiceRunning) {
            try {
                AudioCaptureService.stop(getApplication())
            } catch (e: Exception) {}
        }


    }

    fun setMusicSensitivity(value: Int) {
        dispatch(RgbIntent.SetMusicSensitivity(value))
    }

    private fun startAudioRecording(mode: String) {
        startAudioEngine(mode)
    }

    /**
     * Handles one DSP result the same way the original inline callback did: publish
     * isVisualizerIdle/musicAmplitudes/visualizerHue and queue the derived RGB command.
     */
    private fun publishAudioDspResult(result: com.example.core.audio.AudioDspResult) {
        latestR = result.r
        latestG = result.g
        latestB = result.b

        val cmd = DuoCoProtocol.createMusicColorCommand(result.r, result.g, result.b)
        queueCommand(cmd)

        _uiState.update {
            it.copy(audioSettings = it.audioSettings.copy(isVisualizerIdle = result.isIdle))
        }
        _telemetry.update {
            it.copy(
                musicAmplitudes = result.amplitudes,
                visualizerHue = if (result.isIdle) it.visualizerHue else result.hue
            )
        }
    }

    private fun startAudioEngine(mode: String) {
        _uiState.update { it.copy(audioSettings = it.audioSettings.copy(isAudioSyncRunning = true)) }

        // 1. Audio Capture and Processing
        if (mode == "on_device") {
            val source = com.example.hardware.audio.VisualizerCaptureSource(getApplication())
            val processor = com.example.core.audio.AudioDspProcessor(source.backend)
            audioCaptureSource = source
            audioDspProcessor = processor

            val started = source.start(
                onFrame = { frame ->
                    processor.process(frame, _uiState.value.audioSettings, System.currentTimeMillis())
                        ?.let { publishAudioDspResult(it) }
                },
                onLog = { addLog(it) },
                isRunning = { _uiState.value.audioSettings.isAudioSyncRunning },
                onError = {
                    audioCaptureSource = null
                    audioDspProcessor = null
                    runAudioSimulationEngine()
                }
            )
            if (!started) {
                audioCaptureSource = null
                audioDspProcessor = null
            }
            return
        }

        // 2. "phone_mic" (and any other non-"on_device" mode) — matches the original, which had
        // no further mode branching here and fell straight into the AudioRecord backend.
        val source = com.example.hardware.audio.AudioRecordCaptureSource(getApplication())
        val processor = com.example.core.audio.AudioDspProcessor(source.backend)
        audioCaptureSource = source
        audioDspProcessor = processor

        source.start(
            onFrame = { frame ->
                processor.process(frame, _uiState.value.audioSettings, System.currentTimeMillis())
                    ?.let { publishAudioDspResult(it) }
            },
            onLog = { addLog(it) },
            isRunning = { _uiState.value.audioSettings.isAudioSyncRunning },
            onError = {
                audioCaptureSource = null
                audioDspProcessor = null
                runAudioSimulationEngine()
            }
        )
    }

    // Phase 5, part B: the per-tick DSP math (smoothing, beat heuristic, hue mapping, auto-gain,
    // idle detection, 8-band amplitude simulation) moved verbatim to
    // com.example.core.audio.DemoAudioDspSimulator — this is now a thin orchestrator matching
    // startAudioEngine's shape: construct a fresh simulator per run, tick it on the same 23ms
    // cadence, and publish each result through the same publishAudioDspResult() the real capture
    // pipeline uses.
    private fun runAudioSimulationEngine() {
        addLog("Starting DSP audio simulation engine...")
        com.example.DiagnosticLogger.log("AudioCapture", "Active Engine: SIMULATION started successfully")

        val simulator = com.example.core.audio.DemoAudioDspSimulator()
        val intervalMs = 23L
        var lastSimTime = 0L

        while (!Thread.currentThread().isInterrupted && _uiState.value.audioSettings.isAudioSyncRunning) {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (lastSimTime != 0L) {
                val interval = nowElapsed - lastSimTime
                com.example.DiagnosticLogger.log(
                    "AudioCapture",
                    "Simulation tick interval: ${interval}ms, mode=simulation"
                )
            }
            lastSimTime = nowElapsed

            val startTime = System.currentTimeMillis()
            val result = simulator.process(_uiState.value.audioSettings)
            publishAudioDspResult(result)

            val elapsed = System.currentTimeMillis() - startTime
            val sleepTime = intervalMs - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    break
                }
            } else {
                Thread.yield()
            }
        }
    }

    // Phase 4: the FFT utility that used to live here moved to
    // com.example.hardware.audio.Fft (internal object, algorithm unchanged) — its only caller,
    // the AudioRecord capture loop, now lives in AudioRecordCaptureSource.

    override fun onCleared() {
        if (instance === this) {
            instance = null
        }
        super.onCleared()
        stopMusicSyncInternal()
        stopCalibrationMode()
        try {
            getApplication().unregisterReceiver(routingReceiver)
        } catch (e: Exception) {}
        try {
            val audioManager = getApplication().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {}
    }
}
