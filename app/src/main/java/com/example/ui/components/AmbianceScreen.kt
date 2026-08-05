package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.ambiance.AmbianceCaptureService
import com.example.ambiance.AmbianceCaptureState
import com.example.ambiance.ZoneColor
import androidx.core.graphics.ColorUtils as AndroidColorUtils

/**
 * Top of the ambiance smoothness range, shared by the Settings slider and the idle-preview
 * normalisation so the two can't drift apart again. `smoothnessMs` is the primary EMA time
 * constant in [com.example.ambiance.AmbianceProcessor] (`effectiveTauMs = smoothnessMs *
 * tauMultiplier`), not a final touch-up, so the range has to cover what the presets actually use —
 * 150-350ms today, with headroom above.
 */
const val AMBIANCE_SMOOTHNESS_MAX_MS = 400f
const val AMBIANCE_SMOOTHNESS_MIN_MS = 10f

data class AmbiancePreset(
    val id: String,
    val name: String,
    val description: String,
    val isCustom: Boolean = false,
    val responseSpeed: Float,
    val smoothnessMs: Int,
    val saturationBoost: Float,
    val brightnessCompensation: Float,
    val sceneCutSensitivity: Float,
    val noiseDeadband: Float
)

fun derivePaletteFromParameters(
    smoothnessMs: Int,
    noiseDeadband: Float,
    responseSpeed: Float,
    saturationBoost: Float,
    brightnessCompensation: Float
): List<Color> {
    // 1. Normalize inputs to 0f..1f against their actual boundaries
    val sFrac = (smoothnessMs.toFloat() / AMBIANCE_SMOOTHNESS_MAX_MS).coerceIn(0f, 1f)
    val dbFrac = (noiseDeadband / 0.5f).coerceIn(0f, 1f)
    val speedFrac = responseSpeed.coerceIn(0f, 1f)

    // 2. Base Hue: slow/smooth (high sFrac) -> cooler (teal/blue); fast/energetic (low sFrac) -> warmer (magenta/orange/red)
    // Map sFrac to a beautiful continuous hue arc from warm rose/magenta (340°) to cool teal/blue (200°)
    val baseHue = (340f + sFrac * 220f) % 360f

    // 3. Saturation: higher deadband & speed -> more saturated; lower -> softer pastel
    val satFactor = (0.4f + dbFrac * 0.4f + speedFrac * 0.2f) * (saturationBoost / 1.4f)
    val baseSat = satFactor.coerceIn(0.15f, 1.0f)

    // 4. Contrast / Lightness spread: higher deadband -> more contrast; lower -> softer, closer stops
    val lightnessSpread = (0.04f + dbFrac * 0.20f + speedFrac * 0.06f).coerceIn(0.01f, 0.3f)
    val baseLightness = (0.50f * (brightnessCompensation / 1.0f)).coerceIn(0.25f, 0.75f)

    // 5. Hue step separation: higher deadband & speed -> larger hue differences for higher contrast; lower -> closer hues
    val hueStep = 15f + dbFrac * 40f + speedFrac * 15f

    // Generate 4 stops
    return (0..3).map { i ->
        val h = (baseHue + i * hueStep) % 360f
        
        // Vary lightness pattern to create depth/contrast
        val lOffset = when (i) {
            0 -> lightnessSpread
            1 -> -lightnessSpread
            2 -> 0.5f * lightnessSpread
            else -> -0.5f * lightnessSpread
        }
        val l = (baseLightness + lOffset).coerceIn(0.15f, 0.85f)
        
        // Convert HSL to Android Color Int, then to Compose Color
        val colorInt = AndroidColorUtils.HSLToColor(floatArrayOf(h, baseSat, l))
        Color(colorInt)
    }
}

