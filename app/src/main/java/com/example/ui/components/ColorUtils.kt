package com.example.ui.components

object ColorUtils {
    /**
     * Map warmth percentage (0-100) non-linearly to Kelvin temperature to compensate for 
     * diminishing human visual returns closer to cool (high Kelvin).
     */
    fun warmthToKelvin(percent: Int, exponent: Double = 2.0): Double {
        return com.example.core.color.ColorConverter.warmthToKelvin(percent, exponent)
    }

    /**
     * Convert Kelvin to sRGB.
     */
    fun convertKelvinToRgb(kelvin: Double): IntArray {
        return com.example.core.color.ColorConverter.convertKelvinToRgb(kelvin)
    }
}
