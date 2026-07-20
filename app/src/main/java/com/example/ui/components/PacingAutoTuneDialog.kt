package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BleConnectionState
import com.example.DuoCoProtocol
import com.example.RgbControllerViewModel
import com.example.core.pacing.PacingAutoTuneEngine
import com.example.core.pacing.TuningAction
import com.example.core.pacing.TuningPhase
import com.example.core.pacing.TuningResult
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacingAutoTuneDialog(
    address: String,
    deviceName: String,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    viewModel: RgbControllerViewModel
) {
    DisposableEffect(Unit) {
        onDispose {
            viewModel.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(false))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = "Auto-Tune Pacing",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                
                // Content
                Box(modifier = Modifier.weight(1f)) {
                    PacingAutoTuneContent(address, viewModel, onSave, onDismiss)
                }
            }
        }
    }
}

@Composable
fun PacingAutoTuneContent(
    address: String,
    viewModel: RgbControllerViewModel,
    onComplete: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var phase by remember { mutableStateOf("IDLE") }
    var statusText by remember { mutableStateOf("Ready to determine optimal BLE pacing.") }
    var isTuning by remember { mutableStateOf(false) }
    var isStressTestOnly by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var results by remember { mutableStateOf(listOf<TuningResult>()) }
    var finalPacing by remember { mutableStateOf(-1) }
    
    val startInteractionSource = remember { MutableInteractionSource() }
    val listState = rememberLazyListState()

    // Auto-scroll logic
    LaunchedEffect(results.size) {
        if (results.isNotEmpty()) {
            listState.animateScrollToItem(results.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status Visual
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = if (isComplete && finalPacing != -1) MaterialTheme.colorScheme.primaryContainer 
                            else if (isComplete) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isTuning && !isComplete) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(100.dp),
                    strokeWidth = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Icon(
                imageVector = if (isComplete && finalPacing != -1) Icons.Rounded.Check 
                              else if (isComplete) Icons.Rounded.Close
                              else if (isTuning) Icons.Rounded.Sync 
                              else Icons.Rounded.Speed,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isComplete && finalPacing != -1) MaterialTheme.colorScheme.onPrimaryContainer 
                       else if (isComplete) MaterialTheme.colorScheme.onErrorContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = phase,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Results Log
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results) { res ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${res.interval}ms",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = res.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            Icon(
                                imageVector = if (res.isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                                contentDescription = null,
                                tint = if (res.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Buttons
        if (isTuning && !isComplete) {
            Button(
                onClick = { 
                    isTuning = false 
                    isStressTestOnly = false
                    isComplete = true 
                    finalPacing = -1 
                    phase = "CANCELLED" 
                    statusText = if (isStressTestOnly) "Stress test cancelled." else "Tuning cancelled." 
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = CircleShape
            ) {
                Text("Cancel")
            }
        } else if (!isTuning && !isComplete) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { 
                        isStressTestOnly = false
                        isTuning = true 
                    },
                    interactionSource = startInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .joyfulPress(startInteractionSource),
                    shape = CircleShape
                ) {
                    Text("Start Auto-Tune")
                }
                
                val currentSetMs = viewModel.uiState.value.connectivity.devicePacingMs[address] ?: 100
                OutlinedButton(
                    onClick = { 
                        isStressTestOnly = true
                        isTuning = true 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text("Stress Test Current (${currentSetMs}ms)")
                }
            }
        }

        if (isComplete) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text("Close")
                }
                if (finalPacing != -1) {
                    Button(
                        onClick = { onComplete(finalPacing) },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = CircleShape
                    ) {
                        Text("Apply ${finalPacing}ms")
                    }
                }
            }
        }
    }

    LaunchedEffect(isTuning) {
        if (!isTuning) return@LaunchedEffect

        val originalPacing = viewModel.uiState.value.connectivity.devicePacingMs[address] ?: 100
        val engine = PacingAutoTuneEngine()
        try {
            viewModel.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(true))
            delay(300)
            withContext(Dispatchers.IO) {

                suspend fun runBurstIo(interval: Int): TuningResult {
                    viewModel.setDevicePacing(address, interval)
                    delay(200)

                    for (i in 0 until PacingAutoTuneEngine.BURST_SIZE) {
                        if (!isActive) return TuningResult(interval, false, "Cancelled")
                        val cmd = DuoCoProtocol.createMusicColorCommand((i * 8) % 255, (i * 16) % 255, (i * 24) % 255)
                        viewModel.queueCommand(cmd)
                        delay(if (interval == 0) 1L else interval.toLong())
                    }

                    delay(800) // Let queue drain

                    val isConnected = viewModel.uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED
                    val currentFps = viewModel.telemetry.value.deviceAchievedFps[address] ?: 0
                    return engine.evaluateBurst(interval, isConnected, currentFps)
                }

                suspend fun runStressIo(interval: Int, onProgress: (Float) -> Unit): TuningResult {
                    viewModel.setDevicePacing(address, interval)
                    delay(200)

                    val startTime = System.currentTimeMillis()
                    var elapsed = 0L

                    var i = 0
                    while (elapsed < PacingAutoTuneEngine.STRESS_DURATION_MS) {
                        if (!isActive) return TuningResult(interval, false, "Cancelled")

                        val cmd = DuoCoProtocol.createMusicColorCommand((i * 8) % 255, (i * 16) % 255, (i * 24) % 255)
                        viewModel.queueCommand(cmd)
                        delay(if (interval == 0) 1L else interval.toLong())

                        elapsed = System.currentTimeMillis() - startTime
                        if (i % PacingAutoTuneEngine.STRESS_PROGRESS_CHECK_EVERY == 0) {
                            onProgress(elapsed.toFloat() / PacingAutoTuneEngine.STRESS_DURATION_MS)
                            val isConnected = viewModel.uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED
                            if (!isConnected) return TuningResult(interval, false, "Disconnected")
                        }
                        i++
                    }

                    delay(1000)
                    val isConnected = viewModel.uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED
                    return engine.evaluateStress(interval, isConnected)
                }

                suspend fun runStressWithStatusTicker(interval: Int): TuningResult {
                    var stressProgress = 0f
                    val stressJob = launch {
                        while (isActive) {
                            withContext(Dispatchers.Main) {
                                statusText = "Stress testing ${interval}ms... (${(stressProgress * 120).toInt()}s / 120s)"
                                progress = stressProgress
                            }
                            delay(500)
                        }
                    }
                    val result = runStressIo(interval) { p -> stressProgress = p }
                    stressJob.cancel()
                    return result
                }

                var action: TuningAction = engine.start(originalPacing, isStressTestOnly)

                while (isActive) {
                    when (val currentAction = action) {
                        is TuningAction.RunBurst -> {
                            withContext(Dispatchers.Main) {
                                phase = phaseLabel(engine.phase)
                                statusText = if (engine.phase == TuningPhase.FINE_TUNING) {
                                    "Fine-tuning at ${currentAction.interval}ms..."
                                } else {
                                    "Burst testing ${currentAction.interval}ms..."
                                }
                                progress = engine.burstProgress
                            }
                            val burstResult = runBurstIo(currentAction.interval)
                            action = engine.onBurstResult(burstResult)
                            withContext(Dispatchers.Main) { results = engine.results }
                        }
                        is TuningAction.RunStress -> {
                            withContext(Dispatchers.Main) { phase = phaseLabel(engine.phase) }
                            val stressResult = runStressWithStatusTicker(currentAction.interval)
                            action = engine.onStressResult(stressResult)
                            withContext(Dispatchers.Main) { results = engine.results }
                        }
                        is TuningAction.Finished -> {
                            withContext(Dispatchers.Main) {
                                finalPacing = currentAction.pacingMs
                                statusText = if (currentAction.succeeded) {
                                    "Tuning complete. Optimal pacing: ${currentAction.pacingMs}ms."
                                } else {
                                    "Tuning failed. Kept original ${currentAction.pacingMs}ms."
                                }
                                phase = phaseLabel(engine.phase)
                                progress = 1f
                                isComplete = true
                                isTuning = false
                            }
                            return@withContext
                        }
                    }
                }
            }
        } finally {
            viewModel.setDevicePacing(address, originalPacing)
            viewModel.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(false))
        }
    }
}

/** Maps [TuningPhase] to the literal on-screen phase labels the original inline code used. */
private fun phaseLabel(phase: TuningPhase): String = when (phase) {
    TuningPhase.IDLE -> "IDLE"
    TuningPhase.BURST_PHASE -> "BURST PHASE"
    TuningPhase.FINE_TUNING -> "FINE-TUNING"
    TuningPhase.STRESS_TEST -> "STRESS TEST"
    TuningPhase.STRESS_TEST_ONLY -> "STRESS TEST ONLY"
    TuningPhase.COMPLETE -> "COMPLETE"
    TuningPhase.FAILED -> "FAILED"
    TuningPhase.CANCELLED -> "CANCELLED"
}