// Averages the real 4x4 captured zone grid (AmbianceProcessor's raw per-cell samples) down to
// four quadrant colors (TL, TR, BL, BR) for the live preview gradient.
fun computeQuadrantColors(zones: List<ZoneColor>): List<Color> {
    if (zones.isEmpty()) return emptyList()
    val quadrants = listOf(
        zones.filter { it.row < 2 && it.col < 2 },
        zones.filter { it.row < 2 && it.col >= 2 },
        zones.filter { it.row >= 2 && it.col < 2 },
        zones.filter { it.row >= 2 && it.col >= 2 }
    )
    return quadrants.mapNotNull { group ->
        if (group.isEmpty()) return@mapNotNull null
        val r = group.sumOf { it.r } / group.size
        val g = group.sumOf { it.g } / group.size
        val b = group.sumOf { it.b } / group.size
        Color(red = r / 255f, green = g / 255f, blue = b / 255f)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbianceScreen(
    viewModel: RgbControllerViewModel,
    modifier: Modifier = Modifier,
    onStartCapture: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val presetPrefs = remember { context.getSharedPreferences("ambiance_presets_prefs", Context.MODE_PRIVATE) }

    // Observe active capture state and colors
    val isActive = AmbianceCaptureState.isActive.collectAsState()
    val zoneColors = AmbianceCaptureState.zoneColors.collectAsState()

    // Load custom presets
    var customPresets by remember { mutableStateOf(loadCustomPresetsFromPrefs(context)) }

    var showRenameDialog by remember { mutableStateOf<AmbiancePreset?>(null) }
    var newPresetName by remember { mutableStateOf("") }

    // Define 4 built-in presets
    // sceneCutSensitivity is a *threshold*: AmbianceProcessor does `isSceneCut = aggDelta >
    // sceneCutSensitivity`, so a higher number means fewer instant cuts. The values below were
    // originally ordered as if higher meant more sensitive, which put Gaming ("snappy") at the
    // least cut-prone end and Chill ("slow soothing") at the most. Reordered to match the
    // descriptions; the pref key stays scene_cut_sensitivity for compat.
    val builtInPresets = remember {
        listOf(
            AmbiancePreset(
                id = "balanced",
                name = "Balanced",
                description = "Smooth and natural color responses",
                responseSpeed = 0.50f,
                smoothnessMs = 150,
                saturationBoost = 1.4f,
                brightnessCompensation = 1.0f,
                sceneCutSensitivity = 110.0f,
                noiseDeadband = 0.10f
            ),
            AmbiancePreset(
                id = "movie",
                name = "Movie / Cinematic",
                description = "Rich deep contrast for cozy film nights",
                responseSpeed = 0.40f,
                smoothnessMs = 210,
                saturationBoost = 1.4f,
                brightnessCompensation = 1.1f,
                sceneCutSensitivity = 120.0f,
                noiseDeadband = 0.14f
            ),
            AmbiancePreset(
                id = "gaming",
                name = "Gaming / Fast Action",
                description = "Snappy instant reactivity for intense action",
                responseSpeed = 0.70f,
                smoothnessMs = 200,
                saturationBoost = 1.3f,
                brightnessCompensation = 1.0f,
                sceneCutSensitivity = 90.0f,
                noiseDeadband = 0.10f
            ),
            AmbiancePreset(
                id = "chill",
                name = "Chill / Ambient",
                description = "Slow soothing color flow for relaxation",
                responseSpeed = 0.30f,
                smoothnessMs = 350,
                saturationBoost = 1.2f,
                brightnessCompensation = 1.0f,
                sceneCutSensitivity = 140.0f,
                noiseDeadband = 0.18f
            ),
            AmbiancePreset(
                id = "candlelight",
                name = "Candlelight",
                description = "Very slow, gentle drift — almost never snaps",
                responseSpeed = 0.20f,
                // Slowest of any preset; needs the widened smoothness range to be reachable from
                // the Settings slider without being clamped.
                smoothnessMs = 400,
                saturationBoost = 1.1f,
                brightnessCompensation = 0.9f,
                // Near the top of the threshold range, so scene cuts essentially never fire.
                sceneCutSensitivity = 150.0f,
                noiseDeadband = 0.22f
            )
        )
    }

    // Combined presets list (built-in + custom)
    val allPresets = remember(builtInPresets, customPresets) {
        builtInPresets + customPresets
    }

    // Helper to apply a preset
    val applyPresetHelper: (AmbiancePreset) -> Unit = { preset ->
        viewModel.applyAmbiancePreset(
            presetId = preset.name,
            responseSpeed = preset.responseSpeed,
            smoothnessMs = preset.smoothnessMs,
            saturationBoost = preset.saturationBoost,
            brightnessCompensation = preset.brightnessCompensation,
            sceneCutSensitivity = preset.sceneCutSensitivity,
            noiseDeadband = preset.noiseDeadband
        )
    }

    val scrollState = rememberScrollState()

    // Smooth continuous gradient drift. Duration maps to smoothnessMs so slower smoothing
    // settings drift more slowly, echoing the real capture's response feel — but the motion
    // itself is always one steady linear sweep, never stepping/snapping/jumping, since nothing
    // in the real ambiance pipeline moves that way (AmbianceOutputInterpolator.easeStep just
    // exponentially eases toward a target color; there's no rotation or spring bounce).
    val gradientDurationMs = (2000f + (uiState.ambianceSettings.ambianceSmoothnessMs.toFloat() / AMBIANCE_SMOOTHNESS_MAX_MS).coerceIn(0f, 1f) * 8000f).toInt()
    val infiniteTransition = rememberInfiniteTransition(label = "ambiance_gradient")
    val angleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = gradientDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ambiance_gradient_angle"
    )

    // Idle-preview palette, derived purely from the active preset/slider parameters.
    val presetColors = remember(
        uiState.ambianceSettings.ambianceSmoothnessMs,
        uiState.ambianceSettings.ambianceNoiseDeadband,
        uiState.ambianceSettings.ambianceResponseSpeed,
        uiState.ambianceSettings.ambianceSaturationBoost,
        uiState.ambianceSettings.ambianceBrightnessCompensation
    ) {
        derivePaletteFromParameters(
            smoothnessMs = uiState.ambianceSettings.ambianceSmoothnessMs,
            noiseDeadband = uiState.ambianceSettings.ambianceNoiseDeadband,
            responseSpeed = uiState.ambianceSettings.ambianceResponseSpeed,
            saturationBoost = uiState.ambianceSettings.ambianceSaturationBoost,
            brightnessCompensation = uiState.ambianceSettings.ambianceBrightnessCompensation
        )
    }

    // While capture is running, source the gradient from the real captured screen colors (the
    // 4x4 zone grid AmbianceProcessor reports) instead of the synthetic preset palette, so the
    // preview actually reflects what ambiance mode is seeing. Each quadrant eases toward its new
    // target color rather than snapping frame-to-frame, mirroring the real device output's
    // exponential smoothing.
    val liveQuadrantColors = remember(zoneColors.value) { computeQuadrantColors(zoneColors.value) }
    val quadrantEasing = tween<Color>(durationMillis = 400, easing = LinearOutSlowInEasing)
    val animatedQuadrant0 by animateColorAsState(liveQuadrantColors.getOrElse(0) { presetColors[0] }, quadrantEasing, label = "q0")
    val animatedQuadrant1 by animateColorAsState(liveQuadrantColors.getOrElse(1) { presetColors[1] }, quadrantEasing, label = "q1")
    val animatedQuadrant2 by animateColorAsState(liveQuadrantColors.getOrElse(2) { presetColors[2] }, quadrantEasing, label = "q2")
    val animatedQuadrant3 by animateColorAsState(liveQuadrantColors.getOrElse(3) { presetColors[3] }, quadrantEasing, label = "q3")

    val previewColors = if (isActive.value) {
        listOf(animatedQuadrant0, animatedQuadrant1, animatedQuadrant2, animatedQuadrant3)
    } else {
        presetColors
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Color Preview (Hero Square Cell with Animated Gradient)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(8.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val centerX = width / 2f
                    val centerY = height / 2f
                    val radius = maxOf(width, height) * 0.8f
                    
                    // Convert degrees to radians for precise trig rotation
                    val angleRad = Math.toRadians((angleProgress * 360f).toDouble())
                    val cosAngle = kotlin.math.cos(angleRad).toFloat()
                    val sinAngle = kotlin.math.sin(angleRad).toFloat()

                    // Gentle organic center drift, still tied to the same smooth continuous progress
                    val wobbleX = width * 0.15f * kotlin.math.cos(angleProgress.toDouble() * 2.0 * Math.PI).toFloat()
                    val wobbleY = height * 0.15f * kotlin.math.sin(angleProgress.toDouble() * 2.0 * Math.PI).toFloat()
                    
                    val adjustedCenterX = centerX + wobbleX
                    val adjustedCenterY = centerY + wobbleY

                    val startX = adjustedCenterX + radius * cosAngle
                    val startY = adjustedCenterY + radius * sinAngle
                    val endX = adjustedCenterX - radius * cosAngle
                    val endY = adjustedCenterY - radius * sinAngle
                    
                    val brush = Brush.linearGradient(
                        colors = previewColors,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY)
                    )
                    
                    drawRect(brush = brush)
                }
            }
        }

        // 2. Control Toggle Buttons (Start / Stop)
        val startInteractionSource = remember { MutableInteractionSource() }
        val stopInteractionSource = remember { MutableInteractionSource() }
        if (!isActive.value) {
            Button(
                onClick = onStartCapture,
                interactionSource = startInteractionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("ambiance_toggle_btn")
                    .joyfulPress(startInteractionSource),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Ambiance Mode", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        } else {
            Button(
                onClick = {
                    AmbianceCaptureService.stop(context)
                },
                interactionSource = stopInteractionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("ambiance_toggle_btn")
                    .joyfulPress(stopInteractionSource),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Ambiance Mode", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }

        // 4. Built-in Preset Grid
        Text(
            text = "Ambiance Presets",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp)
        )

        val chunkedBuiltIn = remember(builtInPresets) { builtInPresets.chunked(2) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            chunkedBuiltIn.forEach { rowPresets ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowPresets.forEach { preset ->
                        val isActivePreset = uiState.ambianceSettings.ambiancePreset == preset.name
                        val presetInteraction = remember(preset.id) { MutableInteractionSource() }
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(
                                    width = if (isActivePreset) 1.5.dp else 1.dp,
                                    color = if (isActivePreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable(
                                    interactionSource = presetInteraction,
                                    indication = LocalIndication.current
                                ) {
                                    applyPresetHelper(preset)
                                }
                                .joyfulPress(presetInteraction)
                                .testTag("ambiance_preset_${preset.id}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActivePreset) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isActivePreset) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isActivePreset) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // Odd preset count: keep the last card half-width instead of letting it
                    // stretch across the row.
                    if (rowPresets.size < 2) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // 5. Custom Presets List
        if (customPresets.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Custom Presets",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                customPresets.forEach { customPreset ->
                    val isActivePreset = uiState.ambianceSettings.ambiancePreset == customPreset.name
                    val itemInteraction = remember(customPreset.id) { MutableInteractionSource() }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                width = if (isActivePreset) 1.5.dp else 1.dp,
                                color = if (isActivePreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable(
                                interactionSource = itemInteraction,
                                indication = LocalIndication.current
                            ) {
                                applyPresetHelper(customPreset)
                            }
                            .joyfulPress(itemInteraction)
                            .testTag("custom_preset_${customPreset.id}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActivePreset) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = customPreset.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isActivePreset) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Custom sliders config preset",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isActivePreset) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row {
                                // Rename Action
                                IconButton(
                                    onClick = {
                                        newPresetName = customPreset.name
                                        showRenameDialog = customPreset
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Rename preset",
                                        tint = if (isActivePreset) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Delete Action
                                IconButton(
                                    onClick = {
                                        customPresets = customPresets.filter { it.id != customPreset.id }
                                        saveCustomPresetsToPrefs(context, customPresets)
                                        Toast.makeText(context, "Preset deleted", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete preset",
                                        tint = if (isActivePreset) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


    }

    // Dialog: Rename Custom Preset
    showRenameDialog?.let { targetPreset ->
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new name for preset '${targetPreset.name}'.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("New Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("rename_preset_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedName = newPresetName.trim()
                        if (trimmedName.isNotEmpty()) {
                            customPresets = customPresets.map {
                                if (it.id == targetPreset.id) {
                                    it.copy(name = trimmedName)
                                } else {
                                    it
                                }
                            }
                            saveCustomPresetsToPrefs(context, customPresets)
                            showRenameDialog = null
                            Toast.makeText(context, "Preset renamed successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please enter a valid name", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.height(44.dp),
                    shape = CircleShape
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// Helpers for Saving / Loading custom presets using org.json
internal fun saveCustomPresetsToPrefs(context: Context, presets: List<AmbiancePreset>) {
    val prefs = context.getSharedPreferences("ambiance_presets_prefs", Context.MODE_PRIVATE)
    val jsonArray = org.json.JSONArray()
    for (p in presets) {
        val obj = org.json.JSONObject()
        obj.put("id", p.id)
        obj.put("name", p.name)
        obj.put("description", p.description)
        obj.put("responseSpeed", p.responseSpeed.toDouble())
        obj.put("smoothnessMs", p.smoothnessMs)
        obj.put("saturationBoost", p.saturationBoost.toDouble())
        obj.put("brightnessCompensation", p.brightnessCompensation.toDouble())
        obj.put("sceneCutSensitivity", p.sceneCutSensitivity.toDouble())
        obj.put("noiseDeadband", p.noiseDeadband.toDouble())
        jsonArray.put(obj)
    }
    prefs.edit().putString("custom_presets_json", jsonArray.toString()).apply()
}

internal fun loadCustomPresetsFromPrefs(context: Context): List<AmbiancePreset> {
    val prefs = context.getSharedPreferences("ambiance_presets_prefs", Context.MODE_PRIVATE)
    val jsonStr = prefs.getString("custom_presets_json", null) ?: return emptyList()
    val list = mutableListOf<AmbiancePreset>()
    try {
        val jsonArray = org.json.JSONArray(jsonStr)
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                AmbiancePreset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", "Custom sliders config preset"),
                    isCustom = true,
                    responseSpeed = obj.getDouble("responseSpeed").toFloat(),
                    smoothnessMs = obj.getInt("smoothnessMs"),
                    saturationBoost = obj.getDouble("saturationBoost").toFloat(),
                    brightnessCompensation = obj.getDouble("brightnessCompensation").toFloat(),
                    sceneCutSensitivity = obj.optDouble("sceneCutSensitivity", 110.0).toFloat(),
                    noiseDeadband = obj.optDouble("noiseDeadband", 0.10).toFloat()
                )
            )
        }
    } catch (e: Exception) {
        Log.e("AmbianceScreen", "Error loading custom presets from SharedPreferences", e)
    }
    return list
}

@Composable
fun AmbianceDiagnosticsCard() {
    val context = LocalContext.current
    var isDiagnosticLogExpanded by remember { mutableStateOf(false) }
    val diagnosticLogList by AmbianceCaptureState.diagnosticLog.collectAsState()
    val isRecording = AmbianceCaptureState.isRecording.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { isDiagnosticLogExpanded = !isDiagnosticLogExpanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🧪 Diagnostic Log",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (isDiagnosticLogExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Toggle Diagnostic Log"
                )
            }

            AnimatedVisibility(visible = isDiagnosticLogExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                val logText = diagnosticLogList.joinToString("\n")
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("Ambiance Log", logText))
                                Toast.makeText(context, "Log copied (${diagnosticLogList.size} entries)", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Copy Log to Clipboard", textAlign = TextAlign.Center)
                        }

                        if (isRecording.value) {
                            Button(
                                onClick = {
                                    AmbianceCaptureState.stopRecording()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Stop Recording")
                            }
                        } else {
                            Button(
                                onClick = {
                                    AmbianceCaptureState.startRecording()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Start Recording")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${diagnosticLogList.size} entries logged",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isRecording.value) {
                            Text(
                                text = "🔴 Recording",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color.Red
                            )
                        } else {
                            Text(
                                text = "Not recording",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

