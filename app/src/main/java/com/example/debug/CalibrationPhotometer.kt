package com.example.debug

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
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
 * Measures light using the **driving phone's own camera**, so no second phone, no screen in frame,
 * and nothing to prop up.
 *
 * Was `LatencyCameraProbe`, and latency is still what it does best, but it is no longer only that:
 * with a grid and a settable exposure it is the instrument for the response curve, per-LED
 * uniformity, the CCT question and the chase-direction check as well. The file its CSV is written
 * under keeps the `fuse_latency_` prefix on purpose — 2026-09-02's captures use it, and the
 * analysis scripts glob for it.
 *
 * ## Why this replaces filming with a second phone
 *
 * `latency_pulse` needed a second camera seeing both the strip and the driver's screen, because
 * aligning video to log time using the strip alone subtracts out the very delay being measured.
 * The app writes the command and the app sees the light, so both events can be timestamped on one
 * clock inside one process, and the alignment problem disappears rather than being worked around.
 *
 * The same trick pays again for photometry. Filming needs an external camera app, which takes the
 * foreground and gets Fuse frozen; it needs its exposure set by simulated taps that can silently
 * miss; and it records nothing about what those settings were. Here the sequence sets the exposure
 * and every row carries the value that was in force when the frame was taken.
 *
 * ## The part that decides whether the number is real
 *
 * A frame's *arrival* time in the analyzer is useless: it carries the whole camera pipeline delay,
 * which is the same order as the quantity being measured. What matters is
 * `ImageProxy.imageInfo.timestamp`, the sensor's own timestamp for the start of exposure.
 *
 * That timestamp's clock base is **not fixed**. `SENSOR_INFO_TIMESTAMP_SOURCE` is either `REALTIME`,
 * which shares a base with [SystemClock.elapsedRealtimeNanos], or `UNKNOWN`, which in practice means
 * the monotonic base of [SystemClock.uptimeNanos]. Comparing a write time on one base against a
 * frame time on the other gives a plausible-looking number that is wrong by however long the device
 * has spent asleep. So the source is read from the camera and written into the CSV, and every write
 * is stamped on **both** clocks — the analysis then picks the matching pair rather than assuming.
 *
 * Exposure time still sets the floor: a frame integrates light over its whole exposure, so a change
 * is located to within that window and no finer.
 *
 * ## Two row types, and why the grid is not logged every frame
 *
 * `frame` rows carry one whole-ROI luma at the full frame rate, because that is what an edge needs:
 * the frame interval *is* the resolution of a latency measurement and thinning it would coarsen the
 * answer. `grid` rows carry a [GRID] x [GRID] block of Y, U and V means, and are written only every
 * [gridEveryNth] frames — a ramp holds each byte for about a second, so a few grid rows per step is
 * plenty, while logging the full grid at 60fps would put tens of megabytes into a StringBuilder and
 * the cost of formatting it into the frame rate being measured.
 *
 * Chroma is logged raw as U and V rather than converted to RGB here. The conversion is arithmetic
 * that can be done later against the whole file, and doing it on-device would bake one guess about
 * the encoding into the only copy of the data.
 */
object CalibrationPhotometer {

    private const val TAG = "CalibrationPhotometer"

    /** Y-plane mean over the centre of the frame — cheap, and the strip is what fills it. */
    private const val ROI_FRACTION = 0.5f

    /** 5ms: short enough to hold 60fps and to make a frame's timestamp mean an instant. */
    const val DEFAULT_EXPOSURE_NS = 5_000_000L

    /** High, because 5ms of a dark room is nothing. The strip's own light is what has to register. */
    const val DEFAULT_SENSITIVITY = 1600

    private const val TARGET_FPS = 60

    /** Grid resolution. 8x8 over a frame the strip fills puts individual LEDs in their own cells. */
    const val GRID = 8

    private val rows = StringBuilder()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var active = false
    @Volatile private var timestampSource = "unread"
    @Volatile private var exposureNs = DEFAULT_EXPOSURE_NS
    @Volatile private var sensitivity = DEFAULT_SENSITIVITY
    @Volatile private var gridEveryNth = 12
    @Volatile private var frameCounter = 0L

    /** Latest whole-ROI luma, for the "strip is lying about being lit" guard. */
    @Volatile var lastLuma: Double = 0.0
        private set

    private var provider: ProcessCameraProvider? = null
    private var owner: ProbeLifecycleOwner? = null
    private var camera: Camera? = null

    private class ProbeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    fun isActive(): Boolean = active

    /**
     * Called from the sequence for every command it sends. Inert unless a run is active, so it
     * costs nothing to leave in the hot path of every other sequence.
     */
    @Synchronized
    fun markWrite(label: String) {
        if (!active) return
        rows.append(
            "write,$label,${SystemClock.elapsedRealtimeNanos()},${SystemClock.uptimeNanos()},,," +
                "$exposureNs,$sensitivity\n"
        )
    }

    @Synchronized
    private fun markFrame(sensorTsNs: Long, luma: Double) {
        if (!active) return
        rows.append(
            "frame,,${SystemClock.elapsedRealtimeNanos()},${SystemClock.uptimeNanos()}," +
                "$sensorTsNs,$luma,$exposureNs,$sensitivity\n"
        )
    }

