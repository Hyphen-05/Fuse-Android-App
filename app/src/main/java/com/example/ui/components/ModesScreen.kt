package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BleConnectionState
import com.example.RgbControllerViewModel
import com.example.db.CustomMode

val COLOR_OPTIONS = listOf("Red", "Green", "Blue", "Cyan", "Yellow", "Pink", "Purple", "White", "Fire")

fun getColorValue(name: String): Color {
    return when (name) {
        "Red" -> Color(0xFFE53935)
        "Green" -> Color(0xFF43A047)
        "Blue" -> Color(0xFF1E88E5)
        "Cyan" -> Color(0xFF00ACC1)
        "Yellow" -> Color(0xFFFDD835)
        "Pink" -> Color(0xFFD81B60)
        "Purple" -> Color(0xFF8E24AA)
        "White" -> Color(0xFFFFFFFF)
        "Fire" -> Color(0xFFF4511E)
        else -> Color.Gray
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModesScreen(
    viewModel: RgbControllerViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val customModes by viewModel.customModes.collectAsState()
    val haptic = LocalHapticFeedback.current

    var selectedCategory by remember { mutableStateOf("All") }
    var modeToEdit by remember { mutableStateOf<CustomMode?>(null) }
    var showRenameCategoryDialog by remember { mutableStateOf(false) }
    var categoryToRename by remember { mutableStateOf("") }

    // Dynamic categories extracted from customModes in the database
    val categories = remember(customModes) {
        listOf("All") + customModes.map { it.category }.distinct()
    }

    // Keep selectedCategory valid if the active one got renamed or deleted
    LaunchedEffect(categories) {
        if (selectedCategory != "All" && selectedCategory !in categories) {
            selectedCategory = "All"
        }
    }

    // Filter modes based on selected category
    val filteredModes = remember(customModes, selectedCategory) {
        customModes.filter { mode ->
            selectedCategory == "All" || mode.category == selectedCategory
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {


        // --- Connection / Demo mode Alert Banner ---
        val isConnected = uiState.connectivity.connectionState == BleConnectionState.CONNECTED
        AnimatedVisibility(visible = !isConnected) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("modes_connection_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Device is disconnected. Simulating hardware command updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // --- Mode Speed Slider ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("mode_speed_card"),
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
                        text = "Effect Speed",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Text(
                        text = "${uiState.coreControl.modeSpeed}%",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                ExpressiveSlider(
                    value = uiState.coreControl.modeSpeed,
                    onValueChange = { viewModel.setModeSpeed(it) },
                    labelPrefix = "Speed",
                    activeColor = MaterialTheme.colorScheme.primary,
                    inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mode_speed_slider")
                )
            }
        }

        // --- Filter Chips & Rename Action ---
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Categories",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Only allow renaming if a specific category is selected
                if (selectedCategory != "All") {
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            categoryToRename = selectedCategory
                            showRenameCategoryDialog = true
                        },
                        modifier = Modifier.testTag("rename_category_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename Category",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rename Category", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Category Chips Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedCategory = category
                        },
                        label = {
                            Text(text = category)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("category_chip_$category")
                    )
                }
            }
        }

        // --- Grid of Custom Mode Items ---
        if (filteredModes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Category,
                        contentDescription = "No modes found",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No Modes",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 145.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("modes_grid")
            ) {
                items(filteredModes) { mode ->
                    val isActive = uiState.coreControl.modeIndex == mode.byteValue
                    CustomModeGridItem(
                        mode = mode,
                        isActive = isActive,
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.setMode(mode.byteValue)
                        },
                        onEdit = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            modeToEdit = mode
                        }
                    )
                }
            }
        }
    }

    // --- Edit Mode Dialog ---
    modeToEdit?.let { mode ->
        EditModeDialog(
            mode = mode,
            categories = categories.filter { it != "All" },
            onDismiss = { modeToEdit = null },
            onSave = { updatedMode ->
                viewModel.updateCustomMode(updatedMode)
                modeToEdit = null
            }
        )
    }

    // --- Rename Category Dialog ---
    if (showRenameCategoryDialog) {
        RenameCategoryDialog(
            currentName = categoryToRename,
            onDismiss = { showRenameCategoryDialog = false },
            onConfirm = { newName ->
                viewModel.renameCategory(categoryToRename, newName)
                selectedCategory = newName
                showRenameCategoryDialog = false
            }
        )
    }
}

