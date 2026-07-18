package com.example.ambiance

/**
 * TODO: holds slider values (responseSpeed, smoothness, saturationBoost, brightnessCompensation, updateRateCapFps) with sensible numeric defaults for each
 */
data class AmbianceSettings(
    val responseSpeed: Float = 0.5f,
    val smoothness: Float = 0.5f,
    val saturationBoost: Float = 1.0f,
    val brightnessCompensation: Float = 1.0f,
    val updateRateCapFps: Int = 20,
    val noiseDeadband: Float = 0.10f
)
