package com.example.hardware.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.core.modecapture.CaptureFrame
import com.example.core.modecapture.SpatialSample
import com.example.ui.components.imageProxyToBitmap
import com.example.ui.components.sampleAlongLine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val DEFAULT_SAMPLE_POSITION_COUNT = 24
private const val FRAME_INTERVAL_MS = 100L // ~10Hz — dense enough for typical LED animation speeds

/**
 * Hardware-facing camera source for the Mode Capture experimental feature. Owns the CameraX
 * preview/analysis pipeline and turns raw frames into [CaptureFrame]s sampled along a
 * user-calibrated line across the LED strip, via [imageProxyToBitmap]/[sampleAlongLine] in
 * `com.example.ui.components.CameraCalibrationLogic`. Plain class + callback shape, matching
 * `hardware/audio/AudioCaptureSource` rather than a Service — CameraX needs a LifecycleOwner but
 * no system permission grant or foreground-service lifecycle beyond the runtime CAMERA permission.
 */
class ModeCaptureCameraSource(private val context: Context) {

    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Strip endpoints as FRACTIONS (0..1) of frame width/height, not absolute pixels — the on-screen
    // PreviewView's display size and the ImageAnalysis bitmap's decoded resolution aren't guaranteed
    // to match, so a tap position is normalized against the preview's size at tap time and resolved
    // against each frame's actual bitmap dimensions at sample time instead.
    @Volatile private var stripStartFraction: Offset = Offset.Zero
    @Volatile private var stripEndFraction: Offset = Offset(1f, 0f)
    @Volatile private var samplePositionCount: Int = DEFAULT_SAMPLE_POSITION_COUNT
    @Volatile private var modeStartElapsedRealtimeMs: Long = 0L
    @Volatile private var frameCallback: ((CaptureFrame) -> Unit)? = null
    @Volatile private var oneShotCallback: ((List<SpatialSample>) -> Unit)? = null
    @Volatile private var lastAcceptedAtMs: Long = 0L

    /** Binds Preview + ImageAnalysis to [lifecycleOwner]. Returns false (via [onError]) on failure. */
    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onLog: (String) -> Unit,
        onError: (Throwable) -> Unit
    ): Boolean {
        return try {
            val provider = awaitCameraProvider()

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysisUseCase.setAnalyzer(analyzerExecutor) { imageProxy ->
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAcceptedAtMs >= FRAME_INTERVAL_MS) {
                        lastAcceptedAtMs = now
                        val bitmap = imageProxyToBitmap(imageProxy, roi = null)
                        if (bitmap != null) {
                            val p1 = Offset(stripStartFraction.x * bitmap.width, stripStartFraction.y * bitmap.height)
                            val p2 = Offset(stripEndFraction.x * bitmap.width, stripEndFraction.y * bitmap.height)
                            val samples = sampleAlongLine(bitmap, p1, p2, samplePositionCount)
                            val oneShot = oneShotCallback
                            if (oneShot != null) {
                                oneShotCallback = null
                                oneShot(samples)
                            } else {
                                frameCallback?.invoke(
                                    CaptureFrame(now - modeStartElapsedRealtimeMs, samples)
                                )
                            }
                        }
                    }
                } finally {
                    imageProxy.close()
                }
            }

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                previewUseCase,
                analysisUseCase
            )
            cameraProvider = provider
            imageAnalysis = analysisUseCase
            onLog("Mode capture camera started")
            true
        } catch (t: Throwable) {
            onError(t)
            false
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

    /**
     * Defines the sampling line across the strip from the two endpoints the user tapped.
     * [startFraction]/[endFraction] must be normalized (0..1) against the PreviewView's displayed
     * size at tap time — see the field doc comment above for why.
     */
    fun setStripEndpoints(startFraction: Offset, endFraction: Offset, positionCount: Int = DEFAULT_SAMPLE_POSITION_COUNT) {
        stripStartFraction = startFraction
        stripEndFraction = endFraction
        samplePositionCount = positionCount
    }

    /**
     * Locks focus/exposure/white-balance at the current preview center using CameraX's standard
     * `FocusMeteringAction` + `disableAutoCancel()` — the 3A lock holds until `stop()` or a new
     * metering call, rather than the default 5s auto-revert-to-continuous behavior. Deliberately
     * not hand-rolled Camera2Interop manual exposure: this is the standard CameraX mechanism for
     * "locked for the duration of a run" without needing correct manual ISO/exposure-time values.
     */
    fun lockExposureAndWhiteBalance(
        previewView: PreviewView,
        onLocked: (Boolean) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val cam = camera
        if (cam == null) {
            onError(IllegalStateException("Camera not started"))
            return
        }
        val centerPoint = previewView.meteringPointFactory.createPoint(
            previewView.width / 2f,
            previewView.height / 2f
        )
        val action = FocusMeteringAction.Builder(
            centerPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        ).disableAutoCancel().build()

        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener(
            {
                try {
                    onLocked(future.get().isFocusSuccessful)
                } catch (t: Throwable) {
                    onError(t)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    /** Starts buffering frames for one mode; `onFrame` receives samples with elapsedMs since this call. */
    fun beginModeCapture(onFrame: (CaptureFrame) -> Unit) {
        modeStartElapsedRealtimeMs = SystemClock.elapsedRealtime()
        frameCallback = onFrame
    }

    /** Stops buffering for the current mode (used both at mode-end and while paused). */
    fun pauseOrEndModeCapture() {
        frameCallback = null
    }

    /**
     * Resumes buffering after a pause, shifting the elapsed-time baseline forward by the paused
     * duration so `elapsedMs` in subsequent frames continues counting only active-hold time
     * instead of jumping by the wall-clock pause length.
     */
    fun resumeModeCapture(pausedDurationMs: Long, onFrame: (CaptureFrame) -> Unit) {
        modeStartElapsedRealtimeMs += pausedDurationMs
        frameCallback = onFrame
    }

    /** Grabs the next analyzed frame's line samples once, for the reference-color step. */
    fun captureReferenceSamples(onResult: (List<SpatialSample>) -> Unit) {
        oneShotCallback = onResult
    }

    fun stop() {
        frameCallback = null
        oneShotCallback = null
        imageAnalysis?.clearAnalyzer()
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        imageAnalysis = null
    }
}
