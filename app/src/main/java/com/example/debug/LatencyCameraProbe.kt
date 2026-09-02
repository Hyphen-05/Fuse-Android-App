package com.example.debug

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.util.concurrent.Executors

/**
 * Measures wire-to-light latency using the **driving phone's own camera**, so no second phone, no
 * screen in frame, and nothing to prop up.
 *
 * ## Why this replaces the screen-flash approach
 *
 * `latency_pulse` needed a second camera seeing both the strip and the driver's screen, because
 * aligning video to log time using the strip alone subtracts out the very delay being measured.
 * Joe has one stand, so that rig is not available - and it was never the simplest answer anyway.
 * The app writes the command and the app sees the light, so both events can be timestamped on one
 * clock inside one process, and the alignment problem disappears rather than being worked around.
 *
 * ## The part that decides whether the number is real
 *
 * A frame's *arrival* time in the analyzer is useless here: it carries the whole camera pipeline
 * delay, which is the same order as the quantity being measured. What matters is
 * `ImageProxy.imageInfo.timestamp`, the sensor's own timestamp for the start of exposure.
 *
 * That timestamp's clock base is **not fixed**. `SENSOR_INFO_TIMESTAMP_SOURCE` is either `REALTIME`,
 * which shares a base with [SystemClock.elapsedRealtimeNanos], or `UNKNOWN`, which in practice means
 * the monotonic base of [SystemClock.uptimeNanos]. Comparing a write time on one base against a
 * frame time on the other gives a plausible-looking number that is wrong by however long the device
 * has spent asleep. So the source is read from the camera and written into the CSV, and every write
 * is stamped on **both** clocks - the analysis then picks the matching pair rather than assuming.
 *
 * Exposure time still sets the floor: a frame integrates light over its whole exposure, so a change
 * is located to within that window and no finer.
 */
object LatencyCameraProbe {

    private const val TAG = "LatencyCameraProbe"

    /** Y-plane mean over the centre of the frame - cheap, and the strip is what fills it. */
    private const val ROI_FRACTION = 0.5f

    /** 5ms: short enough to hold 60fps and to make a frame's timestamp mean an instant. */
    private const val EXPOSURE_NS = 5_000_000L

    /** High, because 5ms of a dark room is nothing. The strip's own light is what has to register. */
    private const val SENSITIVITY = 1600

    private const val TARGET_FPS = 60

    private val rows = StringBuilder()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var active = false
    @Volatile private var timestampSource = "unread"

    private var provider: ProcessCameraProvider? = null
    private var owner: ProbeLifecycleOwner? = null

    private class ProbeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * Called from the sequence for every command it sends. Inert unless a latency run is active, so
     * it costs nothing to leave in the hot path of every other sequence.
     */
    @Synchronized
    fun markWrite(label: String) {
        if (!active) return
        rows.append("write,$label,${SystemClock.elapsedRealtimeNanos()},${SystemClock.uptimeNanos()},,\n")
    }

    @Synchronized
    private fun markFrame(sensorTsNs: Long, luma: Double) {
        if (!active) return
        rows.append("frame,,${SystemClock.elapsedRealtimeNanos()},${SystemClock.uptimeNanos()},$sensorTsNs,$luma\n")
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun start(context: Context, onLog: (String) -> Unit): Boolean {
        return try {
            synchronized(this) {
                rows.setLength(0)
                rows.append("event,label,elapsed_ns,uptime_ns,sensor_ts_ns,luma\n")
                active = true
            }
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val lifecycleOwner = ProbeLifecycleOwner()
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED

            val builder = ImageAnalysis.Builder()
                // Latest-only: a backed-up queue would hand us stale frames, and a stale frame here
                // is indistinguishable from a slow strip.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            // The frame interval IS the resolution of this measurement, and left to itself the
            // camera destroys it: in the dark room these runs happen in, auto-exposure stretched to
            // ~66ms a frame, which is coarser than the 30fps video this was built to replace. The
            // first run read median 47ms with a 3ms minimum — a spread dominated by sampling rather
            // than by the strip.
            //
            // So exposure is pinned manually rather than asked for politely. Short exposure buys
            // two things at once: a high frame rate, and a timestamp that means a narrow instant
            // instead of a long smear. LEDs at full white are bright enough not to need more.
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NS)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, SENSITIVITY)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS)
                )
            val analysis = builder.build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    markFrame(proxy.imageInfo.timestamp, meanLuma(proxy))
                } finally {
                    proxy.close()
                }
            }

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis
            )
            timestampSource = when (
                Camera2CameraInfo.from(camera.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown_monotonic"
                else -> "unread"
            }
            provider = cameraProvider
            owner = lifecycleOwner
            onLog("Latency probe started; sensor timestamp source = $timestampSource")
            true
        } catch (t: Throwable) {
            active = false
            android.util.Log.w(TAG, "Could not start latency probe", t)
            onLog("Latency probe failed to start: ${t.message}")
            false
        }
    }

    /** Mean of the Y plane over a centred square. Row stride is honoured; chroma is irrelevant. */
    private fun meanLuma(proxy: ImageProxy): Double {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val w = proxy.width
        val h = proxy.height
        val halfW = (w * ROI_FRACTION / 2).toInt()
        val halfH = (h * ROI_FRACTION / 2).toInt()
        val x0 = (w / 2 - halfW).coerceAtLeast(0)
        val x1 = (w / 2 + halfW).coerceAtMost(w)
        val y0 = (h / 2 - halfH).coerceAtLeast(0)
        val y1 = (h / 2 + halfH).coerceAtMost(h)
        var sum = 0L
        var count = 0
        // Every fourth pixel: the signal is a whole-frame brightness step, and a full scan would put
        // this analyzer's own cost into the frame rate it is trying to measure against.
        var y = y0
        while (y < y1) {
            val base = y * rowStride
            var x = x0
            while (x < x1) {
                sum += (buffer.get(base + x).toInt() and 0xFF)
                count++
                x += 4
            }
            y += 4
        }
        return if (count == 0) 0.0 else sum.toDouble() / count
    }

    @Synchronized
    fun finish(sequence: String, outputDir: File?, startedAt: Long): File? {
        active = false
        try {
            owner?.registry?.currentState = Lifecycle.State.DESTROYED
            provider?.unbindAll()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Could not tear down latency probe", t)
        }
        owner = null
        provider = null
        if (outputDir == null) return null
        return try {
            val file = File(outputDir, "fuse_latency_${sequence}_$startedAt.csv")
            // The clock base goes in the file, not in someone's memory of which phone it was.
            file.writeText("# sensor_timestamp_source=$timestampSource\n" + rows.toString())
            file
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not write latency CSV", e)
            null
        }
    }
}
