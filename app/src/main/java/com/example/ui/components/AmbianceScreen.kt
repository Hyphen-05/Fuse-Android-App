package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.ambiance.AmbianceCaptureService
import com.example.ambiance.AmbianceCaptureState
import androidx.core.graphics.ColorUtils as AndroidColorUtils

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
    val sFrac = (smoothnessMs.toFloat() / 350f).coerceIn(0f, 1f)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbianceScreen(
    viewModel: RgbControllerViewModel,
    modifier: Modifier = Modifier,
    onStartCapture: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val pacingPrefs = remember { context.getSharedPreferences("ble_pacing_prefs", Context.MODE_PRIVATE) }
    val presetPrefs = remember { context.getSharedPreferences("ambiance_presets_prefs", Context.MODE_PRIVATE) }

    // Observe active capture state and colors
    val isActive = AmbianceCaptureState.isActive.collectAsState()
    val zoneColors = AmbianceCaptureState.zoneColors.collectAsState()

    // Load custom presets
    var customPresets by remember { mutableStateOf(loadCustomPresetsFromPrefs(context)) }

    var showRenameDialog by remember { mutableStateOf<AmbiancePreset?>(null) }
    var newPresetName by remember { mutableStateOf("") }

    // Define 4 built-in presets
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
                sceneCutSensitivity = 100.0f,
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
                sceneCutSensitivity = 130.0f,
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
                sceneCutSensitivity = 90.0f,
                noiseDeadband = 0.18f
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

    // --- LIFECYCLE OBSERVATION FOR BATTERY EFFICIENCY ---
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAppResumed = true
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isAppResumed = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Animation progress (0f..1f)
    var t by remember { mutableStateOf(0f) }

    // Coroutine loop for continuous drifting (controlled inversely by smoothnessMs)
    LaunchedEffect(isAppResumed, uiState.ambianceSmoothnessMs) {
        if (!isAppResumed) return@LaunchedEffect
        
        // Map smoothnessMs (real range 0..350) to animation duration range of 2s to 10s
        // Lower smoothnessMs = faster animation, higher smoothnessMs = slower animation
        val durationMs = 2000f + ((uiState.ambianceSmoothnessMs.toFloat() - 0f) / 350f).coerceIn(0f, 1f) * 8000f
        
        val startTime = System.nanoTime()
        val startT = t
        
        while (true) {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
            t = (startT + (elapsedMs.toFloat() / durationMs)) % 1f
            delay(16L) // target ~60fps
        }
    }

    // Motion character (continuous drift vs settle-and-jump): tied to noiseDeadband
    // Map noiseDeadband (real range 0.0f..0.5f) to deadband factor
    val deadbandFactor = ((uiState.ambianceNoiseDeadband - 0.0f) / 0.5f).coerceIn(0f, 1f)
    val steps = 4f
    val stepFraction = (t * steps) % 1f
    val transitionStart = 0.8f
    val smoothStep = if (stepFraction < transitionStart) {
        0f
    } else {
        val rawFrac = (stepFraction - transitionStart) / (1f - transitionStart)
        rawFrac * rawFrac * (3f - 2f * rawFrac)
    }
    val steppedT = ((t * steps).toInt().toFloat() + smoothStep) / steps
    val tModified = t + (steppedT - t) * deadbandFactor

    // Occasional "scene cut" flourish Animatable
    val flourishAnim = remember { androidx.compose.animation.core.Animatable(0f) }

    // Loop for randomized scene cut flourishes tied to scene_cut_sensitivity
    LaunchedEffect(isAppResumed, uiState.ambianceSceneCutSensitivity) {
        if (!isAppResumed) return@LaunchedEffect
        
        // Map sceneCutSensitivity (real range 10.0f..150.0f) to average interval:
        // higher sensitivity = more frequent = smaller delay (3s at 150, 15s at 10)
        val avgDelayMs = 15000f - ((uiState.ambianceSceneCutSensitivity - 10f) / 140f).coerceIn(0f, 1f) * 12000f
        
        while (true) {
            val delayMs = (avgDelayMs * (0.5f + Math.random().toFloat())).toLong().coerceAtLeast(1000L)
            delay(delayMs)
            
            // Trigger rapid/sharp spring transition to a new angle offset
            val targetValue = flourishAnim.value + 120f + (Math.random().toFloat() * 180f)
            flourishAnim.animateTo(
                targetValue = targetValue,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                )
            )
        }
    }

    // Determine the 3-4 color stops dynamically from the active preset's or custom settings' actual parameters
    val presetColors = remember(
        uiState.ambianceSmoothnessMs,
        uiState.ambianceNoiseDeadband,
        uiState.ambianceResponseSpeed,
        uiState.ambianceSaturationBoost,
        uiState.ambianceBrightnessCompensation
    ) {
        derivePaletteFromParameters(
            smoothnessMs = uiState.ambianceSmoothnessMs,
            noiseDeadband = uiState.ambianceNoiseDeadband,
            responseSpeed = uiState.ambianceResponseSpeed,
            saturationBoost = uiState.ambianceSaturationBoost,
            brightnessCompensation = uiState.ambianceBrightnessCompensation
        )
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
                    val angleRad = Math.toRadians((tModified * 360f + flourishAnim.value).toDouble())
                    val cosAngle = kotlin.math.cos(angleRad).toFloat()
                    val sinAngle = kotlin.math.sin(angleRad).toFloat()
                    
                    // Add smooth organic center drifting based on tModified
                    val wobbleX = width * 0.15f * kotlin.math.cos(tModified.toDouble() * 2.0 * Math.PI).toFloat()
                    val wobbleY = height * 0.15f * kotlin.math.sin(tModified.toDouble() * 2.0 * Math.PI).toFloat()
                    
                    val adjustedCenterX = centerX + wobbleX
                    val adjustedCenterY = centerY + wobbleY

                    val startX = adjustedCenterX + radius * cosAngle
                    val startY = adjustedCenterY + radius * sinAngle
                    val endX = adjustedCenterX - radius * cosAngle
                    val endY = adjustedCenterY - radius * sinAngle
                    
                    val brush = Brush.linearGradient(
                        colors = presetColors,
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
                        val isActivePreset = uiState.ambiancePreset == preset.name
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
                    val isActivePreset = uiState.ambiancePreset == customPreset.name
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

