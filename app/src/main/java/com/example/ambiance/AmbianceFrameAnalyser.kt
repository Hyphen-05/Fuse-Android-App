package com.example.ambiance

import com.example.core.color.ColorConverter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Everything ambiance decides between "here are the pixels" and "send this colour", with no
 * Android type anywhere in it.
 *
 * Lifted out of [AmbianceProcessor] verbatim - same order, same arithmetic, same state - so the
 * shipped decision can be run over real video frames off-device. That is the point of the split:
 * `AmbianceDarkSceneSimulation` fed *aggregate colours* straight in, which skipped the ROI crop and
 * the zone grid entirely, so the two stages that actually look at pixels have never been measured.
 *
 * [AmbianceProcessor] keeps what needs the platform: acquiring the image, the capture rate limit,
 * reading preferences, publishing zones and logging.
 */
class AmbianceFrameAnalyser {

    private var emaState = EmaState(0.0, 0.0, 0.0)

    private data class EmaState(val emaLinR: Double, val emaLinG: Double, val emaLinB: Double)

    /** The region the letterbox detector settled on, in pixels, inclusive. */
    data class Roi(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Result(
        val color: Triple<Int, Int, Int>,
        val isSceneCut: Boolean,
        val zones: List<ZoneColor>,
        val roi: Roi
    )

    fun clear() {
        emaState = EmaState(0.0, 0.0, 0.0)
    }

    /**
     * @param pixel returns one pixel packed as 0xRRGGBB. The caller owns how it is read; on device
     *   that is a direct ByteBuffer offset, in a bench it is a decoded bitmap.
     * @param deltaMs time since the previous accepted frame, already clamped by the caller.
     */
    fun analyse(
        width: Int,
        height: Int,
        deltaMs: Long,
        tuning: AmbianceTuning,
        roiOverride: Roi? = null,
        ablation: AmbianceAblation = AmbianceAblation(),
        pixel: (Int, Int) -> Int
    ): Result {
        fun getRgb(x: Int, y: Int): Triple<Int, Int, Int> {
            val p = pixel(x, y)
            return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        }

        val roi = roiOverride ?: detectRoi(width, height, pixel)
        val left = roi.left; val top = roi.top; val right = roi.right; val bottom = roi.bottom

        val gridCols = 4
        val gridRows = 4
        val cellW = (right - left + 1).toDouble() / gridCols
        val cellH = (bottom - top + 1).toDouble() / gridRows

        val tauMultiplier = (2.0 - 2.0 * tuning.responseSpeed).coerceIn(0.2, 2.0)
        val effectiveTauMs = (tuning.smoothnessMs * tauMultiplier).coerceAtLeast(10.0)
        val alpha = (1.0 - kotlin.math.exp(-deltaMs / effectiveTauMs)).coerceIn(0.01, 1.0)
        val deadbandMultiplier = (tuning.noiseDeadband / 0.10f).coerceIn(0.2f, 5.0f)

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
                rawColors[row * gridCols + col] = Triple(
                    ColorConverter.linearToSrgb(sumR / totalSamples),
                    ColorConverter.linearToSrgb(sumG / totalSamples),
                    ColorConverter.linearToSrgb(sumB / totalSamples)
                )
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

        val aggDelta =
            (abs(aggRawR - emaSrgbR) + abs(aggRawG - emaSrgbG) + abs(aggRawB - emaSrgbB)) / 3.0
        val isSceneCut = aggDelta > tuning.sceneCutSensitivity

        val newEmaLinR: Double; val newEmaLinG: Double; val newEmaLinB: Double

        if (isSceneCut) {
            newEmaLinR = ColorConverter.srgbToLinear(aggRawR)
            newEmaLinG = ColorConverter.srgbToLinear(aggRawG)
            newEmaLinB = ColorConverter.srgbToLinear(aggRawB)
        } else {
            val lum = ColorConverter.luminance(
                emaSrgbR.toDouble(), emaSrgbG.toDouble(), emaSrgbB.toDouble()
            ) / 255.0
            val dynamicThreshold =
                AmbianceOutputRules.dynamicThreshold(lum, deadbandMultiplier.toDouble())
            val diff =
                AmbianceOutputRules.diff(aggRawR, aggRawG, aggRawB, emaSrgbR, emaSrgbG, emaSrgbB)
            if (ablation.deadband && diff <= dynamicThreshold) {
                newEmaLinR = emaState.emaLinR
                newEmaLinG = emaState.emaLinG
                newEmaLinB = emaState.emaLinB
            } else {
                val rawLum = ColorConverter.luminance(
                    aggRawR.toDouble(), aggRawG.toDouble(), aggRawB.toDouble()
                ) / 255.0
                val effectiveAlpha = if (ablation.asymmetricFall && rawLum < lum) {
                    (1.0 - (1.0 - alpha).pow(2.2)).coerceIn(0.01, 1.0)
                } else if (ablation.ema) {
                    alpha
                } else {
                    1.0
                }
                newEmaLinR = emaState.emaLinR +
                    effectiveAlpha * (ColorConverter.srgbToLinear(aggRawR) - emaState.emaLinR)
                newEmaLinG = emaState.emaLinG +
                    effectiveAlpha * (ColorConverter.srgbToLinear(aggRawG) - emaState.emaLinG)
                newEmaLinB = emaState.emaLinB +
                    effectiveAlpha * (ColorConverter.srgbToLinear(aggRawB) - emaState.emaLinB)
            }
        }

        emaState = EmaState(newEmaLinR, newEmaLinG, newEmaLinB)

        val compR = (newEmaLinR * tuning.brightnessCompensation).coerceIn(0.0, 1.0)
        val compG = (newEmaLinG * tuning.brightnessCompensation).coerceIn(0.0, 1.0)
        val compB = (newEmaLinB * tuning.brightnessCompensation).coerceIn(0.0, 1.0)

        val sR = ColorConverter.linearToSrgb(compR)
        val sG = ColorConverter.linearToSrgb(compG)
        val sB = ColorConverter.linearToSrgb(compB)

        val lumLinear = ColorConverter.luminance(newEmaLinR, newEmaLinG, newEmaLinB)
        // Previous threshold of 0.1 was in LINEAR space, which corresponds to roughly 38%
        // PERCEPTUAL brightness due to gamma - meaning most ordinary dim/mid-brightness content
        // was having its saturation boost suppressed, not just truly dark content.
        val saturationTaperThreshold = 0.02
        var effBoost = if (ablation.saturationBoost) tuning.saturationBoost else 1.0f
        if (lumLinear < saturationTaperThreshold) {
            effBoost = 1.0f + (tuning.saturationBoost - 1.0f) *
                (lumLinear / saturationTaperThreshold).toFloat().coerceAtLeast(0f)
        }

        val (h, s, v) = rgbToHsv(sR, sG, sB)
        val newS = (s * effBoost).coerceIn(0f, 1f)
        val (hR, hG, hB) = hsvToRgb(h, newS, v)

        // True black bypasses the floor entirely rather than being pushed up to a visible minimum;
        // dim-but-not-black content is lifted so it stays visible.
        val floored = if (ablation.floor) {
            AmbianceOutputRules.floorRamped(hR, hG, hB)
        } else {
            Triple(hR, hG, hB)
        }

        val zones = ArrayList<ZoneColor>(16)
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val (r, g, b) = rawColors[row * gridCols + col]
                zones.add(ZoneColor(col, row, r, g, b))
            }
        }

