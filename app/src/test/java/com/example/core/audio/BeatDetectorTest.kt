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

    // P1 predictive flash scheduling (mapping-proposal-audio-to-led-2026-07-21.md §4, "fast
    // causal trigger when not tempo-locked"): causalIsCandidate is a cheap trailing-window
    // onset check, independent of the centered detector/tempo lock above -- it should read false
    // against a flat, quiet history and flip true the instant a real transient hits, with no
    // lookahead buffer required (unlike isBeat, which needs `hasLatest`/`hasEarliest` samples).
    @Test
    fun `causalIsCandidate is false on a flat quiet history and true on a sudden transient`() {
        val detector = BeatDetector()
        var now = 0L
        var lastResult = detector.process(
            magnitude = uniformFrame(1f).first,
            realBins = uniformFrame(1f).second,
            imagBins = uniformFrame(1f).third,
            bassRange = 1..8,
            midRange = 9..46,
            midWeight = 0.25f,
            thresholdMultiplier = 1.3f,
            minCooldownMs = 180,
            maxCooldownMs = 250,
            now = now
        )
        // Build up a flat, quiet flux history -- identical magnitude every frame means near-zero
        // onset flux (log-compressed diff of an unchanging signal), so the trailing median/MAD
        // settles near zero too.
        repeat(30) {
            now += 20L
            lastResult = detector.process(
                magnitude = uniformFrame(1f).first,
                realBins = uniformFrame(1f).second,
                imagBins = uniformFrame(1f).third,
                bassRange = 1..8,
                midRange = 9..46,
                midWeight = 0.25f,
                thresholdMultiplier = 1.3f,
                minCooldownMs = 180,
                maxCooldownMs = 250,
                now = now
            )
        }
        assertEquals(false, lastResult.causalIsCandidate)

        // A sudden, large magnitude jump produces a large positive onset flux against that flat
        // trailing baseline -- should register as a causal candidate at ~1-frame latency, with no
        // need to wait for the centered detector's lookahead window.
        now += 20L
        val transientResult = detector.process(
            magnitude = uniformFrame(80f).first,
            realBins = uniformFrame(80f).second,
            imagBins = uniformFrame(80f).third,
            bassRange = 1..8,
            midRange = 9..46,
            midWeight = 0.25f,
            thresholdMultiplier = 1.3f,
            minCooldownMs = 180,
            maxCooldownMs = 250,
            now = now
        )
        assertEquals(true, transientResult.causalIsCandidate)
    }
}
