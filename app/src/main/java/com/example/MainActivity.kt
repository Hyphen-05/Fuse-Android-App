package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.app.Activity
import android.media.projection.MediaProjectionManager
import com.example.ambiance.AmbianceCaptureService
import com.example.ambiance.AmbianceCaptureState
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.GraphicEq
import com.example.ui.components.ModesScreen
import com.example.ui.components.MusicScreen
import com.example.ui.components.HomeScreen
import com.example.ui.components.DevicesScreen
import com.example.core.animation.ProceduralSceneParams
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.db.RgbPreset
import com.example.ui.components.ColorWheel
import com.example.ui.components.SettingsTabContent
import com.example.ui.components.joyfulPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.example.ui.components.ExpressiveFabMenu
import com.example.ui.components.ExpressivePowerButtonGroup
import com.example.ui.components.ExpressiveSlider
import com.example.ui.components.HapticBouncySlider
import com.example.ui.theme.MyApplicationTheme
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.navigationBarsPadding


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val appContainer = (context.applicationContext as RgbControllerApplication).container
    val factory = RgbControllerViewModelFactory(
        context.applicationContext,
        appContainer.appPreferencesRepository,
        appContainer.rgbDatabaseRepository,
        appContainer.connectionManager,
        appContainer.bleScanTransport,
        appContainer.bleGattTransport,
        appContainer.ambianceCommandSink
    )
    val viewModel: RgbControllerViewModel = viewModel(factory = factory)
    val aiSceneGeneratorViewModel: com.example.ui.components.AiSceneGeneratorViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val presets by viewModel.savedPresets.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val customModes by viewModel.customModes.collectAsState()
    val connectionStates by appContainer.connectionManager.connectionStates.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current

    // Hoisted Media Projection Launcher for Ambiance Screen Capture
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.setActiveFeatureName("Ambiance")
            AmbianceCaptureService.start(context, result.resultCode, result.data!!)
        }
    }

    val disconnectAllInteractionSource = remember { MutableInteractionSource() }
    val savePresetInteractionSource = remember { MutableInteractionSource() }
    val saveAliasInteractionSource = remember { MutableInteractionSource() }
    val saveCalibrationInteractionSource = remember { MutableInteractionSource() }

    // Required permissions depending on Android SDK version
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    // Permissions check state
    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // Register ActivityResultLauncher for multiple permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            viewModel.setDemoMode(false) // toggle demo mode off if we now have physical permissions!
            viewModel.startScanning()
        }
    }

    // Function to check permissions status
    fun checkPermissions() {
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) {
        checkPermissions()
        // Auto-scan on load if we have permissions and real mode is active
        if (permissionsGranted && !uiState.coreControl.isDemoMode) {
            viewModel.startScanning()
        }
    }

    // Dialog state for Save Preset
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }

    // Dialog state for Save Device Alias
    var deviceToAliasAddress by remember { mutableStateOf<String?>(null) }
    var deviceAliasInput by remember { mutableStateOf("") }

    var selectedTab by remember { mutableStateOf(0) }
    var showAiSceneGenerator by remember { mutableStateOf(false) }
    var editingSmartSceneId by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = showAiSceneGenerator) {
        showAiSceneGenerator = false
        editingSmartSceneId = null
    }

    var liveFps by remember { mutableStateOf(32) }
    if (uiState.coreControl.showFpsTracker && uiState.coreControl.isPowerOn) {
        LaunchedEffect(uiState.coreControl.activeFeatureName, telemetry.deviceAchievedFps) {
            val connectedDevices = uiState.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }
            val achievedFps = if (connectedDevices.isNotEmpty()) {
                telemetry.deviceAchievedFps.values.maxOrNull() ?: 0
            } else 0

            if (achievedFps > 0) {
                liveFps = achievedFps
            } else {
                val baseFps = when (uiState.coreControl.activeFeatureName) {
                    "Ambiance" -> uiState.ambianceSettings.ambianceUpdateRateCapFps
                    "Music" -> 45
                    "Modes" -> 35
                    "Colour", "CCT" -> 32
                    else -> 32
                }
                while (true) {
                    val fluctuation = (-1..1).random()
                    liveFps = (baseFps + fluctuation).coerceAtLeast(1)
                    kotlinx.coroutines.delay(1000L)
                }
            }
        }
    }

    // Settings tab local states
    var settingsModeSelectIndex by remember { mutableStateOf(1) }
    var settingsModeCustomHexInput by remember { mutableStateOf("") }
    var settingsAudioSelectIndex by remember { mutableStateOf(1) }
    var settingsAudioCustomHexInput by remember { mutableStateOf("") }

    // Ambient background pulse for active light preview
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = { it }),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

    val topBarColor by animateColorAsState(
        targetValue = if (uiState.coreControl.isPowerOn) MaterialTheme.colorScheme.primaryContainer else surfaceContainer,
        animationSpec = tween(durationMillis = 300),
        label = "topBarColor"
    )

    val glowColor by animateColorAsState(
        targetValue = if (uiState.coreControl.isPowerOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 300),
        label = "glowColor"
    )

    val topBarTitleColor by animateColorAsState(
        targetValue = if (uiState.coreControl.isPowerOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 300),
        label = "topBarTitleColor"
    )

    val topBarSubtitleColor by animateColorAsState(
        targetValue = if (uiState.coreControl.isPowerOn) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "topBarSubtitleColor"
    )

    val powerButtonBgColor by animateColorAsState(
        targetValue = if (uiState.coreControl.isPowerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "powerButtonBgColor"
    )

    val powerButtonIconTint by animateColorAsState(
        targetValue = if (uiState.coreControl.isPowerOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 300),
        label = "powerButtonIconTint"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showAiSceneGenerator) {
                        IconButton(onClick = { 
                            showAiSceneGenerator = false 
                            editingSmartSceneId = null
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = topBarTitleColor
                            )
                        }
                    }
                },
                title = {
                    Column(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .offset(y = (-6).dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Fuse",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp
                            ),
                            color = topBarTitleColor
                        )
                        val subtitleText = remember(uiState.coreControl.isPowerOn, uiState.coreControl.activeFeatureName, uiState.coreControl.showFpsTracker, liveFps) {
                            if (uiState.coreControl.isPowerOn) {
                                if (uiState.coreControl.showFpsTracker) {
                                    "Active • ${uiState.coreControl.activeFeatureName} • $liveFps Fps"
                                } else {
                                    "Active • ${uiState.coreControl.activeFeatureName}"
                                }
                            } else {
                                "Standby"
                            }
                        }
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = topBarSubtitleColor
                        )
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .offset(y = (-6).dp)
                    ) {
                        val powerInteractionSource = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { viewModel.setPower(!uiState.coreControl.isPowerOn) },
                            interactionSource = powerInteractionSource,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(powerButtonBgColor)
                                .joyfulPress(powerInteractionSource)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Toggle Power",
                                tint = powerButtonIconTint
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarColor,
                    titleContentColor = topBarTitleColor
                ),
                modifier = Modifier
                    .drawBehind {
                    drawLine(
                        color = glowColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            )
        },
        bottomBar = {
            ExpressiveNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = {
                    selectedTab = it
                    showAiSceneGenerator = false
                    editingSmartSceneId = null
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showAiSceneGenerator) {
                com.example.ui.components.AiSceneGeneratorScreen(
                    viewModel = aiSceneGeneratorViewModel,
                    onApplyScene = { params, sceneName, explanation ->
                        val editId = editingSmartSceneId
                        if (editId != null) {
                            viewModel.updateAiSceneSequence(editId, params, sceneName)
                            editingSmartSceneId = null
                        } else {
                            val firstScene = viewModel.saveAiSceneSequence(params, sceneName, explanation)
                            if (firstScene != null) {
                                viewModel.applyScene(firstScene)
                            }
                        }
                        showAiSceneGenerator = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                )
            } else if (selectedTab == 1) {
                ModesScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
            } else if (selectedTab == 4) {
                MusicScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
            } else if (selectedTab == 5) {
                com.example.ui.components.AmbianceScreen(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    onStartCapture = {
                        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                    }
                )
            } else if (selectedTab == 0) {
                HomeScreen(
                    viewModel = viewModel,
                    aiSceneGeneratorViewModel = aiSceneGeneratorViewModel,
                    permissionsGranted = permissionsGranted,
                    requiredPermissions = requiredPermissions,
                    permissionLauncher = permissionLauncher,
                    onStartAmbianceCapture = {
                        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
                    },
                    onCreateSmartScene = {
                        showAiSceneGenerator = true
                    },
                    onEditScene = { scene ->
                        editingSmartSceneId = scene.id
                        aiSceneGeneratorViewModel.loadExistingScene(scene)
                        showAiSceneGenerator = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
            } else if (selectedTab == 2) {
                DevicesScreen(
                    viewModel = viewModel,
                    onScanClick = {
                        checkPermissions()
                        if (!permissionsGranted && !uiState.coreControl.isDemoMode) {
                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                        } else {
                            viewModel.startScanning()
                        }
                    },
                    onEditAlias = { address, currentName ->
                        deviceToAliasAddress = address
                        deviceAliasInput = currentName
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                )
            } else if (selectedTab == 3) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SettingsTabContent(uiState, telemetry, viewModel)
                }
            }

            // Expressive connection loading overlay using soft breathing/pulsing circle animations (Material You style)
            val anyDeviceConnected = uiState.connectivity.connectionState == BleConnectionState.CONNECTED ||
                uiState.connectivity.deviceConnectionStates.values.any { it == BleConnectionState.CONNECTED }

            val isConnecting = !anyDeviceConnected && savedDevices.any { saved ->
                val connState = connectionStates[saved.macAddress]
                val isManuallyDisconnected = connState is com.example.domain.ConnectionState.Disconnected && connState.isManual
                saved.isAutoConnectEnabled && 
                !isManuallyDisconnected &&
                uiState.connectivity.deviceConnectionStates[saved.macAddress] != BleConnectionState.CONNECTED
            }

            AnimatedVisibility(
                visible = isConnecting,
                enter = fadeIn(animationSpec = tween(400)),
                exit = fadeOut(animationSpec = tween(150))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)) // Translucent overlay to darken background content
                        .clickable(enabled = false) {} // Prevent click-through
                        .testTag("connection_loading_overlay"),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveConnectionLoadingIndicator()
                }
            }

    // --- Save Preset Dialog ---
    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("Save Current Color Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Name your custom color settings to save them locally in the database.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("preset_name_input_field")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            viewModel.savePreset(presetNameInput.trim())
                            showSavePresetDialog = false
                        }
                    },
                    interactionSource = savePresetInteractionSource,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("save_preset_confirm_btn")
                        .joyfulPress(savePresetInteractionSource),
                    shape = CircleShape
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Edit Device Alias Dialog ---
    if (deviceToAliasAddress != null) {
        AlertDialog(
            onDismissRequest = { deviceToAliasAddress = null },
            title = { Text("Customize Device Alias") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Assign a nickname to MAC address ${deviceToAliasAddress} for easy identification.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = deviceAliasInput,
                        onValueChange = { deviceAliasInput = it },
                        label = { Text("Device Nickname") },
                        placeholder = { Text("e.g. My Living Room Light") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("device_alias_input_field")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val address = deviceToAliasAddress
                        if (address != null) {
                            if (deviceAliasInput.isNotBlank()) {
                                viewModel.saveDeviceAlias(address, deviceAliasInput.trim())
                            } else {
                                viewModel.deleteDeviceAlias(address)
                            }
                            deviceToAliasAddress = null
                        }
                    },
                    interactionSource = saveAliasInteractionSource,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("save_device_alias_confirm_btn")
                        .joyfulPress(saveAliasInteractionSource),
                    shape = CircleShape
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val address = deviceToAliasAddress
                        if (address != null) {
                            viewModel.deleteDeviceAlias(address)
                        }
                        deviceToAliasAddress = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove Nickname")
                }
            }
        )
    }

    // --- Audio Delay Calibration Dialog ---
    if (uiState.calibrationFlow.isCalibrationModeActive) {
        AlertDialog(
            onDismissRequest = { viewModel.stopCalibrationMode() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Calibration",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Audio Delay Calibration")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "A continuous metronome click is playing on your audio output. Adjust the slider until the light strip flashes perfectly in sync with the click sound.\n\nNote: This calibrates the total visual delay, compensating for BOTH your device's Bluetooth output latency and the internal beat-detection processing latency.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Active Audio Device Info
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = "Bluetooth Device",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = uiState.audioSettings.detectedAudioDeviceName ?: "System Default Audio Output",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (uiState.audioSettings.activeAudioDeviceIdentifier != null) "Hardware Profile Connected" else "Internal Output Target",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Slider
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Delay Offset",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${uiState.calibrationFlow.calibrationDelayOffsetMs} ms",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                        HapticBouncySlider(
                            value = uiState.calibrationFlow.calibrationDelayOffsetMs.toFloat(),
                            onValueChange = { viewModel.updateCalibrationSliderValue(it.toInt()) },
                            valueRange = 0f..500f,
                            steps = 99, // 5ms step size (500 / 5 = 100 values)
                            totalSteps = 100,
                            modifier = Modifier.fillMaxWidth().testTag("calibration_slider")
                        )
                    }

                    // Small instruction banner
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Nudge slider back and forth to find the match",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveCalibrationAndExit() },
                    interactionSource = saveCalibrationInteractionSource,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("save_calibration_button")
                        .joyfulPress(saveCalibrationInteractionSource),
                    shape = CircleShape
                ) {
                    Text("Save Calibration")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.stopCalibrationMode() },
                    modifier = Modifier.testTag("cancel_calibration_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
}

}

