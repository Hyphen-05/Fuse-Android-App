package com.example.presentation

import com.example.RgbUiState
import com.example.CoreControlState
import com.example.RgbIntent
import com.example.BleConnectionState
import com.example.CctCorrectionProfile
import com.example.ActiveDeviceState
import com.example.RgbControllerViewModel
import com.example.core.protocol.DuoCoProtocol
import com.example.ui.components.ColorUtils
import com.example.db.CustomMode
import com.example.db.SavedDevice

// ============================================================================
// CORE SIDE EFFECTS — side effects triggered by core control state transitions
// ============================================================================

sealed interface CoreSideEffect {
    data class SavePrefBoolean(val key: String, val value: Boolean) : CoreSideEffect
    data class SavePrefInt(val key: String, val value: Int) : CoreSideEffect
    data class SavePrefString(val key: String, val value: String) : CoreSideEffect
    data class Log(val message: String) : CoreSideEffect
    /**
     * Sent to every controlled strip, except strips switched off on their own tile - unless
     * [includePoweredOff], which only the global power switch sets, because it is the one broadcast
     * that has to reach a strip that is off.
     */
    data class BroadcastCommand(
        val command: ByteArray,
        val logMessage: String,
        val cancelRunningScenes: Boolean = true,
        val includePoweredOff: Boolean = false
    ) : CoreSideEffect {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BroadcastCommand) return false
            return command.contentEquals(other.command) && logMessage == other.logMessage &&
                cancelRunningScenes == other.cancelRunningScenes && includePoweredOff == other.includePoweredOff
        }
        override fun hashCode(): Int {
            var result = command.contentHashCode()
            result = 31 * result + logMessage.hashCode()
            result = 31 * result + cancelRunningScenes.hashCode()
            result = 31 * result + includePoweredOff.hashCode()
            return result
        }
    }
    data class SendCommandToDeviceDirect(val address: String, val command: ByteArray) : CoreSideEffect {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SendCommandToDeviceDirect) return false
            return address == other.address && command.contentEquals(other.command)
        }
        override fun hashCode(): Int {
            var result = address.hashCode()
            result = 31 * result + command.contentHashCode()
            return result
        }
    }
    data class ConnectDevice(val address: String) : CoreSideEffect
    data class DisconnectDevice(val address: String) : CoreSideEffect
    data class StopMusicSync(val restoreState: Boolean) : CoreSideEffect
    data class StopAmbiance(val restoreState: Boolean) : CoreSideEffect
    object CancelSceneChain : CoreSideEffect
    data class CancelSceneRunner(val address: String) : CoreSideEffect
    object ClearExclusionsIfNotApplyingScene : CoreSideEffect
    object StartBleScan : CoreSideEffect
    object StopBleScan : CoreSideEffect
    object SimulateScan : CoreSideEffect
    object CheckActiveAudioRoute : CoreSideEffect
    data class AutoSaveDeviceIfNew(val address: String, val name: String, val autoConnect: Boolean, val activeControl: Boolean) : CoreSideEffect
    data class ToggleAutoConnect(val address: String, val name: String, val enabled: Boolean) : CoreSideEffect
    data class UpdateActiveControl(val address: String, val name: String, val enabled: Boolean) : CoreSideEffect
    data class SaveDeviceState(val address: String, val automationType: RgbControllerViewModel.AutomationType) : CoreSideEffect
    data class RestoreDeviceState(val address: String, val automationType: RgbControllerViewModel.AutomationType) : CoreSideEffect
    data class DeleteSavedDevice(val address: String) : CoreSideEffect
    data class SaveDeviceAlias(val address: String, val customName: String) : CoreSideEffect
    data class DeleteDeviceAlias(val address: String) : CoreSideEffect
}

/** Where one strip's own power is persisted, so a strip switched off on its tile stays off across launches. */
fun devicePowerPrefKey(address: String) = "power_on_$address"

