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

    @Test
    fun `Beat Only preset floors brightness at visualizerMinBrightness before any beat has fired`() {
        // Beat Only's value formula is entirely driven by beatPulsePeak/lastBeatFlashTime (both
        // start at their zero defaults). With no beat ever detected, beatEnvelope stays at its
        // floor of 0, so value must equal exactly visualizerMinBrightness on every frame. Only 6
        // total frames are processed here (well under the ~34-frame/680ms span BeatDetector needs
        // before it will even evaluate a candidate), so isBeat is structurally guaranteed false —
        // this isn't relying on amplitude to avoid a false beat.
        //
        // visualizerMinBrightness (0.15) is deliberately a *floor*, not something visibly bright:
        // ColorConverter.hsvToRgb's HSV max-channel equals `value` before its cubic perceptual
        // correction ((c/255)^3*255), and 0.15 cubes down to 0 post-correction. So the observable
        // assertion is that the floor renders as black, not a literal 0.15 brightness reading.
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val settings = defaultSettings.copy(visualizerPreset = "Beat Only")
        var now = warmUpQuiet(processor, AudioBackend.AUDIO_RECORD, startMs = 10L)

        val result = processor.process(uniformFrame(3f), settings, now)!!
        val maxChannel = maxOf(result.r, result.g, result.b) / 255f

        assertEquals(0f, maxChannel, 0.001f)
    }

    @Test
    fun `disabling auto-gain pins observed max trackers to 100 and changes output vs auto-gain enabled`() {
        // With isAutoGainEnabled = false, maxObservedBass/maxObservedMid are pinned to 100.0 every
        // frame (instead of adapting to the signal), which routes bassContribution/midContribution
        // through the flat smoothedX/100 formula instead of the adaptive-range one. For an
        // identical moderate-energy input, that must produce different output than the auto-gain
        // enabled path once the auto-gain tracker has had a chance to adapt away from its seed.
        val autoGainOn = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val autoGainOff = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val onSettings = defaultSettings.copy(isAutoGainEnabled = true)
        val offSettings = defaultSettings.copy(isAutoGainEnabled = false)

        val onNow = warmUpQuiet(autoGainOn, AudioBackend.AUDIO_RECORD, startMs = 10L)
        val offNow = warmUpQuiet(autoGainOff, AudioBackend.AUDIO_RECORD, startMs = 10L)

        // Feed a few louder frames so the auto-gain tracker actually diverges from its seed value.
        var onResult: com.example.core.audio.AudioDspResult? = null
        var offResult: com.example.core.audio.AudioDspResult? = null
        var t1 = onNow
        var t2 = offNow
        repeat(5) {
            onResult = autoGainOn.process(uniformFrame(15f), onSettings, t1)
            offResult = autoGainOff.process(uniformFrame(15f), offSettings, t2)
            t1 += 25L
            t2 += 25L
        }

        val rgbDiffers = onResult!!.r != offResult!!.r || onResult!!.g != offResult!!.g || onResult!!.b != offResult!!.b
        assertTrue(
            "expected auto-gain enabled vs disabled to diverge for identical input " +
                "(on=${onResult!!.r},${onResult!!.g},${onResult!!.b} off=${offResult!!.r},${offResult!!.g},${offResult!!.b})",
            rgbDiffers
        )
    }

    @Test
    fun `a strong transient after a quiet baseline raises Beat Only brightness above silence`() {
        // Drives a real beat through the full BeatDetector pipeline: ~600ms of quiet warm-up
        // (comfortably over the centered median window's half-width of 500ms) at magnitude 1,
        // one loud transient frame at magnitude 80, then continuation frames at magnitude 5
        // advanced until "now" reaches the transient's timestamp plus BeatDetector's 180ms
        // lookahead — the point at which evalTimestamp lands exactly on the transient and it
        // becomes the evaluated peak.
        //
        // Continuation frames deliberately use a magnitude (5) distinct from the warm-up
        // magnitude (1): a uniform frame always gates itself out once the noise-floor history is
        // saturated with its own value (see uniformFrame's doc comment above), so replaying the
        // warm-up magnitude forever would self-gate every continuation frame to zero energy and
        // the `totalEnergy >= noiseGateThreshold` guard in AudioDspProcessor.process would mask
        // any real beat regardless of what BeatDetector decides. Magnitude 5 clears the gate
        // (noise floor stays pinned near 1 while warm-up frames still dominate the history) while
        // producing no flux of its own (identical consecutive frames -> zero magnitude diff), so
        // the only flux spike in the whole run is the transient itself.
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val settings = defaultSettings.copy(visualizerPreset = "Beat Only")

        var now = 0L
        repeat(30) {
            processor.process(uniformFrame(1f, backend = AudioBackend.AUDIO_RECORD), settings, now)
            now += 20L
        }

        val transientTime = now
        processor.process(uniformFrame(80f, backend = AudioBackend.AUDIO_RECORD), settings, now)
        now += 20L

        var peakMaxChannel = 0f
        while (now <= transientTime + 200L) {
            val result = processor.process(uniformFrame(5f, backend = AudioBackend.AUDIO_RECORD), settings, now)!!
            peakMaxChannel = maxOf(peakMaxChannel, maxOf(result.r, result.g, result.b) / 255f)
            now += 20L
        }

        assertTrue(
            "expected a detected beat to push Beat Only brightness measurably above the silent floor (peak=$peakMaxChannel)",
            peakMaxChannel > 0.05f
        )
    }

    @Test
    fun `flashFloor and flashRange settings scale the beat flash peak`() {
        // Same beat-driving setup as the transient test above, run twice with the same input:
        // once with flashFloor/flashRange zeroed out (beatPulsePeak should collapse toward 0
        // regardless of strength/confidence) and once with the defaults (0.6f/0.4f, the same
        // values the previously hard-coded formula used). Proves the two AudioSettingsState
        // fields actually drive AudioDspProcessor's output rather than sitting unused.
        fun peakFor(settings: AudioSettingsState): Float {
            val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
            var now = 0L
            repeat(30) {
                processor.process(uniformFrame(1f, backend = AudioBackend.AUDIO_RECORD), settings, now)
                now += 20L
            }
            val transientTime = now
            processor.process(uniformFrame(80f, backend = AudioBackend.AUDIO_RECORD), settings, now)
            now += 20L

            var peak = 0f
            while (now <= transientTime + 200L) {
                val result = processor.process(uniformFrame(5f, backend = AudioBackend.AUDIO_RECORD), settings, now)!!
                peak = maxOf(peak, maxOf(result.r, result.g, result.b) / 255f)
                now += 20L
            }
            return peak
        }

        val zeroedPeak = peakFor(defaultSettings.copy(visualizerPreset = "Beat Only", flashFloor = 0f, flashRange = 0f))
        val defaultPeak = peakFor(defaultSettings.copy(visualizerPreset = "Beat Only"))

        assertTrue(
            "zeroed flashFloor/flashRange should suppress the beat flash relative to the 0.6f/0.4f defaults (zeroed=$zeroedPeak, default=$defaultPeak)",
            zeroedPeak < defaultPeak
        )
    }
}