@Composable
fun ExpressiveNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val hapticType = remember {
        runCatching {
            val companion = HapticFeedbackType.Companion
            val method = companion::class.java.getMethod("getSegmentTick")
            method.invoke(companion) as HapticFeedbackType
        }.getOrElse {
            runCatching {
                val companion = HapticFeedbackType.Companion
                val method = companion::class.java.getMethod("getSegmentFrequentTick")
                method.invoke(companion) as HapticFeedbackType
            }.getOrElse {
                HapticFeedbackType.TextHandleMove
            }
        }
    }

    LaunchedEffect(selectedTab) {
        hapticFeedback.performHapticFeedback(hapticType)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Subtle top border line for elegant boundary separation
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding() // Respect safe areas
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), // Shorter vertical padding for centered icons
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                NavigationItemData(0, Icons.Default.Home, "Home", "nav_controller"),
                NavigationItemData(1, Icons.Default.Palette, "Modes", "nav_modes"),
                NavigationItemData(4, Icons.Default.MusicNote, "Music", "nav_music"),
                NavigationItemData(5, Icons.Default.FilterAlt, "Ambiance", "nav_ambiance"),
                NavigationItemData(2, Icons.Default.Bluetooth, "Devices", "nav_devices"),
                NavigationItemData(3, Icons.Default.Settings, "Settings", "nav_settings")
            )

            tabs.forEach { tab ->
                val selected = selectedTab == tab.id

                val indicatorScale by animateFloatAsState(
                    targetValue = if (selected) 1.0f else 0.85f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "indicatorScale"
                )

                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "iconScale"
                )

                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    },
                    animationSpec = tween(durationMillis = 100), // snappier: 33% reduction from 150ms to 100ms
                    label = "contentColor"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tab.testTag)
                        .clickable(
                            onClick = { onTabSelected(tab.id) },
                            indication = null, // Custom animated container serves as visual feedback
                            interactionSource = remember { MutableInteractionSource() }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = if (selected) indicatorScale else 1.0f
                                scaleY = if (selected) indicatorScale else 1.0f
                            }
                            .background(
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            )
                            .size(width = 56.dp, height = 40.dp), // Fixed pill size
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier
                                .size(26.dp) // Bigger icon
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = contentColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

