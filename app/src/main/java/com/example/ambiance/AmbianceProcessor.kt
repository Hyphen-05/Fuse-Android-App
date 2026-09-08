package com.example.ambiance

import android.content.Context
import android.media.ImageReader
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

data class ZoneColor(val col: Int, val row: Int, val r: Int, val g: Int, val b: Int)

/**
 * The platform half of ambiance: acquire a screen-capture frame, decide whether it is due, read the
 * user's settings, hand the pixels to [AmbianceFrameAnalyser], publish the result.
 *
 * The decision itself lives in the analyser so it can be run over real video off-device - see
 * `AmbianceVideoBench`.
 */
class AmbianceProcessor(
    private val context: Context,
    private val onFinalColor: (Triple<Int, Int, Int>, Boolean) -> Unit
) {
    private var lastCaptureTimeMs = 0L
    private var lastLoggedColor = Triple(0, 0, 0)
    private val analyser = AmbianceFrameAnalyser()

    // Both used to be re-acquired inside processFrame, i.e. up to 60 times a second for the whole
    // capture session — and every AppPreferencesRepositoryImpl opens six SharedPreferences handles.
    // The reads themselves stay per-frame: once the file is loaded they're in-memory map lookups,
    // and they're what makes a slider move show up on the strip straight away.
    private val prefs = context.getSharedPreferences("ambiance_settings_prefs", Context.MODE_PRIVATE)
    private val preferencesRepository = com.example.data.repository.AppPreferencesRepositoryImpl(context)

    fun processFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val nowMs = System.currentTimeMillis()

            val updateRateCapFps = prefs.getInt("update_rate_cap_fps", 20).coerceAtLeast(1)
            val slowestDevicePacing = preferencesRepository
                .getPacingPrefInt(SLOWEST_PACING_PREF_KEY, DEFAULT_SLOWEST_PACING_MS)

            val fpsIntervalMs = 1000 / updateRateCapFps
            val effectiveIntervalMs = max(fpsIntervalMs, slowestDevicePacing)
            if (nowMs - lastCaptureTimeMs < effectiveIntervalMs) return
            val deltaMs = (nowMs - lastCaptureTimeMs).coerceAtLeast(1L).coerceAtMost(500L)
            lastCaptureTimeMs = nowMs

            val tuning = AmbianceTuning(
                responseSpeed = prefs.getFloat("response_speed", 0.5f),
                saturationBoost = prefs.getFloat("saturation_boost", 1.4f),
                brightnessCompensation = prefs.getFloat("brightness_compensation", 1.0f),
                sceneCutSensitivity = prefs.getFloat("scene_cut_sensitivity", 110.0f),
                smoothnessMs = prefs.getInt(SMOOTHNESS_PREF_KEY, DEFAULT_SMOOTHNESS_MS)
                    .coerceAtLeast(10),
                noiseDeadband = prefs.getFloat("noise_deadband", 0.10f)
            )

            val planes = image.planes
            if (planes.isEmpty()) return
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val bufferPos = buffer.position()
            val bufferLimit = buffer.limit()

            val result = analyser.analyse(
                width = image.width,
                height = image.height,
                deltaMs = deltaMs,
                tuning = tuning
            ) { x, y ->
                val offset = bufferPos + y * rowStride + x * pixelStride
                if (offset + 2 >= bufferLimit) {
                    0
                } else {
                    ((buffer.get(offset).toInt() and 0xFF) shl 16) or
                        ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
                        (buffer.get(offset + 2).toInt() and 0xFF)
                }
            }

            AmbianceCaptureState.updateZoneColors(result.zones)

            val finalColor = result.color
            onFinalColor(finalColor, result.isSceneCut)

            val logDelta = abs(finalColor.first - lastLoggedColor.first) +
                abs(finalColor.second - lastLoggedColor.second) +
                abs(finalColor.third - lastLoggedColor.third)
            AmbianceCaptureState.logDiagnostic(
                "t=$nowMs out=$finalColor delta=$logDelta cut=${result.isSceneCut}"
            )
            lastLoggedColor = finalColor
        } catch (e: Exception) {
            Log.e("AmbianceProcessor", "Error processing frame", e)
        } finally {
            image.close()
        }
    }

    fun clear() {
        analyser.clear()
    }
}

/**
 * Pacing floor for the ambiance output, in milliseconds — the slowest write interval any connected
 * device can keep up with, written reactively by the ViewModel as devices connect.
 *
 * The default used to differ by reader: the processor assumed 0 (no floor) and the interpolator
 * assumed 100. It only shows before anything has connected — when ambiance has nothing to write to
 * anyway — so both now take the conservative value rather than the fast one.
 */
internal const val SLOWEST_PACING_PREF_KEY = "slowest_connected_pacing"
/** How long a fade to a new colour should take, in ms. Read by AmbianceOutputInterpolator too. */
internal const val SMOOTHNESS_PREF_KEY = "smoothness_ms"
internal const val DEFAULT_SMOOTHNESS_MS = 150
/**
 * Only used before any device has registered - once one has, the real pacing is published into
 * this pref by the ViewModel. Kept in step with [com.example.core.pacing.BlePacing.DEFAULT_MS] so
 * ambiance does not briefly run to a different number than the wire does.
 */
internal const val DEFAULT_SLOWEST_PACING_MS = com.example.core.pacing.BlePacing.DEFAULT_MS