        return Result(
            color = Triple(floored.first, floored.second, floored.third),
            isSceneCut = isSceneCut,
            zones = zones,
            roi = Roi(left, top, right, bottom)
        )
    }

    /**
     * The letterbox crop: walk in from each edge until a line of ten samples averages brighter than
     * [LETTERBOX_THRESH], then refuse to return anything smaller than half the frame.
     *
     * Public and stateless so a bench can stabilise it without going through [analyse] - which is
     * how it was established that this rectangle moves seven times a second in dark scenes and not
     * at all in bright ones.
     */
    fun detectRoi(width: Int, height: Int, pixel: (Int, Int) -> Int): Roi {
        fun getLum(x: Int, y: Int): Int {
            val p = pixel(x, y)
            return ColorConverter.luminance(
                ((p shr 16) and 0xFF).toDouble(),
                ((p shr 8) and 0xFF).toDouble(),
                (p and 0xFF).toDouble()
            ).toInt()
        }

        var top = 0; var bottom = height - 1; var left = 0; var right = width - 1
        val thresh = LETTERBOX_THRESH
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
        return Roi(left, top, right, bottom)
    }

    companion object {
        /** Mean luma a border line must exceed to count as picture rather than letterbox. */
        const val LETTERBOX_THRESH = 12
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
    // perceptual-brightness correction (r/g/b -> (x/255)^3*255) which this ambiance path has never
    // had. Unifying them would visibly darken ambiance output on every frame.
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

/**
 * Switches for taking one stage of the decision out at a time, so a bench can attribute an effect
 * to a cause rather than guess at one. **Every default is what ships**, so the no-argument value is
 * the shipped pipeline and nothing on the device is affected by this type existing.
 */
data class AmbianceAblation(
    /** The hold-still-until-it-moves-enough test on the aggregate colour. */
    val deadband: Boolean = true,
    /**
     * Falling transitions using a larger alpha than rising ones (the `pow(2.2)` line).
     *
     * **Off since 2026-09-27.** It rectified noise into a sawtooth in dark scenes
     * (`AmbianceVideoBench`), and Joe picked the symmetric fall as steadier in both lab sittings:
     * 5-0 without the film, 6-0 watching it. The cost is that the lights now dim at the same pace
     * they brighten, everywhere; that trade is being judged by living with it.
     */
    val asymmetricFall: Boolean = false,
    /** The 1.4x chroma boost, which multiplies channel *differences* and so is loudest near black. */
    val saturationBoost: Boolean = true,
    /** Lifting dim-but-not-black content to a visible minimum. */
    val floor: Boolean = true,
    /** The exponential smoother itself. Off means follow the frame exactly - the content control. */
    val ema: Boolean = true
)

/**
 * The ambiance settings the per-frame decision reads. On device these come from
 * `ambiance_settings_prefs` every frame; the defaults here are that file's defaults, so a bench
 * that constructs one with no arguments is running what Joe is running.
 */
data class AmbianceTuning(
    val responseSpeed: Float = 0.5f,
    val saturationBoost: Float = 1.4f,
    val brightnessCompensation: Float = 1.0f,
    val sceneCutSensitivity: Float = 110.0f,
    val smoothnessMs: Int = DEFAULT_SMOOTHNESS_MS,
    val noiseDeadband: Float = 0.10f
)