// Mirrors RgbControllerViewModel.updateControlledDevicesInMap() (lines 3000-3039): for every
// target address, create a default ActiveDeviceState from the (already-updated) coreControl
// state if one isn't present yet, then overlay only the fields this intent actually changed.
// The old mapValues-based approach here only touched keys that already existed in
// deviceStatesMap, so newly-connected auto-saved devices (isActiveControlEnabled=true by
// default, added to targetAddresses without ever going through ToggleActiveControl) never
// got an entry created.
private fun applyControlledDeviceUpdates(
    deviceStatesMap: Map<String, ActiveDeviceState>,
    targetAddresses: List<String>,
    coreControl: CoreControlState,
    activeFeatureName: String? = null,
    red: Int? = null,
    green: Int? = null,
    blue: Int? = null,
    warmth: Int? = null,
    modeIndex: Int? = null,
    brightness: Int? = null,
    isPowerOn: Boolean? = null
): Map<String, ActiveDeviceState> {
    if (targetAddresses.isEmpty()) return deviceStatesMap
    val newMap = deviceStatesMap.toMutableMap()
    targetAddresses.forEach { address ->
        val existing = newMap[address] ?: ActiveDeviceState(
            activeFeatureName = coreControl.activeFeatureName,
            red = coreControl.red,
            green = coreControl.green,
            blue = coreControl.blue,
            warmth = coreControl.warmth,
            modeIndex = coreControl.modeIndex,
            brightness = coreControl.brightness,
            isPowerOn = coreControl.isPowerOn
        )
        newMap[address] = existing.copy(
            activeFeatureName = activeFeatureName ?: existing.activeFeatureName,
            red = red ?: existing.red,
            green = green ?: existing.green,
            blue = blue ?: existing.blue,
            warmth = warmth ?: existing.warmth,
            modeIndex = modeIndex ?: existing.modeIndex,
            brightness = brightness ?: existing.brightness,
            isPowerOn = isPowerOn ?: existing.isPowerOn
        )
    }
    return newMap
}