@Composable
fun CustomModeGridItem(
    mode: CustomMode,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    val activeBorderColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        label = "active_border"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(135.dp)
            .testTag("mode_card_${mode.byteValue}")
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f) else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = activeBorderColor
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Hex representation & Arrow/Waveform Indicator & Edit Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = String.format("0x%02X", mode.byteValue),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,

                            fontSize = 10.sp
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Up/Down display
                    when (mode.direction) {
                        "up" -> {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "Direction Up",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        "down" -> {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "Direction Down",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Edit Icon
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("edit_mode_btn_${mode.byteValue}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit mode properties",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Body: Dynamic color dots, Name & Category
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Included Colors Row (small circular color dots)
                val activeColors = remember(mode.colors) {
                    mode.colors.split(",").filter { it.isNotEmpty() }
                }

                if (activeColors.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        activeColors.forEach { colorName ->
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(getColorValue(colorName))
                                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                            )
                        }
                    }
                } else {
                    // Default fallback visual category strip
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                Text(
                    text = mode.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 16.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = mode.category,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EqualizerWaveformMini() {
    val infiniteTransition = rememberInfiniteTransition(label = "eq_mini")
    
    val height1 by infiniteTransition.animateFloat(
        initialValue = 4f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h1"
    )

    val height2 by infiniteTransition.animateFloat(
        initialValue = 12f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "h2"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(14.dp)
    ) {
        Box(modifier = Modifier.width(2.dp).height(height1.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Box(modifier = Modifier.width(2.dp).height(height2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun EditModeDialog(
    mode: CustomMode,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomMode) -> Unit
) {
    var name by remember { mutableStateOf(mode.name) }
    var categoryInput by remember { mutableStateOf(mode.category) }
    var direction by remember { mutableStateOf(mode.direction) }
    var selectedColors by remember {
        mutableStateOf(mode.colors.split(",").filter { it.isNotEmpty() }.toSet())
    }

    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Mode #${mode.byteValue}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Name Input
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Mode Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_mode_name_field")
                )

                // Category Selection with Dropdown Suggestions
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = categoryInput,
                        onValueChange = { 
                            categoryInput = it
                            dropdownExpanded = true
                        },
                        label = { Text("Category") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = {
                            IconButton(onClick = { dropdownExpanded = !dropdownExpanded }) {
                                Icon(
                                    imageVector = if (dropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Expand category suggestion list"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_mode_category_field")
                    )

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = {
                                    categoryInput = cat
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Direction selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Direction Animation",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("up" to "Up", "down" to "Down", "none" to "None").forEach { (dirValue, dirLabel) ->
                            val isSelected = direction == dirValue
                            OutlinedButton(
                                onClick = { direction = dirValue },
                                shape = CircleShape,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("direction_btn_$dirValue")
                            ) {
                                Text(
                                    text = dirLabel,
                                    maxLines = 1,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Colors included multi-selector
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Colors Included",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Display list of circles in simple flow-like columns
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chunks = COLOR_OPTIONS.chunked(3)
                        chunks.forEach { rowColors ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowColors.forEach { col ->
                                    val isIncluded = col in selectedColors
                                    val colorValue = getColorValue(col)
                                    
                                    Surface(
                                        onClick = {
                                            selectedColors = if (isIncluded) {
                                                selectedColors - col
                                            } else {
                                                selectedColors + col
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isIncluded) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (isIncluded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("color_select_$col")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .background(colorValue)
                                                    .border(0.5.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                            )
                                            Text(
                                                text = col,
                                                fontSize = 11.sp,
                                                fontWeight = if (isIncluded) FontWeight.Bold else FontWeight.Normal,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val saveInteractionSource = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    val updatedMode = mode.copy(
                        name = name.trim().ifEmpty { "Mode #${mode.byteValue}" },
                        category = categoryInput.trim().ifEmpty { "Special" },
                        direction = direction,
                        colors = selectedColors.joinToString(",")
                    )
                    onSave(updatedMode)
                },
                interactionSource = saveInteractionSource,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("save_mode_changes_btn")
                    .joyfulPress(saveInteractionSource),
                shape = CircleShape
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RenameCategoryDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename Category",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "This will update all dynamic effects belonging to this category.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_category_field")
                )
            }
        },
        confirmButton = {
            val renameInteractionSource = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    if (name.trim().isNotEmpty()) {
                        onConfirm(name.trim())
                    }
                },
                interactionSource = renameInteractionSource,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("confirm_rename_category_btn")
                    .joyfulPress(renameInteractionSource),
                shape = CircleShape
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
