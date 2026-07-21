package com.example.core.color

import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.roundToInt

object ColorConverter {
    /**
     * Map warmth percentage (0-100) non-linearly to Kelvin temperature to compensate for 
     * diminishing human visual returns closer to cool (high Kelvin).
     */
    fun warmthToKelvin(percent: Int, exponent: Double = 2.0): Double {
        val coercedPercent = percent.coerceIn(0, 100)
        val x = coercedPercent / 100.0
        val y = x.pow(exponent)
        val mired = 500.0 - y * 400.0
        return 1000000.0 / mired
    }

    /**
     * Convert Kelvin to linear sRGB and then gamma-corrected sRGB.
     */
    fun convertKelvinToRgb(kelvin: Double): IntArray {
        // Clamp to valid range for Kim et al. approximation
        val t = kelvin.coerceIn(1667.0, 25000.0)

        // Calculate x(T)
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }

        // Calculate y(x)
        val y = if (t <= 2222.0) {
            -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
        } else if (t <= 4000.0) {
            -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
        } else {
            3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }

        // xy to XYZ
        val Y = 1.0
        val ySafe = if (y == 0.0) 1e-5 else y
        val X = (x / ySafe) * Y
        val Z = ((1.0 - x - y) / ySafe) * Y

        // XYZ to linear sRGB
        var rLin = 3.2404542 * X - 1.5371385 * Y - 0.4985314 * Z
        var gLin = -0.9692660 * X + 1.8760108 * Y + 0.0415560 * Z
        var bLin = 0.0556434 * X - 0.2040259 * Y + 1.0572252 * Z

        // Clamp each channel to valid range with per-channel clamping
        rLin = rLin.coerceIn(0.0, 1.0)
        gLin = gLin.coerceIn(0.0, 1.0)
        bLin = bLin.coerceIn(0.0, 1.0)

        // Gamma correction (linear sRGB -> sRGB)
        val rGamma = if (rLin <= 0.0031308) 12.92 * rLin else 1.055 * rLin.pow(1.0 / 2.4) - 0.055
        val gGamma = if (gLin <= 0.0031308) 12.92 * gLin else 1.055 * gLin.pow(1.0 / 2.4) - 0.055
        val bGamma = if (bLin <= 0.0031308) 12.92 * bLin else 1.055 * bLin.pow(1.0 / 2.4) - 0.055

        val finalRed = (rGamma * 255.0).toInt().coerceIn(0, 255)
        val finalGreen = (gGamma * 255.0).toInt().coerceIn(0, 255)
        val finalBlue = (bGamma * 255.0).toInt().coerceIn(0, 255)

        return intArrayOf(finalRed, finalGreen, finalBlue)
    }

    /**
     * HSV to RGB conversion with Perceptual Brightness Correction (CIE 1931 / strict cubic curve).
     */
    fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rVal, gVal, bVal) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rVal + m) * 255f).toInt().coerceIn(0, 255)
        val g = ((gVal + m) * 255f).toInt().coerceIn(0, 255)
        val b = ((bVal + m) * 255f).toInt().coerceIn(0, 255)

        // 4. Perceptual Brightness Correction (CIE 1931 / strict cubic curve)
        val rCie = (r.toFloat() / 255f).let { it * it * it * 255f }.toInt().coerceIn(0, 255)
        val gCie = (g.toFloat() / 255f).let { it * it * it * 255f }.toInt().coerceIn(0, 255)
        val bCie = (b.toFloat() / 255f).let { it * it * it * 255f }.toInt().coerceIn(0, 255)

        return Triple(rCie, gCie, bCie)
    }

    /**
     * Gamma-decode a single sRGB channel (0-255) to linear light (0.0-1.0).
     */
    fun srgbToLinear(srgb: Int): Double = (srgb / 255.0).pow(2.2)

    /**
     * Gamma-encode a linear-light channel (0.0-1.0) back to sRGB (0-255).
     */
    fun linearToSrgb(linear: Double): Int = (linear.pow(1.0 / 2.2) * 255.0).roundToInt().coerceIn(0, 255)

    /**
     * Rec. 709 relative luminance. Scale-agnostic: pass 0-255 or 0.0-1.0 channels
     * consistently and the result is on the same scale.
     */
    fun luminance(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b
}