fun coreControlsReducer(
    state: RgbUiState,
    intent: RgbIntent,
    customModes: List<CustomMode>,
    savedDevices: List<SavedDevice>,
    cctCalibrations: Map<String, CctCorrectionProfile>,
    isAmbianceActive: Boolean,
    targetAddresses: List<String>,
    deviceAutomationMode: Map<String, RgbControllerViewModel.AutomationType>
): Pair<RgbUiState, List<CoreSideEffect>> {
    return when (intent) {
        is RgbIntent.SetPower -> {
            val newCoreControl = state.coreControl.copy(isPowerOn = intent.isOn)
            val newState = state.copy(
                coreControl = newCoreControl,
                connectivity = state.connectivity.copy(
                    deviceStatesMap = applyControlledDeviceUpdates(
                        deviceStatesMap = state.connectivity.deviceStatesMap,
                        targetAddresses = targetAddresses,
                        coreControl = newCoreControl,
                        isPowerOn = intent.isOn
                    )
                )
            )
            val effects = mutableListOf<CoreSideEffect>(
                CoreSideEffect.CancelSceneChain,
                CoreSideEffect.SavePrefBoolean("power_on", intent.isOn)
            )
            targetAddresses.forEach { effects.add(CoreSideEffect.SavePrefBoolean(devicePowerPrefKey(it), intent.isOn)) }
            if (!intent.isOn) {
                effects.add(CoreSideEffect.StopMusicSync(restoreState = true))
                effects.add(CoreSideEffect.StopAmbiance(restoreState = true))
            }
            effects.add(
                CoreSideEffect.BroadcastCommand(
                    DuoCoProtocol.createPowerCommand(intent.isOn),
                    "Power ${intent.isOn}",
                    includePoweredOff = true
                )
            )
            newState to effects
        }

        is RgbIntent.SetDevicePower -> {
            // One strip's own switch. The global switch still means "every controlled strip", so
            // it is kept honest here: it reads off only once every controlled strip is off, and
            // the strip that brings the first one back turns it on without touching the others.
            val address = intent.address
            val core = state.coreControl
            fun fromCore() = ActiveDeviceState(
                activeFeatureName = core.activeFeatureName,
                red = core.red,
                green = core.green,
                blue = core.blue,
                warmth = core.warmth,
                modeIndex = core.modeIndex,
                brightness = core.brightness,
                isPowerOn = core.isPowerOn
            )
            // Give every controlled strip an entry first. A strip without one reads the global
            // flag, so flipping that flag below would otherwise silently flip strips nobody touched.
            val newMap = state.connectivity.deviceStatesMap.toMutableMap()
            targetAddresses.forEach { if (newMap[it] == null) newMap[it] = fromCore() }

            val controlled = address in targetAddresses
            val musicActive = state.audioSettings.musicMode != null
            val automationFeature = when {
                !controlled -> null
                musicActive -> core.activeFeatureName
                isAmbianceActive -> "Ambiance - ${state.ambianceSettings.ambiancePreset}"
                else -> null
            }
            val existing = newMap[address] ?: fromCore()
            val devState = existing.copy(
                isPowerOn = intent.isOn,
                activeFeatureName = if (intent.isOn && automationFeature != null) automationFeature else existing.activeFeatureName
            )
            newMap[address] = devState

            val effects = mutableListOf<CoreSideEffect>(
                CoreSideEffect.SavePrefBoolean(devicePowerPrefKey(address), intent.isOn),
                CoreSideEffect.CancelSceneRunner(address)
            )
            if (intent.isOn) {
                effects.add(CoreSideEffect.SendCommandToDeviceDirect(address, DuoCoProtocol.createPowerCommand(true)))
                if (automationFeature != null) {
                    // Rejoining music or ambiance: snapshot what it remembered so stopping puts it
                    // back, and let the running automation paint it from the next frame.
                    effects.add(
                        CoreSideEffect.SaveDeviceState(
                            address,
                            if (musicActive) RgbControllerViewModel.AutomationType.AUDIO else RgbControllerViewModel.AutomationType.AMBIANCE
                        )
                    )
                } else {
                    // Anything changed while it was off was remembered, not sent, so send it now.
                    effects.add(CoreSideEffect.SendCommandToDeviceDirect(address, DuoCoProtocol.createBrightnessCommand(devState.brightness)))
                    when {
                        devState.activeFeatureName == "Colour" -> effects.add(
                            CoreSideEffect.SendCommandToDeviceDirect(address, DuoCoProtocol.createColorCommand(devState.red, devState.green, devState.blue))
                        )
                        devState.activeFeatureName == "CCT" -> {
                            val rgb = ColorUtils.convertKelvinToRgb(ColorUtils.warmthToKelvin(devState.warmth))
                            effects.add(CoreSideEffect.SendCommandToDeviceDirect(address, DuoCoProtocol.createColorCommand(rgb[0], rgb[1], rgb[2])))
                        }
                        devState.activeFeatureName.startsWith("Audio") || devState.activeFeatureName.startsWith("Ambiance") -> {}
                        else -> effects.add(CoreSideEffect.SendCommandToDeviceDirect(address, DuoCoProtocol.createModeCommand(devState.modeIndex)))
                    }
                }
            } else {
                val owner = deviceAutomationMode[address]
                if (owner != null) {
                    // Dropping out of music or ambiance. The restore sees the strip is off, so it
                    // hands back the remembered colour and finishes with power off.
                    effects.add(CoreSideEffect.RestoreDeviceState(address, owner))
                } else {
                    effects.add(CoreSideEffect.SendCommandToDeviceDirect(address, DuoCoProtocol.createPowerCommand(false)))
                }
            }

            var newCore = core
            if (controlled) {
                val anyOn = targetAddresses.any { newMap[it]?.isPowerOn ?: core.isPowerOn }
                if (!anyOn && core.isPowerOn) {
                    newCore = core.copy(isPowerOn = false)
                    effects.add(CoreSideEffect.SavePrefBoolean("power_on", false))
                    effects.add(CoreSideEffect.CancelSceneChain)
                    effects.add(CoreSideEffect.StopMusicSync(restoreState = true))
                    effects.add(CoreSideEffect.StopAmbiance(restoreState = true))
                } else if (anyOn && !core.isPowerOn) {
                    newCore = core.copy(isPowerOn = true)
                    effects.add(CoreSideEffect.SavePrefBoolean("power_on", true))
                }
            }

            state.copy(
                coreControl = newCore,
                connectivity = state.connectivity.copy(deviceStatesMap = newMap)
            ) to effects
        }

        is RgbIntent.SetColor -> {
            val newCoreControl = state.coreControl.copy(
                red = intent.r,
                green = intent.g,
                blue = intent.b,
                modeIndex = 0,
                activeFeatureName = "Colour"
            )
            val newState = state.copy(
                coreControl = newCoreControl,
                connectivity = state.connectivity.copy(
                    deviceStatesMap = applyControlledDeviceUpdates(
                        deviceStatesMap = state.connectivity.deviceStatesMap,
                        targetAddresses = targetAddresses,
                        coreControl = newCoreControl,
                        activeFeatureName = "Colour",
                        red = intent.r,
                        green = intent.g,
                        blue = intent.b,
                        modeIndex = 0
                    )
                )
            )
            val effects = listOf(
                CoreSideEffect.CancelSceneChain,
                CoreSideEffect.ClearExclusionsIfNotApplyingScene,
                CoreSideEffect.StopMusicSync(restoreState = false),
                CoreSideEffect.StopAmbiance(restoreState = false),
                CoreSideEffect.SavePrefInt("red", intent.r),
                CoreSideEffect.SavePrefInt("green", intent.g),
                CoreSideEffect.SavePrefInt("blue", intent.b),
                CoreSideEffect.SavePrefInt("mode_index", 0),
                CoreSideEffect.SavePrefString("active_feature_name", "Colour"),
                CoreSideEffect.BroadcastCommand(DuoCoProtocol.createColorCommand(intent.r, intent.g, intent.b), "Color R:${intent.r} G:${intent.g} B:${intent.b}")
            )
            newState to effects
        }

        is RgbIntent.SetBrightness -> {
            val newCoreControl = state.coreControl.copy(brightness = intent.percent)
            val newState = state.copy(
                coreControl = newCoreControl,
                connectivity = state.connectivity.copy(
                    deviceStatesMap = applyControlledDeviceUpdates(
                        deviceStatesMap = state.connectivity.deviceStatesMap,
                        targetAddresses = targetAddresses,
                        coreControl = newCoreControl,
                        brightness = intent.percent
                    )
                )
            )
            val effects = listOf(
                CoreSideEffect.SavePrefInt("brightness", intent.percent),
                CoreSideEffect.BroadcastCommand(
                    command = DuoCoProtocol.createBrightnessCommand(intent.percent),
                    logMessage = "Brightness ${intent.percent}%",
                    cancelRunningScenes = false
                )
            )
            newState to effects
        }

        is RgbIntent.SetMode -> {
            val modeName = customModes.find { it.byteValue == intent.modeIndex }?.name ?: "Mode"
            val newCoreControl = state.coreControl.copy(
                modeIndex = intent.modeIndex,
                activeFeatureName = modeName
            )
            val newState = state.copy(
                coreControl = newCoreControl,
                connectivity = state.connectivity.copy(
                    deviceStatesMap = applyControlledDeviceUpdates(
                        deviceStatesMap = state.connectivity.deviceStatesMap,
                        targetAddresses = targetAddresses,
                        coreControl = newCoreControl,
                        activeFeatureName = modeName,
                        modeIndex = intent.modeIndex
                    )
                )
            )
            val effects = listOf(
                CoreSideEffect.StopMusicSync(restoreState = false),
                CoreSideEffect.StopAmbiance(restoreState = false),
                CoreSideEffect.CancelSceneChain,
                CoreSideEffect.SavePrefString("active_feature_name", modeName),
                CoreSideEffect.SavePrefInt("mode_index", intent.modeIndex),
                CoreSideEffect.BroadcastCommand(DuoCoProtocol.createModeCommand(intent.modeIndex), "Mode ${intent.modeIndex}")
            )
            newState to effects
        }

        is RgbIntent.SetModeSpeed -> {
            val newState = state.copy(
                coreControl = state.coreControl.copy(modeSpeed = intent.speed)
            )
            val effects = listOf(
                CoreSideEffect.CancelSceneChain,
                CoreSideEffect.SavePrefInt("mode_speed", intent.speed),
                CoreSideEffect.BroadcastCommand(DuoCoProtocol.createModeSpeedCommand(intent.speed), "Mode Speed ${intent.speed}%")
            )
            newState to effects
        }

        is RgbIntent.SetWarmth -> {
            val coercedPercent = intent.percent.coerceIn(0, 100)
            val kelvin = ColorUtils.warmthToKelvin(coercedPercent)
            val rgb = ColorUtils.convertKelvinToRgb(kelvin)
            val finalRed = rgb[0]
            val finalGreen = rgb[1]
            val finalBlue = rgb[2]

            val profile = cctCalibrations[state.connectivity.connectedDeviceAddress]
            val sendR = profile?.let { (finalRed * it.scaleR + it.offsetR).toInt().coerceIn(0, 255) } ?: finalRed
            val sendG = profile?.let { (finalGreen * it.scaleG + it.offsetG).toInt().coerceIn(0, 255) } ?: finalGreen
            val sendB = profile?.let { (finalBlue * it.scaleB + it.offsetB).toInt().coerceIn(0, 255) } ?: finalBlue

            val newCoreControl = state.coreControl.copy(
                warmth = coercedPercent,
                modeIndex = 0,
                activeFeatureName = "CCT",
                red = finalRed,
                green = finalGreen,
                blue = finalBlue
            )
            val newState = state.copy(
                coreControl = newCoreControl,
                connectivity = state.connectivity.copy(
                    deviceStatesMap = applyControlledDeviceUpdates(
                        deviceStatesMap = state.connectivity.deviceStatesMap,
                        targetAddresses = targetAddresses,
                        coreControl = newCoreControl,
                        activeFeatureName = "CCT",
                        red = finalRed,
                        green = finalGreen,
                        blue = finalBlue,
                        warmth = coercedPercent,
                        modeIndex = 0
                    )
                )
            )

            val effects = listOf(
                CoreSideEffect.CancelSceneChain,
                CoreSideEffect.ClearExclusionsIfNotApplyingScene,
                CoreSideEffect.StopMusicSync(restoreState = false),
                CoreSideEffect.StopAmbiance(restoreState = false),
                CoreSideEffect.SavePrefString("active_feature_name", "CCT"),
                CoreSideEffect.SavePrefInt("warmth", coercedPercent),
                CoreSideEffect.SavePrefInt("mode_index", 0),
                CoreSideEffect.SavePrefString("active_feature_name", "Colour"),
                CoreSideEffect.SavePrefInt("red", finalRed),
                CoreSideEffect.SavePrefInt("green", finalGreen),
                CoreSideEffect.SavePrefInt("blue", finalBlue),
                CoreSideEffect.BroadcastCommand(
                    command = DuoCoProtocol.createColorCommand(sendR, sendG, sendB),
                    logMessage = "Warmth R:$sendR G:$sendG B:$sendB (Original R:$finalRed G:$finalGreen B:$finalBlue)"
                )
            )
            newState to effects
        }

        is RgbIntent.SetShowFpsTracker -> {
            val newState = state.copy(
                coreControl = state.coreControl.copy(showFpsTracker = intent.enabled)
            )
            val effects = listOf(
                CoreSideEffect.SavePrefBoolean("show_fps_tracker", intent.enabled)
            )
            newState to effects
        }

        is RgbIntent.SetActiveFeatureName -> {
            val newState = state.copy(
                coreControl = state.coreControl.copy(activeFeatureName = intent.name)
            )
            val effects = listOf(
                CoreSideEffect.CancelSceneChain,
                CoreSideEffect.SavePrefString("active_feature_name", intent.name),
                CoreSideEffect.ClearExclusionsIfNotApplyingScene
            )
            newState to effects
        }

        is RgbIntent.SetDemoMode -> {
            val logMsg = if (intent.isDemo) "Switched to Demo Mode" else "Switched to Real Hardware BLE Mode"
            // Persisted since F1 (IMPROVEMENT_PLAN.md), because this is now a Settings toggle the
            // user owns rather than something the app decides for them at startup. The ViewModel's
            // init block reads it back and only overrides it when there is no BLE adapter at all.
            val savePref = CoreSideEffect.SavePrefBoolean("demo_mode", intent.isDemo)

            if (intent.isDemo) {
                // Mirrors ViewModel.disconnect() (called from setDemoMode(true)): it only acts on
                // activeConnections.keys, a live-only registry that holds an address exactly
                // while it's CONNECTED or CONNECTING and is removed from on every disconnect.
                // deviceConnectionStates never removes a key (entries are only ever overwritten
                // in place, see e.g. `+ (address to DISCONNECTED)`), so using its raw keys would
                // keep re-targeting devices that are already DISCONNECTED forever after the first
                // connection of the session.
                val keys = state.connectivity.deviceConnectionStates.filterValues {
                    it == BleConnectionState.CONNECTED || it == BleConnectionState.CONNECTING
                }.keys
                // Scan results belong to the mode that produced them: simulated devices are
                // meaningless in real mode and vice versa, so a mode switch always empties the list
                // and stops any scan in flight. Without this, the four fake devices stayed on the
                // Devices screen after switching back to real hardware.
                val stopScanEffects = if (state.connectivity.isScanning) {
                    listOf(CoreSideEffect.StopBleScan)
                } else {
                    emptyList()
                }

                if (keys.isEmpty()) {
                    val newState = state.copy(
                        coreControl = state.coreControl.copy(isDemoMode = true),
                        connectivity = state.connectivity.copy(
                            connectionState = BleConnectionState.DISCONNECTED,
                            connectedDeviceAddress = null,
                            connectedDeviceName = null,
                            deviceConnectionStates = emptyMap(),
                            scannedDevices = emptyList(),
                            isScanning = false
                        )
                    )
                    newState to (listOf(CoreSideEffect.Log(logMsg), savePref) + stopScanEffects)
                } else {
                    var newDeviceConnectionStates = state.connectivity.deviceConnectionStates
                    val effects = mutableListOf<CoreSideEffect>(CoreSideEffect.Log(logMsg), savePref)
                    effects.addAll(stopScanEffects)
                    keys.forEach { address ->
                        newDeviceConnectionStates = newDeviceConnectionStates + (address to BleConnectionState.DISCONNECTING)
                        effects.add(CoreSideEffect.DisconnectDevice(address))
                    }
                    val newState = state.copy(
                        coreControl = state.coreControl.copy(isDemoMode = true),
                        connectivity = state.connectivity.copy(
                            deviceConnectionStates = newDeviceConnectionStates,
                            scannedDevices = emptyList(),
                            isScanning = false
                        )
                    )
                    newState to effects
                }
            } else {
                val effects = mutableListOf<CoreSideEffect>(CoreSideEffect.Log(logMsg), savePref)
                if (state.connectivity.isScanning) effects.add(CoreSideEffect.StopBleScan)
                val newState = state.copy(
                    coreControl = state.coreControl.copy(isDemoMode = false),
                    connectivity = state.connectivity.copy(
                        scannedDevices = emptyList(),
                        isScanning = false
                    )
                )
                newState to effects
            }
        }

        is RgbIntent.ClearErrorMessage -> {
            if (state.coreControl.errorMessage == null) {
                return state to emptyList()
            }
            state.copy(coreControl = state.coreControl.copy(errorMessage = null)) to emptyList()
        }

        is RgbIntent.StartScanning -> {
            if (state.connectivity.isScanning) {
                return state to emptyList()
            }
            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    isScanning = true
                ),
                coreControl = state.coreControl.copy(
                    errorMessage = null
                )
            )
            val effects = mutableListOf<CoreSideEffect>(
                CoreSideEffect.Log("Scan started...")
            )
            if (state.coreControl.isDemoMode) {
                effects.add(CoreSideEffect.SimulateScan)
            } else {
                effects.add(CoreSideEffect.StartBleScan)
            }
            newState to effects
        }

        is RgbIntent.StopScanning -> {
            if (!state.connectivity.isScanning) {
                return state to emptyList()
            }
            val newState = state.copy(
                connectivity = state.connectivity.copy(isScanning = false)
            )
            val effects = mutableListOf<CoreSideEffect>(
                CoreSideEffect.Log("Scan stopped.")
            )
            if (!state.coreControl.isDemoMode) {
                effects.add(CoreSideEffect.StopBleScan)
            }
            newState to effects
        }

        is RgbIntent.ConnectDevice -> {
            val deviceToConnect = state.connectivity.scannedDevices.find { it.address == intent.address }
            val displayName = deviceToConnect?.alias ?: deviceToConnect?.name ?: "Unknown Device"
            
            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    deviceConnectionStates = state.connectivity.deviceConnectionStates + (intent.address to BleConnectionState.CONNECTING),
                    connectedDeviceAddress = intent.address,
                    connectedDeviceName = displayName
                )
            )
            
            val effects = mutableListOf<CoreSideEffect>(
                CoreSideEffect.Log("Connecting to $displayName (${intent.address})..."),
                CoreSideEffect.ConnectDevice(intent.address)
            )
            
            if (savedDevices.none { it.macAddress == intent.address }) {
                effects.add(CoreSideEffect.AutoSaveDeviceIfNew(
                    address = intent.address,
                    name = displayName,
                    autoConnect = true,
                    activeControl = true
                ))
            }
            
            newState to effects
        }

        is RgbIntent.DisconnectDevice -> {
            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    deviceConnectionStates = state.connectivity.deviceConnectionStates + (intent.address to BleConnectionState.DISCONNECTING)
                )
            )
            val effects = listOf(
                CoreSideEffect.DisconnectDevice(intent.address)
            )
            newState to effects
        }
        
        is RgbIntent.DisconnectAll -> {
            // Same activeConnections-membership mirror as SetDemoMode(true) above — only
            // CONNECTED/CONNECTING addresses need a disconnect issued.
            val keys = state.connectivity.deviceConnectionStates.filterValues {
                it == BleConnectionState.CONNECTED || it == BleConnectionState.CONNECTING
            }.keys
            if (keys.isEmpty()) {
                val newState = state.copy(
                    connectivity = state.connectivity.copy(
                        connectionState = BleConnectionState.DISCONNECTED,
                        connectedDeviceAddress = null,
                        connectedDeviceName = null,
                        deviceConnectionStates = emptyMap()
                    )
                )
                newState to emptyList()
            } else {
                var newDeviceConnectionStates = state.connectivity.deviceConnectionStates
                val effects = mutableListOf<CoreSideEffect>()
                keys.forEach { address ->
                    newDeviceConnectionStates = newDeviceConnectionStates + (address to BleConnectionState.DISCONNECTING)
                    effects.add(CoreSideEffect.DisconnectDevice(address))
                }
                val newState = state.copy(
                    connectivity = state.connectivity.copy(
                        deviceConnectionStates = newDeviceConnectionStates
                    )
                )
                newState to effects
            }
        }

        is RgbIntent.InternalConnectionStateChanged -> {
            val address = intent.address
            val updatedStates = state.connectivity.deviceConnectionStates + (address to intent.newState)
            
            val stillConnected = updatedStates.filter { it.value == BleConnectionState.CONNECTED }
            val activeAddr = stillConnected.keys.firstOrNull()
            
            val activeName = activeAddr?.let { addr -> 
                state.connectivity.scannedDevices.find { it.address == addr }?.name 
            }
            
            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    deviceConnectionStates = updatedStates,
                    connectionState = if (stillConnected.isNotEmpty()) BleConnectionState.CONNECTED else BleConnectionState.DISCONNECTED,
                    connectedDeviceAddress = activeAddr,
                    connectedDeviceName = activeName
                )
            )
            newState to emptyList()
        }

        is RgbIntent.CheckActiveAudioRoute -> {
            state to listOf(CoreSideEffect.CheckActiveAudioRoute)
        }

        is RgbIntent.ToggleAutoConnect -> {
            val existing = savedDevices.find { it.macAddress == intent.address }
            val logMsg = if (existing != null) "Updated Auto-Connect for ${existing.customName} to ${intent.enabled}"
                         else "Saved device ${intent.name} with Auto-Connect = ${intent.enabled}"
            
            val effects = listOf(
                CoreSideEffect.ToggleAutoConnect(intent.address, intent.name, intent.enabled),
                CoreSideEffect.Log(logMsg)
            )
            state to effects
        }

        is RgbIntent.ToggleActiveControl -> {
            val existing = savedDevices.find { it.macAddress == intent.address }
            val logMsg = if (existing != null) "Updated Active Control for ${existing.customName} to ${intent.enabled}"
                         else "Saved device ${intent.name} with Active Control = ${intent.enabled}"
            
            val effects = mutableListOf<CoreSideEffect>(
                CoreSideEffect.UpdateActiveControl(intent.address, intent.name, intent.enabled),
                CoreSideEffect.Log(logMsg)
            )
            var newState = state

            if (intent.enabled) {
                val musicActive = state.audioSettings.musicMode != null
                val currentFeatureName = if (musicActive) state.coreControl.activeFeatureName 
                                         else if (isAmbianceActive) "Ambiance - ${state.ambianceSettings.ambiancePreset}" 
                                         else state.coreControl.activeFeatureName

                var devState = state.connectivity.deviceStatesMap[intent.address]
                if (devState == null || devState.activeFeatureName != currentFeatureName) {
                    val baseState = devState ?: ActiveDeviceState(
                        red = state.coreControl.red,
                        green = state.coreControl.green,
                        blue = state.coreControl.blue,
                        warmth = state.coreControl.warmth,
                        modeIndex = state.coreControl.modeIndex,
                        brightness = state.coreControl.brightness,
                        isPowerOn = state.coreControl.isPowerOn,
                        activeFeatureName = state.coreControl.activeFeatureName
                    )
                    val newDevState = baseState.copy(activeFeatureName = currentFeatureName)
                    newState = newState.copy(
                        connectivity = newState.connectivity.copy(
                            deviceStatesMap = newState.connectivity.deviceStatesMap + (intent.address to newDevState)
                        )
                    )
                    devState = newDevState
                }

                effects.add(CoreSideEffect.SendCommandToDeviceDirect(intent.address, DuoCoProtocol.createPowerCommand(devState.isPowerOn)))
                effects.add(CoreSideEffect.SendCommandToDeviceDirect(intent.address, DuoCoProtocol.createBrightnessCommand(devState.brightness)))

                when {
                    devState.activeFeatureName == "Colour" -> {
                        effects.add(CoreSideEffect.SendCommandToDeviceDirect(intent.address, DuoCoProtocol.createColorCommand(devState.red, devState.green, devState.blue)))
                    }
                    devState.activeFeatureName == "CCT" -> {
                        val kelvin = ColorUtils.warmthToKelvin(devState.warmth)
                        val rgb = ColorUtils.convertKelvinToRgb(kelvin)
                        effects.add(CoreSideEffect.SendCommandToDeviceDirect(intent.address, DuoCoProtocol.createColorCommand(rgb[0], rgb[1], rgb[2])))
                    }
                    devState.activeFeatureName.startsWith("Audio") || devState.activeFeatureName.startsWith("Ambiance") -> {
                        // no additional command
                    }
                    else -> {
                        effects.add(CoreSideEffect.SendCommandToDeviceDirect(intent.address, DuoCoProtocol.createModeCommand(devState.modeIndex)))
                    }
                }

                if (musicActive) {
                    effects.add(CoreSideEffect.SaveDeviceState(intent.address, RgbControllerViewModel.AutomationType.AUDIO))
                } else if (isAmbianceActive) {
                    effects.add(CoreSideEffect.SaveDeviceState(intent.address, RgbControllerViewModel.AutomationType.AMBIANCE))
                }
            } else {
                var devState = state.connectivity.deviceStatesMap[intent.address]
                if (devState == null) {
                    val baseState = ActiveDeviceState(
                        red = state.coreControl.red,
                        green = state.coreControl.green,
                        blue = state.coreControl.blue,
                        warmth = state.coreControl.warmth,
                        modeIndex = state.coreControl.modeIndex,
                        brightness = state.coreControl.brightness,
                        isPowerOn = state.coreControl.isPowerOn,
                        activeFeatureName = state.coreControl.activeFeatureName
                    )
                    newState = newState.copy(
                        connectivity = newState.connectivity.copy(
                            deviceStatesMap = newState.connectivity.deviceStatesMap + (intent.address to baseState)
                        )
                    )
                }
                
                val mode = deviceAutomationMode[intent.address]
                if (mode != null) {
                    effects.add(CoreSideEffect.RestoreDeviceState(intent.address, mode))
                }
            }
            newState to effects
        }

        is RgbIntent.DeleteSavedDevice -> {
            val effects = listOf(
                CoreSideEffect.DeleteSavedDevice(intent.address),
                CoreSideEffect.Log("Removed device from Saved Center: ${intent.address}"),
                CoreSideEffect.DisconnectDevice(intent.address)
            )
            state to effects
        }

        is RgbIntent.SaveDeviceAlias -> {
            val connectedAddressUpdated = if (state.connectivity.connectedDeviceAddress == intent.address) {
                intent.customName
            } else {
                state.connectivity.connectedDeviceName
            }
            
            val newScannedDevices = state.connectivity.scannedDevices.map { 
                if (it.address == intent.address) it.copy(alias = intent.customName) else it 
            }

            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    connectedDeviceName = connectedAddressUpdated,
                    scannedDevices = newScannedDevices
                )
            )
            
            val effects = listOf(
                CoreSideEffect.SaveDeviceAlias(intent.address, intent.customName),
                CoreSideEffect.Log("Saved Alias '${intent.customName}' for address: ${intent.address}")
            )
            newState to effects
        }

        is RgbIntent.DeleteDeviceAlias -> {
            val connectedNameReverted = if (state.connectivity.connectedDeviceAddress == intent.address) {
                state.connectivity.scannedDevices.find { it.address == intent.address }?.name ?: "Unknown Device"
            } else {
                state.connectivity.connectedDeviceName
            }
            
            val newScannedDevices = state.connectivity.scannedDevices.map { 
                if (it.address == intent.address) it.copy(alias = null) else it 
            }

            val newState = state.copy(
                connectivity = state.connectivity.copy(
                    connectedDeviceName = connectedNameReverted,
                    scannedDevices = newScannedDevices
                )
            )
            
            val effects = listOf(
                CoreSideEffect.DeleteDeviceAlias(intent.address),
                CoreSideEffect.Log("Deleted Alias for address: ${intent.address}")
            )
            newState to effects
        }

        else -> state to emptyList()
    }
}
