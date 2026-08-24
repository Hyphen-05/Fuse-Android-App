package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.RgbControllerViewModel
import com.example.ambiance.AmbianceCaptureState
import com.example.domain.model.AppScene

/**
 * Saved scenes: the grid, the create tile, and every dialog that hangs off them.
 *
 * Lifted out of `HomeScreen` on 2026-08-24 and moved to the Scenes tab (the former Modes tab). It
 * is one composable rather than pieces the host assembles because the grid and its four dialogs
 * share the same handful of selection variables — splitting them would mean hoisting all of that
 * into whichever screen hosts it, which is exactly the coupling that kept this on Home.
 *
 * Not a `LazyColumn` any more: it now sits inside the Scenes tab's `Column`, above the modes grid,
 * so it lays out as ordinary rows. The scene list is small and bounded by what the user has saved,
 * so nothing is lost by dropping the laziness.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScenesSection(
    viewModel: RgbControllerViewModel,
    onStartAmbianceCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val savedDevices by viewModel.savedDevices.collectAsState()
    val scenes by viewModel.scenes.collectAsState()

    var showCreateSceneDialog by rememberSaveable { mutableStateOf(false) }
    var selectedSceneId by rememberSaveable { mutableStateOf<String?>(null) }
    var sceneToDelete by remember { mutableStateOf<AppScene?>(null) }
    var sceneToRename by remember { mutableStateOf<AppScene?>(null) }
    var sceneToEdit by remember { mutableStateOf<AppScene?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var showMenuForSceneId by remember { mutableStateOf<String?>(null) }

    fun getSceneDescription(scene: AppScene): String {
        val state = scene.state
        val modeName = when (state?.groupASelection) {
            "Colour" -> "Solid Colour"
            "CCT" -> "Color Temp (CCT)"
            "HardwareMode" -> "LED Animation"
            "Audio" -> "Audio Visualizer"
            "Ambiance" -> "Video Ambiance"
            else -> "Settings Only"
        }
        val brightnessStr = if (state?.brightness != null) ", ${state.brightness}%" else ""
        return "$modeName$brightnessStr"
    }

    if (showCreateSceneDialog) {
        val currentGlobalMode = if (!uiState.coreControl.isPowerOn) "Power Off"
            else when {
                uiState.coreControl.activeFeatureName == "Colour" -> "Colour"
                uiState.coreControl.activeFeatureName == "CCT" -> "CCT"
                uiState.coreControl.activeFeatureName.startsWith("Audio") || uiState.coreControl.activeFeatureName.startsWith("LED Visualiser") -> "Audio"
                uiState.coreControl.activeFeatureName.startsWith("Ambiance") -> "Ambiance"
                else -> "HardwareMode"
            }
        SceneCreationDialog(
            devices = savedDevices,
            currentGlobalMode = currentGlobalMode,
            availableScenes = scenes,
            onDismissRequest = { showCreateSceneDialog = false },
            onSaveScene = { name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds ->
                viewModel.saveScene(name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds)
                showCreateSceneDialog = false
            }
        )
    }

    if (sceneToEdit != null) {
        val currentGlobalMode = sceneToEdit?.state?.groupASelection ?: ""
        SceneCreationDialog(
            devices = savedDevices,
            currentGlobalMode = currentGlobalMode,
            availableScenes = scenes,
            sceneToEdit = sceneToEdit,
            onDismissRequest = { sceneToEdit = null },
            onSaveScene = { name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds ->
                viewModel.updateScene(sceneToEdit!!.id, name, groupA, includeBrightness, includeModeSpeed, targetScope, macList, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings, chainedSceneId, chainedSceneDelaySeconds)
                sceneToEdit = null
            }
        )
    }

    if (sceneToDelete != null) {
        AlertDialog(
            onDismissRequest = { sceneToDelete = null },
            title = { Text("Delete Scene", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = { Text("Are you sure you want to delete the scene '${sceneToDelete?.name}'?") },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            confirmButton = {
                Button(
                    onClick = {
                        sceneToDelete?.let {
                            viewModel.deleteScene(it.id)
                        }
                        sceneToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.height(44.dp),
                    shape = CircleShape
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { sceneToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (sceneToRename != null) {
        AlertDialog(
            onDismissRequest = { sceneToRename = null },
            title = { Text("Rename Scene", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new name for scene '${sceneToRename?.name}':", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        singleLine = true,
                        placeholder = { Text("New Scene Name") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("rename_scene_input")
                    )
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = renameInputText.trim()
                        if (trimmed.isNotEmpty()) {
                            sceneToRename?.let {
                                viewModel.renameScene(it.id, trimmed)
                            }
                            sceneToRename = null
                        }
                    },
                    enabled = renameInputText.isNotBlank(),
                    modifier = Modifier.height(44.dp),
                    shape = CircleShape
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { sceneToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Scenes",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val gridItems = scenes + listOf(null)
        gridItems.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { scene ->
                    if (scene != null) {
                        val chipInteractionSource = remember { MutableInteractionSource() }
                        val isSelected = selectedSceneId == scene.id
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .combinedClickable(
                                    interactionSource = chipInteractionSource,
                                    indication = androidx.compose.foundation.LocalIndication.current,
                                    onClick = {
                                        selectedSceneId = scene.id
                                        viewModel.applyScene(scene)
                                        if (scene.state.groupASelection == "Ambiance" &&
                                            scene.state.ambianceIsOn == true &&
                                            !AmbianceCaptureState.isActive.value) {
                                            onStartAmbianceCapture()
                                        }
                                    },
                                    onLongClick = {
                                        showMenuForSceneId = scene.id
                                    }
                                )
                                .testTag("scene_chip_${scene.id}")
                                .joyfulPress(chipInteractionSource),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = scene.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = getSceneDescription(scene),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showMenuForSceneId == scene.id,
                                    onDismissRequest = { showMenuForSceneId = null },
                                    shape = RoundedCornerShape(16.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Edit", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) },
                                        onClick = {
                                            showMenuForSceneId = null
                                            sceneToEdit = scene
                                        },
                                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = "Edit Scene", modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) },
                                        onClick = {
                                            showMenuForSceneId = null
                                            sceneToRename = scene
                                            renameInputText = scene.name
                                        },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Rename Scene", modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenuForSceneId = null
                                            sceneToDelete = scene
                                        },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = "Delete Scene", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                            }
                        }
                    } else {
                        val addInteractionSource = remember { MutableInteractionSource() }
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable(
                                    interactionSource = addInteractionSource,
                                    indication = androidx.compose.foundation.LocalIndication.current
                                ) {
                                    showCreateSceneDialog = true
                                }
                                .testTag("create_scene_chip_home")
                                .joyfulPress(addInteractionSource),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Create Scene",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Create",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "New Scene",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
                if (rowItems.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
