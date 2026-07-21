package com.example.ambiance

import android.content.Context
import android.media.ImageReader
import android.util.Log
import com.example.core.color.ColorConverter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class ZoneColor(val col: Int, val row: Int, val r: Int, val g: Int, val b: Int)

class AmbianceProcessor(
    private val context: Context,
    private val onFinalColor: (Triple<Int, Int, Int>, Boolean) -> Unit
) {
    private var lastCaptureTimeMs = 0L
    private var emaState = EmaState(0.0, 0.0, 0.0)
    private var lastLoggedColor = Triple(0, 0, 0)
    private var wasSceneCut = false

    private data class EmaState(
        val emaLinR: Double, val emaLinG: Double, val emaLinB: Double
    )

    fun processFrame(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            val nowMs = System.currentTimeMillis()
            
            val prefs = context.getSharedPreferences("ambiance_settings_prefs", Context.MODE_PRIVATE)
            val updateRateCapFps = prefs.getInt("update_rate_cap_fps", 20).coerceAtLeast(1)
            val slowestDevicePacing = com.example.data.repository.AppPreferencesRepositoryImpl(context)
                .getPacingPrefInt("slowest_connected_pacing", 0)
            
            val fpsIntervalMs = 1000 / updateRateCapFps
            val effectiveIntervalMs = max(fpsIntervalMs, slowestDevicePacing)
            if (nowMs - lastCaptureTimeMs < effectiveIntervalMs) return
            val deltaMs = (nowMs - lastCaptureTimeMs).coerceAtLeast(1L).coerceAtMost(500L)
            lastCaptureTimeMs = nowMs

            val responseSpeed = prefs.getFloat("response_speed", 0.5f)
            val saturationBoost = prefs.getFloat("saturation_boost", 1.4f)
            val brightnessCompensation = prefs.getFloat("brightness_compensation", 1.0f)
            val sceneCutSensitivity = prefs.getFloat("scene_cut_sensitivity", 110.0f)
            val smoothnessMs = prefs.getInt("smoothness_ms", 150).coerceAtLeast(10)
            val noiseDeadband = prefs.getFloat("noise_deadband", 0.10f)

            val planes = image.planes
            if (planes.isEmpty()) return
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height
            val bufferPos = buffer.position()
            val bufferLimit = buffer.limit()

            fun getRgb(x: Int, y: Int): Triple<Int, Int, Int> {
                val offset = bufferPos + y * rowStride + x * pixelStride
                if (offset + 2 >= bufferLimit) return Triple(0, 0, 0)
                return Triple(
                    buffer.get(offset).toInt() and 0xFF,
                    buffer.get(offset + 1).toInt() and 0xFF,
                    buffer.get(offset + 2).toInt() and 0xFF
                )
            }

            fun getLum(x: Int, y: Int): Int {
                val (r, g, b) = getRgb(x, y)
                return ColorConverter.luminance(r.toDouble(), g.toDouble(), b.toDouble()).toInt()
            }

            var top = 0; var bottom = height - 1; var left = 0; var right = width - 1
            val thresh = 12
            for (y in 0 until height / 2 step 2) {
                var sum = 0
                for (i in 0 until 10) sum += getLum(if (width > 1) (i * (width - 1)) / 9 else 0, y)
                if (sum / 10 > thresh) { top = y; break }
            }
            for (y in (height - 1) downTo (height / 2) step 2) {
                var sum = 0
                for (i in 0 until 10) sum += getLum(if (width > 1) (i * (width - 1)) / 9 else 0, y)
                if (sum / 10 > thresh) { bottom = y; break }
            }
            for (x in 0 until width / 2 step 2) {
                var sum = 0
                for (i in 0 until 10) sum += getLum(x, if (height > 1) (i * (height - 1)) / 9 else 0)
                if (sum / 10 > thresh) { left = x; break }
            }
            for (x in (width - 1) downTo (width / 2) step 2) {
                var sum = 0
                for (i in 0 until 10) sum += getLum(x, if (height > 1) (i * (height - 1)) / 9 else 0)
                if (sum / 10 > thresh) { right = x; break }
            }
            
            val minW = width / 2; val minH = height / 2
            if (right - left + 1 < minW) {
                val cx = (left + right) / 2
                left = max(0, cx - minW / 2)
                right = (left + minW - 1).coerceAtMost(width - 1)
            }
            if (bottom - top + 1 < minH) {
                val cy = (top + bottom) / 2
                top = max(0, cy - minH / 2)
                bottom = (top + minH - 1).coerceAtMost(height - 1)
            }

            val gridCols = 4
            val gridRows = 4
            val cellW = (right - left + 1).toDouble() / gridCols
            val cellH = (bottom - top + 1).toDouble() / gridRows

            val tauMultiplier = (2.0 - 2.0 * responseSpeed).coerceIn(0.2, 2.0)
            val effectiveTauMs = (smoothnessMs * tauMultiplier).coerceAtLeast(10.0)
            val alpha = (1.0 - kotlin.math.exp(-deltaMs / effectiveTauMs)).coerceIn(0.01, 1.0)
            val deadbandMultiplier = (noiseDeadband / 0.10f).coerceIn(0.2f, 5.0f)

            val rawColors = Array(16) { Triple(0, 0, 0) }

            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
                    val cellL = left + col * cellW
                    val cellT = top + row * cellH
                    val samples = 8
                    for (j in 0 until samples) {
                        val y = (cellT + (j + 0.5) * (cellH / samples)).toInt().coerceIn(top, bottom)
                        for (i in 0 until samples) {
                            val x = (cellL + (i + 0.5) * (cellW / samples)).toInt().coerceIn(left, right)
                            val (r, g, b) = getRgb(x, y)
                            sumR += ColorConverter.srgbToLinear(r)
                            sumG += ColorConverter.srgbToLinear(g)
                            sumB += ColorConverter.srgbToLinear(b)
                        }
                    }
                    val totalSamples = samples * samples
                    val rawR = ColorConverter.linearToSrgb(sumR / totalSamples)
                    val rawG = ColorConverter.linearToSrgb(sumG / totalSamples)
                    val rawB = ColorConverter.linearToSrgb(sumB / totalSamples)

                    val idx = row * gridCols + col
                    rawColors[idx] = Triple(rawR, rawG, rawB)
                }
            }

            var sumLinR = 0.0; var sumLinG = 0.0; var sumLinB = 0.0
            for ((r, g, b) in rawColors) {
                sumLinR += ColorConverter.srgbToLinear(r)
                sumLinG += ColorConverter.srgbToLinear(g)
                sumLinB += ColorConverter.srgbToLinear(b)
            }
            val aggRawR = ColorConverter.linearToSrgb(sumLinR / 16.0)
            val aggRawG = ColorConverter.linearToSrgb(sumLinG / 16.0)
            val aggRawB = ColorConverter.linearToSrgb(sumLinB / 16.0)

            val emaSrgbR = ColorConverter.linearToSrgb(emaState.emaLinR)
            val emaSrgbG = ColorConverter.linearToSrgb(emaState.emaLinG)
            val emaSrgbB = ColorConverter.linearToSrgb(emaState.emaLinB)

            val aggDelta = (abs(aggRawR - emaSrgbR) + abs(aggRawG - emaSrgbG) + abs(aggRawB - emaSrgbB)) / 3.0
            val isSceneCut = aggDelta > sceneCutSensitivity
            wasSceneCut = isSceneCut

            var newEmaLinR: Double; var newEmaLinG: Double; var newEmaLinB: Double

            if (isSceneCut) {
                newEmaLinR = ColorConverter.srgbToLinear(aggRawR)
                newEmaLinG = ColorConverter.srgbToLinear(aggRawG)
                newEmaLinB = ColorConverter.srgbToLinear(aggRawB)
            } else {
                val lum = ColorConverter.luminance(emaSrgbR.toDouble(), emaSrgbG.toDouble(), emaSrgbB.toDouble()) / 255.0
                val dynamicThreshold = (5.0 + 10.0 * lum + 15.0 * (1.0 - lum).pow(2)) * deadbandMultiplier
                val diff = abs(aggRawR - emaSrgbR) + abs(aggRawG - emaSrgbG) + abs(aggRawB - emaSrgbB)
                if (diff <= dynamicThreshold) {
                    newEmaLinR = emaState.emaLinR
                    newEmaLinG = emaState.emaLinG
                    newEmaLinB = emaState.emaLinB
                } else {
                    val rawLum = ColorConverter.luminance(aggRawR.toDouble(), aggRawG.toDouble(), aggRawB.toDouble()) / 255.0
                    val effectiveAlpha = if (rawLum < lum) {
                        (1.0 - (1.0 - alpha).pow(2.2)).coerceIn(0.01, 1.0)
                    } else {
                        alpha
                    }
                    newEmaLinR = emaState.emaLinR + effectiveAlpha * (ColorConverter.srgbToLinear(aggRawR) - emaState.emaLinR)
                    newEmaLinG = emaState.emaLinG + effectiveAlpha * (ColorConverter.srgbToLinear(aggRawG) - emaState.emaLinG)
                    newEmaLinB = emaState.emaLinB + effectiveAlpha * (ColorConverter.srgbToLinear(aggRawB) - emaState.emaLinB)
                }
            }

            emaState = EmaState(newEmaLinR, newEmaLinG, newEmaLinB)

            val compR = (newEmaLinR * brightnessCompensation).coerceIn(0.0, 1.0)
            val compG = (newEmaLinG * brightnessCompensation).coerceIn(0.0, 1.0)
            val compB = (newEmaLinB * brightnessCompensation).coerceIn(0.0, 1.0)

            val sR = ColorConverter.linearToSrgb(compR)
            val sG = ColorConverter.linearToSrgb(compG)
            val sB = ColorConverter.linearToSrgb(compB)

            val lumLinear = ColorConverter.luminance(newEmaLinR, newEmaLinG, newEmaLinB)
            // Previous threshold of 0.1 was in LINEAR space, which corresponds to 
            // roughly 38% PERCEPTUAL brightness due to gamma — meaning most 
            // ordinary dim/mid-brightness content was having its saturation boost 
            // suppressed, not just truly dark content. Lowered to represent 
            // genuinely dark content only.
            val saturationTaperThreshold = 0.02
            var effBoost = saturationBoost
            if (lumLinear < saturationTaperThreshold) {
                effBoost = 1.0f + (saturationBoost - 1.0f) * (lumLinear / saturationTaperThreshold).toFloat().coerceAtLeast(0f)
            }
            
            val (h, s, v) = rgbToHsv(sR, sG, sB)
            val newS = (s * effBoost).coerceIn(0f, 1f)
            var (fR, fG, fB) = hsvToRgb(h, newS, v)

            val maxC = maxOf(fR, fG, fB)
            // True black (allowing for residual EMA/noise, hence <= 2 rather than 
            // == 0) bypasses the floor boost entirely instead of always being 
            // pushed up to a visible minimum.
            val trueBlackCutoff = 2
            // Lowered from 25 — previous floor read as too bright for content 
            // meant to be very dim.
            val floorTarget = 14
            if (maxC in (trueBlackCutoff + 1) until floorTarget) {
                val boost = floorTarget.toFloat() / maxC
                fR = (fR * boost).roundToInt().coerceIn(0, 255)
                fG = (fG * boost).roundToInt().coerceIn(0, 255)
                fB = (fB * boost).roundToInt().coerceIn(0, 255)
            }

            val finalColor = Triple(fR, fG, fB)

            val rawZoneColors = mutableListOf<ZoneColor>()
            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    val idx = row * gridCols + col
                    val (r, g, b) = rawColors[idx]
                    rawZoneColors.add(ZoneColor(col, row, r, g, b))
                }
            }
            AmbianceCaptureState.updateZoneColors(rawZoneColors)
            
            onFinalColor(finalColor, wasSceneCut)

            val logDelta = abs(finalColor.first - lastLoggedColor.first) + abs(finalColor.second - lastLoggedColor.second) + abs(finalColor.third - lastLoggedColor.third)
            AmbianceCaptureState.logDiagnostic("t=$nowMs out=$finalColor delta=$logDelta cut=$wasSceneCut")
            lastLoggedColor = finalColor

        } catch (e: Exception) {
            Log.e("AmbianceProcessor", "Error processing frame", e)
        } finally {
            image.close()
        }
    }

    fun clear() {
        emaState = EmaState(0.0, 0.0, 0.0)
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rP = r / 255f; val gP = g / 255f; val bP = b / 255f
        val max = maxOf(rP, gP, bP); val min = minOf(rP, gP, bP)
        val diff = max - min
        val h = if (diff == 0f) 0f else when (max) {
            rP -> (60f * ((gP - bP) / diff) + 360f) % 360f
            gP -> (60f * ((bP - rP) / diff) + 120f) % 360f
            else -> (60f * ((rP - gP) / diff) + 240f) % 360f
        }
        val s = if (max == 0f) 0f else diff / max
        return Triple(h, s, max)
    }

    // Not merged into ColorConverter.hsvToRgb: that version additionally applies a cubic
    // perceptual-brightness correction (r/g/b -> (x/255)^3*255) which this ambiance path
    // has never had. Unifying them would visibly darken ambiance output on every frame.
    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rP, gP, bP) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(
            ((rP + m) * 255f).roundToInt().coerceIn(0, 255),
            ((gP + m) * 255f).roundToInt().coerceIn(0, 255),
            ((bP + m) * 255f).roundToInt().coerceIn(0, 255)
        )
    }
}
