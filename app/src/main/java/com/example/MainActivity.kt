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
import com.example.domain.model.AppScene
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
        appContainer.connectionManager
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

    val grantPermissionsInteractionSource = remember { MutableInteractionSource() }
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

    // Parse active color to display
    val activeComposeColor = Color(uiState.coreControl.red, uiState.coreControl.green, uiState.coreControl.blue)

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
            } else {
                val scenes by viewModel.scenes.collectAsState()
                var showSceneChoiceDialog by remember { mutableStateOf(false) }
                var showCreateSceneDialogFromHome by remember { mutableStateOf(false) }
                var selectedSceneId by remember { mutableStateOf<String?>(null) }
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

                if (showSceneChoiceDialog) {
                    AlertDialog(
                        onDismissRequest = { showSceneChoiceDialog = false },
                        title = { Text("Create Scene", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            ) {
                                // Option 1: Create Scene (Manual)
                                val manualInteractionSource = remember { MutableInteractionSource() }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = manualInteractionSource,
                                            indication = androidx.compose.foundation.LocalIndication.current
                                        ) {
                                            showSceneChoiceDialog = false
                                            showCreateSceneDialogFromHome = true
                                        }
                                        .testTag("choice_create_scene_manual"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Tune,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Create Scene",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Manually configure colors and effects",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                // Option 2: Smart Scene (AI)
                                val smartInteractionSource = remember { MutableInteractionSource() }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = smartInteractionSource,
                                            indication = androidx.compose.foundation.LocalIndication.current
                                        ) {
                                            showSceneChoiceDialog = false
                                            aiSceneGeneratorViewModel.resetToIdle()
                                            showAiSceneGenerator = true
                                        }
                                        .testTag("choice_create_scene_smart"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Smart Scene",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "Describe a scene and let on-device AI build it",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        confirmButton = {},
                        dismissButton = {}
                    )
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
                    com.example.ui.components.SceneCreationDialog(
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
                    com.example.ui.components.SceneCreationDialog(
                        devices = savedDevices,
                        currentGlobalMode = currentGlobalMode,
                        availableScenes = scenes,
                        sceneToEdit = sceneToEdit,
                        onDismissRequest = { sceneToEdit = null },
                        onEditAnimation = {
                            val activeScene = sceneToEdit
                            if (activeScene != null) {
                                editingSmartSceneId = activeScene.id
                                aiSceneGeneratorViewModel.loadExistingScene(activeScene)
                                showAiSceneGenerator = true
                                sceneToEdit = null
                            }
                        },
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

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (selectedTab == 0) {

                    // --- Permissions Alert Card (when in Real Mode & no permissions) ---
                    if (!permissionsGranted && !uiState.coreControl.isDemoMode) {
                        item {
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
                                    text = "To scan and connect to physical DuoCo RGB light strips, this app requires local Bluetooth permissions.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                    )
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                                        },
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
                                        Text("Grant Permissions")
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Connected Device Tiles ---
                val connectedAddresses = uiState.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }.keys.toList()
                if (connectedAddresses.isEmpty()) {
                    item {
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
                    item {
                        if (connectedAddresses.size <= 2) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                connectedAddresses.forEach { address ->
                                    DeviceTile(
                                        address = address,
                                        viewModel = viewModel,
                                        uiState = uiState,
                                        savedDevices = savedDevices,
                                        activeComposeColor = activeComposeColor,
                                        modifier = Modifier.weight(1f)
                                    )
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
                    // CCT Warmth Slider Card (in place of power status card)
                    if (uiState.coreControl.isPowerOn) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
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
                                        val k = com.example.ui.components.ColorUtils.warmthToKelvin(pct)
                                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(k)
                                        Color(rgb[0], rgb[1], rgb[2])
                                    }
                                    Brush.horizontalGradient(colors)
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

                    }
                    // Brightness Slider Card (below power card)
                    if (uiState.coreControl.isPowerOn) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                            text = "Dimming Control",
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
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                    }
                } // End of connectionState == CONNECTED

                // --- Grid-based Quick Access Scenes ---
                val gridItems = scenes + listOf(null)
                val chunked = gridItems.chunked(2)
                chunked.forEach { rowItems ->
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                                        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                                        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
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
                                                showSceneChoiceDialog = true
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

                } // End of selectedTab == 0

                if (selectedTab == 2) {
                    // --- Unified Device Center Dashboard ---
                    item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (uiState.connectivity.isScanning) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Scanning...",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        checkPermissions()
                                        if (!permissionsGranted && !uiState.coreControl.isDemoMode) {
                                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                                        } else {
                                            viewModel.startScanning()
                                        }
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                ) {
                                    Text("Scan for Devices")
                                }
                            }
                        }

                        // Section 1: Saved Devices
                        Text(
                            text = "Saved Devices (${savedDevices.size})",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        )

                        if (savedDevices.isEmpty() && uiState.coreControl.isDbLoaded) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bluetooth,
                                        contentDescription = "Bluetooth Logo",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = "No saved devices in your center yet. Connect to a device below to register it.",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    )
                                }
                            }
                        } else {
                            savedDevices.forEach { saved ->
                                val connState = uiState.connectivity.deviceConnectionStates[saved.macAddress] ?: BleConnectionState.DISCONNECTED
                                val isConnected = connState == BleConnectionState.CONNECTED
                                val isConnecting = connState == BleConnectionState.CONNECTING

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("saved_device_card_${saved.macAddress}"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isConnected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Row 1: Header (Name, Mac, status and delete)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                                contentDescription = "Bluetooth Device",
                                                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = saved.customName,
                                                    style = MaterialTheme.typography.bodyLarge.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = saved.macAddress,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                            }

                                            // Connection Status Badge
                                            Surface(
                                                color = when (connState) {
                                                    BleConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                                    BleConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                                    BleConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.error
                                                    BleConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                                },
                                                contentColor = when (connState) {
                                                    BleConnectionState.CONNECTED -> MaterialTheme.colorScheme.onPrimary
                                                    BleConnectionState.CONNECTING -> MaterialTheme.colorScheme.onTertiary
                                                    BleConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.onError
                                                    BleConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            ) {
                                                Text(
                                                    text = connState.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }

                                            // Edit Name
                                            IconButton(
                                                onClick = {
                                                    deviceToAliasAddress = saved.macAddress
                                                    deviceAliasInput = saved.customName
                                                },
                                                modifier = Modifier.size(32.dp).testTag("edit_alias_button_${saved.macAddress}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "Edit alias",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            // Delete Device
                                            IconButton(
                                                onClick = { viewModel.deleteSavedDevice(saved.macAddress) },
                                                modifier = Modifier.size(32.dp).testTag("delete_saved_${saved.macAddress}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete saved device",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }

                                        HorizontalDivider(color = if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant)

                                        // Row 2: Switches and Control
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Toggles
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Switch(
                                                        checked = saved.isAutoConnectEnabled,
                                                        onCheckedChange = { viewModel.toggleAutoConnect(saved.macAddress, saved.customName, it) },
                                                        modifier = Modifier.testTag("auto_connect_switch_${saved.macAddress}")
                                                    )
                                                    Text(
                                                        text = "Auto-Connect",
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Switch(
                                                        checked = saved.isActiveControlEnabled,
                                                        onCheckedChange = { viewModel.toggleActiveControl(saved.macAddress, saved.customName, it) },
                                                        modifier = Modifier.testTag("active_control_switch_${saved.macAddress}")
                                                    )
                                                    Text(
                                                        text = "Active Control",
                                                        style = MaterialTheme.typography.labelMedium.copy(
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                }
                                            }

                                            // Connect / Disconnect button
                                            val connectInteractionSource = remember(saved.macAddress) { MutableInteractionSource() }
                                            val isDisconnecting = connState == BleConnectionState.DISCONNECTING
                                            if (isConnected || isDisconnecting) {
                                                Button(
                                                    onClick = { viewModel.disconnectDevice(saved.macAddress) },
                                                    enabled = !isDisconnecting,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.primary,
                                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                                    ),
                                                    shape = CircleShape,
                                                    interactionSource = connectInteractionSource,
                                                    modifier = Modifier
                                                        .height(44.dp)
                                                        .testTag("disconnect_saved_${saved.macAddress}")
                                                        .joyfulPress(connectInteractionSource, enabled = !isDisconnecting)
                                                ) {
                                                    Text(if (isDisconnecting) "Disconnecting..." else "Disconnect")
                                                }
                                            } else {
                                                Button(
                                                    onClick = { viewModel.connectDevice(saved.macAddress) },
                                                    enabled = !isConnecting,
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.primary,
                                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                                    ),
                                                    shape = CircleShape,
                                                    interactionSource = connectInteractionSource,
                                                    modifier = Modifier
                                                        .height(44.dp)
                                                        .testTag("connect_saved_${saved.macAddress}")
                                                        .joyfulPress(connectInteractionSource, enabled = !isConnecting)
                                                ) {
                                                    Text(if (isConnecting) "Connecting..." else "Connect")
                                                }
                                            }
                                        }


                                    }
                                }
                            }
                        }

                        // Section 2: Scanned/Discovered Devices (excluding saved devices)
                        Text(
                            text = "Discovered Devices",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        )

                        val unsavedScannedDevices = uiState.connectivity.scannedDevices.filter { scanned ->
                            savedDevices.none { it.macAddress == scanned.address }
                        }

                        if (unsavedScannedDevices.isEmpty() && uiState.coreControl.isDbLoaded) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "No new devices found in range. Click Scan to discover.",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    )
                                }
                            }
                        } else {
                            unsavedScannedDevices.forEach { device ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.connectDevice(device.address) }
                                        .testTag("scanned_device_card_${device.address}"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Bluetooth,
                                            contentDescription = "Discovered BLE Device",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = device.alias ?: device.name ?: "Unknown Device",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (device.isDuoCoSuspected) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = "DuoCo",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                color = MaterialTheme.colorScheme.secondary,
                                                                fontSize = 8.sp,
                                                                fontWeight = FontWeight.Bold
                                                            ),
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        val connectDiscoveredInteractionSource = remember(device.address) { MutableInteractionSource() }
                                        val deviceConnState = uiState.connectivity.deviceConnectionStates[device.address] ?: BleConnectionState.DISCONNECTED
                                        val isDeviceConnecting = deviceConnState == BleConnectionState.CONNECTING
                                        Button(
                                            onClick = { viewModel.connectDevice(device.address) },
                                            enabled = !isDeviceConnecting,
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                                contentColor = MaterialTheme.colorScheme.onSecondary
                                            ),
                                            interactionSource = connectDiscoveredInteractionSource,
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("connect_discovered_${device.address}")
                                                .joyfulPress(connectDiscoveredInteractionSource, enabled = !isDeviceConnecting)
                                        ) {
                                            Text(if (isDeviceConnecting) "Connecting..." else "Connect", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                }

                if (selectedTab == 3) {
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

@Composable
fun DeviceTile(
    address: String,
    viewModel: com.example.RgbControllerViewModel,
    uiState: com.example.RgbUiState,
    savedDevices: List<com.example.db.SavedDevice>,
    activeComposeColor: Color,
    modifier: Modifier = Modifier
) {
    val savedDev = savedDevices.find { it.macAddress == address }
    val scannedDev = uiState.connectivity.scannedDevices.find { it.address == address }
    val displayName = savedDev?.customName ?: scannedDev?.alias ?: scannedDev?.name ?: "DuoCo Light"
    val isActiveControlled = savedDev?.isActiveControlEnabled ?: true
    val hapticFeedback = LocalHapticFeedback.current
    val hapticType = remember {
        runCatching {
            val companion = HapticFeedbackType.Companion
            val method = companion::class.java.getMethod("getSegmentTick")
            method.invoke(companion) as HapticFeedbackType
        }.getOrElse { HapticFeedbackType.TextHandleMove }
    }

    val deviceState = uiState.connectivity.deviceStatesMap[address] ?: com.example.ActiveDeviceState(
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
