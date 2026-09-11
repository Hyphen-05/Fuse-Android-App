package com.example.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ActiveDeviceState
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.RgbUiState
import com.example.ambiance.AmbianceCaptureState
import com.example.db.SavedDevice
import com.example.domain.model.AppScene
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: RgbControllerViewModel,
    permissionsGranted: Boolean,
    permissionsBlocked: Boolean,
    onGrantPermissions: () -> Unit,
    onStartAmbianceCapture: () -> Unit,
    modifier: Modifier = Modifier,
    // Strips with a radio link that are still discovering services -- a second or so from usable.
    // Only connectionManager knows this, so the caller works it out. See the tiles row below.
    linkingAddresses: Set<String> = emptySet()
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val scenes by viewModel.scenes.collectAsState()

    val activeComposeColor = Color(uiState.coreControl.red, uiState.coreControl.green, uiState.coreControl.blue)

    var showCreateSceneDialogFromHome by rememberSaveable { mutableStateOf(false) }
    var selectedSceneId by rememberSaveable { mutableStateOf<String?>(null) }
    var sceneToDelete by remember { mutableStateOf<AppScene?>(null) }
    var sceneToRename by remember { mutableStateOf<AppScene?>(null) }
    var sceneToEdit by remember { mutableStateOf<AppScene?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var showMenuForSceneId by remember { mutableStateOf<String?>(null) }

    fun getSceneDescription(scene: AppScene): String {
        val state = scene.state
        val modeName = when (state?.groupASelection) {
            "Colour" -> "Solid Colour"
            "CCT" -> "Color Temp (CCT)"
            "HardwareMode" -> "LED Animation"
            "Audio" -> "Audio Visualizer"
            "Ambiance" -> "Video Ambiance"
            else -> "Settings Only"
        }
        val brightnessStr = if (state?.brightness != null) ", ${state.brightness}%" else ""
        return "$modeName$brightnessStr"
    }

    if (showCreateSceneDialogFromHome) {
        val currentGlobalMode = if (!uiState.coreControl.isPowerOn) "Power Off"
            else when {
                uiState.coreControl.activeFeatureName == "Colour" -> "Colour"
                uiState.coreControl.activeFeatureName == "CCT" -> "CCT"
                uiState.coreControl.activeFeatureName.startsWith("Audio") || uiState.coreControl.activeFeatureName.startsWith("LED Visualiser") -> "Audio"
                uiState.coreControl.activeFeatureName.startsWith("Ambiance") -> "Ambiance"
                else -> "HardwareMode"
            }
        SceneCreationDialog(
            devices = savedDevices,
            currentGlobalMode = currentGlobalMode,
            availableScenes = scenes,
            onDismissRequest = { showCreateSceneDialogFromHome = false },
            onSaveScene = { name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds ->
                viewModel.saveScene(name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds)
                showCreateSceneDialogFromHome = false
            }
        )
    }

    if (sceneToEdit != null) {
        val currentGlobalMode = sceneToEdit?.state?.groupASelection ?: ""
        SceneCreationDialog(
            devices = savedDevices,
            currentGlobalMode = currentGlobalMode,
            availableScenes = scenes,
            sceneToEdit = sceneToEdit,
            onDismissRequest = { sceneToEdit = null },
            onSaveScene = { name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds ->
                viewModel.updateScene(sceneToEdit!!.id, name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds)
                sceneToEdit = null
            }
        )
    }

    if (sceneToDelete != null) {
        AlertDialog(
            onDismissRequest = { sceneToDelete = null },
            title = { Text("Delete Scene", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = { Text("Are you sure you want to delete the scene '${sceneToDelete?.name}'?") },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            confirmButton = {
                Button(
                    onClick = {
                        sceneToDelete?.let {
                            viewModel.deleteScene(it.id)
                        }
                        sceneToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.height(44.dp),
                    shape = CircleShape
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { sceneToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (sceneToRename != null) {
        AlertDialog(
            onDismissRequest = { sceneToRename = null },
            title = { Text("Rename Scene", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new name for scene '${sceneToRename?.name}':", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        singleLine = true,
                        placeholder = { Text("New Scene Name") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("rename_scene_input")
                    )
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInputText.trim()
                        if (trimmed.isNotEmpty()) {
                            sceneToRename?.let {
                                viewModel.renameScene(it.id, trimmed)
                            }
                            sceneToRename = null
                        }
                    },
                    enabled = renameInputText.isNotBlank(),
                    modifier = Modifier.height(44.dp),
                    shape = CircleShape
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { sceneToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // The connect used to end with the control deck simply being there on the next frame. It is
    // now choreographed in *drawing* only -- see connectReveal at the bottom of this file. Layout
    // lands in one frame; each piece then fades and rises into its own place, one beat after the
    // piece above it.
    //
    // revealAt marks a connect that happened while this screen was already on show. It is null on
    // the first composition, so a cold launch, a tab switch back to Home, or a ViewModel that was
    // already connected all draw the page at rest. composedOnce is a plain array rather than state
    // on purpose: writing state from SideEffect would cost a recomposition for nothing.
    val connected = uiState.connectivity.connectionState == BleConnectionState.CONNECTED
    val composedOnce = remember { booleanArrayOf(false) }
    SideEffect { composedOnce[0] = true }
    val revealAt = remember(connected) {
        if (connected && composedOnce[0]) SystemClock.uptimeMillis() else null
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Permissions Alert Card (when in Real Mode & no permissions) ---
        if (!permissionsGranted && !uiState.coreControl.isDemoMode) {
            item(key = "permissions_card") {
                val grantPermissionsInteractionSource = remember { MutableInteractionSource() }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Bluetooth Permissions Required",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                        }
                        Text(
                            text = if (permissionsBlocked) {
                                "Bluetooth permissions were denied, and Android won't ask again from " +
                                    "inside the app. Turn them on in the app's system settings to scan " +
                                    "for light strips."
                            } else {
                                "To scan and connect to physical DuoCo RGB light strips, this app " +
                                    "requires local Bluetooth permissions."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onGrantPermissions,
                                interactionSource = grantPermissionsInteractionSource,
                                modifier = Modifier
                                    .height(44.dp)
                                    .joyfulPress(grantPermissionsInteractionSource),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = CircleShape
                            ) {
                                Text(if (permissionsBlocked) "Open App Settings" else "Grant Permissions")
                            }
                        }
                    }
                }
            }
        }

        // --- Connected Device Tiles ---
        val connectedAddresses = uiState.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }.keys.toList()
        if (connectedAddresses.isEmpty()) {
            item(key = "disconnected_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connection_status_card_empty"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = "Disconnected",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                        Text(
                            text = "Select a compatible BLE RGB device from the Devices tab to connect and control.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        } else {
            item(key = "device_tiles") {
                // A strip that is already linked holds its slot before it is usable. Two strips
                // rarely land together -- 224ms apart on the recorded connect -- and without the
                // reserved slot the first tile arrived at full width and halved in one frame as the
                // second popped in beside it: a layout jump in the middle of the reveal. Linking
                // lasts about a second, so the empty slot is never on screen for long, and a strip
                // that is merely *trying* to connect gets no slot at all, so a strip that is switched
                // off cannot leave a hole next to a working one.
                //
                // Map order, not arrival order, so a tile never changes side when its partner lands.
                val slotAddresses = uiState.connectivity.deviceConnectionStates.keys.filter { address ->
                    uiState.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED ||
                        address in linkingAddresses
                }
                if (slotAddresses.size <= 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        slotAddresses.forEach { address ->
                            key(address) {
                                val isConnected = uiState.connectivity.deviceConnectionStates[address] ==
                                    BleConnectionState.CONNECTED
                                // Each tile reveals on its own arrival, so the second strip fades
                                // into its slot instead of popping. Same first-composition rule as
                                // revealAt: tiles already connected when Home appears draw at rest.
                                val arrivedAt = remember(isConnected) {
                                    if (isConnected && composedOnce[0]) SystemClock.uptimeMillis() else null
                                }
                                if (isConnected) {
                                    DeviceTile(
                                        address = address,
                                        viewModel = viewModel,
                                        uiState = uiState,
                                        savedDevices = savedDevices,
                                        activeComposeColor = activeComposeColor,
                                        modifier = Modifier
                                            .weight(1f)
                                            .connectReveal(arrivedAt, 0)
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        items(connectedAddresses) { address ->
                            DeviceTile(
                                address = address,
                                viewModel = viewModel,
                                uiState = uiState,
                                savedDevices = savedDevices,
                                activeComposeColor = activeComposeColor,
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- Control Deck (Only displayed when CONNECTED) ---
        if (uiState.connectivity.connectionState == BleConnectionState.CONNECTED) {
            val controlsInert = !uiState.coreControl.isPowerOn

            if (controlsInert) {
                item(key = "power_off_hint") {
                    PowerOffHintCard(
                        onTurnOn = { viewModel.setPower(true) },
                        modifier = Modifier
                            .connectReveal(revealAt, 1)
                    )
                }
            }

            // CCT Warmth Slider Card (in place of power status card)
            item(key = "cct_card") {
                Card(
                    modifier = Modifier
                        .connectReveal(revealAt, 2)
                        .fillMaxWidth()
                        .inertWhen(controlsInert)
                        .testTag("cct_control_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        val warmthPercentage = uiState.coreControl.warmth
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Color Temperature (CCT)",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                            Text(
                                text = "$warmthPercentage%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,

                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        val warmthGradient = remember {
                            // Generate a perceptually matched gradient track to align with the non-linear slider mapping
                            val colors = (0..10).map { step ->
                                val pct = step * 10
                                val k = ColorUtils.warmthToKelvin(pct)
                                val rgb = ColorUtils.convertKelvinToRgb(k)
                                Color(rgb[0], rgb[1], rgb[2])
                            }
                            androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                        }

                        ExpressiveSlider(
                            value = warmthPercentage,
                            onValueChange = { viewModel.setWarmth(it) },
                            labelPrefix = "Warmth",
                            activeColor = Color.Transparent,
                            inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = Color.White,
                            trackBrush = warmthGradient,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("warmth_slider")
                        )
                    }
                }
            }
            // Brightness Slider Card (below power card)
            item(key = "brightness_card") {
                Card(
                    modifier = Modifier
                        .connectReveal(revealAt, 3)
                        .fillMaxWidth()
                        .inertWhen(controlsInert)
                        .testTag("brightness_control_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dimming",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "${uiState.coreControl.brightness}%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,

                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        ExpressiveSlider(
                            value = uiState.coreControl.brightness,
                            onValueChange = { viewModel.setBrightness(it) },
                            labelPrefix = "Brightness",
                            activeColor = MaterialTheme.colorScheme.primary,
                            inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("brightness_slider")
                        )
                    }
                }
            }

            // Color Preview Card (existing colour card)
            item(key = "colour_card") {
                Card(
                    modifier = Modifier
                        .connectReveal(revealAt, 4)
                        .fillMaxWidth()
                        .inertWhen(controlsInert)
                        .testTag("control_deck_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val paletteColors = listOf(
                                Color.Red, Color.Green, Color.Blue,
                                Color.Yellow, Color.Cyan, Color(0xFF6A0DAD),
                                Color.White, Color(0xFFFF1493), Color(0xFFFFA500), Color(0xFF00FF7F)
                            )
                            val leftChips = paletteColors.take(5)
                            val rightChips = paletteColors.drop(5)

                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                leftChips.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (activeComposeColor == color) 3.dp else 1.dp,
                                                color = if (activeComposeColor == color) MaterialTheme.colorScheme.onSurface else Color.White,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                viewModel.setColor(
                                                    (color.red * 255).toInt(),
                                                    (color.green * 255).toInt(),
                                                    (color.blue * 255).toInt()
                                                )
                                            }
                                            .testTag("palette_color_${(color.red * 255).toInt()}_${(color.green * 255).toInt()}_${(color.blue * 255).toInt()}")
                                    )
                                }
                            }

                            // Interactive Canvas Color Wheel
                            ColorWheel(
                                selectedColor = activeComposeColor,
                                onColorChanged = { r, g, b ->
                                    viewModel.setColor(r, g, b)
                                },
                                modifier = Modifier
                                    .size(200.dp)
                                    .testTag("interactive_color_wheel")
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                rightChips.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (activeComposeColor == color) 3.dp else 1.dp,
                                                color = if (activeComposeColor == color) MaterialTheme.colorScheme.onSurface else Color.White,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                viewModel.setColor(
                                                    (color.red * 255).toInt(),
                                                    (color.green * 255).toInt(),
                                                    (color.blue * 255).toInt()
                                                )
                                            }
                                            .testTag("palette_color_${(color.red * 255).toInt()}_${(color.green * 255).toInt()}_${(color.blue * 255).toInt()}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } // End of connectionState == CONNECTED

        // --- Grid-based Quick Access Scenes ---
        val gridItems = scenes + listOf(null)
        val chunked = gridItems.chunked(2)
        // The last beats of the connect reveal. The deck pushes these ~1000px down, so they re-enter
        // at their new position rather than travelling to it -- the travel was what looked wrong.
        chunked.forEachIndexed { rowIndex, rowItems ->
            item(key = "scene_row_" + rowIndex) {
                Row(
                    modifier = Modifier
                        .connectReveal(revealAt, 5 + rowIndex)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { scene ->
                        if (scene != null) {
                            val chipInteractionSource = remember { MutableInteractionSource() }
                            val isSelected = selectedSceneId == scene.id
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .combinedClickable(
                                        interactionSource = chipInteractionSource,
                                        indication = androidx.compose.foundation.LocalIndication.current,
                                        onClick = {
                                            selectedSceneId = scene.id
                                            viewModel.applyScene(scene)
                                            if (scene.state.groupASelection == "Ambiance" &&
                                                scene.state.ambianceIsOn == true &&
                                                !AmbianceCaptureState.isActive.value) {
                                                onStartAmbianceCapture()
                                            }
                                        },
                                        onLongClick = {
                                            showMenuForSceneId = scene.id
                                        }
                                    )
                                    .testTag("scene_chip_${scene.id}")
                                    .joyfulPress(chipInteractionSource),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = scene.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = getSceneDescription(scene),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showMenuForSceneId == scene.id,
                                        onDismissRequest = { showMenuForSceneId = null },
                                        shape = RoundedCornerShape(16.dp),
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Edit", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) },
                                            onClick = {
                                                showMenuForSceneId = null
                                                sceneToEdit = scene
                                            },
                                            leadingIcon = { Icon(Icons.Default.Tune, contentDescription = "Edit Scene", modifier = Modifier.size(18.dp)) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) },
                                            onClick = {
                                                showMenuForSceneId = null
                                                sceneToRename = scene
                                                renameInputText = scene.name
                                            },
                                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Rename Scene", modifier = Modifier.size(18.dp)) }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                showMenuForSceneId = null
                                                sceneToDelete = scene
                                            },
                                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete Scene", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                                        )
                                    }
                                }
                            }
                        } else {
                            val addInteractionSource = remember { MutableInteractionSource() }
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .clickable(
                                        interactionSource = addInteractionSource,
                                        indication = androidx.compose.foundation.LocalIndication.current
                                    ) {
                                        showCreateSceneDialogFromHome = true
                                    }
                                    .testTag("create_scene_chip_home")
                                    .joyfulPress(addInteractionSource),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Create Scene",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "Create",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "New Scene",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (rowItems.size < 2) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceTile(
    address: String,
    viewModel: RgbControllerViewModel,
    uiState: RgbUiState,
    savedDevices: List<SavedDevice>,
    activeComposeColor: Color,
    modifier: Modifier = Modifier
) {
    val savedDev = savedDevices.find { it.macAddress == address }
    val scannedDev = uiState.connectivity.scannedDevices.find { it.address == address }
    val displayName = savedDev?.customName ?: scannedDev?.alias ?: scannedDev?.name ?: "DuoCo Light"
    val isActiveControlled = savedDev?.isActiveControlEnabled ?: true
    val hapticFeedback = LocalHapticFeedback.current
    val hapticType = rememberExpressiveHapticType()

    val deviceState = uiState.connectivity.deviceStatesMap[address] ?: ActiveDeviceState(
        activeFeatureName = uiState.coreControl.activeFeatureName,
        red = uiState.coreControl.red,
        green = uiState.coreControl.green,
        blue = uiState.coreControl.blue,
        warmth = uiState.coreControl.warmth,
        modeIndex = uiState.coreControl.modeIndex,
        brightness = uiState.coreControl.brightness,
        isPowerOn = uiState.coreControl.isPowerOn
    )
    val deviceColor = Color(deviceState.red, deviceState.green, deviceState.blue)

    Card(
        modifier = modifier
            .testTag("device_tile_$address")
            .clip(RoundedCornerShape(24.dp))
            .clickable {
                hapticFeedback.performHapticFeedback(hapticType)
                viewModel.toggleActiveControl(address, displayName, !isActiveControlled)
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActiveControlled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(
            width = if (isActiveControlled) 2.dp else 1.dp,
            color = if (isActiveControlled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isActiveControlled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }

                if (deviceState.activeFeatureName == "Colour" || deviceState.activeFeatureName == "CCT") {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(deviceColor)
                            .border(1.dp, Color.White, CircleShape)
                    )
                } else {
                    val icon = when {
                        deviceState.activeFeatureName.contains("Music", ignoreCase = true) ||
                        deviceState.activeFeatureName.contains("Visualiser", ignoreCase = true) ||
                        deviceState.activeFeatureName.contains("Audio", ignoreCase = true) -> Icons.Default.MusicNote
                        deviceState.activeFeatureName.contains("Ambiance", ignoreCase = true) ||
                        deviceState.activeFeatureName.contains("Ambience", ignoreCase = true) -> Icons.Default.FilterAlt
                        else -> Icons.Default.Palette
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Mode: ${deviceState.activeFeatureName}",
                        modifier = Modifier.size(20.dp),
                        tint = if (isActiveControlled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isActiveControlled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = deviceState.activeFeatureName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActiveControlled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Shown on Home when a device is connected but power is off, above the dimmed control deck.
 *
 * The deck used to be hidden outright in this state, which left Home nearly blank and gave no clue
 * that the power button in the top bar is what brings the controls back.
 */
@Composable
private fun PowerOffHintCard(onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("power_off_hint_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Power is off",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Turn the lights on to use the controls below.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            FilledTonalButton(
                onClick = onTurnOn,
                shape = CircleShape,
                modifier = Modifier.testTag("power_on_hint_btn")
            ) {
                Text("Turn on")
            }
        }
    }
}

/** How far behind the piece above it each piece of the connect reveal starts. */
private const val REVEAL_STAGGER_MS = 50L

/**
 * A piece that first composes this long after the connect draws at rest. Lazy items compose as they
 * scroll in, and without this a card first reached a minute later would play an entrance for nobody.
 */
private const val REVEAL_WINDOW_MS = 1_000L

/** Small on purpose: a settle into place, not travel. Long moves are what looked wrong before. */
private val REVEAL_RISE = 24.dp

/**
 * One beat of the connect reveal: fade in and rise [REVEAL_RISE] into place, [index] beats after the
 * connect, on the Expressive motion scheme's springs (MotionScheme.expressive) -- effects spec for
 * the fade, which has no bounce because a bouncing opacity reads as flicker, and spatial spec for the
 * rise, whose bounce is the settle.
 *
 * All of it happens in graphicsLayer, and that is the point. The card is full-size in layout from the
 * first frame, so nothing below it is pushed while it animates. Both earlier attempts failed on
 * layout moving -- once as a jump, once as a 1000px slide.
 */
@Composable
private fun Modifier.connectReveal(revealAt: Long?, index: Int): Modifier {
    val motion = remember { MotionScheme.expressive() }
    val risePx = with(LocalDensity.current) { REVEAL_RISE.toPx() }
    val startDelayMs = remember(revealAt) {
        revealAt?.let { at ->
            val elapsed = SystemClock.uptimeMillis() - at
            if (elapsed > REVEAL_WINDOW_MS) null else (index * REVEAL_STAGGER_MS - elapsed).coerceAtLeast(0L)
        }
    }
    val fade = remember(revealAt) { Animatable(if (startDelayMs == null) 1f else 0f) }
    val lift = remember(revealAt) { Animatable(if (startDelayMs == null) 0f else 1f) }
    LaunchedEffect(revealAt) {
        if (startDelayMs == null) return@LaunchedEffect
        delay(startDelayMs)
        launch { fade.animateTo(1f, motion.defaultEffectsSpec()) }
        lift.animateTo(0f, motion.defaultSpatialSpec())
    }
    return this.graphicsLayer {
        alpha = fade.value
        translationY = lift.value * risePx
    }
}
