package com.example.feel

import com.example.hardware.audio.AudioBackend
import com.example.hardware.audio.AudioCaptureFrame
import com.example.hardware.audio.Fft
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Turns audio into exactly the frames the phone-mic backend would hand the DSP, without a phone.
 *
 * The framing mirrors `AudioRecordCaptureSource` field for field — 1024-sample windows, the same
 * Hamming coefficients, the same `Fft.fft`, 512 magnitude bins, `AUDIO_RECORD` backend — so what
 * the DSP sees here is what it sees on device. If that source's capture size or windowing ever
 * changes, this has to change with it or the harness quietly stops describing the real app.
 */
object OfflineAudio {

    const val SAMPLE_RATE = 44100
    const val WINDOW = 1024

    /** Real capture delivers a window every 1024 samples — ~23.2ms, i.e. ~43 frames/sec. */
    const val FRAME_INTERVAL_MS = WINDOW * 1000.0 / SAMPLE_RATE

    private val hammingWindow = FloatArray(WINDOW) { i ->
        0.54f - 0.46f * cos(2.0 * PI * i / (WINDOW - 1.0)).toFloat()
    }

    /**
     * Splits PCM into DSP frames. `nowMs` values are derived from the sample position rather than
     * a wall clock, so a run is bit-identical every time — the DSP's envelopes and beat scheduler
     * are all `dtMs`-driven, and real jitter would make renders incomparable between runs.
     */
    fun frames(pcm: ShortArray): List<Pair<AudioCaptureFrame, Long>> {
        val out = mutableListOf<Pair<AudioCaptureFrame, Long>>()
        var offset = 0
        var frameIndex = 0
        while (offset + WINDOW <= pcm.size) {
            val real = FloatArray(WINDOW)
            val imag = FloatArray(WINDOW)
            for (i in 0 until WINDOW) {
                real[i] = pcm[offset + i].toFloat() * hammingWindow[i]
            }
            Fft.fft(real, imag)
            val magnitude = FloatArray(512)
            for (k in 0 until 512) {
                magnitude[k] = kotlin.math.sqrt((real[k] * real[k] + imag[k] * imag[k]).toDouble()).toFloat()
            }
            val nowMs = (frameIndex * FRAME_INTERVAL_MS).toLong()
            out.add(
                AudioCaptureFrame(
                    magnitude = magnitude,
                    realBins = real,
                    imagBins = imag,
                    numBins = 512,
                    backend = AudioBackend.AUDIO_RECORD,
                    timestampMs = nowMs
                ) to nowMs
            )
            offset += WINDOW
            frameIndex++
        }
        return out
    }

