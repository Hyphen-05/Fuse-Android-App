package com.example.core.calibration

import com.example.db.ColorCalibration

/**
 * Pure calibration-matrix helpers moved verbatim out of `RgbControllerViewModel` (Phase 6).
 * No Android/hardware dependencies, no ViewModel state — parses [ColorCalibration] samples and
 * interpolates between per-brightness-level 3x3 color correction matrices.
 */
object CalibrationMatrixOps {

    fun getCalibrationMatrices(calibration: ColorCalibration): Map<Float, FloatArray> {
        val map = mutableMapOf<Float, FloatArray>()
        try {
            val json = org.json.JSONObject(calibration.samplesJson)
            if (json.optBoolean("is_multi_brightness", false)) {
                val matricesObj = json.getJSONObject("matrices")
                val keys = matricesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val keyFloat = key.toFloatOrNull() ?: continue
                    val arr = matricesObj.getJSONArray(key)
                    val matrix = FloatArray(9)
                    for (i in 0..8) {
                        matrix[i] = arr.getDouble(i).toFloat()
                    }
                    map[keyFloat] = matrix
                }
            }
        } catch (e: Exception) {
            // Error parsing multi-brightness matrices
        }

        // If empty, populate with the single matrix from ColorCalibration fields
        if (map.isEmpty()) {
            map[100f] = floatArrayOf(
                calibration.m11, calibration.m12, calibration.m13,
                calibration.m21, calibration.m22, calibration.m23,
                calibration.m31, calibration.m32, calibration.m33
            )
        }
        return map
    }

    fun interpolateMatrices(brightnessPercent: Float, matrices: Map<Float, FloatArray>): FloatArray {
        if (matrices.isEmpty()) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }
        if (matrices.size == 1) {
            return matrices.values.first()
        }

        val sortedLevels = matrices.keys.sorted()
        val minLevel = sortedLevels.first()
        val maxLevel = sortedLevels.last()

        if (brightnessPercent <= minLevel) {
            return matrices[minLevel] ?: matrices.values.first()
        }
        if (brightnessPercent >= maxLevel) {
            return matrices[maxLevel] ?: matrices.values.first()
        }

        var lower = minLevel
        var upper = maxLevel
        for (i in 0 until sortedLevels.size - 1) {
            val l1 = sortedLevels[i]
            val l2 = sortedLevels[i + 1]
            if (brightnessPercent >= l1 && brightnessPercent <= l2) {
                lower = l1
                upper = l2
                break
            }
        }

        val m1 = matrices[lower] ?: return matrices.values.first()
        val m2 = matrices[upper] ?: return m1

        val t = (brightnessPercent - lower) / (upper - lower)
        val result = FloatArray(9)
        for (i in 0..8) {
            result[i] = m1[i] * (1f - t) + m2[i] * t
        }
        return result
    }

    /**
     * Old full-spectrum RGB calibration is entirely disabled per scope change.
     * Preserved as a stub (relocated, not re-enabled) — see CLAUDE.md Phase 6 notes.
     */
    fun applyCalibrationIfRequired(address: String, cmd: ByteArray): ByteArray {
        return cmd
    }
}
