package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BleConnectionState
import com.example.RgbControllerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: RgbControllerViewModel,
    onScanClick: () -> Unit,
    onEditAlias: (address: String, currentName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            // --- Unified Device Center Dashboard ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.connectivity.isScanning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Scanning...",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    } else {
                        Button(
                            onClick = onScanClick,
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text("Scan for Devices")
                        }
                    }
                }

                // Section 1: Saved Devices
                Text(
                    text = "Saved Devices (${savedDevices.size})",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                )

                if (savedDevices.isEmpty() && uiState.coreControl.isDbLoaded) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = "Bluetooth Logo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "No saved devices in your center yet. Connect to a device below to register it.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                } else {
                    savedDevices.forEach { saved ->
                        val connState = uiState.connectivity.deviceConnectionStates[saved.macAddress] ?: BleConnectionState.DISCONNECTED
                        val isConnected = connState == BleConnectionState.CONNECTED
                        val isConnecting = connState == BleConnectionState.CONNECTING

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("saved_device_card_${saved.macAddress}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isConnected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Row 1: Header (Name, Mac, status and delete)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                                        contentDescription = "Bluetooth Device",
                                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = saved.customName,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = saved.macAddress,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }

                                    // Connection Status Badge
                                    Surface(
                                        color = when (connState) {
                                            BleConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                            BleConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                            BleConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.error
                                            BleConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                        },
                                        contentColor = when (connState) {
                                            BleConnectionState.CONNECTED -> MaterialTheme.colorScheme.onPrimary
                                            BleConnectionState.CONNECTING -> MaterialTheme.colorScheme.onTertiary
                                            BleConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.onError
                                            BleConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Text(
                                            text = connState.name,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }

                                    // Edit Name
                                    IconButton(
                                        onClick = {
                                            onEditAlias(saved.macAddress, saved.customName)
                                        },
                                        modifier = Modifier.size(32.dp).testTag("edit_alias_button_${saved.macAddress}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "Edit alias",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Delete Device
                                    IconButton(
                                        onClick = { viewModel.deleteSavedDevice(saved.macAddress) },
                                        modifier = Modifier.size(32.dp).testTag("delete_saved_${saved.macAddress}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete saved device",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                HorizontalDivider(color = if (isConnected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outlineVariant)

                                // Row 2: Switches and Control
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Toggles
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Switch(
                                                checked = saved.isAutoConnectEnabled,
                                                onCheckedChange = { viewModel.toggleAutoConnect(saved.macAddress, saved.customName, it) },
                                                modifier = Modifier.testTag("auto_connect_switch_${saved.macAddress}")
                                            )
                                            Text(
                                                text = "Auto-Connect",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Switch(
                                                checked = saved.isActiveControlEnabled,
                                                onCheckedChange = { viewModel.toggleActiveControl(saved.macAddress, saved.customName, it) },
                                                modifier = Modifier.testTag("active_control_switch_${saved.macAddress}")
                                            )
                                            Text(
                                                text = "Active Control",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                        }
                                    }

                                    // Connect / Disconnect button
                                    val connectInteractionSource = remember(saved.macAddress) { MutableInteractionSource() }
                                    val isDisconnecting = connState == BleConnectionState.DISCONNECTING
                                    if (isConnected || isDisconnecting) {
                                        Button(
                                            onClick = { viewModel.disconnectDevice(saved.macAddress) },
                                            enabled = !isDisconnecting,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            shape = CircleShape,
                                            interactionSource = connectInteractionSource,
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("disconnect_saved_${saved.macAddress}")
                                                .joyfulPress(connectInteractionSource, enabled = !isDisconnecting)
                                        ) {
                                            Text(if (isDisconnecting) "Disconnecting..." else "Disconnect")
                                        }
                                    } else {
                                        Button(
                                            onClick = { viewModel.connectDevice(saved.macAddress) },
                                            enabled = !isConnecting,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            shape = CircleShape,
                                            interactionSource = connectInteractionSource,
                                            modifier = Modifier
                                                .height(44.dp)
                                                .testTag("connect_saved_${saved.macAddress}")
                                                .joyfulPress(connectInteractionSource, enabled = !isConnecting)
                                        ) {
                                            Text(if (isConnecting) "Connecting..." else "Connect")
                                        }
                                    }
                                }


                            }
                        }
                    }
                }

                // Section 2: Scanned/Discovered Devices (excluding saved devices)
                Text(
                    text = "Discovered Devices",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                )

                val unsavedScannedDevices = uiState.connectivity.scannedDevices.filter { scanned ->
                    savedDevices.none { it.macAddress == scanned.address }
                }

                if (unsavedScannedDevices.isEmpty() && uiState.coreControl.isDbLoaded) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "No new devices found in range. Click Scan to discover.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }
                } else {
                    unsavedScannedDevices.forEach { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.connectDevice(device.address) }
                                .testTag("scanned_device_card_${device.address}"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = "Discovered BLE Device",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = device.alias ?: device.name ?: "Unknown Device",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (device.isDuoCoSuspected) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "DuoCo",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        color = MaterialTheme.colorScheme.secondary,
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                val connectDiscoveredInteractionSource = remember(device.address) { MutableInteractionSource() }
                                val deviceConnState = uiState.connectivity.deviceConnectionStates[device.address] ?: BleConnectionState.DISCONNECTED
                                val isDeviceConnecting = deviceConnState == BleConnectionState.CONNECTING
                                Button(
                                    onClick = { viewModel.connectDevice(device.address) },
                                    enabled = !isDeviceConnecting,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary,
                                        contentColor = MaterialTheme.colorScheme.onSecondary
                                    ),
                                    interactionSource = connectDiscoveredInteractionSource,
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("connect_discovered_${device.address}")
                                        .joyfulPress(connectDiscoveredInteractionSource, enabled = !isDeviceConnecting)
                                ) {
                                    Text(if (isDeviceConnecting) "Connecting..." else "Connect", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
