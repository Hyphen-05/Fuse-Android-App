package com.example.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeatDetectorTest {

    private fun uniformFrame(value: Float, numBins: Int = 349): Triple<FloatArray, FloatArray, FloatArray> {
        val magnitude = FloatArray(numBins) { if (it == 0) 0f else value }
        val real = FloatArray(numBins) { if (it == 0) 0f else value }
        val imag = FloatArray(numBins)
        return Triple(magnitude, real, imag)
    }

    // Mapping-layer stage 4 (mapping-proposal-audio-to-led-2026-07-21.md §4, "anticipatory
    // dimming"): BeatResult.nextPredictedBeatMs is a new, purely additive field -- no existing
    // BeatDetector test coverage existed before this, so this file covers only that new surface,
    // not the pre-existing detection/tuning math (which stays explicitly out of scope for this
    // mapping-layer work per the proposal's §7).
    @Test
    fun `nextPredictedBeatMs is null before any tempo lock`() {
        val detector = BeatDetector()
        val (magnitude, real, imag) = uniformFrame(1f)

        val result = detector.process(
            magnitude = magnitude,
            realBins = real,
            imagBins = imag,
            bassRange = 1..8,
            midRange = 9..46,
            midWeight = 0.25f,
            thresholdMultiplier = 1.3f,
            minCooldownMs = 180,
            maxCooldownMs = 250,
            now = 0L
        )

        assertEquals(0f, result.bpm)
        assertNull(
            "no tempo lock has been established yet, so there is no beat grid to extrapolate from",
            result.nextPredictedBeatMs
        )
    }
}
