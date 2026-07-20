package com.example.presentation

import com.example.ActiveDeviceState
import com.example.BleConnectionState
import com.example.CctCorrectionProfile
import com.example.ConnectivityState
import com.example.CoreControlState
import com.example.RgbControllerViewModel
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.ScannedRgbDevice
import com.example.core.protocol.DuoCoProtocol
import com.example.db.CustomMode
import com.example.db.SavedDevice
import com.example.ui.components.ColorUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreControlsReducerTest {

    // Convenience wrapper matching the reducer's real signature, with sane defaults so
    // each test only has to specify the parameters it actually cares about.
    private fun reduce(
        state: RgbUiState = RgbUiState(),
        intent: RgbIntent,
        customModes: List<CustomMode> = emptyList(),
        savedDevices: List<SavedDevice> = emptyList(),
        cctCalibrations: Map<String, CctCorrectionProfile> = emptyMap(),
        isAmbianceActive: Boolean = false,
        targetAddresses: List<String> = emptyList(),
        deviceAutomationMode: Map<String, RgbControllerViewModel.AutomationType> = emptyMap()
    ): Pair<RgbUiState, List<CoreSideEffect>> = coreControlsReducer(
        state, intent, customModes, savedDevices, cctCalibrations,
        isAmbianceActive, targetAddresses, deviceAutomationMode
    )

    // ========================================================================
    // Core Controls group
    // ========================================================================

    @Test
    fun setPower_on_updatesCoreControlAndBroadcastsCommand() {
        val (newState, effects) = reduce(intent = RgbIntent.SetPower(true))

        assertTrue(newState.coreControl.isPowerOn)
        assertTrue(effects.contains(CoreSideEffect.SavePrefBoolean("power_on", true)))
        assertTrue(effects.contains(CoreSideEffect.CancelSceneChain))
        assertTrue(effects.any {
            it is CoreSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createPowerCommand(true))
        })
        assertFalse(effects.any { it is CoreSideEffect.StopMusicSync })
        assertFalse(effects.any { it is CoreSideEffect.StopAmbiance })
    }

    @Test
    fun setPower_off_alsoStopsMusicAndAmbianceWithRestore() {
        val (_, effects) = reduce(intent = RgbIntent.SetPower(false))

        assertTrue(effects.contains(CoreSideEffect.StopMusicSync(restoreState = true)))
        assertTrue(effects.contains(CoreSideEffect.StopAmbiance(restoreState = true)))
        assertTrue(effects.any {
            it is CoreSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createPowerCommand(false))
        })
    }

    @Test
    fun setColor_updatesCoreControlAndEmitsExpectedEffects() {
        val (newState, effects) = reduce(intent = RgbIntent.SetColor(10, 20, 30))

        assertEquals(10, newState.coreControl.red)
        assertEquals(20, newState.coreControl.green)
        assertEquals(30, newState.coreControl.blue)
        assertEquals(0, newState.coreControl.modeIndex)
        assertEquals("Colour", newState.coreControl.activeFeatureName)

        assertTrue(effects.contains(CoreSideEffect.SavePrefInt("red", 10)))
        assertTrue(effects.contains(CoreSideEffect.SavePrefInt("green", 20)))
        assertTrue(effects.contains(CoreSideEffect.SavePrefInt("blue", 30)))
        assertTrue(effects.contains(CoreSideEffect.SavePrefString("active_feature_name", "Colour")))
        assertTrue(effects.contains(CoreSideEffect.StopMusicSync(restoreState = false)))
        assertTrue(effects.contains(CoreSideEffect.StopAmbiance(restoreState = false)))
        assertTrue(effects.contains(CoreSideEffect.ClearExclusionsIfNotApplyingScene))
    }

    @Test
    fun setBrightness_updatesCoreControlAndEmitsNonSceneCancellingBroadcast() {
        val (newState, effects) = reduce(intent = RgbIntent.SetBrightness(42))

        assertEquals(42, newState.coreControl.brightness)
        assertTrue(effects.contains(CoreSideEffect.SavePrefInt("brightness", 42)))
        val broadcast = effects.filterIsInstance<CoreSideEffect.BroadcastCommand>().single()
        assertFalse(broadcast.cancelRunningScenes)
        assertTrue(broadcast.command.contentEquals(DuoCoProtocol.createBrightnessCommand(42)))
    }

    @Test
    fun setMode_usesMatchingCustomModeName() {
        val modes = listOf(CustomMode(byteValue = 3, name = "Sunset", category = "cat", direction = "none", colors = "Red,Orange"))
        val (newState, effects) = reduce(intent = RgbIntent.SetMode(3), customModes = modes)

        assertEquals(3, newState.coreControl.modeIndex)
        assertEquals("Sunset", newState.coreControl.activeFeatureName)
        assertTrue(effects.contains(CoreSideEffect.SavePrefString("active_feature_name", "Sunset")))
        assertTrue(effects.contains(CoreSideEffect.SavePrefInt("mode_index", 3)))
    }

    @Test
    fun setMode_fallsBackToDefaultNameWhenModeIndexUnknown() {
        val (newState, _) = reduce(intent = RgbIntent.SetMode(99), customModes = emptyList())

        assertEquals("Mode", newState.coreControl.activeFeatureName)
    }

    @Test
    fun setModeSpeed_updatesCoreControlAndBroadcasts() {
        val (newState, effects) = reduce(intent = RgbIntent.SetModeSpeed(75))

        assertEquals(75, newState.coreControl.modeSpeed)
        assertTrue(effects.contains(CoreSideEffect.CancelSceneChain))
        assertTrue(effects.contains(CoreSideEffect.SavePrefInt("mode_speed", 75)))
        assertTrue(effects.any {
            it is CoreSideEffect.BroadcastCommand &&
                it.command.contentEquals(DuoCoProtocol.createModeSpeedCommand(75))
        })
    }

    @Test
    fun setWarmth_convertsToRgbAndAppliesCctCalibration() {
        val address = "AA:BB:CC:DD:EE:FF"
        val profile = CctCorrectionProfile(
            macAddress = address, timestamp = 0L,
            scaleR = 1.0f, offsetR = 5f, scaleG = 1.0f, offsetG = 0f, scaleB = 1.0f, offsetB = -5f,
            iso = 100, exposureNs = 0L
        )
        val state = RgbUiState(
            connectivity = ConnectivityState(connectedDeviceAddress = address)
        )

        val (newState, effects) = reduce(
            state = state,
            intent = RgbIntent.SetWarmth(50),
            cctCalibrations = mapOf(address to profile)
        )

        val kelvin = ColorUtils.warmthToKelvin(50)
        val rgb = ColorUtils.convertKelvinToRgb(kelvin)

        assertEquals(50, newState.coreControl.warmth)
        assertEquals(0, newState.coreControl.modeIndex)
        assertEquals("CCT", newState.coreControl.activeFeatureName)
        assertEquals(rgb[0], newState.coreControl.red)
        assertEquals(rgb[1], newState.coreControl.green)
        assertEquals(rgb[2], newState.coreControl.blue)

        val expectedSendR = (rgb[0] * profile.scaleR + profile.offsetR).toInt().coerceIn(0, 255)
        val expectedSendG = (rgb[1] * profile.scaleG + profile.offsetG).toInt().coerceIn(0, 255)
        val expectedSendB = (rgb[2] * profile.scaleB + profile.offsetB).toInt().coerceIn(0, 255)
        val broadcast = effects.filterIsInstance<CoreSideEffect.BroadcastCommand>().single()
        assertTrue(broadcast.command.contentEquals(DuoCoProtocol.createColorCommand(expectedSendR, expectedSendG, expectedSendB)))
    }

    @Test
    fun setWarmth_coercesOutOfRangePercent() {
        val (newState, _) = reduce(intent = RgbIntent.SetWarmth(150))
        assertEquals(100, newState.coreControl.warmth)

        val (newState2, _) = reduce(intent = RgbIntent.SetWarmth(-20))
        assertEquals(0, newState2.coreControl.warmth)
    }

    @Test
    fun setShowFpsTracker_updatesCoreControlAndSavesPref() {
        val (newState, effects) = reduce(intent = RgbIntent.SetShowFpsTracker(true))

        assertTrue(newState.coreControl.showFpsTracker)
        assertTrue(effects.contains(CoreSideEffect.SavePrefBoolean("show_fps_tracker", true)))
    }

    // ------------------------------------------------------------------------
    // Regression: deviceStatesMap entry-creation fix.
    // Before the fix, applyControlledDeviceUpdates used mapValues, which only
    // touches keys already present in deviceStatesMap — newly-targeted
    // addresses silently got no entry at all.
    // ------------------------------------------------------------------------

    @Test
    fun setPower_createsMissingDeviceStatesMapEntryForNewTargetAddress() {
        val address = "11:11:11:11:11:11"
        val (newState, _) = reduce(intent = RgbIntent.SetPower(false), targetAddresses = listOf(address))

        val entry = newState.connectivity.deviceStatesMap[address]
        assertNotNull("expected a deviceStatesMap entry to be created for $address", entry)
        assertEquals(false, entry!!.isPowerOn)
    }

    @Test
    fun setColor_createsMissingDeviceStatesMapEntryForNewTargetAddress() {
        val address = "22:22:22:22:22:22"
        val (newState, _) = reduce(intent = RgbIntent.SetColor(1, 2, 3), targetAddresses = listOf(address))

        val entry = newState.connectivity.deviceStatesMap[address]
        assertNotNull(entry)
        assertEquals(1, entry!!.red)
        assertEquals(2, entry.green)
        assertEquals(3, entry.blue)
        assertEquals("Colour", entry.activeFeatureName)
    }

    @Test
    fun setBrightness_createsMissingDeviceStatesMapEntryForNewTargetAddress() {
        val address = "33:33:33:33:33:33"
        val (newState, _) = reduce(intent = RgbIntent.SetBrightness(64), targetAddresses = listOf(address))

        val entry = newState.connectivity.deviceStatesMap[address]
        assertNotNull(entry)
        assertEquals(64, entry!!.brightness)
    }

    @Test
    fun setMode_createsMissingDeviceStatesMapEntryForNewTargetAddress() {
        val address = "44:44:44:44:44:44"
        val (newState, _) = reduce(intent = RgbIntent.SetMode(5), targetAddresses = listOf(address))

        val entry = newState.connectivity.deviceStatesMap[address]
        assertNotNull(entry)
        assertEquals(5, entry!!.modeIndex)
    }

    @Test
    fun setWarmth_createsMissingDeviceStatesMapEntryForNewTargetAddress() {
        val address = "55:55:55:55:55:55"
        val (newState, _) = reduce(intent = RgbIntent.SetWarmth(30), targetAddresses = listOf(address))

        val entry = newState.connectivity.deviceStatesMap[address]
        assertNotNull(entry)
        assertEquals(30, entry!!.warmth)
        assertEquals("CCT", entry.activeFeatureName)
    }

    @Test
    fun setColor_preservesUntargetedExistingEntriesAndOverlaysTargetedOnes() {
        val untouchedAddress = "AA:AA:AA:AA:AA:AA"
        val targetedAddress = "BB:BB:BB:BB:BB:BB"
        val untouchedEntry = ActiveDeviceState(red = 9, green = 9, blue = 9)
        val existingTargetedEntry = ActiveDeviceState(red = 1, green = 1, blue = 1, brightness = 77)
        val state = RgbUiState(
            connectivity = ConnectivityState(
                deviceStatesMap = mapOf(untouchedAddress to untouchedEntry, targetedAddress to existingTargetedEntry)
            )
        )

        val (newState, _) = reduce(state = state, intent = RgbIntent.SetColor(5, 6, 7), targetAddresses = listOf(targetedAddress))

        assertEquals(untouchedEntry, newState.connectivity.deviceStatesMap[untouchedAddress])
        val updated = newState.connectivity.deviceStatesMap[targetedAddress]!!
        assertEquals(5, updated.red)
        assertEquals(6, updated.green)
        assertEquals(7, updated.blue)
        // fields not touched by SetColor (brightness) must be preserved from the existing entry
        assertEquals(77, updated.brightness)
    }

    // ========================================================================
    // Connectivity & Scanning group
    // ========================================================================

    @Test
    fun setActiveFeatureName_updatesCoreControlAndEmitsEffects() {
        val (newState, effects) = reduce(intent = RgbIntent.SetActiveFeatureName("Ambiance - Balanced"))

        assertEquals("Ambiance - Balanced", newState.coreControl.activeFeatureName)
        assertTrue(effects.contains(CoreSideEffect.SavePrefString("active_feature_name", "Ambiance - Balanced")))
        assertTrue(effects.contains(CoreSideEffect.CancelSceneChain))
        assertTrue(effects.contains(CoreSideEffect.ClearExclusionsIfNotApplyingScene))
    }

    @Test
    fun setDemoMode_true_withNoActiveConnections_clearsConnectivityDirectly() {
        val state = RgbUiState(
            connectivity = ConnectivityState(
                deviceConnectionStates = mapOf("stale" to BleConnectionState.DISCONNECTED)
            )
        )
        val (newState, effects) = reduce(state = state, intent = RgbIntent.SetDemoMode(true))

        assertTrue(newState.coreControl.isDemoMode)
        assertEquals(BleConnectionState.DISCONNECTED, newState.connectivity.connectionState)
        assertNull(newState.connectivity.connectedDeviceAddress)
        assertTrue(newState.connectivity.deviceConnectionStates.isEmpty())
        assertTrue(effects.none { it is CoreSideEffect.DisconnectDevice })
    }

    @Test
    fun setDemoMode_true_withActiveConnections_disconnectsOnlyConnectedOrConnectingAddresses() {
        // Regression: deviceConnectionStates never shrinks, so a stale DISCONNECTED entry
        // from an earlier session must NOT be re-targeted for disconnect, only the live
        // CONNECTED/CONNECTING ones (mirrors ViewModel.activeConnections membership).
        val state = RgbUiState(
            connectivity = ConnectivityState(
                deviceConnectionStates = mapOf(
                    "stale-disconnected" to BleConnectionState.DISCONNECTED,
                    "live-connected" to BleConnectionState.CONNECTED,
                    "live-connecting" to BleConnectionState.CONNECTING
                )
            )
        )
        val (newState, effects) = reduce(state = state, intent = RgbIntent.SetDemoMode(true))

        val disconnectAddresses = effects.filterIsInstance<CoreSideEffect.DisconnectDevice>().map { it.address }.toSet()
        assertEquals(setOf("live-connected", "live-connecting"), disconnectAddresses)
        assertFalse(disconnectAddresses.contains("stale-disconnected"))

        assertEquals(BleConnectionState.DISCONNECTED, newState.connectivity.deviceConnectionStates["stale-disconnected"])
        assertEquals(BleConnectionState.DISCONNECTING, newState.connectivity.deviceConnectionStates["live-connected"])
        assertEquals(BleConnectionState.DISCONNECTING, newState.connectivity.deviceConnectionStates["live-connecting"])
    }

    @Test
    fun setDemoMode_false_onlyTogglesFlagAndLogs() {
        val state = RgbUiState(coreControl = CoreControlState(isDemoMode = true))
        val (newState, effects) = reduce(state = state, intent = RgbIntent.SetDemoMode(false))

        assertFalse(newState.coreControl.isDemoMode)
        assertEquals(listOf(CoreSideEffect.Log("Switched to Real Hardware BLE Mode")), effects)
    }

    @Test
    fun startScanning_whenIdle_startsBleScanAndClearsError() {
        val state = RgbUiState(coreControl = CoreControlState(errorMessage = "boom", isDemoMode = false))
        val (newState, effects) = reduce(state = state, intent = RgbIntent.StartScanning)

        assertTrue(newState.connectivity.isScanning)
        assertNull(newState.coreControl.errorMessage)
        assertTrue(effects.contains(CoreSideEffect.StartBleScan))
    }

    @Test
    fun startScanning_inDemoMode_simulatesScanInsteadOfBle() {
        val state = RgbUiState(coreControl = CoreControlState(isDemoMode = true))
        val (_, effects) = reduce(state = state, intent = RgbIntent.StartScanning)

        assertTrue(effects.contains(CoreSideEffect.SimulateScan))
        assertFalse(effects.contains(CoreSideEffect.StartBleScan))
    }

    @Test
    fun startScanning_whenAlreadyScanning_isNoOp() {
        val state = RgbUiState(connectivity = ConnectivityState(isScanning = true))
        val (newState, effects) = reduce(state = state, intent = RgbIntent.StartScanning)

        assertSame(state, newState)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun stopScanning_whenScanning_stopsBleScan() {
        val state = RgbUiState(
            connectivity = ConnectivityState(isScanning = true),
            coreControl = CoreControlState(isDemoMode = false)
        )
        val (newState, effects) = reduce(state = state, intent = RgbIntent.StopScanning)

        assertFalse(newState.connectivity.isScanning)
        assertTrue(effects.contains(CoreSideEffect.StopBleScan))
    }

    @Test
    fun stopScanning_whenNotScanning_isNoOp() {
        val state = RgbUiState(connectivity = ConnectivityState(isScanning = false))
        val (newState, effects) = reduce(state = state, intent = RgbIntent.StopScanning)

        assertSame(state, newState)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun connectDevice_knownScannedDevice_setsConnectingStateAndDoesNotAutoSave() {
        val address = "66:66:66:66:66:66"
        val scanned = ScannedRgbDevice(address = address, name = "DuoCo Strip", rssi = -50, isDuoCoSuspected = true)
        val state = RgbUiState(connectivity = ConnectivityState(scannedDevices = listOf(scanned)))
        val saved = listOf(SavedDevice(macAddress = address, customName = "DuoCo Strip"))

        val (newState, effects) = reduce(state = state, intent = RgbIntent.ConnectDevice(address), savedDevices = saved)

        assertEquals(BleConnectionState.CONNECTING, newState.connectivity.deviceConnectionStates[address])
        assertEquals(address, newState.connectivity.connectedDeviceAddress)
        assertEquals("DuoCo Strip", newState.connectivity.connectedDeviceName)
        assertTrue(effects.contains(CoreSideEffect.ConnectDevice(address)))
        assertFalse(effects.any { it is CoreSideEffect.AutoSaveDeviceIfNew })
    }

    @Test
    fun connectDevice_unknownToSavedDevices_autoSavesNewDevice() {
        val address = "77:77:77:77:77:77"
        val scanned = ScannedRgbDevice(address = address, name = "New Strip", rssi = -60, isDuoCoSuspected = true)
        val state = RgbUiState(connectivity = ConnectivityState(scannedDevices = listOf(scanned)))

        val (_, effects) = reduce(state = state, intent = RgbIntent.ConnectDevice(address), savedDevices = emptyList())

        assertTrue(effects.contains(
            CoreSideEffect.AutoSaveDeviceIfNew(address = address, name = "New Strip", autoConnect = true, activeControl = true)
        ))
    }

    @Test
    fun disconnectDevice_setsDisconnectingStateAndEmitsEffect() {
        val address = "88:88:88:88:88:88"
        val (newState, effects) = reduce(intent = RgbIntent.DisconnectDevice(address))

        assertEquals(BleConnectionState.DISCONNECTING, newState.connectivity.deviceConnectionStates[address])
        assertEquals(listOf(CoreSideEffect.DisconnectDevice(address)), effects)
    }

    @Test
    fun disconnectAll_withNoActiveConnections_clearsConnectivityDirectly() {
        val state = RgbUiState(
            connectivity = ConnectivityState(
                deviceConnectionStates = mapOf("stale" to BleConnectionState.DISCONNECTED)
            )
        )
        val (newState, effects) = reduce(state = state, intent = RgbIntent.DisconnectAll)

        assertEquals(BleConnectionState.DISCONNECTED, newState.connectivity.connectionState)
        assertNull(newState.connectivity.connectedDeviceAddress)
        assertTrue(newState.connectivity.deviceConnectionStates.isEmpty())
        assertTrue(effects.isEmpty())
    }

    @Test
    fun disconnectAll_withActiveConnections_disconnectsOnlyConnectedOrConnectingAddresses() {
        // Regression: same activeConnections-membership mirror as SetDemoMode(true) — a stale
        // DISCONNECTED entry must not be re-targeted.
        val state = RgbUiState(
            connectivity = ConnectivityState(
                deviceConnectionStates = mapOf(
                    "stale-disconnected" to BleConnectionState.DISCONNECTED,
                    "live-connected" to BleConnectionState.CONNECTED
                )
            )
        )
        val (newState, effects) = reduce(state = state, intent = RgbIntent.DisconnectAll)

        val disconnectAddresses = effects.filterIsInstance<CoreSideEffect.DisconnectDevice>().map { it.address }.toSet()
        assertEquals(setOf("live-connected"), disconnectAddresses)
        assertEquals(BleConnectionState.DISCONNECTED, newState.connectivity.deviceConnectionStates["stale-disconnected"])
        assertEquals(BleConnectionState.DISCONNECTING, newState.connectivity.deviceConnectionStates["live-connected"])
    }

    @Test
    fun internalConnectionStateChanged_toConnected_setsActiveAddressAndName() {
        val address = "99:99:99:99:99:99"
        val scanned = ScannedRgbDevice(address = address, name = "Strip", rssi = -40, isDuoCoSuspected = true)
        val state = RgbUiState(connectivity = ConnectivityState(scannedDevices = listOf(scanned)))

        val (newState, effects) = reduce(
            state = state,
            intent = RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.CONNECTED)
        )

        assertEquals(BleConnectionState.CONNECTED, newState.connectivity.connectionState)
        assertEquals(address, newState.connectivity.connectedDeviceAddress)
        assertEquals("Strip", newState.connectivity.connectedDeviceName)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun internalConnectionStateChanged_toDisconnected_clearsActiveAddressWhenNoneRemainConnected() {
        val address = "AB:AB:AB:AB:AB:AB"
        val state = RgbUiState(
            connectivity = ConnectivityState(
                deviceConnectionStates = mapOf(address to BleConnectionState.CONNECTED),
                connectionState = BleConnectionState.CONNECTED,
                connectedDeviceAddress = address,
                connectedDeviceName = "Strip"
            )
        )

        val (newState, _) = reduce(
            state = state,
            intent = RgbIntent.InternalConnectionStateChanged(address, BleConnectionState.DISCONNECTED)
        )

        assertEquals(BleConnectionState.DISCONNECTED, newState.connectivity.connectionState)
        assertNull(newState.connectivity.connectedDeviceAddress)
        assertNull(newState.connectivity.connectedDeviceName)
    }

    @Test
    fun checkActiveAudioRoute_emitsSingleEffectAndDoesNotChangeState() {
        val state = RgbUiState()
        val (newState, effects) = reduce(state = state, intent = RgbIntent.CheckActiveAudioRoute)

        assertSame(state, newState)
        assertEquals(listOf(CoreSideEffect.CheckActiveAudioRoute), effects)
    }

    // ========================================================================
    // Device management group
    // ========================================================================

    @Test
    fun toggleAutoConnect_existingDevice_logsUpdateMessage() {
        val address = "CD:CD:CD:CD:CD:CD"
        val saved = listOf(SavedDevice(macAddress = address, customName = "Kitchen Strip"))

        val (_, effects) = reduce(
            intent = RgbIntent.ToggleAutoConnect(address, "Kitchen Strip", false),
            savedDevices = saved
        )

        assertTrue(effects.contains(CoreSideEffect.ToggleAutoConnect(address, "Kitchen Strip", false)))
        assertTrue(effects.contains(CoreSideEffect.Log("Updated Auto-Connect for Kitchen Strip to false")))
    }

    @Test
    fun toggleAutoConnect_newDevice_logsSavedMessage() {
        val address = "CE:CE:CE:CE:CE:CE"
        val (_, effects) = reduce(
            intent = RgbIntent.ToggleAutoConnect(address, "New Device", true),
            savedDevices = emptyList()
        )

        assertTrue(effects.contains(CoreSideEffect.Log("Saved device New Device with Auto-Connect = true")))
    }

    @Test
    fun toggleActiveControl_enabling_forColourFeature_sendsPowerBrightnessAndColorCommands() {
        val address = "DE:DE:DE:DE:DE:DE"
        val coreControl = CoreControlState(isPowerOn = true, red = 1, green = 2, blue = 3, brightness = 50, activeFeatureName = "Colour")
        val state = RgbUiState(coreControl = coreControl)

        val (newState, effects) = reduce(
            state = state,
            intent = RgbIntent.ToggleActiveControl(address, "Strip", true)
        )

        val entry = newState.connectivity.deviceStatesMap[address]
        assertNotNull(entry)
        assertEquals("Colour", entry!!.activeFeatureName)

        assertTrue(effects.contains(CoreSideEffect.UpdateActiveControl(address, "Strip", true)))
        assertTrue(effects.any {
            it is CoreSideEffect.SendCommandToDeviceDirect && it.address == address &&
                it.command.contentEquals(DuoCoProtocol.createPowerCommand(true))
        })
        assertTrue(effects.any {
            it is CoreSideEffect.SendCommandToDeviceDirect && it.address == address &&
                it.command.contentEquals(DuoCoProtocol.createBrightnessCommand(50))
        })
        assertTrue(effects.any {
            it is CoreSideEffect.SendCommandToDeviceDirect && it.address == address &&
                it.command.contentEquals(DuoCoProtocol.createColorCommand(1, 2, 3))
        })
    }

    @Test
    fun toggleActiveControl_disabling_createsBaselineEntryAndRestoresAutomationStateWhenTracked() {
        val address = "EF:EF:EF:EF:EF:EF"
        val automation = mapOf(address to RgbControllerViewModel.AutomationType.AUDIO)

        val (newState, effects) = reduce(
            intent = RgbIntent.ToggleActiveControl(address, "Strip", false),
            deviceAutomationMode = automation
        )

        assertNotNull(newState.connectivity.deviceStatesMap[address])
        assertTrue(effects.contains(CoreSideEffect.RestoreDeviceState(address, RgbControllerViewModel.AutomationType.AUDIO)))
    }

    @Test
    fun toggleActiveControl_disabling_noAutomationTracked_doesNotEmitRestore() {
        val address = "F0:F0:F0:F0:F0:F0"
        val (_, effects) = reduce(
            intent = RgbIntent.ToggleActiveControl(address, "Strip", false),
            deviceAutomationMode = emptyMap()
        )

        assertFalse(effects.any { it is CoreSideEffect.RestoreDeviceState })
    }

    @Test
    fun deleteSavedDevice_emitsDeleteLogAndDisconnectEffects() {
        val address = "F1:F1:F1:F1:F1:F1"
        val (newState, effects) = reduce(intent = RgbIntent.DeleteSavedDevice(address))

        assertEquals(
            listOf(
                CoreSideEffect.DeleteSavedDevice(address),
                CoreSideEffect.Log("Removed device from Saved Center: $address"),
                CoreSideEffect.DisconnectDevice(address)
            ),
            effects
        )
        assertEquals(RgbUiState(), newState)
    }

    @Test
    fun saveDeviceAlias_forConnectedDevice_updatesConnectedDeviceNameAndScannedAlias() {
        val address = "F2:F2:F2:F2:F2:F2"
        val scanned = ScannedRgbDevice(address = address, name = "Strip", rssi = -55, isDuoCoSuspected = true)
        val state = RgbUiState(
            connectivity = ConnectivityState(connectedDeviceAddress = address, scannedDevices = listOf(scanned))
        )

        val (newState, effects) = reduce(state = state, intent = RgbIntent.SaveDeviceAlias(address, "Living Room"))

        assertEquals("Living Room", newState.connectivity.connectedDeviceName)
        assertEquals("Living Room", newState.connectivity.scannedDevices.single().alias)
        assertTrue(effects.contains(CoreSideEffect.SaveDeviceAlias(address, "Living Room")))
    }

    @Test
    fun saveDeviceAlias_forDifferentDevice_doesNotChangeConnectedDeviceName() {
        val targetAddress = "F3:F3:F3:F3:F3:F3"
        val connectedAddress = "F4:F4:F4:F4:F4:F4"
        val state = RgbUiState(
            connectivity = ConnectivityState(connectedDeviceAddress = connectedAddress, connectedDeviceName = "Original")
        )

        val (newState, _) = reduce(state = state, intent = RgbIntent.SaveDeviceAlias(targetAddress, "Renamed"))

        assertEquals("Original", newState.connectivity.connectedDeviceName)
    }

    @Test
    fun deleteDeviceAlias_forConnectedDevice_revertsToScannedDeviceName() {
        val address = "F5:F5:F5:F5:F5:F5"
        val scanned = ScannedRgbDevice(address = address, name = "Original Name", rssi = -55, isDuoCoSuspected = true, alias = "Custom Alias")
        val state = RgbUiState(
            connectivity = ConnectivityState(connectedDeviceAddress = address, connectedDeviceName = "Custom Alias", scannedDevices = listOf(scanned))
        )

        val (newState, effects) = reduce(state = state, intent = RgbIntent.DeleteDeviceAlias(address))

        assertEquals("Original Name", newState.connectivity.connectedDeviceName)
        assertNull(newState.connectivity.scannedDevices.single().alias)
        assertTrue(effects.contains(CoreSideEffect.DeleteDeviceAlias(address)))
    }

    @Test
    fun deleteDeviceAlias_forUnknownConnectedDevice_fallsBackToUnknownDeviceLabel() {
        val address = "F6:F6:F6:F6:F6:F6"
        val state = RgbUiState(
            connectivity = ConnectivityState(connectedDeviceAddress = address, connectedDeviceName = "Custom Alias", scannedDevices = emptyList())
        )

        val (newState, _) = reduce(state = state, intent = RgbIntent.DeleteDeviceAlias(address))

        assertEquals("Unknown Device", newState.connectivity.connectedDeviceName)
    }
}
