package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.db.SavedDevice

/**
 * The four multi-device visualiser layouts, as ids stored in `SavedDevice.deviceRole`.
 *
 * "HueSplit" is the odd one out: it is not a role itself but a *pattern* of roles — the first
 * device stays Mirror and the others become HueOffset, spread around the wheel. See
 * [com.example.RgbControllerViewModel.applyVisualizerRoleLayout].
 */
val VISUALIZER_LAYOUTS = listOf(
    "Mirror" to "All in sync",
    "HueSplit" to "Complementary colours",
    "AlternatingFlash" to "Beats alternate between strips",
    "BandSplit" to "One takes bass, one the highs"
)

private val LAYOUT_LABELS = mapOf(
    "Mirror" to "Mirror",
    "HueSplit" to "Hue Split",
    "AlternatingFlash" to "Alternate",
    "BandSplit" to "Band Split"
)

/**
 * Works out which layout the saved roles currently add up to, or null if they don't match any (a
 * hand-set combination from an older build, for instance).
 */
fun currentVisualizerLayout(devices: List<SavedDevice>): String? {
    val roles = devices.map { it.deviceRole }
    if (roles.isEmpty()) return null
    // "BandSplit" without a half is what older builds wrote; it still renders (by list position)
    // so it still has to read as the Band Split layout.
    val bandSplitRoles = setOf("BandSplitLow", "BandSplitHigh", "BandSplit")
    return when {
        roles.all { it == "Mirror" } -> "Mirror"
        roles.all { it == "AlternatingFlash" } -> "AlternatingFlash"
        roles.all { it in bandSplitRoles } -> "BandSplit"
        roles.first() == "Mirror" && roles.drop(1).all { it == "HueOffset" } -> "HueSplit"
        else -> null
    }
}

/**
 * Whether swapping would actually change anything.
 *
 * Mirror and Alternate give every strip the same part to play — Alternate rotates the flash through
 * all of them — so there is nothing to trade, and offering the action would be a lie.
 */
fun rolesAreSwappable(devices: List<SavedDevice>): Boolean =
    devices.size >= 2 && devices.map { it.deviceRole to it.hueOffsetDegrees }.distinct().size > 1

/**
 * What each strip is actually doing under the current roles, in the order the visualiser assigns
 * them — the same ordering `publishAudioDspResult` uses, so "Bass" here is the strip really getting
 * the bass.
 *
 * This is what makes Swap Roles legible: the button changes nothing visible until music is playing,
 * so without a readout there is no way to tell it did anything.
 */
fun deviceRoleSummaries(devices: List<SavedDevice>): List<Pair<SavedDevice, String>> {
    // Legacy "BandSplit" rows still take their half from list position; everything written since
    // carries its half in the role itself, which is what makes the labels move on a swap.
    val legacyBandSplitOrder = devices.filter { it.deviceRole == "BandSplit" }.map { it.macAddress }
    return devices.map { device ->
        val label = when (device.deviceRole) {
            "HueOffset" -> "Hue +${device.hueOffsetDegrees.toInt()}°"
            "BandSplitLow" -> "Bass"
            "BandSplitHigh" -> "Mids & highs"
            "BandSplit" ->
                if (legacyBandSplitOrder.indexOf(device.macAddress) % 2 == 0) "Bass" else "Mids & highs"
            // Every strip takes a turn, so none of them is "the first one" for longer than a beat.
            "AlternatingFlash" -> "Alternating"
            else -> "In sync"
        }
        device to label
    }
}

/**
 * How the visualiser is spread across several strips, as one choice for the whole setup.
 *
 * This replaces the per-device "Visualizer Role" chips that used to sit on each saved device's card
 * in the Devices tab: the roles are only meaningful in combination, and setting them one device at
 * a time made it easy to land on a set that does nothing (two strips both on Band Split's bass
 * half, say). Swapping which strip plays which part lives in Settings.
 */
@Composable
fun VisualizerRoleLayoutCard(
    controlledDevices: List<SavedDevice>,
    onSelectLayout: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selected = currentVisualizerLayout(controlledDevices)
    val singleDevice = controlledDevices.size < 2
    val haptic = LocalHapticFeedback.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("visualizer_layout_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Across Your Strips",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Text(
                text = if (singleDevice) {
                    "Connect a second strip to spread the visualiser across them."
                } else {
                    VISUALIZER_LAYOUTS.firstOrNull { it.first == selected }?.second
                        ?: "Custom per-device roles"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // One scrolling row of natural-width chips, matching the category chips on the Modes
            // tab. Laying them out two-per-row with weight(1f) stretched each chip to half the
            // screen, which reads as four buttons rather than as a set of chips.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(VISUALIZER_LAYOUTS) { (layout, _) ->
                    FilterChip(
                        selected = selected == layout,
                        enabled = !singleDevice,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectLayout(layout)
                        },
                        label = { Text(text = LAYOUT_LABELS[layout] ?: layout) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("visualizer_layout_chip_$layout")
                    )
                }
            }
        }
    }
}
