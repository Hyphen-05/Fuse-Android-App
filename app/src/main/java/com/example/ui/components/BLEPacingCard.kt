package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ControllerUiState
import com.example.RgbControllerViewModel
import com.example.BleConnectionState

@Composable
fun BLEPacingCard(state: ControllerUiState, viewModel: RgbControllerViewModel) {
    var selectedDeviceForCalibration by remember { mutableStateOf<String?>(null) }

    ExpandableCategoryCard(
        title = "BLE Device Pacing",
        icon = Icons.Default.Speed,
        iconTint = MaterialTheme.colorScheme.primary,
        initiallyExpanded = false
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Adjust the command pacing for each connected device. Lower interval = faster, but may cause lag, flicker, or disconnects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val connectedDevices = state.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }
            
            if (connectedDevices.isEmpty()) {
                Text(
                    text = "No devices connected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            connectedDevices.forEach { (address, _) ->
                androidx.compose.runtime.key(address) {
                val deviceName = state.scannedDevices.find { it.address == address }?.alias 
                    ?: state.scannedDevices.find { it.address == address }?.name 
                    ?: address

                val currentPacing = state.devicePacingMs[address] ?: 100
                val achievedFps = state.deviceAchievedFps[address] ?: 0
                val isTesting = state.isTestPatternRunning[address] == true

                val testInteractionSource = remember(address) { MutableInteractionSource() }
                val tuneInteractionSource = remember(address) { MutableInteractionSource() }
                val resetInteractionSource = remember(address) { MutableInteractionSource() }

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
                                text = "Interval: ${currentPacing}ms",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Achieved: ~$achievedFps fps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
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
                                    containerColor = if (isTesting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = CircleShape
                            ) {
                                Text(if (isTesting) "Stop Test" else "Test Pattern", style = MaterialTheme.typography.labelMedium)
                            }
                            
                            OutlinedButton(
                                onClick = { selectedDeviceForCalibration = address },
                                interactionSource = tuneInteractionSource,
                                modifier = Modifier
                                    .height(44.dp)
                                    .joyfulPress(tuneInteractionSource),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = CircleShape
                            ) {
                                Text("Auto Tune", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    HapticBouncySlider(
                        value = currentPacing.toFloat(),
                        onValueChange = { viewModel.setDevicePacing(address, it.toInt()) },
                        valueRange = 0f..200f,
                        totalSteps = 200,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = { viewModel.setDevicePacing(address, 100) },
                        interactionSource = resetInteractionSource,
                        modifier = Modifier
                            .align(Alignment.End)
                            .height(44.dp)
                            .joyfulPress(resetInteractionSource),
                        shape = CircleShape
                    ) {
                        Text("Reset to Safe Default (100ms)")
                    }
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 8.dp))
                }
                }
            }
        }
    }
    
    if (selectedDeviceForCalibration != null) {
        val address = selectedDeviceForCalibration!!
        val deviceName = state.scannedDevices.find { it.address == address }?.alias 
                    ?: state.scannedDevices.find { it.address == address }?.name 
                    ?: address
        
        PacingAutoTuneDialog(
            address = address,
            deviceName = deviceName,
            onDismiss = { selectedDeviceForCalibration = null },
            onSave = { bestPacing ->
                viewModel.setDevicePacing(address, bestPacing)
                selectedDeviceForCalibration = null
            },
            viewModel = viewModel
        )
    }
}
