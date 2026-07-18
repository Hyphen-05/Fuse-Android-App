package com.example.core

import com.example.core.color.ColorConverter
import com.example.core.calibration.CalibrationMatrixSolver
import com.example.core.protocol.DuoCoProtocol
import com.example.core.animation.ProceduralSceneParams
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoreExtractionTest {

    @Test
    fun testWarmthToKelvin() {
        // 0% warmth should map to mired = 500, which is 2000K
        val kelvinMin = ColorConverter.warmthToKelvin(0)
        assertEquals(2000.0, kelvinMin, 0.01)

        // 100% warmth should map to mired = 100, which is 10000K
        val kelvinMax = ColorConverter.warmthToKelvin(100)
        assertEquals(10000.0, kelvinMax, 0.01)

        // 50% warmth should map to mired = 500 - (0.5^2)*400 = 400, which is 2500K
        val kelvinMid = ColorConverter.warmthToKelvin(50)
        assertEquals(2500.0, kelvinMid, 0.01)
    }

    @Test
    fun testConvertKelvinToRgb() {
        // Kelvin = 6500K (standard daylight D65)
        val rgb6500 = ColorConverter.convertKelvinToRgb(6500.0)
        assertNotNull(rgb6500)
        assertEquals(3, rgb6500.size)
        // Verify channel values are within RGB bounds [0, 255]
        for (channel in rgb6500) {
            assertTrue(channel in 0..255)
        }

        // Kelvin = 2000K (warm light, should have high red, lower blue)
        val rgb2000 = ColorConverter.convertKelvinToRgb(2000.0)
        assertTrue(rgb2000[0] > rgb2000[2]) // Red > Blue
    }

    @Test
    fun testHsvToRgb() {
        // Test pure Green with S=1, V=1, H=120
        val greenRgb = ColorConverter.hsvToRgb(120f, 1f, 1f)
        assertEquals(0, greenRgb.first)
        assertEquals(255, greenRgb.second)
        assertEquals(0, greenRgb.third)

        // Test black with V=0
        val blackRgb = ColorConverter.hsvToRgb(0f, 1f, 0f)
        assertEquals(0, blackRgb.first)
        assertEquals(0, blackRgb.second)
        assertEquals(0, blackRgb.third)
    }

    @Test
    fun testFit3x3Matrix_identity() {
        val targets = listOf(
            intArrayOf(255, 0, 0),
            intArrayOf(0, 255, 0),
            intArrayOf(0, 0, 255),
            intArrayOf(255, 255, 255)
        )
        val measured = targets

        val matrix = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        val epsilon = 0.01f
        assertTrue(abs(matrix[0] - 1.0f) < epsilon)
        assertTrue(abs(matrix[1] - 0.0f) < epsilon)
        assertTrue(abs(matrix[2] - 0.0f) < epsilon)
        
        assertTrue(abs(matrix[3] - 0.0f) < epsilon)
        assertTrue(abs(matrix[4] - 1.0f) < epsilon)
        assertTrue(abs(matrix[5] - 0.0f) < epsilon)
        
        assertTrue(abs(matrix[6] - 0.0f) < epsilon)
        assertTrue(abs(matrix[7] - 0.0f) < epsilon)
        assertTrue(abs(matrix[8] - 1.0f) < epsilon)
    }

    @Test
    fun testFit3x3Matrix_scaled() {
        val targets = listOf(
            intArrayOf(255, 0, 0),
            intArrayOf(0, 255, 0),
            intArrayOf(0, 0, 255),
            intArrayOf(255, 255, 255)
        )
        val measured = listOf(
            intArrayOf(255, 0, 0),
            intArrayOf(0, 128, 0),
            intArrayOf(0, 0, 255),
            intArrayOf(255, 128, 255)
        )

        val matrix = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        val epsilon = 0.1f
        assertTrue(abs(matrix[0] - 1.0f) < epsilon)
        assertTrue(abs(matrix[4] - 2.0f) < epsilon) // Compenses green loss (128 -> 255 is factor ~2)
        assertTrue(abs(matrix[8] - 1.0f) < epsilon)
    }

    @Test
    fun testDuoCoProtocolCommands() {
        // Power On Command
        val pOn = DuoCoProtocol.createPowerCommand(true)
        val pOnHex = DuoCoProtocol.formatHex(pOn)
        assertEquals("7E 00 04 F0 00 01 FF 00 EF", pOnHex)

        // Power Off Command
        val pOff = DuoCoProtocol.createPowerCommand(false)
        val pOffHex = DuoCoProtocol.formatHex(pOff)
        assertEquals("7E 00 04 00 00 00 FF 00 EF", pOffHex)

        // Color Command
        val colorCmd = DuoCoProtocol.createColorCommand(255, 0, 128)
        val colorHex = DuoCoProtocol.formatHex(colorCmd)
        assertEquals("7E 07 05 03 FF 00 80 10 EF", colorHex)

        // Parse Hex
        val parsed = DuoCoProtocol.parseHex("7E 00 04 F0 00 01 FF 00 EF")
        assertNotNull(parsed)
        assertArrayEquals(pOn, parsed)
    }

    @Test
    fun testProceduralSceneParamsJson() {
        val params = ProceduralSceneParams(
            palette = listOf(Triple(255, 0, 0), Triple(0, 255, 0)),
            movementType = "drift",
            energy = 0.8f,
            randomness = 0.2f,
            colorInterpMode = "sine",
            brightnessBehaviour = "pulse",
            syncBrightnessToColor = true,
            harmonicLayering = "none",
            sequenceMode = "random"
        )

        val json = params.toJson()
        val parsed = ProceduralSceneParams.fromJson(json)

        assertEquals(params.palette, parsed.palette)
        assertEquals(params.movementType, parsed.movementType)
        assertEquals(params.energy, parsed.energy, 0.001f)
        assertEquals(params.randomness, parsed.randomness, 0.001f)
        assertEquals(params.colorInterpMode, parsed.colorInterpMode)
        assertEquals(params.brightnessBehaviour, parsed.brightnessBehaviour)
        assertEquals(params.syncBrightnessToColor, parsed.syncBrightnessToColor)
        assertEquals(params.harmonicLayering, parsed.harmonicLayering)
        assertEquals(params.sequenceMode, parsed.sequenceMode)
    }
}