data class NavigationItemData(
    val id: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val testTag: String
)

@Composable
fun ExpressiveConnectionLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_and_breathe")
    
    // Soft pulsing breathing scale loop
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheScale"
    )
    
    // Soft pulsing breathing alpha loop
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheAlpha"
    )

    // Secondary offset/staggered pulse for complex multi-layered breathing depth (asymmetry)
    val secondScale by infiniteTransition.animateFloat(
        initialValue = 1.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "secondScale"
    )

    val secondAlpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "secondAlpha"
    )

    // Constant rotation for inner design accents
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        // 1. Layer 1: Outermost breathing pulse circle
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = breatheScale
                    scaleY = breatheScale
                }
                .size(160.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = breatheAlpha),
                    shape = CircleShape
                )
        )

        // 2. Layer 2: Secondary offset breathing circle for visual depth
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = secondScale
                    scaleY = secondScale
                }
                .size(140.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = secondAlpha),
                    shape = CircleShape
                )
        )

        // 3. Layer 3: Central card container containing the loading indicator
        Card(
            modifier = Modifier
                .size(130.dp)
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(28.dp)
                ),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large indeterminate circular progress indicator using Material 3 expressive tokens
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            rotationZ = rotation
                        },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Fusing...",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = "Connecting BLE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                )
            }
        }
    }
}
