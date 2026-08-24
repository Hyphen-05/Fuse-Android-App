package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.RgbUiState
import com.example.TelemetryState

/**
 * What each connected link is actually doing. Read-only.
 *
 * This replaced `BLEPacingCard` in Tier E Phase 3 step 4. That card offered a per-device pacing
 * slider, a "Reset to Safe Default (100ms)" button and an auto-tune dialog that stored its answer
 * in a pref — a stack of controls over a number that stopped reaching the radio in step 2, when
 * writes became completion-gated. Measurement is what is left worth showing: `Achieved` is the
 * write rate the link is sustaining, and `In flight` is the measured issued -> ack time
 * (`DeviceWriteManager.inFlightMsEstimate`, ~4.6ms per strip on a warm link when this was
 * calibrated). Telling Joe what the link is delivering beats asking him to tune against a number
 * that was stale by the time it was stored.
 *
 * Both actions here are diagnostics rather than settings: the test pattern, and the 120s sustained
 * run the plan explicitly said to keep.
 */
@Composable
fun LinkDiagnosticsCard(state: RgbUiState, telemetry: TelemetryState, viewModel: RgbControllerViewModel) {
    var selectedDeviceForStress by remember { mutableStateOf<String?>(null) }

    ExpandableCategoryCard(
        title = "Link Diagnostics",
        icon = Icons.Default.Speed,
        iconTint = MaterialTheme.colorScheme.primary,
        initiallyExpanded = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "What each connected link is delivering. Write pacing is set by the radio, not " +
                    "configured — these are measurements, not controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val connectedDevices = state.connectivity.deviceConnectionStates
                .filter { it.value == BleConnectionState.CONNECTED }

            if (connectedDevices.isEmpty()) {
                Text(
                    text = "No devices connected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            connectedDevices.forEach { (address, _) ->
                androidx.compose.runtime.key(address) {
                    val deviceName = state.connectivity.scannedDevices.find { it.address == address }?.alias
                        ?: state.connectivity.scannedDevices.find { it.address == address }?.name
                        ?: address

                    val achievedFps = telemetry.deviceAchievedFps[address] ?: 0
                    val inFlightMs = telemetry.deviceInFlightMs[address] ?: 0.0
                    val isTesting = state.connectivity.isTestPatternRunning[address] == true

                    val testInteractionSource = remember(address) { MutableInteractionSource() }
                    val stressInteractionSource = remember(address) { MutableInteractionSource() }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Achieved: ~$achievedFps fps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    // 0.0 is "nothing measured yet" — the EMA only moves once a
                                    // write has been acked, so a freshly connected idle device has
                                    // no reading rather than a reading of zero.
                                    text = if (inFlightMs > 0.0) {
                                        "In flight: ${"%.1f".format(inFlightMs)} ms/write"
                                    } else {
                                        "In flight: no writes yet"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.toggleTestPattern(address) },
                                    interactionSource = testInteractionSource,
                                    modifier = Modifier
                                        .height(44.dp)
                                        .joyfulPress(testInteractionSource),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isTesting) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.secondary
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = CircleShape
                                ) {
                                    Text(
                                        if (isTesting) "Stop Test" else "Test Pattern",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                OutlinedButton(
                                    onClick = { selectedDeviceForStress = address },
                                    interactionSource = stressInteractionSource,
                                    modifier = Modifier
                                        .height(44.dp)
                                        .joyfulPress(stressInteractionSource),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    shape = CircleShape
                                ) {
                                    Text("Stress Test", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    selectedDeviceForStress?.let { address ->
        val deviceName = state.connectivity.scannedDevices.find { it.address == address }?.alias
            ?: state.connectivity.scannedDevices.find { it.address == address }?.name
            ?: address

        LinkStressTestDialog(
            address = address,
            deviceName = deviceName,
            onDismiss = { selectedDeviceForStress = null },
            viewModel = viewModel
        )
    }
}
