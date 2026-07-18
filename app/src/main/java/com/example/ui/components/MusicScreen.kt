package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.RgbControllerViewModel
import com.example.AudioCaptureService
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

data class ExternalMicPreset(
    val id: String,
    val name: String,
    val description: String,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(
    viewModel: RgbControllerViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var pendingMode by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingMode?.let { mode ->
                if (mode == "on_device" || mode == "phone_mic") {
                    AudioCaptureService.start(context, mode)
                }
                viewModel.startMusicSync(mode)
            }
        } else {
            viewModel.addLog("Record Audio Permission Denied.")
        }
        pendingMode = null
    }

    val requestRecordAudioPermissionAndStart: (String) -> Unit = { mode ->
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            if (mode == "on_device" || mode == "phone_mic") {
                AudioCaptureService.start(context, mode)
            }
            viewModel.startMusicSync(mode)
        } else {
            pendingMode = mode
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val presets = remember {
        listOf(
            ExternalMicPreset("energetic_1", "Energetic 1", "Fast pace dynamic flash", Color(0xFFE53935)),
            ExternalMicPreset("energetic_2", "Energetic 2", "Alternating pulse colors", Color(0xFFF4511E)),
            ExternalMicPreset("rhythm_1", "Rhythm 1", "Beat-sensitive slow fade", Color(0xFFFDD835)),
            ExternalMicPreset("rhythm_2", "Rhythm 2", "Multi-color jumping beat", Color(0xFF43A047)),
            ExternalMicPreset("spectrum_1", "Spectrum 1", "Frequency band strobe", Color(0xFF00ACC1)),
            ExternalMicPreset("spectrum_2", "Spectrum 2", "Continuous spectrum sweep", Color(0xFF1E88E5)),
            ExternalMicPreset("rolling_1", "Rolling 1", "Chasing waves flow effect", Color(0xFF8E24AA)),
            ExternalMicPreset("rolling_2", "Rolling 2", "Color wave bounce reverse", Color(0xFFD81B60))
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {


        // --- Real-time Waveform Visualizer ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .testTag("audio_visualizer_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                val amplitudes = uiState.musicAmplitudes
                var previousAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
                var currentAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }
                var lastUpdateTime by remember { mutableStateOf(System.currentTimeMillis()) }
                var currentInterval by remember { mutableStateOf(23.0f) }

                LaunchedEffect(amplitudes) {
                    val now = System.currentTimeMillis()
                    val interval = (now - lastUpdateTime).toFloat()
                    if (interval > 5.0f && interval < 200.0f) {
                        currentInterval = interval
                    }
                    val prev = currentAmplitudes
                    previousAmplitudes = if (prev.size == amplitudes.size) prev else amplitudes
                    currentAmplitudes = amplitudes
                    lastUpdateTime = now
                }

                var interpolatedAmplitudes by remember { mutableStateOf<List<Float>>(emptyList()) }

                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameMillis { _ ->
                            val elapsed = System.currentTimeMillis() - lastUpdateTime
                            val fraction = (elapsed.toFloat() / currentInterval).coerceIn(0f, 1f)
                            val prev = previousAmplitudes
                            val curr = currentAmplitudes
                            if (prev.size == curr.size && curr.isNotEmpty()) {
                                interpolatedAmplitudes = List(curr.size) { i ->
                                    val p = prev[i]
                                    val c = curr[i]
                                    p + (c - p) * fraction
                                }
                            } else {
                                interpolatedAmplitudes = curr
                            }
                        }
                    }
                }

                val barCount = 16
                val finalAmplitudes = if (interpolatedAmplitudes.isNotEmpty()) interpolatedAmplitudes else amplitudes
                val resampledAmplitudes = resampleAmplitudes(finalAmplitudes, barCount)

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        val shouldShowIdleWave = uiState.isVisualizerIdle || !uiState.isAudioSyncRunning
                        for (i in 0 until barCount) {
                            val ampValue = resampledAmplitudes[i]
                            VisualizerBar(
                                ampValue = ampValue,
                                isIdle = uiState.isVisualizerIdle,
                                visualizerHue = uiState.visualizerHue,
                                showIdleWave = shouldShowIdleWave,
                                barIndex = i
                            )
                        }
                    }
                }
            }
        }

        // --- Mic Sensitivity Controls ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Microphone Sensitivity",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = "${uiState.musicSensitivity}%",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                }
                
                HapticBouncySlider(
                    value = uiState.musicSensitivity.toFloat(),
                    onValueChange = { viewModel.setMusicSensitivity(it.toInt()) },
                    valueRange = 0f..100f,
                    totalSteps = 100,
                    modifier = Modifier.testTag("music_sensitivity_slider")
                )
            }
        }

        // --- Core Capture Modes (Phone Mic & On Device) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val isPhoneActive = uiState.musicMode == "phone_mic"
            val isOnDeviceActive = uiState.musicMode == "on_device"

            val phoneMicInteractionSource = remember { MutableInteractionSource() }
            val onDeviceInteractionSource = remember { MutableInteractionSource() }

            // --- Phone Mic Card ---
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = if (isPhoneActive) 2.dp else 1.dp,
                        color = if (isPhoneActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable(
                        interactionSource = phoneMicInteractionSource,
                        indication = androidx.compose.foundation.LocalIndication.current
                    ) {
                        if (isPhoneActive) viewModel.stopMusicSync() else requestRecordAudioPermissionAndStart("phone_mic")
                    }
                    .testTag("phone_mic_card")
                    .joyfulPress(phoneMicInteractionSource),
                colors = CardDefaults.cardColors(
                    containerColor = if (isPhoneActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isPhoneActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Phone Microphone",
                            tint = if (isPhoneActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Phone Mic",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isPhoneActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Analyzes sound through your phone's microphone",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = if (isPhoneActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- On Device Card ---
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = if (isOnDeviceActive) 2.dp else 1.dp,
                        color = if (isOnDeviceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .clickable(
                        interactionSource = onDeviceInteractionSource,
                        indication = androidx.compose.foundation.LocalIndication.current
                    ) {
                        if (isOnDeviceActive) viewModel.stopMusicSync() else requestRecordAudioPermissionAndStart("on_device")
                    }
                    .testTag("on_device_card")
                    .joyfulPress(onDeviceInteractionSource),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOnDeviceActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isOnDeviceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "On Device Audio",
                            tint = if (isOnDeviceActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "On Device",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (isOnDeviceActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Syncs with media playing on your phone's speaker",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = if (isOnDeviceActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Visualiser Modes ---
        Text(
            text = "Visualiser Presets",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )

        val visualPresets = remember {
            listOf(
                ExternalMicPreset("Default", "Balanced", "Standard rhythmic response", Color(0xFF9E9E9E)),
                ExternalMicPreset("Punchy", "Punchy", "Hits hard and drops fast", Color(0xFFE53935)),
                ExternalMicPreset("Smooth Flow", "Smooth Flow", "Gliding fluid transitions", Color(0xFF00ACC1)),
                ExternalMicPreset("Strobe Blast", "Strobe Blast", "Intense rapid flashing", Color(0xFF8E24AA)),
                ExternalMicPreset("Ambient Chill", "Ambient Chill", "Slow lingering fades", Color(0xFF3949AB)),
                ExternalMicPreset("Bass Thump", "Bass Thump", "Reacts heavily to bass", Color(0xFF43A047)),
                ExternalMicPreset("Laser Sharp", "Laser Sharp", "Instant reaction, no trails", Color(0xFFD81B60)),
                ExternalMicPreset("Beat Only", "Beat Only", "Pure beat strobe", Color(0xFFFF5722))
            )
        }

        val chunkedVisualPresets = remember(visualPresets) { visualPresets.chunked(2) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("visual_presets_grid"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            chunkedVisualPresets.forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowPresets.forEach { preset ->
                        val isActive = (uiState.musicMode == "phone_mic" || uiState.musicMode == "on_device") && uiState.visualizerPreset == preset.id
                        val presetInteractionSource = remember(preset.id) { MutableInteractionSource() }
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .border(
                                    width = if (isActive) 1.5.dp else 1.dp,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable(
                                    interactionSource = presetInteractionSource,
                                    indication = androidx.compose.foundation.LocalIndication.current
                                ) {
                                    viewModel.setVisualizerPreset(preset.id)
                                }
                                .testTag("visual_preset_${preset.id}")
                                .joyfulPress(presetInteractionSource),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(preset.color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = preset.name,
                                        tint = preset.color,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    if (rowPresets.size < 2) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // --- LED Modes ---
        Text(
            text = "LED Presets",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )

        val chunkedPresets = remember(presets) { presets.chunked(2) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("music_presets_grid"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            chunkedPresets.forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowPresets.forEach { preset ->
                        val isActive = uiState.musicMode == preset.id
                        val ledInteractionSource = remember(preset.id) { MutableInteractionSource() }
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .border(
                                    width = if (isActive) 1.5.dp else 1.dp,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable(
                                    interactionSource = ledInteractionSource,
                                    indication = androidx.compose.foundation.LocalIndication.current
                                ) {
                                    if (isActive) viewModel.stopMusicSync() else viewModel.startMusicSync(preset.id)
                                }
                                .testTag("music_preset_${preset.id}")
                                .joyfulPress(ledInteractionSource),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(preset.color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MusicVideo,
                                        contentDescription = preset.name,
                                        tint = preset.color,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    if (rowPresets.size < 2) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun resampleAmplitudes(source: List<Float>, targetSize: Int): List<Float> {
    if (source.isEmpty()) return List(targetSize) { 0.1f }
    if (source.size == targetSize) return source
    return List(targetSize) { i ->
        val pos = i * (source.size - 1).toFloat() / (targetSize - 1).coerceAtLeast(1)
        val lowerIndex = kotlin.math.floor(pos.toDouble()).toInt().coerceIn(0, source.size - 1)
        val upperIndex = kotlin.math.ceil(pos.toDouble()).toInt().coerceIn(0, source.size - 1)
        val fraction = pos - lowerIndex
        source[lowerIndex] + (source[upperIndex] - source[lowerIndex]) * fraction
    }
}

@Composable
fun RowScope.VisualizerBar(
    ampValue: Float,
    isIdle: Boolean,
    visualizerHue: Float,
    showIdleWave: Boolean,
    barIndex: Int,
    gravity: Float = 0.0015f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "idle_wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * kotlin.math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val targetHeight = if (showIdleWave) {
        0.3f + 0.35f * (kotlin.math.sin(phase + barIndex * (kotlin.math.PI / 8)).toFloat() * 0.5f + 0.5f)
    } else {
        (ampValue * 0.9f + 0.1f).coerceIn(0.1f, 1.0f)
    }

    val animatedHeightFraction by animateFloatAsState(
        targetValue = targetHeight,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "bar_height"
    )

    val baseColor = if (showIdleWave) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Dynamic active bar (bottom-anchored, thick, expressive custom corners)
        Box(
            modifier = Modifier
                .width(14.dp)
                .fillMaxHeight(animatedHeightFraction * 0.95f)
                .clip(RoundedCornerShape(50))
                .background(baseColor)
        )
    }
}

