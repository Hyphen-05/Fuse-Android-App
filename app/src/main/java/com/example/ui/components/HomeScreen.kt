package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ActiveDeviceState
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.RgbUiState
import com.example.db.SavedDevice

@Composable
fun HomeScreen(
    viewModel: RgbControllerViewModel,
    permissionsGranted: Boolean,
    permissionsBlocked: Boolean,
    onGrantPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()

    val activeComposeColor = Color(uiState.coreControl.red, uiState.coreControl.green, uiState.coreControl.blue)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Permissions Alert Card (when in Real Mode & no permissions) ---
        if (!permissionsGranted && !uiState.coreControl.isDemoMode) {
            item {
                val grantPermissionsInteractionSource = remember { MutableInteractionSource() }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(20.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Bluetooth Permissions Required",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            )
                        }
                        Text(
                            text = if (permissionsBlocked) {
                                "Bluetooth permissions were denied, and Android won't ask again from " +
                                    "inside the app. Turn them on in the app's system settings to scan " +
                                    "for light strips."
                            } else {
                                "To scan and connect to physical DuoCo RGB light strips, this app " +
                                    "requires local Bluetooth permissions."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onGrantPermissions,
                                interactionSource = grantPermissionsInteractionSource,
                                modifier = Modifier
                                    .height(44.dp)
                                    .joyfulPress(grantPermissionsInteractionSource),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = CircleShape
                            ) {
                                Text(if (permissionsBlocked) "Open App Settings" else "Grant Permissions")
                            }
                        }
                    }
                }
            }
        }

        // --- Connected Device Tiles ---
        val connectedAddresses = uiState.connectivity.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }.keys.toList()
        if (connectedAddresses.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connection_status_card_empty"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outline)
                            )
                            Text(
                                text = "Disconnected",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                        Text(
                            text = "Select a compatible BLE RGB device from the Devices tab to connect and control.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        } else {
            item {
                if (connectedAddresses.size <= 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        connectedAddresses.forEach { address ->
                            DeviceTile(
                                address = address,
                                viewModel = viewModel,
                                uiState = uiState,
                                savedDevices = savedDevices,
                                activeComposeColor = activeComposeColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) {
                        items(connectedAddresses) { address ->
                            DeviceTile(
                                address = address,
                                viewModel = viewModel,
                                uiState = uiState,
                                savedDevices = savedDevices,
                                activeComposeColor = activeComposeColor,
                                modifier = Modifier.width(160.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- Control Deck (Only displayed when CONNECTED) ---
        if (uiState.connectivity.connectionState == BleConnectionState.CONNECTED) {
            val controlsInert = !uiState.coreControl.isPowerOn

            if (controlsInert) {
                item {
                    PowerOffHintCard(onTurnOn = { viewModel.setPower(true) })
                }
            }

            // CCT Warmth Slider Card (in place of power status card)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inertWhen(controlsInert)
                        .testTag("cct_control_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        val warmthPercentage = uiState.coreControl.warmth
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Color Temperature (CCT)",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                            Text(
                                text = "$warmthPercentage%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,

                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        val warmthGradient = remember {
                            // Generate a perceptually matched gradient track to align with the non-linear slider mapping
                            val colors = (0..10).map { step ->
                                val pct = step * 10
                                val k = ColorUtils.warmthToKelvin(pct)
                                val rgb = ColorUtils.convertKelvinToRgb(k)
                                Color(rgb[0], rgb[1], rgb[2])
                            }
                            androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                        }

                        ExpressiveSlider(
                            value = warmthPercentage,
                            onValueChange = { viewModel.setWarmth(it) },
                            labelPrefix = "Warmth",
                            activeColor = Color.Transparent,
                            inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = Color.White,
                            trackBrush = warmthGradient,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("warmth_slider")
                        )
                    }
                }
            }
            // Brightness Slider Card (below power card)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inertWhen(controlsInert)
                        .testTag("brightness_control_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dimming",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = "${uiState.coreControl.brightness}%",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,

                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        ExpressiveSlider(
                            value = uiState.coreControl.brightness,
                            onValueChange = { viewModel.setBrightness(it) },
                            labelPrefix = "Brightness",
                            activeColor = MaterialTheme.colorScheme.primary,
                            inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("brightness_slider")
                        )
                    }
                }
            }

            // Color Preview Card (existing colour card)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .inertWhen(controlsInert)
                        .testTag("control_deck_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val paletteColors = listOf(
                                Color.Red, Color.Green, Color.Blue,
                                Color.Yellow, Color.Cyan, Color(0xFF6A0DAD),
                                Color.White, Color(0xFFFF1493), Color(0xFFFFA500), Color(0xFF00FF7F)
                            )
                            val leftChips = paletteColors.take(5)
                            val rightChips = paletteColors.drop(5)

                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                leftChips.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (activeComposeColor == color) 3.dp else 1.dp,
                                                color = if (activeComposeColor == color) MaterialTheme.colorScheme.onSurface else Color.White,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                viewModel.setColor(
                                                    (color.red * 255).toInt(),
                                                    (color.green * 255).toInt(),
                                                    (color.blue * 255).toInt()
                                                )
                                            }
                                            .testTag("palette_color_${(color.red * 255).toInt()}_${(color.green * 255).toInt()}_${(color.blue * 255).toInt()}")
                                    )
                                }
                            }

                            // Interactive Canvas Color Wheel
                            ColorWheel(
                                selectedColor = activeComposeColor,
                                onColorChanged = { r, g, b ->
                                    viewModel.setColor(r, g, b)
                                },
                                modifier = Modifier
                                    .size(200.dp)
                                    .testTag("interactive_color_wheel")
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                rightChips.forEach { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (activeComposeColor == color) 3.dp else 1.dp,
                                                color = if (activeComposeColor == color) MaterialTheme.colorScheme.onSurface else Color.White,
                                                shape = CircleShape
                                            )
                                            .clickable {
                                                viewModel.setColor(
                                                    (color.red * 255).toInt(),
                                                    (color.green * 255).toInt(),
                                                    (color.blue * 255).toInt()
                                                )
                                            }
                                            .testTag("palette_color_${(color.red * 255).toInt()}_${(color.green * 255).toInt()}_${(color.blue * 255).toInt()}")
                                    )
                                }
                            }
                        }

                        // --- RGB channel sliders, under the wheel ---
                        // Same card, because they are the same act as picking on the wheel: the
                        // wheel is for choosing a colour by eye, these are for saying exactly which
                        // one. Both write through viewModel.setColor, so each follows the other.
                        Spacer(modifier = Modifier.height(16.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RgbChannelSlider(
                                label = "Red",
                                value = uiState.coreControl.red,
                                channelColor = Color.Red,
                                onValueChange = {
                                    viewModel.setColor(it, uiState.coreControl.green, uiState.coreControl.blue)
                                },
                                testTag = "red_slider"
                            )
                            RgbChannelSlider(
                                label = "Green",
                                value = uiState.coreControl.green,
                                channelColor = Color.Green,
                                onValueChange = {
                                    viewModel.setColor(uiState.coreControl.red, it, uiState.coreControl.blue)
                                },
                                testTag = "green_slider"
                            )
                            RgbChannelSlider(
                                label = "Blue",
                                value = uiState.coreControl.blue,
                                channelColor = Color.Blue,
                                onValueChange = {
                                    viewModel.setColor(uiState.coreControl.red, uiState.coreControl.green, it)
                                },
                                testTag = "blue_slider"
                            )
                        }
                    }
                }
            }
        } // End of connectionState == CONNECTED

    }
}

