package com.example.domain

import com.example.ActiveDeviceState
import com.example.BleConnectionState
import com.example.DeviceStateStore
import com.example.RgbUiState
import com.example.SceneAnimationRunner
import com.example.core.protocol.DuoCoProtocol
import com.example.db.CustomMode
import com.example.domain.model.AppScene
import com.example.domain.model.DeviceSceneState
import com.example.domain.repository.AppPreferencesRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Command/setter passthrough for [SceneManager]. Bundles the ~30 ViewModel methods that
 * scene application drives (device writes + every audio/ambiance/calibration setter that
 * [SceneManager.applyDeviceState]'s tail invokes) so the constructor isn't 30 individual
 * lambda params. The ViewModel supplies these as method references.
 *
 * NOTE (flag at review time): the breadth of this list is a direct measure of how coupled
 * scene application is to the ViewModel's public setter surface. It was preserved verbatim
 * from the pre-extraction ViewModel — no setter calls were added, removed, or reordered.
 */
class SceneCommandSink(
    val sendCommandToDeviceDirect: (String, ByteArray) -> Unit,
    val getControlledAddresses: () -> List<String>,
    val setPower: (Boolean) -> Unit,
    val setColor: (Int, Int, Int) -> Unit,
    val setBrightness: (Int) -> Unit,
    val setMode: (Int) -> Unit,
    val setModeSpeed: (Int) -> Unit,
    val setWarmth: (Int) -> Unit,
    val startMusicSync: (String) -> Unit,
    val setVisualizerPreset: (String) -> Unit,
    val applyAmbiancePreset: (String, Float, Int, Float, Float, Float, Float) -> Unit,
    val setAmbianceResponseSpeed: (Float) -> Unit,
    val setAmbianceSmoothnessMs: (Int) -> Unit,
    val setAmbianceSaturationBoost: (Float) -> Unit,
    val setAmbianceBrightnessCompensation: (Float) -> Unit,
    val setAmbianceUpdateRateCapFps: (Int) -> Unit,
    val setAmbianceSceneCutSensitivity: (Float) -> Unit,
    val setAudioSmoothingAttack: (Float) -> Unit,
    val setAudioSmoothingDecay: (Float) -> Unit,
    val setAudioFlashStrength: (Float) -> Unit,
    val setNoiseGateThreshold: (Float) -> Unit,
    val setBassGain: (Float) -> Unit,
    val setMidGain: (Float) -> Unit,
    val setHighGain: (Float) -> Unit,
    val setAutoGainEnabled: (Boolean) -> Unit,
    val setPaletteCyclingEnabled: (Boolean) -> Unit,
    val setLogarithmicScalingEnabled: (Boolean) -> Unit,
    val setAudioGammaExponent: (Float) -> Unit,
    val setVisualizerMinBrightness: (Float) -> Unit,
    val setVisualizerColorSpeed: (Float) -> Unit,
    val setBluetoothDelayMs: (Int) -> Unit
)

/**
 * Scene orchestration extracted verbatim from RgbControllerViewModel (Phase 6, scene slice).
 *
 * This is a plain class — no interface, no DI framework — but unlike [ConnectionManager] it does
 * NOT own its state: it borrows the ViewModel's `_uiState`/`_scenes`/`customModes`/`sceneRunners`/
 * `activeExcludedMacs`/`deviceStateStore` by reference, so it is constructed by the ViewModel itself
 * (not by AppContainer). The scene-chain [Job] and the `isApplyingScene` flag DO belong to this
 * class and are owned here.
 *
 * Preserved quirks (do NOT "fix" — flagged in the extraction report):
 *  - applyScene runs prefs-persistence side effects from inside the `_uiState.update {}` lambda.
 *  - applyScene's recursive scene-chaining self-launch via `scope.launch { delay(...); applyScene(...) }`.
 *  - The direct reach-into AmbianceCaptureState/AmbianceCaptureService (Toast + Handler.post) and
 *    ui.components.ColorUtils cross-layer warts — relocated as-is.
 */
class SceneManager(
    private val scope: CoroutineScope,
    private val application: android.content.Context,
    private val prefsRepo: AppPreferencesRepository,
    private val uiState: MutableStateFlow<RgbUiState>,
    private val scenes: MutableStateFlow<List<AppScene>>,
    private val customModes: StateFlow<List<CustomMode>>,
    private val sceneRunners: ConcurrentHashMap<String, SceneAnimationRunner>,
    private val activeExcludedMacs: MutableSet<String>,
    private val deviceStateStore: DeviceStateStore,
    private val commands: SceneCommandSink
) {
    private fun getApplication(): android.app.Application = application as android.app.Application

    private var sceneChainJob: Job? = null

    var isApplyingScene = false
        private set

    fun cancelSceneChain() {
        sceneChainJob?.cancel()
        sceneChainJob = null
    }

    // --- SCENES LOGIC ---
    fun captureCurrentDeviceState(groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean = false, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false): DeviceSceneState {
        val s = uiState.value
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

        val current = scenes.value.toMutableList()
        current.add(newScene)
        scenes.value = current
        prefsRepo.saveScenes(current)
        return newId
    }

    fun updateScene(sceneId: String, name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false, chainedSceneId: String? = null, chainedSceneDelaySeconds: Int? = null, chainedSceneReverseId: String? = null) {
        val stateSnapshot = captureCurrentDeviceState(groupA, includeBrightness, includeModeSpeed, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings)

        val current = scenes.value.toMutableList()
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
            scenes.value = current
            prefsRepo.saveScenes(current)
        }
    }

    fun deleteScene(sceneId: String) {
        val current = scenes.value.filter { it.id != sceneId }
        scenes.value = current
        prefsRepo.saveScenes(current)
    }

    fun renameScene(sceneId: String, newName: String) {
        val current = scenes.value.map { if (it.id == sceneId) it.copy(name = newName) else it }
        scenes.value = current
        prefsRepo.saveScenes(current)
    }

    fun applyScene(scene: AppScene, isReversing: Boolean = false) {
        cancelSceneChain()
        isApplyingScene = true
        activeExcludedMacs.clear()

        val targetMacs = if (scene.targetScope == "SELECT_DEVICES" && scene.selectedDeviceMacs != null) {
            scene.selectedDeviceMacs
        } else {
            commands.getControlledAddresses()
        }

        if (scene.state.groupASelection == "Audio" || scene.state.groupASelection == "Ambiance") {
            val allConnected = uiState.value.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }.keys
            activeExcludedMacs.addAll(allConnected.filter { !targetMacs.contains(it) })
            applyDeviceState(scene.state, null)
        } else {
            applyDeviceState(scene.state, targetMacs)

            // Fix: Update relevant global UI state fields when applying HardwareMode/Colour/CCT scenes
            uiState.update { current ->
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
            val targetScene = scenes.value.find { it.id == nextSceneId }
            if (targetScene != null) {
                sceneChainJob = scope.launch {
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
        uiState.update { current ->
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
        val macs = targetMacsList ?: commands.getControlledAddresses()

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
                sendCommand = { m, cmd -> commands.sendCommandToDeviceDirect(m, cmd) },
                saveState = { m, p, r, g, b, w ->
                    scope.launch {
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
            if (targetMacsList == null) commands.setPower(isOn)
            else macs.forEach { commands.sendCommandToDeviceDirect(it, DuoCoProtocol.createPowerCommand(isOn)) }
        }

        // Group B
        state.brightness?.let { br ->
            if (targetMacsList == null) commands.setBrightness(br)
            else macs.forEach { mac -> commands.sendCommandToDeviceDirect(mac, DuoCoProtocol.createBrightnessCommand(br)) }
        }

        // Group A
        if (state.animatedSequence != null) {
            return // Skip applying static colour/mode if an animated sequence is running
        }

        when (state.groupASelection) {
            "Colour" -> {
                if (state.colorR != null && state.colorG != null && state.colorB != null) {
                    if (targetMacsList == null) commands.setColor(state.colorR, state.colorG, state.colorB)
                    else macs.forEach { commands.sendCommandToDeviceDirect(it, DuoCoProtocol.createColorCommand(state.colorR, state.colorG, state.colorB)) }
                }
            }
            "CCT" -> {
                state.cctWarmth?.let { warmth ->
                    if (targetMacsList == null) commands.setWarmth(warmth)
                    // Wait, warmth command for direct is complex (needs applying calibration logic directly? No, setWarmth applies globally)
                    // Let's just create color command for warmth
                    else {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(warmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        val mappedRed = rgb[0]
                        val mappedGreen = rgb[1]
                        val mappedBlue = rgb[2]
                        macs.forEach { mac -> commands.sendCommandToDeviceDirect(mac, DuoCoProtocol.createColorCommand(mappedRed, mappedGreen, mappedBlue)) }
                    }
                }
            }
            "HardwareMode" -> {
                state.modeIndex?.let { mi ->
                    if (targetMacsList == null) commands.setMode(mi)
                    else macs.forEach { mac -> commands.sendCommandToDeviceDirect(mac, DuoCoProtocol.createModeCommand(mi)) }
                }
                state.modeSpeed?.let { ms ->
                    if (targetMacsList == null) commands.setModeSpeed(ms)
                    else macs.forEach { mac -> commands.sendCommandToDeviceDirect(mac, DuoCoProtocol.createModeSpeedCommand(ms)) }
                }
            }
            "Audio" -> {
                if (targetMacsList == null) {
                    state.musicMode?.let { commands.startMusicSync(it) }
                    state.audioPreset?.let { commands.setVisualizerPreset(it) }
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
                        commands.applyAmbiancePreset(
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
                        uiState.update { it.copy(ambianceSettings = it.ambianceSettings.copy(ambianceUpdateRateCapFps = fps)) }
                        prefsRepo.putAmbiancePrefInt("update_rate_cap_fps", fps)
                    } else if (state.ambianceIsOn == false && com.example.ambiance.AmbianceCaptureState.isActive.value) {
                        com.example.ambiance.AmbianceCaptureService.stop(getApplication())
                    }
                }
            }
        }

        // Independent App-level Settings
        // Ambiance Settings
        state.ambianceResponseSpeed?.let { commands.setAmbianceResponseSpeed(it) }
        state.ambianceSmoothnessMs?.let { commands.setAmbianceSmoothnessMs(it) }
        state.ambianceSaturationBoost?.let { commands.setAmbianceSaturationBoost(it) }
        state.ambianceBrightnessCompensation?.let { commands.setAmbianceBrightnessCompensation(it) }
        state.ambianceUpdateRateCapFps?.let { commands.setAmbianceUpdateRateCapFps(it) }
        state.ambianceSceneCutSensitivity?.let { commands.setAmbianceSceneCutSensitivity(it) }

        // Audio Settings
        state.audioAttack?.let { commands.setAudioSmoothingAttack(it) }
        state.audioDecay?.let { commands.setAudioSmoothingDecay(it) }
        state.audioFlash?.let { commands.setAudioFlashStrength(it) }
        state.noiseGateThreshold?.let { commands.setNoiseGateThreshold(it) }
        state.bassGain?.let { commands.setBassGain(it) }
        state.midGain?.let { commands.setMidGain(it) }
        state.highGain?.let { commands.setHighGain(it) }
        state.isAutoGainEnabled?.let { commands.setAutoGainEnabled(it) }
        state.isPaletteCyclingEnabled?.let { commands.setPaletteCyclingEnabled(it) }
        state.isLogarithmicScalingEnabled?.let { commands.setLogarithmicScalingEnabled(it) }
        state.audioGammaExponent?.let { commands.setAudioGammaExponent(it) }
        state.visualizerMinBrightness?.let { commands.setVisualizerMinBrightness(it) }
        state.visualizerColorSpeed?.let { commands.setVisualizerColorSpeed(it) }

        // Calibration Settings
        state.bluetoothDelayMs?.let { commands.setBluetoothDelayMs(it) }
    }
}
