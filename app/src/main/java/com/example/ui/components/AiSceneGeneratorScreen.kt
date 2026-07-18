package com.example.ui.components

import com.example.domain.model.AppScene
import com.example.core.animation.ProceduralSceneParams
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ============================================================================
// State definitions
// ============================================================================
sealed interface AiSceneGeneratorState {
    object Idle : AiSceneGeneratorState
    object Loading : AiSceneGeneratorState
    data class Success(
        val params: ProceduralSceneParams,
        val sceneName: String,
        val explanation: String,
        val colors: List<SemanticSceneStep>
    ) : AiSceneGeneratorState
    object Unavailable : AiSceneGeneratorState
    data class Error(val message: String) : AiSceneGeneratorState
}

// ============================================================================
// ViewModel
// ============================================================================
class AiSceneGeneratorViewModel(
    private val generator: AiSceneGenerator = AiSceneGenerator()
) : ViewModel() {

    private val _state = MutableStateFlow<AiSceneGeneratorState>(AiSceneGeneratorState.Idle)
    val state: StateFlow<AiSceneGeneratorState> = _state.asStateFlow()

    fun generateScene(userPrompt: String) {
        if (userPrompt.isBlank()) return

        viewModelScope.launch {
            _state.value = AiSceneGeneratorState.Loading
            try {
                // First check if the on-device model is available
                val isAvailable = generator.checkAvailability()
                if (!isAvailable) {
                    _state.value = AiSceneGeneratorState.Unavailable
                    return@launch
                }

                // Call the actual AI generation pipeline
                when (val result = generator.generateScene(userPrompt)) {
                    is SceneGenerationResult.Success -> {
                        val params = mapToProceduralParams(result.result)
                        _state.value = AiSceneGeneratorState.Success(
                            params = params,
                            sceneName = result.result.sceneName,
                            explanation = result.result.explanation,
                            colors = result.result.palette
                        )
                    }
                    is SceneGenerationResult.Unavailable -> {
                        _state.value = AiSceneGeneratorState.Unavailable
                    }
                    is SceneGenerationResult.Error -> {
                        _state.value = AiSceneGeneratorState.Error(result.message)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AiSceneGeneratorState.Error(e.localizedMessage ?: "Unknown error occurred")
            }
        }
    }

    fun resetToIdle() {
        _state.value = AiSceneGeneratorState.Idle
    }

    fun loadExistingScene(scene: AppScene) {
        val seq = scene.state.animatedSequence ?: return
        val semanticSteps = seq.palette.map { color ->
            val colorName = String.format("#%02x%02x%02x", color.first, color.second, color.third)
            SemanticSceneStep(color = colorName, rgbHex = colorName)
        }
        _state.value = AiSceneGeneratorState.Success(
            params = seq,
            sceneName = scene.name,
            explanation = "Editing existing smart scene animation sequence.",
            colors = semanticSteps
        )
    }

    fun updateSceneSettings(energy: Float, randomness: Float) {
        val currentState = _state.value
        if (currentState is AiSceneGeneratorState.Success) {
            val newParams = currentState.params.copy(
                energy = energy,
                randomness = randomness
            )
            _state.value = currentState.copy(
                params = newParams
            )
        }
    }

    private fun mapToProceduralParams(result: ProceduralSceneResult): ProceduralSceneParams {
        val palette = result.palette.mapNotNull { colorStep ->
            if (!colorStep.rgbHex.isNullOrBlank()) {
                try {
                    val hexClean = colorStep.rgbHex.trim().lowercase().removePrefix("#")
                    val r = hexClean.substring(0, 2).toInt(16)
                    val g = hexClean.substring(2, 4).toInt(16)
                    val b = hexClean.substring(4, 6).toInt(16)
                    Triple(r, g, b)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }
        
        if (palette.isEmpty()) {
            com.example.DiagnosticLogger.log("AiSceneGeneratorScreen", "All colors failed to parse from AI result — falling back to default palette. Raw colors: ${result.palette}")
        }
        val finalPalette = if (palette.isEmpty()) listOf(Triple(0, 191, 255)) else palette
        
        return ProceduralSceneParams(
            palette = finalPalette,
            movementType = result.movementType,
            energy = result.energy,
            randomness = result.randomness,
            colorInterpMode = result.colorInterpMode,
            brightnessBehaviour = result.brightnessBehaviour,
            syncBrightnessToColor = result.syncBrightnessToColor,
            harmonicLayering = result.harmonicLayering,
            sequenceMode = result.sequenceMode
        )
    }

    override fun onCleared() {
        super.onCleared()
        generator.close()
    }
}

// ============================================================================
// Composable Screen
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSceneGeneratorScreen(
    viewModel: AiSceneGeneratorViewModel,
    onApplyScene: (params: ProceduralSceneParams, sceneName: String, explanation: String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var promptText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Screen Header
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "AI Scene Generator",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Describe any scene, theme, or mood to generate a customized, multi-layered procedural behavioral lighting animation using local AI.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Configuration / Prompt Section
        if (state is AiSceneGeneratorState.Idle || state is AiSceneGeneratorState.Loading) {
            val isLoading = state is AiSceneGeneratorState.Loading

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_prompt_input"),
                        enabled = !isLoading,
                        label = { Text("Describe your lighting scene") },
                        placeholder = { 
                            Text(
                                text = "e.g., cozy sunset, forest rain, cyberpunk pulse, candlelight glow",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ) 
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        ),
                        singleLine = false,
                        maxLines = 3
                    )

                    Button(
                        onClick = { viewModel.generateScene(promptText) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("generate_button"),
                        enabled = promptText.isNotBlank() && !isLoading,
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
                            Text(
                                text = "Generate Scene",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // State Renderer
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (val currentState = state) {
                is AiSceneGeneratorState.Idle -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Enter a prompt above and tap Generate to begin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is AiSceneGeneratorState.Loading -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("loading_indicator"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Analyzing Prompt...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "On-device AI is designing your procedural behavior. This may take a moment.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                is AiSceneGeneratorState.Success -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("success_explanation"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Scene Explanation",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp).padding(top = 2.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = currentState.sceneName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = currentState.explanation,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }

                        // Behavior properties preview
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Procedural Behaviors",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text("Move: ${currentState.params.movementType.replaceFirstChar { it.uppercase() }}") },
                                    shape = RoundedCornerShape(100.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text("Glow: ${currentState.params.brightnessBehaviour.replaceFirstChar { it.uppercase() }}") },
                                    shape = RoundedCornerShape(100.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text("Fade: ${currentState.params.colorInterpMode.replaceFirstChar { it.uppercase() }}") },
                                    shape = RoundedCornerShape(100.dp),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                )
                                if (currentState.params.syncBrightnessToColor) {
                                    SuggestionChip(
                                        onClick = { },
                                        label = { Text("Sync Brightness") },
                                        shape = RoundedCornerShape(100.dp),
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    )
                                }
                                if (currentState.params.harmonicLayering != "none") {
                                    SuggestionChip(
                                        onClick = { },
                                        label = { Text("Layer: ${currentState.params.harmonicLayering.replaceFirstChar { it.uppercase() }}") },
                                        shape = RoundedCornerShape(100.dp),
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    )
                                }
                            }

                            // Energy Slider
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Energy (Speed/Intensity)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${(currentState.params.energy * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                HapticBouncySlider(
                                    value = currentState.params.energy,
                                    onValueChange = { newEnergy ->
                                        viewModel.updateSceneSettings(
                                            energy = newEnergy,
                                            randomness = currentState.params.randomness
                                        )
                                    },
                                    valueRange = 0.0f..1.0f,
                                    totalSteps = 10,
                                    modifier = Modifier.fillMaxWidth().testTag("energy_slider")
                                )
                            }

                            // Randomness Slider
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Organic Randomness",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${(currentState.params.randomness * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                HapticBouncySlider(
                                    value = currentState.params.randomness,
                                    onValueChange = { newRandom ->
                                        viewModel.updateSceneSettings(
                                            energy = currentState.params.energy,
                                            randomness = newRandom
                                        )
                                    },
                                    valueRange = 0.0f..1.0f,
                                    totalSteps = 10,
                                    modifier = Modifier.fillMaxWidth().testTag("random_slider")
                                )
                            }
                        }

                        // Palette Preview
                        Text(
                            text = "Color Palette",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            currentState.params.palette.forEachIndexed { index, colorTriple ->
                                val composeColor = Color(
                                    colorTriple.first,
                                    colorTriple.second,
                                    colorTriple.third
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(composeColor)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.resetToIdle() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("regenerate_button"),
                                shape = RoundedCornerShape(100.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Regenerate", fontWeight = FontWeight.Bold)
                                }
                            }

                            Button(
                                onClick = {
                                    onApplyScene(currentState.params, currentState.sceneName, currentState.explanation)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("apply_button"),
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text("Apply", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                is AiSceneGeneratorState.Unavailable -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "AI Unavailable",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "On-Device AI Unavailable",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "This device does not support on-device AI generation. Please ensure Google Play Services and on-device model capabilities are up to date.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                is AiSceneGeneratorState.Error -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("error_text"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Generation Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Generation Failed",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = currentState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Button(
                                onClick = { viewModel.resetToIdle() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("try_again_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(100.dp)
                            ) {
                                Text("Try Again", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLoopMode(loopMode: String): String {
    return when (loopMode.lowercase()) {
        "loop" -> "Type: Loop"
        "ping-pong" -> "Type: Ping-pong"
        "one-shot" -> "Type: One-shot"
        else -> "Type: ${loopMode.replaceFirstChar { it.uppercase() }}"
    }
}