/**
 * One RGB channel on the Home colour card, built from the same pieces as Dimming and CCT: a
 * title/value row above an [ExpressiveSlider].
 *
 * The two differences from those are the range and the track. It runs 0-255 rather than 0-100
 * because a colour channel *is* a byte and quantising it to a hundred steps would make some values
 * unreachable, and the track is a black-to-channel gradient for the same reason CCT's is a warmth
 * gradient — the control shows what it does.
 */
@Composable
private fun RgbChannelSlider(
    label: String,
    value: Int,
    channelColor: Color,
    onValueChange: (Int) -> Unit,
    testTag: String
) {
    val trackGradient = remember(channelColor) {
        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black, channelColor))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
        }

        ExpressiveSlider(
            value = value,
            onValueChange = onValueChange,
            labelPrefix = label,
            maxValue = 255,
            activeColor = Color.Transparent,
            inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = Color.White,
            trackBrush = trackGradient,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
fun DeviceTile(
    address: String,
    viewModel: RgbControllerViewModel,
    uiState: RgbUiState,
    savedDevices: List<SavedDevice>,
    activeComposeColor: Color,
    modifier: Modifier = Modifier
) {
    val savedDev = savedDevices.find { it.macAddress == address }
    val scannedDev = uiState.connectivity.scannedDevices.find { it.address == address }
    val displayName = savedDev?.customName ?: scannedDev?.alias ?: scannedDev?.name ?: "DuoCo Light"
    val isActiveControlled = savedDev?.isActiveControlEnabled ?: true
    val hapticFeedback = LocalHapticFeedback.current
    val hapticType = rememberExpressiveHapticType()

    val deviceState = uiState.connectivity.deviceStatesMap[address] ?: ActiveDeviceState(
        activeFeatureName = uiState.coreControl.activeFeatureName,
        red = uiState.coreControl.red,
        green = uiState.coreControl.green,
        blue = uiState.coreControl.blue,
        warmth = uiState.coreControl.warmth,
        modeIndex = uiState.coreControl.modeIndex,
        brightness = uiState.coreControl.brightness,
        isPowerOn = uiState.coreControl.isPowerOn
    )
    val deviceColor = Color(deviceState.red, deviceState.green, deviceState.blue)

    Card(
        modifier = modifier
            .testTag("device_tile_$address")
            .clip(RoundedCornerShape(24.dp))
            .clickable {
                hapticFeedback.performHapticFeedback(hapticType)
                viewModel.toggleActiveControl(address, displayName, !isActiveControlled)
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActiveControlled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(
            width = if (isActiveControlled) 2.dp else 1.dp,
            color = if (isActiveControlled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isActiveControlled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                }

                if (deviceState.activeFeatureName == "Colour" || deviceState.activeFeatureName == "CCT") {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(deviceColor)
                            .border(1.dp, Color.White, CircleShape)
                    )
                } else {
                    val icon = when {
                        deviceState.activeFeatureName.contains("Music", ignoreCase = true) ||
                        deviceState.activeFeatureName.contains("Visualiser", ignoreCase = true) ||
                        deviceState.activeFeatureName.contains("Audio", ignoreCase = true) -> Icons.Default.MusicNote
                        deviceState.activeFeatureName.contains("Ambiance", ignoreCase = true) ||
                        deviceState.activeFeatureName.contains("Ambience", ignoreCase = true) -> Icons.Default.FilterAlt
                        else -> Icons.Default.Palette
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Mode: ${deviceState.activeFeatureName}",
                        modifier = Modifier.size(20.dp),
                        tint = if (isActiveControlled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isActiveControlled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = deviceState.activeFeatureName,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActiveControlled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Shown on Home when a device is connected but power is off, above the dimmed control deck.
 *
 * The deck used to be hidden outright in this state, which left Home nearly blank and gave no clue
 * that the power button in the top bar is what brings the controls back.
 */
@Composable
private fun PowerOffHintCard(onTurnOn: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("power_off_hint_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Power is off",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Turn the lights on to use the controls below.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
            FilledTonalButton(
                onClick = onTurnOn,
                shape = CircleShape,
                modifier = Modifier.testTag("power_on_hint_btn")
            ) {
                Text("Turn on")
            }
        }
    }
}
