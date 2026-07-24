package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.RgbControllerViewModel
import com.example.core.modecapture.ReferenceCapture
import com.example.presentation.modecapture.ModeCapturePhase
import com.example.presentation.modecapture.ModeCaptureViewModel

/**
 * Experimental full-screen tool: cycles through every mode except Auto Play while a camera
 * records multi-position color samples along the strip, for later offline analysis of the
 * real mode metadata (name/category/direction/colors currently shown in ModesScreen are guessed,
 * not measured — see CLAUDE.md). Reached from Settings > Experimental.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModeCaptureScreen(viewModel: RgbControllerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Fully-qualified to avoid resolving to the `viewModel: RgbControllerViewModel` parameter
    // above, which shadows the top-level `viewModel()` composable factory function by name.
    val modeCaptureViewModel: ModeCaptureViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val uiState by modeCaptureViewModel.uiState.collectAsStateWithLifecycle()
    val customModes by viewModel.customModes.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            modeCaptureViewModel.startCamera(lifecycleOwner, previewView)
        }
    }

    val runActive = uiState.phase == ModeCapturePhase.Running || uiState.phase == ModeCapturePhase.Paused
    var showDiscardConfirm by remember { mutableStateOf(false) }

    fun requestClose() {
        if (runActive) showDiscardConfirm = true else onClose()
    }

    BackHandler { requestClose() }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard capture run?") },
            text = { Text("A mode capture is still in progress. Leaving now discards anything not yet exported.") },
            confirmButton = {
                TextButton(onClick = {
                    modeCaptureViewModel.stop()
                    showDiscardConfirm = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mode Capture") },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (!hasCameraPermission) {
                CameraPermissionRequestContent(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
            } else {
                when (uiState.phase) {
                    ModeCapturePhase.Setup, ModeCapturePhase.LockingCamera -> SetupPhaseContent(
                        previewView = previewView,
                        isLocking = uiState.phase == ModeCapturePhase.LockingCamera,
                        errorMessage = uiState.errorMessage,
                        onEndpointsChosen = { start, end -> modeCaptureViewModel.onStripEndpointsSet(start, end) },
                        onLockSettings = { modeCaptureViewModel.lockCameraSettings(previewView) }
                    )

                    ModeCapturePhase.Reference -> ReferencePhaseContent(
                        previewView = previewView,
                        reference = uiState.reference,
                        onShowReferenceColor = {
                            viewModel.setPower(true)
                            viewModel.setColor(255, 255, 255)
                            viewModel.setBrightness(100)
                        },
                        onCapture = { modeCaptureViewModel.captureReference(255, 255, 255) },
                        onRetake = { modeCaptureViewModel.retakeReference() },
                        onConfirm = {
                            modeCaptureViewModel.startCycle(customModes) { byteValue -> viewModel.setMode(byteValue) }
                        }
                    )

                    ModeCapturePhase.Running, ModeCapturePhase.Paused -> RunningPhaseContent(
                        previewView = previewView,
                        modeName = uiState.currentModeName,
                        modeIndex = uiState.currentModeIndex,
                        totalCount = uiState.totalCount,
                        elapsedMsInMode = uiState.elapsedMsInMode,
                        isPaused = uiState.phase == ModeCapturePhase.Paused,
                        onPause = { modeCaptureViewModel.pause() },
                        onResume = { modeCaptureViewModel.resume() },
                        onStop = { modeCaptureViewModel.stop() },
                        onExportSoFar = { shareExport(context, modeCaptureViewModel.export()) }
                    )

                    ModeCapturePhase.Done -> DonePhaseContent(
                        completedCount = uiState.completedCount,
                        totalCount = uiState.totalCount,
                        onExport = { shareExport(context, modeCaptureViewModel.export()) },
                        onClose = onClose
                    )
                }
            }
        }
    }
}

private fun shareExport(context: Context, uri: Uri?) {
    if (uri == null) return
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share Mode Capture Data"))
}

@Composable
private fun CameraPermissionRequestContent(onRequest: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Camera permission is required to capture mode data.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRequest) { Text("Grant Camera Permission") }
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    previewView: PreviewView,
    tapPoints: List<Offset>,
    onTap: ((Offset, Size) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .then(
                if (onTap != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onTap(offset, Size(size.width.toFloat(), size.height.toFloat()))
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (tapPoints.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                tapPoints.forEach { point -> drawCircle(color = Color.Yellow, radius = 12f, center = point) }
                if (tapPoints.size == 2) {
                    drawLine(color = Color.Yellow, start = tapPoints[0], end = tapPoints[1], strokeWidth = 4f)
                }
            }
        }
    }
}

@Composable
private fun SetupPhaseContent(
    previewView: PreviewView,
    isLocking: Boolean,
    errorMessage: String?,
    onEndpointsChosen: (Offset, Offset) -> Unit,
    onLockSettings: () -> Unit
) {
    var tapPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Frame the LED strip in view, then tap its two physical ends (start, then end).",
            style = MaterialTheme.typography.bodyMedium
        )
        CameraPreviewWithOverlay(
            previewView = previewView,
            tapPoints = tapPoints,
            onTap = { offset, size ->
                tapPoints = if (tapPoints.size >= 2) listOf(offset) else tapPoints + offset
                if (tapPoints.size == 2 && size.width > 0 && size.height > 0) {
                    val start = Offset(tapPoints[0].x / size.width, tapPoints[0].y / size.height)
                    val end = Offset(tapPoints[1].x / size.width, tapPoints[1].y / size.height)
                    onEndpointsChosen(start, end)
                }
            }
        )
        if (errorMessage != null) {
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onLockSettings,
            enabled = tapPoints.size == 2 && !isLocking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLocking) "Locking..." else "Lock Camera Settings")
        }
    }
}

@Composable
private fun ReferencePhaseContent(
    previewView: PreviewView,
    reference: ReferenceCapture?,
    onShowReferenceColor: () -> Unit,
    onCapture: () -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit
) {
    LaunchedEffect(Unit) { onShowReferenceColor() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "The strip is now showing a solid white reference. Capture it before starting the run.",
            style = MaterialTheme.typography.bodyMedium
        )
        CameraPreviewWithOverlay(previewView = previewView, tapPoints = emptyList())

        if (reference == null) {
            Button(onClick = onCapture, modifier = Modifier.fillMaxWidth()) {
                Text("Capture Reference")
            }
        } else {
            Text(
                text = "Measured average across ${reference.measuredSamples.size} positions captured.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Start Capture Run") }
            }
        }
    }
}

@Composable
private fun RunningPhaseContent(
    previewView: PreviewView,
    modeName: String,
    modeIndex: Int,
    totalCount: Int,
    elapsedMsInMode: Long,
    isPaused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExportSoFar: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CameraPreviewWithOverlay(previewView = previewView, tapPoints = emptyList())

        Text(
            text = "Mode ${modeIndex + 1}/$totalCount: $modeName",
            style = MaterialTheme.typography.titleMedium
        )
        LinearProgressIndicator(
            progress = (elapsedMsInMode / 10_000f).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = if (isPaused) onResume else onPause, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isPaused) "Resume" else "Pause")
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(imageVector = Icons.Default.Stop, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Stop")
            }
        }

        if (isPaused) {
            Button(onClick = onExportSoFar, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Captured So Far")
            }
        }
    }
}

@Composable
private fun DonePhaseContent(
    completedCount: Int,
    totalCount: Int,
    onExport: () -> Unit,
    onClose: () -> Unit
) {
    LaunchedEffect(Unit) { onExport() }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Captured $completedCount of $totalCount modes.",
            style = MaterialTheme.typography.titleMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Again")
            }
            Button(onClick = onClose) { Text("Close") }
        }
    }
}
