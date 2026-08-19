package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.RgbUiState
import com.example.ambiance.AmbiancePreset
import com.example.ambiance.AmbiancePresetStore

/**
 * The seven ambiance tuning sliders, the reset button and the save-as-preset flow.
 *
 * Extracted from `SettingsTabContent` so the Ambiance tab can host the same controls next to the
 * presets they feed: the sliders lived only in Settings, two tabs away from the preset grid that
 * applies them, and the "save current configuration as a preset" button sat beside the sliders
 * rather than beside the preset list it adds to.
 */
@Composable
fun AmbianceFineTuneControls(
    state: RgbUiState,
    viewModel: RgbControllerViewModel,
    title: String = "Ambiance",
    initiallyExpanded: Boolean = false
) {
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
        title = title,
        icon = Icons.Default.FilterAlt,
        iconTint = MaterialTheme.colorScheme.primary,
        initiallyExpanded = initiallyExpanded
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
                    text = "The main colour smoothing time constant. Response Speed scales it (Fast ×0.2, Slow ×2.0). Higher means slower, calmer colour changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Range must cover what the presets actually set (150-350ms). It used to stop
                // at 60 with copy calling this a "small final touch-up" — that description
                // belongs to AmbianceOutputInterpolator's hardcoded per-tick ease, not to this
                // value, and the mismatch meant touching the slider after picking Chill (350ms)
                // silently clamped it to 60 and made the response ~6x snappier.
                HapticBouncySlider(
                    value = smoothnessMs.toFloat(),
                    onValueChange = { newValue ->
                        viewModel.setAmbianceSmoothnessMs(newValue.toInt())
                    },
                    valueRange = AMBIANCE_SMOOTHNESS_MIN_MS..AMBIANCE_SMOOTHNESS_MAX_MS,
                    totalSteps = 78,
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
            val slowestDevicePacing = if (connectedDevices.isEmpty()) {
                0
            } else {
                connectedDevices.keys.mapNotNull { address ->
                    state.connectivity.devicePacingMs[address] ?: viewModel.savedPacingMs(address)
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
            var confirmResetAmbiance by rememberSaveable { mutableStateOf(false) }
            if (confirmResetAmbiance) {
                ResetConfirmDialog(
                    title = "Reset Ambiance Defaults",
                    message = "This puts all seven ambiance settings back to the Balanced preset.",
                    confirmTestTag = "confirm_reset_ambiance",
                    onConfirm = {
                        viewModel.applyAmbiancePreset(
                            presetId = "Balanced",
                            responseSpeed = 0.5f,
                            smoothnessMs = 150,
                            saturationBoost = 1.4f,
                            brightnessCompensation = 1.0f,
                            sceneCutSensitivity = 110.0f,
                            noiseDeadband = 0.10f
                        )
                        confirmResetAmbiance = false
                    },
                    onDismiss = { confirmResetAmbiance = false }
                )
            }
            OutlinedButton(
                onClick = { confirmResetAmbiance = true },
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
                                val shadowsBuiltIn = BUILT_IN_AMBIANCE_PRESET_NAMES.any {
                                    it.equals(trimmedName, ignoreCase = true)
                                }
                                if (trimmedName.isNotEmpty() && !shadowsBuiltIn) {
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
                                    // Same-name replacement is the store's rule now, so the
                                    // Ambiance tab's list updates the moment this lands.
                                    AmbiancePresetStore.save(context, newPreset)

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
                                } else if (shadowsBuiltIn) {
                                    android.widget.Toast.makeText(context, "'$trimmedName' is a built-in preset — pick another name", android.widget.Toast.LENGTH_SHORT).show()
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
