package com.example

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.ui.components.ColorWheel
import com.example.ui.components.SettingsTabContent
import com.example.ui.components.joyfulPress
import com.example.ui.components.rememberExpressiveHapticType
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
        appContainer.ambianceCommandSink,
        appContainer.adbControlSink
    )
    val viewModel: RgbControllerViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
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

    // Launched from the "Enable" action on the Bluetooth-disabled snackbar.
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startScanning()
        }
    }

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
            // Deliberately does NOT force demo mode off any more: it's a persisted user choice
            // (Settings → Demo Mode) since F1, and granting permissions isn't a request to leave it.
            // startScanning() already routes to the simulated scan while demo mode is on.
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

    // Dialog state for Save Device Alias
    var deviceToAliasAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceAliasInput by rememberSaveable { mutableStateOf("") }

    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var showModeCaptureScreen by rememberSaveable { mutableStateOf(false) }
    // Unlocked by tapping the version footer at the bottom of Settings 7 times, same gesture as
    // stock Android's "tap Build number to enable Developer options" — familiar to anyone who'd
    // recognize the pattern, invisible to everyone else. Tapping the same sequence again re-hides
    // it. rememberSaveable so it survives rotation but intentionally not persisted to prefs —
    // this is a manual reveal, not a setting.
    var experimentalUnlocked by rememberSaveable { mutableStateOf(false) }

    // Real writes-per-second, sampled at 1Hz per device by DeviceWriteManager. Only currently
    // connected addresses count — deviceAchievedFps keeps its last entry for a device after it
    // drops, and a stale value from a disconnected device could otherwise win the max. Null means
    // nothing is connected, i.e. there is genuinely no rate to report; 0 is a real reading.
    val achievedFps: Int? = remember(uiState.connectivity.deviceConnectionStates, telemetry.deviceAchievedFps) {
        val connected = uiState.connectivity.deviceConnectionStates
            .filterValues { it == BleConnectionState.CONNECTED }
            .keys
        telemetry.deviceAchievedFps.filterKeys { it in connected }.values.maxOrNull()
    }
    val targetFps = when (uiState.coreControl.activeFeatureName) {
        "Ambiance" -> uiState.ambianceSettings.ambianceUpdateRateCapFps
        "Music" -> 45
        "Modes" -> 35
        else -> 32
    }
    val fpsLabel = achievedFps?.let { "$it Fps" } ?: "~$targetFps Fps target"

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

    // Scan/connect failures are written to coreControl.errorMessage by the ViewModel; this is the
    // only place that reads it. Without this the "Scan for Devices" button just silently no-ops
    // when Bluetooth is off. It used to be a bottom Snackbar; it now shares ConnectionStatusSurface
    // with the connection indicator, because the two could previously be on screen simultaneously
    // saying contradictory things.
    val offerEnableBluetooth = uiState.coreControl.errorMessage
        ?.contains("Bluetooth is disabled", ignoreCase = true) == true

    // Connection progress used to be a full-screen scrim with click-through disabled, shown
    // whenever a saved auto-connect device wasn't currently CONNECTED. That condition never cleared
    // on its own — one saved light that's powered off or out of range covered every tab forever,
    // including the Devices screen you'd need to reach to turn auto-connect off. This is a thin
    // non-blocking pill instead: Cancel marks the device manually disconnected, and it gives up
    // nagging after 15s (the retry ladder in handleConnectionStateChange carries on regardless).
    //
    // F2 (IMPROVEMENT_PLAN.md): this deliberately tracks *hunting*, not ConnectionState.Connecting.
    // Auto-connect only calls connectDevice() from inside the scan callback (handleScanResult /
    // addSimulatedDevice), so a strip that's powered off is never found, never reaches Connecting,
    // and the pill never appeared in the one case it exists for. Connecting, meanwhile, is set
    // instantly by a manual Connect tap — where DevicesScreen already shows "Connecting…" on the
    // button itself, making the pill redundant. Hunting covers both the searching and the
    // attempt-in-flight phases of an auto-connect, and is never entered by a manual tap on a device
    // that doesn't have auto-connect enabled.
    // Bluetooth being off makes "Looking for X…" a lie — nothing can be found, and the scan-error
    // snackbar is already saying the useful thing. Tracked live so the pill comes back by itself
    // once Bluetooth is re-enabled (including via the snackbar's own Enable action).
    var bluetoothEnabled by remember {
        mutableStateOf(
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled ?: false
        )
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    bluetoothEnabled = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val huntingDevice = if (!bluetoothEnabled && !uiState.coreControl.isDemoMode) {
        null
    } else {
        savedDevices.firstOrNull { device ->
            device.isAutoConnectEnabled && when (val s = connectionStates[device.macAddress]) {
                is com.example.domain.ConnectionState.Connected -> false
                is com.example.domain.ConnectionState.Disconnected -> !s.isManual
                else -> true // Connecting, or never attempted this session
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {},
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
                        val subtitleText = remember(uiState.coreControl.isPowerOn, uiState.coreControl.activeFeatureName, uiState.coreControl.showFpsTracker, fpsLabel) {
                            if (uiState.coreControl.isPowerOn) {
                                if (uiState.coreControl.showFpsTracker) {
                                    "Active • ${uiState.coreControl.activeFeatureName} • $fpsLabel"
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
                    showModeCaptureScreen = false
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showModeCaptureScreen) {
                com.example.ui.components.ModeCaptureScreen(
                    viewModel = viewModel,
                    onClose = { showModeCaptureScreen = false }
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
                    permissionsGranted = permissionsGranted,
                    requiredPermissions = requiredPermissions,
                    permissionLauncher = permissionLauncher,
                    onStartAmbianceCapture = {
                        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent())
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
                    SettingsTabContent(
                        state = uiState,
                        telemetry = telemetry,
                        viewModel = viewModel,
                        onOpenModeCapture = { showModeCaptureScreen = true },
                        experimentalUnlocked = experimentalUnlocked,
                        onToggleExperimentalUnlocked = { experimentalUnlocked = !experimentalUnlocked }
                    )
                }
            }

            // Last child of the content Box so it draws above every tab, but with no scrim and no
            // gesture modifier of its own — the tab underneath stays fully interactive, which is
            // the constraint that killed the original blocking overlay.
            com.example.ui.components.ConnectionStatusSurface(
                huntingDeviceName = huntingDevice?.customName,
                errorMessage = uiState.coreControl.errorMessage,
                offerEnableBluetooth = offerEnableBluetooth,
                onEnableBluetooth = {
                    runCatching {
                        enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                    viewModel.clearErrorMessage()
                },
                onDismissError = { viewModel.clearErrorMessage() }
            )

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
    val hapticType = rememberExpressiveHapticType()

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

