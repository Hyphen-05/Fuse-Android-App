package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import com.example.RgbControllerViewModel
import com.example.core.pacing.LinkStressTest
import com.example.core.pacing.StressResult
import com.example.core.protocol.DuoCoProtocol
import kotlinx.coroutines.*

/**
 * The 120-second sustained-write diagnostic, all that remains of `PacingAutoTuneDialog` after
 * Tier E Phase 3 step 4.
 *
 * What went: the burst probe, the fine-tune loop, the "Apply Nms" button and the save into the
 * pacing pref. Those produced a stored pacing number, and there is no longer anywhere for one to
 * go — the radio paces the writes. What stayed is the question the run answers on its own: does
 * this link hold up under two minutes of writes at full rate, and what is it delivering while it
 * does. The run no longer sets or restores pacing, so there is nothing to put back afterwards.
 */
@Composable
fun LinkStressTestDialog(
    address: String,
    deviceName: String,
    onDismiss: () -> Unit,
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
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(24.dp)
                ) {
                    Column {
                        Text(
                            text = "Link Stress Test",
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

                LinkStressTestContent(address, viewModel, onDismiss)
            }
        }
    }
}

@Composable
private fun LinkStressTestContent(
    address: String,
    viewModel: RgbControllerViewModel,
    onDismiss: () -> Unit
) {
    var statusText by remember {
        mutableStateOf("Sends writes at full rate for 120 seconds and reports what the link did.")
    }
    var isRunning by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<StressResult?>(null) }

    val startInteractionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = when {
                        isComplete && result?.passed == true -> MaterialTheme.colorScheme.primaryContainer
                        isComplete -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isRunning && !isComplete) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(100.dp),
                    strokeWidth = 6.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Icon(
                imageVector = when {
                    isComplete && result?.passed == true -> Icons.Rounded.Check
                    isComplete -> Icons.Rounded.Close
                    isRunning -> Icons.Rounded.Sync
                    else -> Icons.Rounded.Speed
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = when {
                    isComplete && result?.passed == true -> MaterialTheme.colorScheme.onPrimaryContainer
                    isComplete -> MaterialTheme.colorScheme.onErrorContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        result?.let { res ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = res.reason,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (res.passed) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Achieved ~${res.achievedFps} fps at " +
                            "${"%.1f".format(res.inFlightMs)} ms per write in flight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        // Said plainly because the plan calls this the weakest of the three
                        // failure modes: a link can render half of what it is sent and still
                        // pass this.
                        text = "Pass means the link stayed connected. It does not prove the strip " +
                            "rendered every write.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        when {
            isRunning && !isComplete -> {
                Button(
                    // Flipping `isRunning` re-keys the LaunchedEffect below, which cancels the run
                    // coroutine outright — so the terminal state is set here rather than in there.
                    // Its `finally` still runs and puts the mic toggle back.
                    onClick = {
                        result = LinkStressTest.aborted(
                            "Cancelled",
                            viewModel.telemetry.value.deviceAchievedFps[address] ?: 0,
                            viewModel.telemetry.value.deviceInFlightMs[address] ?: 0.0
                        )
                        statusText = "Stress test cancelled."
                        isComplete = true
                        isRunning = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text("Cancel")
                }
            }

            isComplete -> {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = CircleShape
                ) {
                    Text("Close")
                }
            }

            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { isRunning = true },
                        interactionSource = startInteractionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .joyfulPress(startInteractionSource),
                        shape = CircleShape
                    ) {
                        Text("Start 120s Stress Test")
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = CircleShape
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect

        fun isConnected() =
            viewModel.uiState.value.connectivity.deviceConnectionStates[address] == BleConnectionState.CONNECTED

        fun fps() = viewModel.telemetry.value.deviceAchievedFps[address] ?: 0
        fun inFlight() = viewModel.telemetry.value.deviceInFlightMs[address] ?: 0.0

        try {
            viewModel.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(true))
            delay(300)

            val outcome = withContext(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                var elapsed = 0L
                var i = 0

                while (elapsed < LinkStressTest.DURATION_MS) {
                    if (!isActive) return@withContext LinkStressTest.aborted("Cancelled", fps(), inFlight())

                    val cmd = DuoCoProtocol.createMusicColorCommand(
                        (i * 8) % 255, (i * 16) % 255, (i * 24) % 255
                    )
                    viewModel.queueCommand(cmd)
                    delay(LinkStressTest.SEND_DELAY_MS)

                    elapsed = System.currentTimeMillis() - startTime
                    if (i % LinkStressTest.PROGRESS_CHECK_EVERY == 0) {
                        val fraction = elapsed.toFloat() / LinkStressTest.DURATION_MS
                        withContext(Dispatchers.Main) {
                            progress = fraction
                            statusText = "Stress testing... (${(fraction * 120).toInt()}s / 120s)"
                        }
                        if (!isConnected()) {
                            return@withContext LinkStressTest.aborted("Disconnected mid-run", fps(), inFlight())
                        }
                    }
                    i++
                }

                // Let the queue drain before reading the final numbers, so the fps sample and the
                // in-flight EMA describe the run rather than its tail.
                delay(1000)
                LinkStressTest.evaluate(isConnected(), fps(), inFlight())
            }

            result = outcome
            statusText = if (outcome.passed) "Stress test passed." else "Stress test failed."
            progress = 1f
            isComplete = true
            isRunning = false
        } finally {
            viewModel.broadcastCommand(DuoCoProtocol.createPhoneMicToggleCommand(false))
        }
    }
}
