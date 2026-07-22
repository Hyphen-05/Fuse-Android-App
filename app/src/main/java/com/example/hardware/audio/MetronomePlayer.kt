package com.example.hardware.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

// Phase 5, part B: moved verbatim out of RgbControllerViewModel.kt — raw AudioTrack synthesis
// for the calibration metronome click, not tangled up with the ViewModel's other responsibilities.
// Lives in hardware/audio/ alongside AudioCaptureSource et al. since it drives real audio-output
// hardware (AudioTrack), unlike the pure-logic BeatDetector/AudioDspProcessor in core/audio/.
class MetronomePlayer {
    private val sampleRate = 44100
    private var clickData: ShortArray? = null

    // Bug investigation (2026-07-22): AdbTestMetronome's one-shot-per-click mode constructs and
    // tears down a brand-new AudioTrack every ~120ms (see playClickData below). Testing the
    // hypothesis that on_device's Visualizer(0) digital tap misses very short-lived AudioTrack
    // sessions by offering one continuous, long-lived AudioTrack instead — a single MODE_STREAM
    // track that loops the same click waveform for the session's duration, so there is exactly
    // one AudioTrack construct/release for the whole capture rather than one per click.
    @Volatile
    private var sustainedTrack: AudioTrack? = null
    private var sustainedThread: Thread? = null

    /**
     * Starts a single long-lived AudioTrack that loops a click pattern at the given bpm for the
     * session duration. Distinct from [playClick]/the per-click adb path — this exists purely to
     * test whether AudioTrack session lifetime (not the acoustic content) is what on_device's
     * Visualizer(0) tap is missing.
     */
    fun startSustained(bpm: Float, frequencyHz: Double, volumeScale: Float) {
        stopSustained()
        val intervalMs = (60000f / bpm.coerceIn(20f, 400f)).toLong()
        val periodSamples = (sampleRate * intervalMs / 1000L).toInt().coerceAtLeast(1)
        val click = synthesizeClick(frequencyHz, 50)
        val period = ShortArray(periodSamples)
        click.copyInto(period, 0, 0, minOf(click.size, periodSamples))

        val track = buildNonFastAudioTrack(period.size * 2)
        track.setVolume(volumeScale.coerceIn(0f, 1f))
        track.play()
        sustainedTrack = track

        sustainedThread = Thread {
            try {
                while (sustainedTrack === track) {
                    track.write(period, 0, period.size)
                }
            } catch (e: Exception) {
            }
        }.also {
            it.isDaemon = true
            it.name = "SustainedMetronome"
            it.start()
        }
    }

    /**
     * Diagnostic-only (2026-07-22): plays a continuous, non-decaying sine wave with no silence
     * gaps at all -- unlike [startSustained]'s click-per-period pattern (~10% duty cycle at
     * typical bpm), this guarantees any point-in-time sample of the raw Visualizer FFT bytes
     * should show real content if the capture path is working, removing any ambiguity from a
     * throttled diagnostic sample landing in a silent gap between clicks. Not meant to be
     * musically useful (no beat structure) -- exists purely to disambiguate "no real signal
     * reaching Visualizer" from "diagnostic sampling got unlucky."
     */
    fun startContinuousTone(frequencyHz: Double, volumeScale: Float) {
        stopSustained()
        val chunkSamples = sampleRate / 10 // 100ms chunk, looped indefinitely
        val chunk = ShortArray(chunkSamples) { i ->
            val t = i.toDouble() / sampleRate
            (Math.sin(2.0 * Math.PI * frequencyHz * t) * Short.MAX_VALUE).toInt().toShort()
        }

        val track = buildNonFastAudioTrack(chunk.size * 2)
        track.setVolume(volumeScale.coerceIn(0f, 1f))
        track.play()
        sustainedTrack = track

        sustainedThread = Thread {
            try {
                while (sustainedTrack === track) {
                    track.write(chunk, 0, chunk.size)
                }
            } catch (e: Exception) {
            }
        }.also {
            it.isDaemon = true
            it.name = "ContinuousTone"
            it.start()
        }
    }

    // Bug fix (2026-07-22): the original legacy AudioTrack(streamType, ...) constructor used here,
    // with a buffer sized right at Android's minimum, qualifies for the platform's low-latency
    // "FastMixer"/FastTrack output path -- confirmed via `adb shell dumpsys media.audio_flinger`
    // showing an active FastMixer thread (AUDIO_OUTPUT_FLAG_FAST, activeMask=0x1) and "0 Effect
    // Chains" on every output thread during a capture. FastTrack playback is mixed on a dedicated
    // low-latency thread that bypasses the Normal mixer thread's effect chain entirely --
    // Visualizer(session=0) taps the Normal thread, so a fast-tracked AudioTrack's samples never
    // reach it no matter how many times the Visualizer is reconstructed. This is a different
    // failure mode than the actual on_device bug being tested (a real, timing-dependent
    // stale-attach issue) and was silently invalidating every prior capture made with this
    // harness's sustained/click AudioTrack. Real media apps (ExoPlayer, MediaPlayer) normally use
    // larger, non-minimum buffers and are not fast-tracked, which is why real on-device music
    // behaves differently from this harness's old click track. Fixed by explicitly requesting
    // AudioAttributes matching real media playback (USAGE_MEDIA / CONTENT_TYPE_MUSIC) and
    // AudioTrack.PERFORMANCE_MODE_NONE (explicitly opts out of the low-latency path FastTrack
    // requires), with a buffer several times the platform minimum -- both are documented
    // disqualifiers for FastTrack eligibility.
    private fun buildNonFastAudioTrack(minChunkBytes: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize * 4, minChunkBytes * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .build()
    }

    fun stopSustained() {
        val track = sustainedTrack
        sustainedTrack = null
        sustainedThread?.interrupt()
        sustainedThread = null
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
        }
    }

    fun isSustainedRunning(): Boolean = sustainedTrack != null

    init {
        clickData = synthesizeClick(1200.0, 50)
    }

    /**
     * Generates a decaying-sine click. Exposed (not just used internally) so the adb test
     * harness (AdbTestMetronome) can synthesize clicks at arbitrary frequency/duration without
     * duplicating the waveform math.
     */
    private fun synthesizeClick(frequencyHz: Double, durationMs: Int): ShortArray {
        val numSamples = (sampleRate * durationMs) / 1000
        return ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = (numSamples - i).toDouble() / numSamples
            val value = Math.sin(2.0 * Math.PI * frequencyHz * t) * envelope * Short.MAX_VALUE
            value.toInt().toShort()
        }
    }

    fun playClick() {
        val data = clickData ?: return
        playClickData(data, 1f)
    }

    /**
     * Adjustable variant for the adb test harness: frequencyHz/durationMs control timbre,
     * volumeScale (0f-1f) controls loudness independent of the device's STREAM_MUSIC volume so a
     * "soft click" preset can be scripted reliably regardless of the device's current volume
     * setting.
     */
    fun playClick(frequencyHz: Double, volumeScale: Float, durationMs: Int) {
        playClickData(synthesizeClick(frequencyHz, durationMs), volumeScale)
    }

    private fun playClickData(data: ShortArray, volumeScale: Float) {
        try {
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                data.size * 2,
                AudioTrack.MODE_STATIC
            )
            track.setVolume(volumeScale.coerceIn(0f, 1f))
            track.write(data, 0, data.size)
            track.play()
            Thread {
                try {
                    Thread.sleep(120)
                    track.stop()
                    track.release()
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
