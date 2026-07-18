package com.example.ui.components

import com.example.domain.model.AppScene
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SceneCreationDialog(
    devices: List<com.example.db.SavedDevice>,
    currentGlobalMode: String,
    availableScenes: List<AppScene>,
    sceneToEdit: AppScene? = null,
    onDismissRequest: () -> Unit,
    onEditAnimation: () -> Unit = {},
    onSaveScene: (name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean, includeCalibrationSettings: Boolean, includeAudioSettings: Boolean, chainedSceneId: String?, chainedSceneDelaySeconds: Int?) -> Unit
) {
    FullScreenDialog(
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            SceneCreationScreenContent(
                devices = devices,
                currentGlobalMode = currentGlobalMode,
                availableScenes = availableScenes,
                sceneToEdit = sceneToEdit,
                onDismissRequest = onDismissRequest,
                onEditAnimation = onEditAnimation,
                onSaveScene = onSaveScene
            )
        }
    }
}

// NOTE: Do NOT place bottom-anchored action buttons (Save/Cancel etc.) inside a
// Scaffold's `bottomBar` slot when that Scaffold lives inside a Dialog-based
// full-screen composable (like this one, wrapped in FullScreenDialog). Dialog
// windows in Jetpack Compose spawn their own separate Android window, and
// system navigation-bar insets do not reliably propagate into that window's
// bottomBar slot — this caused the Save button to be cut off/obscured behind
// the nav bar (see [date/session] fix). Instead, place action buttons as the
// final item(s) inside the screen's normal scrollable content Column, so they
// scroll into view naturally and inherit the same content padding as
// everything else. This applies specifically to Dialog-hosted full-screen
// screens — bottomBar is fine to use in screens hosted directly by the main
// Activity/Scaffold (e.g. the main tab screens), which don't have this
// inset-dispatch problem.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneCreationScreenContent(
    devices: List<com.example.db.SavedDevice>,
    currentGlobalMode: String,
    availableScenes: List<AppScene>,
    sceneToEdit: AppScene? = null,
    onDismissRequest: () -> Unit,
    onEditAnimation: () -> Unit = {},
    onSaveScene: (name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean, includeCalibrationSettings: Boolean, includeAudioSettings: Boolean, chainedSceneId: String?, chainedSceneDelaySeconds: Int?) -> Unit
) {
    var sceneName by remember { mutableStateOf(sceneToEdit?.name ?: "") }
    
    val modeOptions = listOf(
        Triple("Colour", "Colour", "Save the current solid colour picker value"),
        Triple("CCT", "CCT", "Save the current color temperature"),
        Triple("HardwareMode", "LED Mode", "Save the current built-in hardware animation mode and speed"),
        Triple("Audio", "Audio Visualiser", "Save the current music sync mode, preset, and settings"),
        Triple("Ambiance", "Ambiance", "Save the current video ambiance capture state and settings"),
        Triple("Power Off", "Power Off", "Save the power off state")
    )
    var includeBrightness by remember { mutableStateOf(sceneToEdit?.state?.brightness != null) }
    var includeSpeed by remember { mutableStateOf(sceneToEdit?.state?.modeSpeed != null) }
    var targetScope by remember { mutableStateOf(sceneToEdit?.targetScope ?: "ALL_DEVICES") }
    var selectedDeviceMacs by remember { mutableStateOf<Set<String>>(sceneToEdit?.selectedDeviceMacs?.toSet() ?: emptySet()) }
    var includeAmbianceSettings by remember { mutableStateOf(sceneToEdit?.state?.ambianceResponseSpeed != null) }
    var includeCalibrationSettings by remember { mutableStateOf(sceneToEdit?.state?.bluetoothDelayMs != null) }
    var includeAudioSettings by remember { mutableStateOf(sceneToEdit?.state?.audioAttack != null) }
    var selectedChainedSceneId by remember { mutableStateOf<String?>(sceneToEdit?.chainedSceneId) }
    var chainedSceneDelaySeconds by remember { mutableStateOf(sceneToEdit?.chainedSceneDelaySeconds ?: 0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (sceneToEdit != null) "Edit Scene" else "Create Scene", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Introductory helpful tip card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "A scene lets you capture your active device setup (color, modes, active configurations) and quickly restore it later with a single tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 1: Scene Identity
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Scene Name",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = sceneName,
                        onValueChange = { sceneName = it },
                        placeholder = { Text("e.g., Movie Night, Gaming Session") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // Section 2: Mode (Read-Only)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Mode",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (sceneToEdit != null) {
                            val isSmartScene = sceneToEdit.state.animatedSequence != null
                            val friendlyName = if (isSmartScene) "Smart Scene" else {
                                val savedMode = sceneToEdit.state.groupASelection
                                modeOptions.find { it.first == savedMode }?.second ?: savedMode ?: "Settings Only"
                            }
                            Text(
                                text = "Saved mode: $friendlyName",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isSmartScene) {
                                Text(
                                    text = "This scene runs a custom on-device AI generated animation sequence.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = onEditAnimation,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .testTag("edit_animation_button"),
                                    shape = RoundedCornerShape(100.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text("Edit Animation", fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                Text(
                                    text = "The mode captured by this scene cannot be changed. Create a new scene to capture a different mode.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                text = "Currently capturing: $currentGlobalMode",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val activeDesc = modeOptions.find { it.first == currentGlobalMode }?.third ?: "Save the current active state"
                            Text(
                                text = activeDesc,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The captured state reflects the app's single global active setting, not any specific individual device's state.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Section 3: Additional Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Additional Settings",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Select any additional settings to capture and restore:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { includeBrightness = !includeBrightness }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Capture Brightness",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Include current brightness level in this scene",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = includeBrightness,
                            onCheckedChange = { includeBrightness = it },
                            modifier = Modifier.testTag("capture_brightness_switch")
                        )
                    }

                    if (currentGlobalMode == "HardwareMode") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { includeSpeed = !includeSpeed }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Capture Effect Speed",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Include current effect speed level in this scene",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = includeSpeed,
                                onCheckedChange = { includeSpeed = it },
                                modifier = Modifier.testTag("capture_effect_speed_switch")
                            )
                        }
                    }
                }
            }

            
            // Section 3.5: Settings to Include
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Settings to Include",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Select independent settings to capture alongside the mode:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { includeAmbianceSettings = !includeAmbianceSettings }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Ambiance Settings",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Include response speed, smoothness, boost, etc.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = includeAmbianceSettings,
                            onCheckedChange = { includeAmbianceSettings = it }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { includeCalibrationSettings = !includeCalibrationSettings }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Calibration Settings",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Include Bluetooth delay calibration",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = includeCalibrationSettings,
                            onCheckedChange = { includeCalibrationSettings = it }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { includeAudioSettings = !includeAudioSettings }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Include Audio Settings",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Include attack, decay, gain, gamma, min brightness",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = includeAudioSettings,
                            onCheckedChange = { includeAudioSettings = it }
                        )
                    }
                }
            }
            
            // Section 4: Devices
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Devices",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Choose which connected devices this scene applies to when triggered",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    val scopes = listOf(
                        "ALL_DEVICES" to "All Devices",
                        "SELECT_DEVICES" to "Select Devices"
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        scopes.forEach { (scopeId, scopeLabel) ->
                            val isSelected = targetScope == scopeId
                            Surface(
                                selected = isSelected,
                                onClick = { targetScope = scopeId },
                                shape = RoundedCornerShape(100.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = scopeLabel,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (targetScope == "SELECT_DEVICES") {
                        Spacer(modifier = Modifier.height(8.dp))
                        devices.filter { it.isActiveControlEnabled }.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (selectedDeviceMacs.contains(device.macAddress)) {
                                            selectedDeviceMacs = selectedDeviceMacs - device.macAddress
                                        } else {
                                            selectedDeviceMacs = selectedDeviceMacs + device.macAddress
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = device.customName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = selectedDeviceMacs.contains(device.macAddress),
                                    onCheckedChange = null
                                )
                            }
                        }
                    }
                }
            }

            // Section 5: Chain Another Scene
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Chain Another Scene",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Optionally run another saved scene automatically after this one, with a delay. Chains can loop, and will stop automatically if you make a manual change.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    var chainDropdownExpanded by remember { mutableStateOf(false) }
                    val isChainSelected = selectedChainedSceneId != null
                    val currentChainedSceneName = availableScenes.find { it.id == selectedChainedSceneId }?.name ?: "No chained scene"

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            selected = isChainSelected,
                            onClick = { chainDropdownExpanded = true },
                            shape = RoundedCornerShape(100.dp),
                            color = if (isChainSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isChainSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = currentChainedSceneName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isChainSelected) FontWeight.Bold else FontWeight.Normal),
                                    color = if (isChainSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = chainDropdownExpanded,
                            onDismissRequest = { chainDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.8f),
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            DropdownMenuItem(
                                text = { Text("No chained scene", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) },
                                onClick = {
                                    selectedChainedSceneId = null
                                    chainDropdownExpanded = false
                                }
                            )
                            availableScenes.forEach { scene ->
                                DropdownMenuItem(
                                    text = { Text(scene.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) },
                                    onClick = {
                                        selectedChainedSceneId = scene.id
                                        chainDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedChainedSceneId != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Delay Before Running: ${if (chainedSceneDelaySeconds == 0) "Immediately" else "${chainedSceneDelaySeconds}s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        HapticBouncySlider(
                            value = chainedSceneDelaySeconds.toFloat(),
                            onValueChange = { chainedSceneDelaySeconds = it.toInt() },
                            valueRange = 0f..120f,
                            steps = 119,
                            totalSteps = 120
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val groupA = if (sceneToEdit != null) sceneToEdit.state.groupASelection else if (currentGlobalMode == "None") null else currentGlobalMode
                        val macList = if (targetScope == "SELECT_DEVICES") selectedDeviceMacs.toList() else null
                        onSaveScene(sceneName, groupA, includeBrightness, includeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, selectedChainedSceneId, chainedSceneDelaySeconds)
                    },
                    enabled = sceneName.isNotBlank() && (targetScope == "ALL_DEVICES" || selectedDeviceMacs.isNotEmpty()),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(if (sceneToEdit != null) "Save Changes" else "Save Scene")
                }
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}
