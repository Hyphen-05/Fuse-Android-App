package com.example.core.calibration

import com.example.db.ColorCalibration
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalibrationMatrixOpsTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    private fun calibration(
        samplesJson: String = "{}",
        m11: Float = 1f, m12: Float = 2f, m13: Float = 3f,
        m21: Float = 4f, m22: Float = 5f, m23: Float = 6f,
        m31: Float = 7f, m32: Float = 8f, m33: Float = 9f
    ) = ColorCalibration(
        macAddress = "AA:BB:CC:DD:EE:FF",
        timestamp = 0L,
        version = 1,
        m11 = m11, m12 = m12, m13 = m13,
        m21 = m21, m22 = m22, m23 = m23,
        m31 = m31, m32 = m32, m33 = m33,
        samplesJson = samplesJson,
        exposureTimeNs = 0L,
        sensitivityIso = 100,
        whiteBalanceLocked = false
    )

    // --- getCalibrationMatrices ---

    @Test
    fun `getCalibrationMatrices falls back to single 100pct entry when samplesJson has no multi-brightness flag`() {
        val cal = calibration(samplesJson = "{}")

        val result = CalibrationMatrixOps.getCalibrationMatrices(cal)

        assertEquals(1, result.size)
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f),
            result[100f],
            0.0001f
        )
    }

    @Test
    fun `getCalibrationMatrices falls back to single entry when samplesJson is malformed`() {
        val cal = calibration(samplesJson = "not valid json")

        val result = CalibrationMatrixOps.getCalibrationMatrices(cal)

        assertEquals(1, result.size)
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f),
            result[100f],
            0.0001f
        )
    }

    @Test
    fun `getCalibrationMatrices parses multi-brightness matrices from json`() {
        val matrix50 = (0..8).map { it.toDouble() }
        val matrix100 = (0..8).map { (it * 2).toDouble() }

        val json = JSONObject().apply {
            put("is_multi_brightness", true)
            put("matrices", JSONObject().apply {
                put("50.0", org.json.JSONArray(matrix50))
                put("100.0", org.json.JSONArray(matrix100))
            })
        }
        val cal = calibration(samplesJson = json.toString())

        val result = CalibrationMatrixOps.getCalibrationMatrices(cal)

        assertEquals(2, result.size)
        assertArrayEquals(
            floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            result[50f],
            0.0001f
        )
        assertArrayEquals(
            floatArrayOf(0f, 2f, 4f, 6f, 8f, 10f, 12f, 14f, 16f),
            result[100f],
            0.0001f
        )
    }

    @Test
    fun `getCalibrationMatrices ignores non-numeric brightness keys`() {
        val json = JSONObject().apply {
            put("is_multi_brightness", true)
            put("matrices", JSONObject().apply {
                put("not_a_number", org.json.JSONArray((0..8).map { it.toDouble() }))
            })
        }
        val cal = calibration(samplesJson = json.toString())

        val result = CalibrationMatrixOps.getCalibrationMatrices(cal)

        // The bad key is skipped, leaving the map empty, so it falls back to the single-matrix path.
        assertEquals(1, result.size)
        assertArrayEquals(
            floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f),
            result[100f],
            0.0001f
        )
    }

    // --- interpolateMatrices ---

    @Test
    fun `interpolateMatrices returns identity fallback on empty map`() {
        val result = CalibrationMatrixOps.interpolateMatrices(50f, emptyMap())

        assertArrayEquals(identity, result, 0.0001f)
    }

    @Test
    fun `interpolateMatrices returns the only entry when map has a single level`() {
        val single = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)

        val result = CalibrationMatrixOps.interpolateMatrices(75f, mapOf(100f to single))

        assertArrayEquals(single, result, 0.0001f)
    }

    @Test
    fun `interpolateMatrices returns exact match at a known brightness level`() {
        val m50 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val m100 = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)
        val matrices = mapOf(50f to m50, 100f to m100)

        val result = CalibrationMatrixOps.interpolateMatrices(50f, matrices)

        assertArrayEquals(m50, result, 0.0001f)
    }

    @Test
    fun `interpolateMatrices interpolates linearly between two known brightness levels`() {
        val m0 = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val m100 = floatArrayOf(10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f, 10f)
        val matrices = mapOf(0f to m0, 100f to m100)

        val result = CalibrationMatrixOps.interpolateMatrices(25f, matrices)

        val expected = floatArrayOf(2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f, 2.5f)
        assertArrayEquals(expected, result, 0.0001f)
    }

    @Test
    fun `interpolateMatrices clamps below the minimum level`() {
        val m50 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val m100 = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)
        val matrices = mapOf(50f to m50, 100f to m100)

        val result = CalibrationMatrixOps.interpolateMatrices(10f, matrices)

        assertArrayEquals(m50, result, 0.0001f)
    }

    @Test
    fun `interpolateMatrices clamps above the maximum level`() {
        val m50 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val m100 = floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f)
        val matrices = mapOf(50f to m50, 100f to m100)

        val result = CalibrationMatrixOps.interpolateMatrices(150f, matrices)

        assertArrayEquals(m100, result, 0.0001f)
    }

    // --- applyCalibrationIfRequired ---

    @Test
    fun `applyCalibrationIfRequired is a disabled stub that returns the input unchanged`() {
        val cmd = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)

        val result = CalibrationMatrixOps.applyCalibrationIfRequired("AA:BB:CC:DD:EE:FF", cmd)

        assertArrayEquals(cmd, result)
    }
}