    /**
     * A synthetic track, so the harness needs no committed audio file and every run is identical.
     *
     * Deliberately not a drum machine: it has a four-on-the-floor kick, offbeat hats, a sustained
     * pad, and — from [busyFromSeconds] — doubled kick and louder hats, so one render shows how a
     * preset behaves in both a sparse and a dense passage. That contrast is most of what "feel"
     * means for these presets.
     */
    fun syntheticTrack(
        seconds: Double = 20.0,
        bpm: Double = 120.0,
        busyFromSeconds: Double = 10.0
    ): ShortArray {
        val total = (seconds * SAMPLE_RATE).toInt()
        val out = ShortArray(total)
        val beatSamples = (60.0 / bpm * SAMPLE_RATE).toInt()
        // Deterministic noise for the hats — Random() would break run-to-run comparability.
        var noiseState = 0x2F6E2B1
        fun noise(): Double {
            noiseState = noiseState * 1103515245 + 12345
            return ((noiseState ushr 16) and 0x7FFF) / 16384.0 - 1.0
        }

        for (n in 0 until total) {
            val t = n.toDouble() / SAMPLE_RATE
            val busy = t >= busyFromSeconds
            var sample = 0.0

            // Pad: two detuned sines, always present, so silence-gating never kicks in.
            sample += 0.10 * sin(2 * PI * 110.0 * t) + 0.08 * sin(2 * PI * 164.81 * t)

            // Kick: 55Hz thump with a fast decay, on every beat (and every half-beat when busy).
            val kickPeriod = if (busy) beatSamples / 2 else beatSamples
            val intoKick = (n % kickPeriod).toDouble() / SAMPLE_RATE
            if (intoKick < 0.18) {
                val env = exp(-intoKick * 22.0)
                sample += 0.85 * env * sin(2 * PI * 55.0 * intoKick)
            }

            // Hats: filtered noise on the offbeat, louder in the busy half.
            val intoHat = ((n + beatSamples / 2) % beatSamples).toDouble() / SAMPLE_RATE
            if (intoHat < 0.05) {
                val env = exp(-intoHat * 90.0)
                sample += (if (busy) 0.35 else 0.18) * env * noise()
            }

            out[n] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        return out
    }

    /** The sections [structuredTrack] lays out, with the second range each occupies. */
    enum class Section(val fromSec: Double, val toSec: Double) {
        INTRO(0.0, 8.0),
        BUILD(8.0, 16.0),
        CHORUS(16.0, 26.0),
        BREAKDOWN(26.0, 32.0),
        CHORUS_2(32.0, 40.0)
    }

    /**
     * A 40s track with actual musical structure, for the Tier D work.
     *
     * [syntheticTrack] contrasts sparse against dense at a constant level, which is the right test
     * for "does this preset pump". It cannot test the Tier D questions at all, because those are
     * about *loudness relative to the song*: a quiet intro should not look like a chorus, and a
     * quiet track should not produce a dim, sluggish show. Both need a track whose level actually
     * moves, so this one runs intro → build → chorus → breakdown → chorus.
     *
     * Deterministic, like its sibling: the same bytes every run, so before/after numbers compare.
     */
    fun structuredTrack(bpm: Double = 120.0): ShortArray {
        val seconds = Section.entries.maxOf { it.toSec }
        val total = (seconds * SAMPLE_RATE).toInt()
        val out = ShortArray(total)
        val beatSamples = (60.0 / bpm * SAMPLE_RATE).toInt()
        var noiseState = 0x2F6E2B1
        fun noise(): Double {
            noiseState = noiseState * 1103515245 + 12345
            return ((noiseState ushr 16) and 0x7FFF) / 16384.0 - 1.0
        }

        for (n in 0 until total) {
            val t = n.toDouble() / SAMPLE_RATE
            val section = Section.entries.first { t >= it.fromSec && t < it.toSec || it == Section.CHORUS_2 }

            // Level is what separates the sections; the build ramps rather than stepping, so a
            // section classifier has a trajectory to read rather than an edge.
            val level = when (section) {
                Section.INTRO -> 0.25
                Section.BUILD -> {
                    val p = (t - section.fromSec) / (section.toSec - section.fromSec)
                    0.25 + 0.55 * p
                }
                Section.CHORUS, Section.CHORUS_2 -> 0.95
                Section.BREAKDOWN -> 0.15
            }

            // Density moves with the section too: a chorus is not merely louder, it is busier.
            val kickEvery = when (section) {
                Section.INTRO -> beatSamples * 2
                Section.BUILD -> beatSamples
                Section.CHORUS, Section.CHORUS_2 -> beatSamples / 2
                Section.BREAKDOWN -> 0
            }

            var sample = 0.0
            sample += level * (0.10 * sin(2 * PI * 110.0 * t) + 0.08 * sin(2 * PI * 164.81 * t))

            if (kickEvery > 0) {
                val intoKick = (n % kickEvery).toDouble() / SAMPLE_RATE
                if (intoKick < 0.18) {
                    val env = exp(-intoKick * 22.0)
                    sample += level * 0.85 * env * sin(2 * PI * 55.0 * intoKick)
                }
                val intoHat = ((n + beatSamples / 2) % beatSamples).toDouble() / SAMPLE_RATE
                if (intoHat < 0.05) {
                    val env = exp(-intoHat * 90.0)
                    sample += level * 0.30 * env * noise()
                }
            }

            out[n] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        return out
    }

    /**
     * The same recording, quieter — a track mastered low, or a phone held further from the speaker.
     *
     * The show should survive this. Anything judging level against a fixed threshold will not.
     */
    fun atGain(pcm: ShortArray, gain: Double): ShortArray =
        ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }

    /**
     * Reads 16-bit PCM mono/stereo WAV, for when a real track matters more than the synthetic one.
     * Stereo is averaged to mono, matching what a single mic channel would hear.
     */
    fun readWav(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "${file.name}: too short to be a WAV" }
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "${file.name}: not a RIFF/WAVE file"
        }

        fun le16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun le32(at: Int) = le16(at) or (le16(at + 2) shl 16)

        var pos = 12
        var channels = 1
        var bitsPerSample = 16
        var dataStart = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = le32(pos + 4)
            when (chunkId) {
                "fmt " -> {
                    channels = le16(pos + 10)
                    bitsPerSample = le16(pos + 22)
                }
                "data" -> {
                    dataStart = pos + 8
                    dataLength = chunkSize
                }
            }
            pos += 8 + chunkSize + (chunkSize and 1)
            if (dataStart >= 0) break
        }
        require(dataStart >= 0) { "${file.name}: no data chunk" }
        require(bitsPerSample == 16) { "${file.name}: only 16-bit PCM is supported, got $bitsPerSample" }

        val frameCount = dataLength / 2 / channels
        val out = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            var acc = 0
            for (c in 0 until channels) {
                val at = dataStart + (i * channels + c) * 2
                acc += le16(at).toShort().toInt()
            }
            out[i] = (acc / channels).toShort()
        }
        return out
    }
}
