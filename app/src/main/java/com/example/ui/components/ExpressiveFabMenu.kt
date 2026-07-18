package com.example.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveFabMenu(
    isScanning: Boolean,
    onAddPreset: () -> Unit,
    onToggleScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingActionButton(
        onClick = onToggleScan,
        shape = RoundedCornerShape(16.dp),
        containerColor = if (isScanning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        contentColor = if (isScanning) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.size(56.dp).testTag("main_expressive_fab")
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = if (isScanning) "Stop Scan" else "Scan Devices",
            modifier = Modifier.size(24.dp)
        )
    }
}
