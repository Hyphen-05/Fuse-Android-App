package com.github.hyphen05.fuse.ui.components

object ColorUtils {
    /**
     * Map warmth percentage (0-100) non-linearly to Kelvin temperature to compensate for 
     * diminishing human visual returns closer to cool (high Kelvin).
     */
    fun warmthToKelvin(percent: Int, exponent: Double = 2.0): Double {
        return com.github.hyphen05.fuse.core.color.ColorConverter.warmthToKelvin(percent, exponent)
    }

    /**
     * Convert Kelvin to sRGB.
     */
    fun convertKelvinToRgb(kelvin: Double): IntArray {
        return com.github.hyphen05.fuse.core.color.ColorConverter.convertKelvinToRgb(kelvin)
    }
}
