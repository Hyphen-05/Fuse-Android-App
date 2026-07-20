package com.example.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import com.example.RgbUiState
import com.example.TelemetryState
import com.example.RgbControllerViewModel
import com.example.BleConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun ExpandableCategoryCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "$title Icon",
                        tint = iconTint
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(
                        durationMillis = 250,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(
                    animationSpec = tween(
                        durationMillis = 250,
                        easing = FastOutSlowInEasing
                    )
                ),
                exit = shrinkVertically(
                    animationSpec = tween(
                        durationMillis = 250,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeOut(
                    animationSpec = tween(
                        durationMillis = 250,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    content()
                }
            }
        }
    }
}

fun LazyListScope.SettingsTabContent(state: RgbUiState, telemetry: TelemetryState, viewModel: RgbControllerViewModel) {
    if (state.calibrationFlow.showCalibrationPrompt && state.audioSettings.detectedAudioDeviceName != null) {
        item {
            val calibratePromptInteractionSource = remember { MutableInteractionSource() }
            Card(
                modifier = Modifier.fillMaxWidth().testTag("calibration_prompt_card"),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = "Alert")
                        Text(
                            text = "New Audio Device Detected",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Text(
                        text = "We noticed you're outputting audio to '${state.audioSettings.detectedAudioDeviceName}'. Bluetooth output latency, combined with internal beat-detection latency, causes the lights to flash out of sync. Calibrate to fix this.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = { viewModel.startCalibrationMode() },
                        interactionSource = calibratePromptInteractionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("start_calibration_btn")
                            .joyfulPress(calibratePromptInteractionSource),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Calibrate",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Calibrate Audio Delay (Recommended)")
                    }
                }
            }
        }
    }

    item {
        val context = LocalContext.current
        val responseSpeed = state.ambianceSettings.ambianceResponseSpeed
        val smoothnessMs = state.ambianceSettings.ambianceSmoothnessMs
        val saturationBoost = state.ambianceSettings.ambianceSaturationBoost
        val brightnessCompensation = state.ambianceSettings.ambianceBrightnessCompensation
        val updateRateCapFps = state.ambianceSettings.ambianceUpdateRateCapFps
        val sceneCutSensitivity = state.ambianceSettings.ambianceSceneCutSensitivity
        val noiseDeadband = state.ambianceSettings.ambianceNoiseDeadband

        var showSavePresetDialog by remember { mutableStateOf(false) }
        var presetNameToSave by remember { mutableStateOf("") }

        ExpandableCategoryCard(
            title = "Ambiance",
            icon = Icons.Default.FilterAlt,
            iconTint = MaterialTheme.colorScheme.primary,
            initiallyExpanded = false
        ) {
// 1. Response Speed
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Response Speed",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format("%.2f", responseSpeed),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Controls how fast each zone's color reacts. High = snappy / raw, Low = smooth / averaged.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = responseSpeed,
                        onValueChange = { newValue ->
                            viewModel.setAmbianceResponseSpeed(newValue)
                        },
                        valueRange = 0.0f..1.0f,
                        totalSteps = 100,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_response_speed_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 2. Smoothness (Interpolation)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Smoothness (Interpolation)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${smoothnessMs}ms",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Sets a small final smoothing touch-up on top of the main Response Speed timing. Keep this low (under 30ms) for responsiveness.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = smoothnessMs.toFloat(),
                        onValueChange = { newValue ->
                            viewModel.setAmbianceSmoothnessMs(newValue.toInt())
                        },
                        valueRange = 0f..60f,
                        totalSteps = 60,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_smoothness_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 3. Saturation Boost
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Saturation Boost",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format("%.2f", saturationBoost),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Boosts color vividness to combat natural color-averaging washout. Default: 1.4.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = saturationBoost,
                        onValueChange = { newValue ->
                            viewModel.setAmbianceSaturationBoost(newValue)
                        },
                        valueRange = 0.0f..3.0f,
                        totalSteps = 300,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_saturation_boost_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 4. Brightness Compensation
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Brightness Compensation",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format("%.2f", brightnessCompensation),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Compensates for dim video input. 1.0 is neutral, 2.0 is double brightness.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = brightnessCompensation,
                        onValueChange = { newValue ->
                            viewModel.setAmbianceBrightnessCompensation(newValue)
                        },
                        valueRange = 0.0f..2.0f,
                        totalSteps = 200,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_brightness_compensation_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 5. Update Rate Cap
                val connectedDevices = state.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }
                val pacingPrefs = remember { context.getSharedPreferences("ble_pacing_prefs", Context.MODE_PRIVATE) }
                val slowestDevicePacing = if (connectedDevices.isEmpty()) {
                    0
                } else {
                    connectedDevices.keys.mapNotNull { address ->
                        state.connectivity.devicePacingMs[address] ?: pacingPrefs.getInt(address, 100)
                    }.maxOrNull() ?: 0
                }
                val maxFps = if (slowestDevicePacing <= 0) 20 else (1000 / slowestDevicePacing).coerceIn(1, 60)
                val totalStepsVal = (maxFps - 1).coerceAtLeast(1)

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Update Rate Cap",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${updateRateCapFps.coerceIn(1, maxFps)} fps",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val capExplanation = if (slowestDevicePacing > 0) {
                        " (Max rate capped at ${maxFps} fps based on slowest connected device's pacing of ${slowestDevicePacing}ms)"
                    } else {
                        " (No devices connected, fallback max is 20 fps)"
                    }
                    Text(
                        text = "Maximum frame processing rate. Higher rate is more responsive but uses more CPU.$capExplanation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = updateRateCapFps.coerceIn(1, maxFps).toFloat(),
                        onValueChange = { newValue ->
                            viewModel.setAmbianceUpdateRateCapFps(newValue.toInt().coerceIn(1, maxFps))
                        },
                        valueRange = 1f..maxFps.toFloat(),
                        totalSteps = totalStepsVal,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_update_rate_cap_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 6. Scene Cut Sensitivity
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Scene Cut Sensitivity",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format("%.0f", sceneCutSensitivity),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Threshold for instant cuts. Lower = more responsive/twitchy, Higher = only large changes snap instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = sceneCutSensitivity,
                        onValueChange = { newValue ->
                            viewModel.setAmbianceSceneCutSensitivity(newValue)
                        },
                        valueRange = 10f..150f,
                        totalSteps = 140,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_scene_cut_sensitivity_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // 7. Noise Deadband
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Noise Deadband",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = String.format("%.0f%%", noiseDeadband * 100f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Ignore minor screen changes below this threshold to reduce jitter. At 0%, all changes process; higher values filter out small changes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticBouncySlider(
                        value = noiseDeadband,
                        onValueChange = { newValue ->
                            viewModel.setAmbianceNoiseDeadband(newValue)
                        },
                        valueRange = 0.0f..0.5f,
                        totalSteps = 50,
                        modifier = Modifier.fillMaxWidth().testTag("ambiance_noise_deadband_slider")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                // Save Current Configuration as Preset Button (Pill style, height 52.dp, RoundedCornerShape(12.dp))
                val saveActionInteraction = remember { MutableInteractionSource() }

                Button(
                    onClick = {
                        presetNameToSave = ""
                        showSavePresetDialog = true
                    },
                    interactionSource = saveActionInteraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .joyfulPress(saveActionInteraction)
                        .testTag("save_current_preset_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = CircleShape
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = "Save Icon", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save Current Configuration as Preset",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                val resetAmbianceInteractionSource = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        viewModel.applyAmbiancePreset(
                            presetId = "Balanced",
                            responseSpeed = 0.5f,
                            smoothnessMs = 150,
                            saturationBoost = 1.4f,
                            brightnessCompensation = 1.0f,
                            sceneCutSensitivity = 110.0f,
                            noiseDeadband = 0.10f
                        )
                    },
                    interactionSource = resetAmbianceInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("reset_ambiance_btn")
                        .joyfulPress(resetAmbianceInteractionSource),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Ambiance Defaults")
                }

                // Dialog: Save Preset
                if (showSavePresetDialog) {
                    AlertDialog(
                        onDismissRequest = { showSavePresetDialog = false },
                        title = { Text("Save Ambiance Preset") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Enter a unique name for this preset configuration.", style = MaterialTheme.typography.bodyMedium)
                                OutlinedTextField(
                                    value = presetNameToSave,
                                    onValueChange = { presetNameToSave = it },
                                    label = { Text("Preset Name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("preset_name_input")
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val trimmedName = presetNameToSave.trim()
                                    if (trimmedName.isNotEmpty()) {
                                        val newPreset = AmbiancePreset(
                                            id = "custom_" + System.currentTimeMillis(),
                                            name = trimmedName,
                                            description = "Custom sliders config preset",
                                            isCustom = true,
                                            responseSpeed = responseSpeed,
                                            smoothnessMs = smoothnessMs,
                                            saturationBoost = saturationBoost,
                                            brightnessCompensation = brightnessCompensation,
                                            sceneCutSensitivity = sceneCutSensitivity,
                                            noiseDeadband = noiseDeadband
                                        )
                                        val customPresets = loadCustomPresetsFromPrefs(context)
                                        val updatedPresets = customPresets.filter { it.name.lowercase() != trimmedName.lowercase() } + newPreset
                                        saveCustomPresetsToPrefs(context, updatedPresets)

                                        viewModel.applyAmbiancePreset(
                                            presetId = trimmedName,
                                            responseSpeed = responseSpeed,
                                            smoothnessMs = smoothnessMs,
                                            saturationBoost = saturationBoost,
                                            brightnessCompensation = brightnessCompensation,
                                            sceneCutSensitivity = sceneCutSensitivity,
                                            noiseDeadband = noiseDeadband
                                        )

                                        showSavePresetDialog = false
                                        android.widget.Toast.makeText(context, "Preset '$trimmedName' saved!", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(context, "Please enter a valid name", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
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
            
        }
    }

    item {
        ExpandableCategoryCard(
            title = "Calibration",
            icon = Icons.Default.Build,
            iconTint = MaterialTheme.colorScheme.primary,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BLEPacingCard(state, telemetry, viewModel)
                
                val calibrateSliderInteractionSource = remember { MutableInteractionSource() }
                ExpandableCategoryCard(
                    title = "Latency & Transmission Speed",
                    icon = Icons.Default.Timer,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = false
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Bluetooth Audio Delay Compensation",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${state.audioSettings.bluetoothDelayMs} ms",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Text(
                            text = "Delays the light transmission to perfectly sync with lagging Bluetooth speakers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HapticBouncySlider(
                                value = state.audioSettings.bluetoothDelayMs.toFloat(),
                                onValueChange = { viewModel.setBluetoothDelayMs(it.toInt()) },
                                valueRange = 0f..1000f,
                                totalSteps = 1000,
                                modifier = Modifier.weight(1f).testTag("bluetooth_delay_slider")
                            )
                            OutlinedButton(
                                onClick = { viewModel.startCalibrationMode() },
                                interactionSource = calibrateSliderInteractionSource,
                                modifier = Modifier
                                        .height(44.dp)
                                        .testTag("open_calibration_btn")
                                        .joyfulPress(calibrateSliderInteractionSource),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                shape = CircleShape
                            ) {
                                Text("Calibrate")
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                val resetCalibrationInteractionSource = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = {
                        viewModel.resetCalibrationSettings()
                    },
                    interactionSource = resetCalibrationInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("reset_calibration_btn")
                        .joyfulPress(resetCalibrationInteractionSource),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset Calibration Defaults")
                }
            }
        }
    }

    item {
        ExpandableCategoryCard(
            title = "Audio",
            icon = Icons.Default.Audiotrack,
            iconTint = MaterialTheme.colorScheme.primary,
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExpandableCategoryCard(
                    title = "Temporal Filtering & Smoothing",
                    icon = Icons.Default.FilterAlt,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = false
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Filter Attack Coefficient",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.2f", state.audioSettings.audioSmoothingAttack),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "How fast the lights rise to a loud peak. Higher values mean quicker responsiveness.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.audioSmoothingAttack,
                            onValueChange = { viewModel.setAudioSmoothingAttack(it) },
                            valueRange = 0.05f..1.00f,
                            totalSteps = 95,
                            modifier = Modifier.fillMaxWidth().testTag("smoothing_attack_slider")
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Filter Decay Coefficient",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.3f", state.audioSettings.audioSmoothingDecay),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "How fast the lights fade after a peak. Lower values create a smoother, lingering fade. Higher values are snappier.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.audioSmoothingDecay,
                            onValueChange = { viewModel.setAudioSmoothingDecay(it) },
                            valueRange = 0.005f..0.500f,
                            totalSteps = 495,
                            modifier = Modifier.fillMaxWidth().testTag("smoothing_decay_slider")
                        )
                    }
                }

                ExpandableCategoryCard(
                    title = "Noise Gate & Beat Sensitivity",
                    icon = Icons.Default.Tune,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = false
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Noise Gate Threshold",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.3f", state.audioSettings.noiseGateThreshold),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            text = "Minimum volume required before lights activate. Increase this to ignore background hiss and room noise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.noiseGateThreshold,
                            onValueChange = { viewModel.setNoiseGateThreshold(it) },
                            valueRange = 0.001f..0.100f,
                            totalSteps = 99,
                            modifier = Modifier.fillMaxWidth().testTag("noise_gate_slider")
                        )
                    }
                }

                ExpandableCategoryCard(
                    title = "Equalizer & Frequency Band Gains",
                    icon = Icons.Default.GraphicEq,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = false
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Bass Gain",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.1fx", state.audioSettings.bassGain),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HapticBouncySlider(
                            value = state.audioSettings.bassGain,
                            onValueChange = { viewModel.setBassGain(it) },
                            valueRange = 0.5f..5.0f,
                            totalSteps = 45,
                            modifier = Modifier.fillMaxWidth().testTag("bass_gain_slider")
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Mid Gain",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.1fx", state.audioSettings.midGain),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HapticBouncySlider(
                            value = state.audioSettings.midGain,
                            onValueChange = { viewModel.setMidGain(it) },
                            valueRange = 0.5f..5.0f,
                            totalSteps = 45,
                            modifier = Modifier.fillMaxWidth().testTag("mid_gain_slider")
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "High Gain",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.1fx", state.audioSettings.highGain),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HapticBouncySlider(
                            value = state.audioSettings.highGain,
                            onValueChange = { viewModel.setHighGain(it) },
                            valueRange = 0.5f..5.0f,
                            totalSteps = 45,
                            modifier = Modifier.fillMaxWidth().testTag("high_gain_slider")
                        )
                    }
                }

                ExpandableCategoryCard(
                    title = "Flags & Options",
                    icon = Icons.Default.Settings,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = false
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Automatic Gain Control (AGC)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Dynamically adjusts sensitivity based on the overall volume of the music.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.audioSettings.isAutoGainEnabled,
                                onCheckedChange = { viewModel.setAutoGainEnabled(it) },
                                modifier = Modifier.testTag("auto_gain_switch")
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Palette Cycling Mode",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Constantly shifts base colors over time regardless of audio pitch.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.audioSettings.isPaletteCyclingEnabled,
                                onCheckedChange = { viewModel.setPaletteCyclingEnabled(it) },
                                modifier = Modifier.testTag("palette_cycling_switch")
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Logarithmic Pitch Scale",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Translates pitch to color using a human auditory logarithmic curve rather than linear frequencies.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.audioSettings.isLogarithmicScalingEnabled,
                                onCheckedChange = { viewModel.setLogarithmicScalingEnabled(it) },
                                modifier = Modifier.testTag("log_scale_switch")
                            )
                        }
                    }
                }

                ExpandableCategoryCard(
                    title = "Advanced Visualizer Dynamics",
                    icon = Icons.Default.Science,
                    iconTint = MaterialTheme.colorScheme.primary,
                    initiallyExpanded = false
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Audio Gamma Exponent",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.2f", state.audioSettings.audioGammaExponent),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Power factor to compress or expand perceived brightness ranges.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.audioGammaExponent,
                            onValueChange = { viewModel.setAudioGammaExponent(it) },
                            valueRange = 0.10f..2.50f,
                            totalSteps = 240,
                            modifier = Modifier.fillMaxWidth().testTag("gamma_exponent_slider")
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Minimum Base Brightness",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.2f", state.audioSettings.visualizerMinBrightness),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Ensures LEDs never turn completely off during silent moments.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.visualizerMinBrightness,
                            onValueChange = { viewModel.setVisualizerMinBrightness(it) },
                            valueRange = 0.00f..1.00f,
                            totalSteps = 100,
                            modifier = Modifier.fillMaxWidth().testTag("min_brightness_slider")
                        )
                    }
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Beat Flash Overlay Strength",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.2f", state.audioSettings.audioFlashStrength),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Strength of the bypass linear flash overlay triggered on beat registrations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.audioFlashStrength,
                            onValueChange = { viewModel.setAudioFlashStrength(it) },
                            valueRange = 0.00f..1.00f,
                            totalSteps = 100,
                            modifier = Modifier.fillMaxWidth().testTag("flash_strength_slider")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Color Transition Speed",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format("%.2fx", state.audioSettings.visualizerColorSpeed),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Multiplier for how fast the color changes in response to music.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticBouncySlider(
                            value = state.audioSettings.visualizerColorSpeed,
                            onValueChange = { viewModel.setVisualizerColorSpeed(it) },
                            valueRange = 0.10f..5.00f,
                            totalSteps = 490,
                            modifier = Modifier.fillMaxWidth().testTag("color_speed_slider")
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            val resetAudioInteractionSource = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = {
                    viewModel.resetAudioPipelineSettings()
                },
                interactionSource = resetAudioInteractionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("reset_audio_btn")
                    .joyfulPress(resetAudioInteractionSource),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Audio Defaults")
            }
        }
    }

    item {
        ExpandableCategoryCard(
            title = "Top Bar",
            icon = Icons.Default.Tune,
            iconTint = MaterialTheme.colorScheme.primary,
            initiallyExpanded = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("fps_tracker_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "FPS Live Tracker",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Show live frame processing rate in the status text of the top bar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.coreControl.showFpsTracker,
                    onCheckedChange = { viewModel.setShowFpsTracker(it) },
                    modifier = Modifier.testTag("fps_tracker_switch")
                )
            }
        }
    }

    item {
        val context = LocalContext.current
        var isExporting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        
        ExpandableCategoryCard(
            title = "Experimental",
            icon = Icons.Default.Science,
            iconTint = MaterialTheme.colorScheme.primary,
            initiallyExpanded = false
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Export Custom Modes",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Copies the complete custom modes database (including all of your modifications) to the clipboard as JSON data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                val exportModesInteractionSource = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        isExporting = true
                        scope.launch(Dispatchers.IO) {
                            try {
                                val modesList = viewModel.customModes.value
                                val jsonBuilder = StringBuilder()
                                jsonBuilder.append("[\n")
                                modesList.forEachIndexed { index, mode ->
                                    jsonBuilder.append("  {\n")
                                    jsonBuilder.append("    \"byteValue\": ${mode.byteValue},\n")
                                    jsonBuilder.append("    \"name\": \"${mode.name.replace("\"", "\\\"")}\",\n")
                                    jsonBuilder.append("    \"category\": \"${mode.category.replace("\"", "\\\"")}\",\n")
                                    jsonBuilder.append("    \"direction\": \"${mode.direction}\",\n")
                                    jsonBuilder.append("    \"colors\": \"${mode.colors}\"\n")
                                    if (index < modesList.size - 1) {
                                        jsonBuilder.append("  },\n")
                                    } else {
                                        jsonBuilder.append("  }\n")
                                    }
                                }
                                jsonBuilder.append("]")
                                val jsonText = jsonBuilder.toString()
                                
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clipData = android.content.ClipData.newPlainText("Exported Custom Modes", jsonText)
                                clipboardManager.setPrimaryClip(clipData)
                                
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(context, "Exported ${modesList.size} modes to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    interactionSource = exportModesInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("export_modes_btn")
                        .joyfulPress(exportModesInteractionSource),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    shape = CircleShape,
                    enabled = !isExporting
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Export to Clipboard",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isExporting) "Exporting..." else "Export Modes to Clipboard",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                AmbianceDiagnosticsCard()

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // LED Freeze Diagnostic Logging Section
                var isDiagnosticRecording by remember { mutableStateOf(com.example.DiagnosticLogger.isRecording()) }
                var exportedLogUri by remember { mutableStateOf<android.net.Uri?>(null) }
                val diagInteractionSource = remember { MutableInteractionSource() }
                val exportInteractionSource = remember { MutableInteractionSource() }

                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "LED Freeze Diagnostic Recorder",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Logs Bluetooth activity, write queues, watchdogs, smoother drift, and exception traces to debug intermittent freezing bugs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (isDiagnosticRecording) {
                                    com.example.DiagnosticLogger.stop()
                                    isDiagnosticRecording = false
                                    exportedLogUri = com.example.DiagnosticLogger.exportToFile(context)
                                    android.widget.Toast.makeText(context, "Recording stopped. Log exported!", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    com.example.DiagnosticLogger.start()
                                    isDiagnosticRecording = true
                                    exportedLogUri = null
                                    android.widget.Toast.makeText(context, "Recording started", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            interactionSource = diagInteractionSource,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("toggle_diagnostic_recording_btn")
                                .joyfulPress(diagInteractionSource),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDiagnosticRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (isDiagnosticRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = if (isDiagnosticRecording) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isDiagnosticRecording) "Stop" else "Start",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isDiagnosticRecording) "Stop Diagnostic Recording" else "Start Diagnostic Recording",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    if (!isDiagnosticRecording && exportedLogUri != null) {
                        Button(
                            onClick = {
                                try {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, exportedLogUri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Diagnostic Log"))
                                } catch (e: java.lang.Exception) {
                                    android.widget.Toast.makeText(context, "Failed to share: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            interactionSource = exportInteractionSource,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("export_diagnostic_log_btn")
                                .joyfulPress(exportInteractionSource),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Export Log & Share",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }

    item { Spacer(modifier = Modifier.height(16.dp)) }



    item { Spacer(modifier = Modifier.height(24.dp)) }
}
