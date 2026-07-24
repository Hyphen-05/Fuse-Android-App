package com.example.presentation.modecapture

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.core.modecapture.CaptureFrame
import com.example.core.modecapture.ModeCaptureExport
import com.example.core.modecapture.ModeCaptureExporter
import com.example.core.modecapture.ModeCaptureRecord
import com.example.core.modecapture.ReferenceCapture
import com.example.core.modecapture.modesToCapture
import com.example.db.CustomMode
import com.example.hardware.camera.ModeCaptureCameraSource
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MODE_HOLD_MS = 10_000L
private const val TICK_MS = 250L
private const val DEFAULT_SAMPLE_POSITION_COUNT = 24

enum class ModeCapturePhase { Setup, LockingCamera, Reference, Running, Paused, Done }

data class ModeCaptureUiState(
    val phase: ModeCapturePhase = ModeCapturePhase.Setup,
    val cameraLocked: Boolean = false,
    val currentModeIndex: Int = 0,
    val currentModeName: String = "",
    val elapsedMsInMode: Long = 0L,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val reference: ReferenceCapture? = null,
    val exportedUri: Uri? = null,
    val errorMessage: String? = null
)

/**
 * Self-contained Compose-scoped ViewModel for the Mode Capture experimental feature — deliberately
 * NOT wired into RgbUiState/RgbIntent/the reducer chain (see plan doc: nothing else in the app
 * observes this progress, nothing persists to prefs/DB, and it's fundamentally CameraX/Activity-
 * lifecycle-bound like AmbianceCaptureService's MediaProjection flow). Reaches into the shared
 * RgbControllerViewModel only via the `setMode`/`setColor`/`setPower` callbacks passed in by the
 * caller, and the `customModes` list passed to [startCycle].
 *
 * Retained via viewModel() — survives configuration change, not process death. Upgrading to
 * persisted state is out of scope (see plan doc's "wiggle room" note on pause/resume persistence).
 */
class ModeCaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraSource = ModeCaptureCameraSource(application)

    private val _uiState = MutableStateFlow(ModeCaptureUiState())
    val uiState: StateFlow<ModeCaptureUiState> = _uiState.asStateFlow()

    private var cycleJob: Job? = null
    private var pausedAtElapsedRealtimeMs: Long = 0L
    private var samplePositionCount: Int = DEFAULT_SAMPLE_POSITION_COUNT

    private var referenceCapture: ReferenceCapture? = null

    // Buffered records for modes that finished their full 10s hold.
    private val buffer = mutableListOf<ModeCaptureRecord>()

    // The mode currently being captured (may be partial if paused/stopped mid-hold).
    private var currentModeByteValue: Int = 0
    private var currentModeName: String = ""
    private var currentModeCategory: String = ""
    private var currentModeFrames: MutableList<CaptureFrame> = mutableListOf()
    private var currentModeInProgress: Boolean = false

    suspend fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraSource.start(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onLog = { /* no-op: surfaced via on-device logcat only, not user-facing */ },
            onError = { t -> _uiState.update { it.copy(errorMessage = t.message ?: "Camera failed to start") } }
        )
    }

    /** [startFraction]/[endFraction] must be normalized (0..1) against the preview's displayed size. */
    fun onStripEndpointsSet(startFraction: Offset, endFraction: Offset, positionCount: Int = DEFAULT_SAMPLE_POSITION_COUNT) {
        samplePositionCount = positionCount
        cameraSource.setStripEndpoints(startFraction, endFraction, positionCount)
    }

    fun lockCameraSettings(previewView: PreviewView) {
        _uiState.update { it.copy(phase = ModeCapturePhase.LockingCamera) }
        cameraSource.lockExposureAndWhiteBalance(
            previewView,
            onLocked = { success ->
                _uiState.update {
                    it.copy(
                        phase = ModeCapturePhase.Reference,
                        cameraLocked = success,
                        errorMessage = if (success) null else "Focus did not converge — you can still continue."
                    )
                }
            },
            onError = { t ->
                _uiState.update {
                    it.copy(phase = ModeCapturePhase.Setup, errorMessage = t.message ?: "Failed to lock camera")
                }
            }
        )
    }

    fun captureReference(knownR: Int, knownG: Int, knownB: Int) {
        cameraSource.captureReferenceSamples { samples ->
            val capture = ReferenceCapture(knownR, knownG, knownB, samples)
            referenceCapture = capture
            _uiState.update { it.copy(reference = capture) }
        }
    }

    fun retakeReference() {
        referenceCapture = null
        _uiState.update { it.copy(reference = null) }
    }

    /** Cycles through [modes] (already filtered to exclude Auto Play — see [modesToCapture]), holding
     * each for MODE_HOLD_MS while the camera buffers frames. [setMode] is the caller-supplied
     * RgbControllerViewModel.setMode(Int) one-liner — this class never references RgbControllerViewModel
     * directly. */
    fun startCycle(modes: List<CustomMode>, setMode: (Int) -> Unit) {
        if (cycleJob?.isActive == true) return
        val toCapture = modesToCapture(modes)
        _uiState.update { it.copy(totalCount = toCapture.size, completedCount = 0, phase = ModeCapturePhase.Running) }

        cycleJob = viewModelScope.launch {
            for ((index, mode) in toCapture.withIndex()) {
                currentModeByteValue = mode.byteValue
                currentModeName = mode.name
                currentModeCategory = mode.category
                currentModeFrames = mutableListOf()
                currentModeInProgress = true

                _uiState.update {
                    it.copy(
                        phase = ModeCapturePhase.Running,
                        currentModeIndex = index,
                        currentModeName = mode.name,
                        elapsedMsInMode = 0L
                    )
                }
                setMode(mode.byteValue)
                cameraSource.beginModeCapture { frame -> currentModeFrames.add(frame) }

                var elapsed = 0L
                while (elapsed < MODE_HOLD_MS) {
                    delay(TICK_MS)
                    if (_uiState.value.phase == ModeCapturePhase.Running) {
                        elapsed += TICK_MS
                        _uiState.update { it.copy(elapsedMsInMode = elapsed) }
                    }
                    // While Paused, this loop keeps polling at TICK_MS granularity without
                    // accumulating elapsed — pause()/resume() handle the camera-side buffering cutover.
                }

                cameraSource.pauseOrEndModeCapture()
                buffer.add(
                    ModeCaptureRecord(mode.byteValue, mode.name, mode.category, currentModeFrames.toList(), complete = true)
                )
                currentModeInProgress = false
                _uiState.update { it.copy(completedCount = index + 1) }
            }
            _uiState.update { it.copy(phase = ModeCapturePhase.Done) }
            export()
        }
    }

    fun pause() {
        if (_uiState.value.phase != ModeCapturePhase.Running) return
        pausedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        cameraSource.pauseOrEndModeCapture()
        _uiState.update { it.copy(phase = ModeCapturePhase.Paused) }
    }

    fun resume() {
        if (_uiState.value.phase != ModeCapturePhase.Paused) return
        val pausedDurationMs = SystemClock.elapsedRealtime() - pausedAtElapsedRealtimeMs
        cameraSource.resumeModeCapture(pausedDurationMs) { frame -> currentModeFrames.add(frame) }
        _uiState.update { it.copy(phase = ModeCapturePhase.Running) }
    }

    /** Aborts the run, leaving whatever's been captured so far (including the in-progress mode,
     * marked incomplete) available to [export]. */
    fun stop() {
        cycleJob?.cancel()
        cycleJob = null
        cameraSource.pauseOrEndModeCapture()
        _uiState.update { it.copy(phase = ModeCapturePhase.Done) }
    }

    /** Writes the captured data (completed modes + the in-progress one, if any) to a JSON file and
     * returns a FileProvider content:// Uri, mirroring DiagnosticLogger.exportToFile's pattern. */
    fun export(): Uri? {
        val records = buffer.toMutableList()
        if (currentModeInProgress) {
            records.add(
                ModeCaptureRecord(
                    currentModeByteValue, currentModeName, currentModeCategory,
                    currentModeFrames.toList(), complete = false
                )
            )
        }
        if (records.isEmpty()) return null

        val export = ModeCaptureExport(
            exportedAtEpochMs = System.currentTimeMillis(),
            samplePositionCount = samplePositionCount,
            reference = referenceCapture,
            modes = records
        )
        val context = getApplication<Application>()
        val dir = File(context.getExternalFilesDir(null), "mode_capture")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "mode_capture_${System.currentTimeMillis()}.json")
        // Streamed straight to disk (not built as one in-memory String first) — a full capture run's
        // JSON can reach tens of megabytes, which OOM'd here before this was a streaming write.
        file.bufferedWriter().use { writer -> ModeCaptureExporter.writeJson(export, writer) }

        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        _uiState.update { it.copy(exportedUri = uri) }
        return uri
    }

    override fun onCleared() {
        cameraSource.stop()
        super.onCleared()
    }
}
