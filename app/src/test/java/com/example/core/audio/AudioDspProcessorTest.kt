package com.example.core.audio

import com.example.AudioSettingsState
import com.example.hardware.audio.AudioBackend
import com.example.hardware.audio.AudioCaptureFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspProcessorTest {

    private val defaultSettings = AudioSettingsState()

    private fun silentFrame(numBins: Int = 512, backend: AudioBackend = AudioBackend.AUDIO_RECORD): AudioCaptureFrame {
        return AudioCaptureFrame(
            magnitude = FloatArray(numBins),
            realBins = FloatArray(numBins),
            imagBins = FloatArray(numBins),
            numBins = numBins,
            backend = backend,
            timestampMs = 0L
        )
    }

    /**
     * A frame whose bins 1..numBins-1 all carry [value]. Because the noise gate derives its
     * threshold (10th-percentile-of-recent-frame-average * 1.5) from the same signal, a single
     * uniform-magnitude frame always gates itself out completely (avg == value, so
     * value < avg * 1.5 always holds) — this mirrors the real algorithm exactly, it isn't a test
     * artifact. To get a frame that actually survives the gate, prime the noise floor low first
     * with [warmUpQuiet], then feed a louder uniform frame.
     */
    private fun uniformFrame(value: Float, numBins: Int = 512, backend: AudioBackend = AudioBackend.AUDIO_RECORD): AudioCaptureFrame {
        val magnitude = FloatArray(numBins) { if (it == 0) 0f else value }
        return AudioCaptureFrame(
            magnitude = magnitude,
            realBins = FloatArray(numBins) { if (it == 0) 0f else value },
            imagBins = FloatArray(numBins),
            numBins = numBins,
            backend = backend,
            timestampMs = 0L
        )
    }

    /** Feeds several quiet frames through [processor] so the rolling noise floor settles low. */
    private fun warmUpQuiet(processor: AudioDspProcessor, backend: AudioBackend, startMs: Long): Long {
        var now = startMs
        repeat(5) {
            processor.process(uniformFrame(1f, backend = backend), defaultSettings, now)
            now += 25L
        }
        return now
    }

    @Test
    fun `frame with fewer than 349 bins is skipped entirely`() {
        val processor = AudioDspProcessor(AudioBackend.VISUALIZER)
        val frame = silentFrame(numBins = 300, backend = AudioBackend.VISUALIZER)

        val result = processor.process(frame, defaultSettings, nowMs = 100L)

        assertNull("frames below the 349-bin floor must be dropped, mirroring the original `if (numBins < 349) return` guard", result)
    }

    @Test
    fun `sustained silence eventually reports isIdle true after idleTriggerDelayMs`() {
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val frame = silentFrame()

        // Use a non-zero base timestamp: the original code (preserved here) treats
        // silenceStartTime == 0L as "unset", so a first call at nowMs == 0L would collide with
        // that sentinel and never actually arm the idle timer.
        val first = processor.process(frame, defaultSettings, nowMs = 1_000L)
        assertTrue(first != null)
        assertFalse("elapsed time is 0 on the very first silent frame, so isIdle must still be false", first!!.isIdle)

        val afterDelay = processor.process(frame, defaultSettings, nowMs = 1_000L + defaultSettings.idleTriggerDelayMs + 1)
        assertTrue(afterDelay!!.isIdle)
    }

    @Test
    fun `loud frame that clears the noise gate is not idle`() {
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        var now = warmUpQuiet(processor, AudioBackend.AUDIO_RECORD, startMs = 10L)

        // 20x the primed noise floor comfortably clears the noiseFloor * 1.5 gate.
        val result = processor.process(uniformFrame(20f), defaultSettings, now)

        assertTrue(result != null)
        assertFalse(result!!.isIdle)
        assertTrue(result.r in 0..255)
        assertTrue(result.g in 0..255)
        assertTrue(result.b in 0..255)
        assertEquals(8, result.amplitudes.size)
    }

    @Test
    fun `idle amplitudes are a pulsing 8-band list once idle is reached`() {
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val frame = silentFrame()

        processor.process(frame, defaultSettings, nowMs = 500L)
        val idleResult = processor.process(frame, defaultSettings, nowMs = 500L + defaultSettings.idleTriggerDelayMs + 1)!!

        assertTrue(idleResult.isIdle)
        assertEquals(8, idleResult.amplitudes.size)
        // The idle pulse formula (0.15 +/- 0.10, coerced into [0.05, 1.0]) is identical across
        // all 8 bands for a given timestamp.
        val first = idleResult.amplitudes[0]
        idleResult.amplitudes.forEach { assertEquals(first, it, 0.0001f) }
        assertTrue(first in 0.05f..1.0f)
    }

    @Test
    fun `baseSat coefficients diverge by backend for identical input, per source`() {
        // Regression test for the one real, intentional per-backend divergence documented in
        // AudioDspProcessor: baseSat uses (0.5 + 0.5 * ratio).coerceIn(0.5, 1.0) for VISUALIZER
        // vs (0.4 + 0.6 * ratio).coerceIn(0.4, 1.0) for AUDIO_RECORD. A single moderate-energy
        // frame (not enough to saturate either formula's coerceIn upper bound at 1.0) fed
        // through both, right after warm-up, should produce different saturation and therefore
        // different RGB.
        val visProcessor = AudioDspProcessor(AudioBackend.VISUALIZER)
        val micProcessor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)

        val visNow = warmUpQuiet(visProcessor, AudioBackend.VISUALIZER, startMs = 10L)
        val micNow = warmUpQuiet(micProcessor, AudioBackend.AUDIO_RECORD, startMs = 10L)

        // Moderate: clears the primed noise gate (1f * 1.5 = 1.5 threshold) but, on the very
        // first loud frame, sits well below either backend's initial maxObservedMid seed
        // (20f for VISUALIZER, 10f for AUDIO_RECORD), so neither formula's ratio saturates to 1.
        val visResult = visProcessor.process(uniformFrame(6f, backend = AudioBackend.VISUALIZER), defaultSettings, visNow)!!
        val micResult = micProcessor.process(uniformFrame(6f, backend = AudioBackend.AUDIO_RECORD), defaultSettings, micNow)!!

        val rgbDiffers = visResult.r != micResult.r || visResult.g != micResult.g || visResult.b != micResult.b
        assertTrue(
            "expected VISUALIZER and AUDIO_RECORD backends to diverge given identical input due to the preserved baseSat formula difference " +
                "(vis=${visResult.r},${visResult.g},${visResult.b} mic=${micResult.r},${micResult.g},${micResult.b})",
            rgbDiffers
        )
    }
}