    @Synchronized
    private fun markGrid(sensorTsNs: Long, cells: String) {
        if (!active) return
        rows.append(
            "grid,,${SystemClock.elapsedRealtimeNanos()},${SystemClock.uptimeNanos()}," +
                "$sensorTsNs,$cells,$exposureNs,$sensitivity\n"
        )
    }

    /**
     * Changes exposure on a running camera, so one sequence can measure the same byte at several
     * exposures without rebinding — a rebind costs hundreds of milliseconds and drops frames, which
     * is exactly what a stitched two-take ramp had to work around before.
     *
     * Returns once the request is queued, not once a frame with the new setting has arrived. The
     * caller must let a few frames pass before believing what it reads; the applied values go into
     * every row so the analysis can discard the ones taken during the changeover rather than trust
     * a settling delay.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun setExposure(newExposureNs: Long, newSensitivity: Int): Boolean {
        val cam = camera ?: return false
        return try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, newExposureNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, newSensitivity)
                    .build()
            )
            exposureNs = newExposureNs
            sensitivity = newSensitivity
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Could not change exposure", t)
            false
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun start(
        context: Context,
        startExposureNs: Long = DEFAULT_EXPOSURE_NS,
        startSensitivity: Int = DEFAULT_SENSITIVITY,
        gridEvery: Int = 12,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            synchronized(this) {
                rows.setLength(0)
                rows.append("event,label,elapsed_ns,uptime_ns,sensor_ts_ns,luma,exposure_ns,iso\n")
                exposureNs = startExposureNs
                sensitivity = startSensitivity
                gridEveryNth = gridEvery.coerceAtLeast(1)
                frameCounter = 0
                lastLuma = 0.0
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
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, startExposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, startSensitivity)
                // Auto white balance re-tints per frame, which would put a moving colour cast into
                // every colour measurement. Fixed preset, not auto.
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS)
                )
            val analysis = builder.build()
            analysis.setAnalyzer(executor) { proxy ->
                try {
                    val n = frameCounter++
                    markFrame(proxy.imageInfo.timestamp, meanLuma(proxy).also { lastLuma = it })
                    if (n % gridEveryNth == 0L) {
                        markGrid(proxy.imageInfo.timestamp, gridCells(proxy))
                    }
                } finally {
                    proxy.close()
                }
            }

            cameraProvider.unbindAll()
            val bound = cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis
            )
            timestampSource = when (
                Camera2CameraInfo.from(bound.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ) {
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "realtime"
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "unknown_monotonic"
                else -> "unread"
            }
            provider = cameraProvider
            owner = lifecycleOwner
            camera = bound
            onLog("Photometer started; sensor timestamp source = $timestampSource")
            true
        } catch (t: Throwable) {
            active = false
            android.util.Log.w(TAG, "Could not start photometer", t)
            onLog("Photometer failed to start: ${t.message}")
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

    /**
     * Y, U and V means for each cell of a [GRID] x [GRID] tiling of the **whole** frame — not the
     * centre ROI, because the point of the grid is where things are, and a strip that runs out of
     * the middle of the frame is exactly the case it exists for.
     *
     * Emitted as one pipe-separated field so a grid row stays one CSV row: `y0|y1|...;u0|...;v0|...`.
     */
    private fun gridCells(proxy: ImageProxy): String {
        val w = proxy.width
        val h = proxy.height
        val yPlane = proxy.planes[0]
        val uPlane = proxy.planes[1]
        val vPlane = proxy.planes[2]
        val ys = StringBuilder()
        val us = StringBuilder()
        val vs = StringBuilder()
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val x0 = gx * w / GRID
                val x1 = (gx + 1) * w / GRID
                val y0 = gy * h / GRID
                val y1 = (gy + 1) * h / GRID
                if (ys.isNotEmpty()) {
                    ys.append('|'); us.append('|'); vs.append('|')
                }
                ys.append(planeMean(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, x0, x1, y0, y1, 4))
                // Chroma planes are half resolution in both axes on YUV_420_888, so the same frame
                // coordinates have to be halved before they index into them.
                us.append(planeMean(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, x0 / 2, x1 / 2, y0 / 2, y1 / 2, 2))
                vs.append(planeMean(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, x0 / 2, x1 / 2, y0 / 2, y1 / 2, 2))
            }
        }
        return "$ys;$us;$vs"
    }

    private fun planeMean(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        step: Int
    ): Int {
        var sum = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            val base = y * rowStride
            var x = x0
            while (x < x1) {
                val idx = base + x * pixelStride
                if (idx < buffer.limit()) {
                    sum += (buffer.get(idx).toInt() and 0xFF)
                    count++
                }
                x += step
            }
            y += step
        }
        return if (count == 0) 0 else (sum / count).toInt()
    }

    @Synchronized
    fun finish(sequence: String, outputDir: File?, startedAt: Long): File? {
        active = false
        try {
            owner?.registry?.currentState = Lifecycle.State.DESTROYED
            provider?.unbindAll()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Could not tear down photometer", t)
        }
        owner = null
        provider = null
        camera = null
        if (outputDir == null) return null
        return try {
            val file = File(outputDir, "fuse_latency_${sequence}_$startedAt.csv")
            // The clock base goes in the file, not in someone's memory of which phone it was.
            file.writeText(
                "# sensor_timestamp_source=$timestampSource grid=$GRID\n" + rows.toString()
            )
            file
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not write photometer CSV", e)
            null
        }
    }
}
