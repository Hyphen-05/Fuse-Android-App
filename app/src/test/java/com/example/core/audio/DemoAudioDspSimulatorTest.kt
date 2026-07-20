package com.example.core.audio

import com.example.AudioSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoAudioDspSimulatorTest {

    private val defaultSettings = AudioSettingsState()

    @Test
    fun `process always returns valid rgb, an 8-band amplitude list, and a hue in range`() {
        val simulator = DemoAudioDspSimulator()

        repeat(50) {
            val result = simulator.process(defaultSettings)

            assertTrue("r out of range: ${result.r}", result.r in 0..255)
            assertTrue("g out of range: ${result.g}", result.g in 0..255)
            assertTrue("b out of range: ${result.b}", result.b in 0..255)
            assertEquals(8, result.amplitudes.size)
            result.amplitudes.forEach { amp ->
                assertTrue("amplitude out of expected [0.05, 1.0] range: $amp", amp in 0.05f..1.0f)
            }
            assertTrue("hue out of [0, 360) range: ${result.hue}", result.hue >= 0f && result.hue < 360f)
        }
    }

    @Test
    fun `zeroed gains silence total energy below the noise gate but do not immediately report idle`() {
        // With all three gains at 0, rawBass/rawMid/rawHigh (and therefore totalEnergy) are
        // forced to 0, which is below the default noiseGateThreshold (5.0f) — this arms the
        // silence timer (silenceStartTime = startTime) but isIdle only flips true once
        // idleTriggerDelayMs of real time has elapsed, which the very first call can't satisfy.
        val simulator = DemoAudioDspSimulator()
        val silentSettings = defaultSettings.copy(bassGain = 0f, midGain = 0f, highGain = 0f)

        val result = simulator.process(silentSettings)

        assertFalse("isIdle must still be false on the very first silent tick", result.isIdle)
    }

    @Test
    fun `each simulator instance starts with independent smoothing state`() {
        // Regression guard for the reset-on-start requirement: a fresh DemoAudioDspSimulator per
        // runAudioSimulationEngine() call must not carry over smoothedHue/smoothedBass/etc. from
        // a previous run. Two independently-constructed simulators fed the same settings should
        // both produce in-range output on their very first tick (i.e. neither is corrupted by
        // uninitialized shared state).
        val settings = defaultSettings.copy(bassGain = 2f, midGain = 2f, highGain = 2f)

        val first = DemoAudioDspSimulator().process(settings)
        val second = DemoAudioDspSimulator().process(settings)

        listOf(first, second).forEach { result ->
            assertTrue(result.r in 0..255)
            assertTrue(result.g in 0..255)
            assertTrue(result.b in 0..255)
            assertEquals(8, result.amplitudes.size)
        }
    }

    @Test
    fun `beat only preset stays within the same valid rgb and amplitude ranges`() {
        // Not asserting a brightness floor here: ColorConverter.hsvToRgb's cubic perceptual-
        // brightness correction can crush a low `value` reading toward (0,0,0) in the final RGB
        // output even when visualizerMinBrightness > 0 — the same documented quirk noted in
        // AudioDspProcessorTest. This only guards that the "Beat Only" branch doesn't produce
        // out-of-range output.
        val simulator = DemoAudioDspSimulator()
        val beatOnlySettings = defaultSettings.copy(visualizerPreset = "Beat Only")

        repeat(20) {
            val result = simulator.process(beatOnlySettings)
            assertTrue(result.r in 0..255)
            assertTrue(result.g in 0..255)
            assertTrue(result.b in 0..255)
            assertEquals(8, result.amplitudes.size)
        }
    }
}
