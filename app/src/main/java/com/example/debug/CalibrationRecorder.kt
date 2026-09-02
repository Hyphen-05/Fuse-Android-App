package com.example.debug

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File

/**
 * Films a capture session from a **second** phone, driven entirely over adb.
 *
 * ## Why this exists rather than tapping a camera app
 *
 * Every session before this one filmed with Open Camera and drove it by simulated taps at stored
 * coordinates. That works and it is how the 2026-09-02 data was got, but it has the worst failure
 * mode in the whole rig: a tap that misses produces a recording that looks perfectly fine and is
 * void, and nobody finds out until the analysis. It already happened once — picking the phone up
 * rotated the UI and a whole burst of shutter taps produced no photos.
 *
 * It also cannot record what it did. Open Camera's ISO and shutter live in its own
 * SharedPreferences, which adb cannot read without root, so the exposure a run was shot at is
 * whatever someone wrote down. An exposure that was locked but not recorded is barely better than
 * one that drifted, because two frames are only comparable if the settings behind them are known.
 *
 * Binding [VideoCapture] here fixes both: the settings are set by the same code that starts the
 * recording, they are returned in the log line and written into the filename, and there is nothing
 * to tap.
 *
 * ## What is pinned, and why each one
 *
 * - **Auto-exposure off**, with explicit exposure time and sensitivity. An auto-exposing camera
 *   re-normalises every frame, so a brighter strip and a longer exposure become indistinguishable
 *   and every photometric run measures nothing. This is the setting the whole rig turns on.
 * - **White balance fixed** to a daylight preset rather than left on auto. Auto WB re-tints per
 *   frame, which would put a moving colour cast straight into `colour_primaries` and `cct_sweep` —
 *   the two sequences whose entire content is colour.
 * - **Focus fixed**, at a distance the caller gives, because refocusing mid-run changes the blur
 *   and blur changes measured intensity. The default is 3 dioptres — about 33cm — which suits a
 *   phone propped near a strip far better than infinity does.
 *
 * There is deliberately no audio track: it would need `RECORD_AUDIO` for a channel nothing reads,
 * and the sync flashes every sequence opens with are the alignment cue.
 *
 * The file lands in the app's own external files dir, so the same `adb pull` that collects the CSVs
 * collects the video too.
 */
object CalibrationRecorder {

    private const val TAG = "CalibrationRecorder"

    /** ~33cm in dioptres. A phone filming a strip is near it, and infinity focus would be soft. */
    const val DEFAULT_FOCUS_DIOPTRES = 3.0f

    /** Matches the exposures the 2026-09-02 session shot at: bright end of a dark room. */
    const val DEFAULT_EXPOSURE_NS = 25_000_000L // 1/40s
    const val DEFAULT_SENSITIVITY = 300

    private class RecorderLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private var provider: ProcessCameraProvider? = null
    private var owner: RecorderLifecycleOwner? = null
    private var recording: Recording? = null

    @Volatile
    private var currentFile: File? = null

    fun isRecording(): Boolean = recording != null

    /** What the current recording was configured with, for the status dump. */
    @Volatile
    var settingsLine: String = "not recording"
        private set

    @SuppressLint("UnsafeOptInUsageError", "MissingPermission")
    @Synchronized
    fun start(
        context: Context,
        name: String,
        exposureNs: Long = DEFAULT_EXPOSURE_NS,
        sensitivity: Int = DEFAULT_SENSITIVITY,
        focusDioptres: Float = DEFAULT_FOCUS_DIOPTRES,
        fhd: Boolean = true,
        onLog: (String) -> Unit
    ): Boolean {
        if (recording != null) {
            onLog("Recorder already running: ${currentFile?.name}")
            return false
        }
        return try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val lifecycleOwner = RecorderLifecycleOwner()
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(if (fhd) Quality.FHD else Quality.UHD)
                )
                .build()
            // Built through the Builder rather than VideoCapture.withOutput(), because
            // Camera2Interop extends a builder and there is nowhere to put these options once the
            // use case exists. Without them the camera auto-exposes and the recording is decorative.
            val builder = VideoCapture.Builder(recorder)

            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, sensitivity)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                )
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDioptres)
            val videoCapture = builder.build()

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture
            )

            val dir = context.getExternalFilesDir(null)
            val file = File(dir, "rec_${name}_${System.currentTimeMillis()}.mp4")
            recording = videoCapture.output
                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize ->
                            Log.i(TAG, "recording finalized: ${file.absolutePath} error=${event.error}")
                        is VideoRecordEvent.Start -> Log.i(TAG, "recording started: ${file.name}")
                        else -> Unit
                    }
                }

            provider = cameraProvider
            owner = lifecycleOwner
            currentFile = file
            // The timestamp source is not needed to *record* — nothing here is aligned on a clock,
            // the sync flashes do that — but a camera that reports one is a camera whose frames
            // could later be used the way the in-app photometer's are, so it is worth knowing.
            val tsSource = Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            settingsLine =
                "file=${file.name} exposure_ns=$exposureNs iso=$sensitivity focus_dioptres=" +
                    "$focusDioptres quality=${if (fhd) "FHD" else "UHD"} sensor_ts_source=$tsSource"
            onLog("Recording started: $settingsLine")
            Log.i(TAG, "start_recording: $settingsLine")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start recording", t)
            onLog("Recording failed to start: ${t.message}")
            teardown()
            false
        }
    }

    @Synchronized
    fun stop(onLog: (String) -> Unit): File? {
        val file = currentFile
        try {
            recording?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not stop recording cleanly", t)
        }
        recording = null
        teardown()
        // Finalisation is asynchronous: the file is on disk but its moov atom may land a moment
        // later, so a pull immediately after this returns can get a truncated file. The caller
        // waits — see the runbook.
        onLog("Recording stopped: ${file?.name ?: "none"} (allow a second before pulling)")
        Log.i(TAG, "stop_recording: ${file?.absolutePath}")
        currentFile = null
        settingsLine = "not recording"
        return file
    }

    private fun teardown() {
        try {
            owner?.registry?.currentState = Lifecycle.State.DESTROYED
            provider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not tear down recorder", t)
        }
        owner = null
        provider = null
    }
}
