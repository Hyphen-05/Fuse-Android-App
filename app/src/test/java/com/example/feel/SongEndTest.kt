package com.example.feel

import com.example.AudioSettingsState
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.audio.AudioDspProcessor
import com.example.hardware.audio.AudioBackend
import com.example.presentation.audioSettingsReducer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the strip does after the music stops.
 *
 * Joe, 2026-08-20: "lights keep flashing after song is finished for a while." The tempo grid is why.
 * `BeatDetector.nextPredictedBeatMs` extrapolates forward by whole periods for as long as a lock
 * survives, and the lock is computed from an 8s flux history, so for several seconds after the
 * audio ends the grid still believes in a beat that is not coming. The predictive scheduler
 * (mechanism 1) fires on that grid.
 *
 * Both of the *other* trigger paths already require real energy — the centered detector's `isBeat`
 * is anded with `totalEnergy >= effectiveNoiseGate`, and the fast causal trigger got the same gate
 * on 2026-07-22 for exactly this class of bug. Mechanism 1 was the one left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SongEndTest {

    private fun settingsFor(preset: String): AudioSettingsState =
        audioSettingsReducer(
            RgbUiState(audioSettings = AudioSettingsState()),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings

    /**
     * Track then silence, in one stream, so the detector meets the transition the way it really
     * does.
     *
     * [noiseAmplitude] is what makes this a real test. Digital zeros are not what a microphone
     * hears when a song ends: a quiet room still delivers a hiss floor, and every energy gate in
     * the DSP is judged against it. At 0.0 the whole pipeline goes black within a second and there
     * is no bug to see — which is exactly why this was not caught before.
     */
    private fun trackThenSilence(
        silenceSeconds: Double,
        noiseAmplitude: Double = 0.0
    ): Pair<ShortArray, Long> {
        val track = OfflineAudio.syntheticTrack(seconds = 20.0, bpm = 120.0)
        val tail = ShortArray((silenceSeconds * OfflineAudio.SAMPLE_RATE).toInt())
        var noiseState = 0x5D3F19B
        for (n in tail.indices) {
            noiseState = noiseState * 1103515245 + 12345
            val white = ((noiseState ushr 16) and 0x7FFF) / 16384.0 - 1.0
            tail[n] = (white * noiseAmplitude * Short.MAX_VALUE).toInt().toShort()
        }
        val endMs = (20.0 * 1000).toLong()
        return (track + tail) to endMs
    }

    /**
     * Flashes are counted as upward jumps in luma: the ambient level drifts, a flash steps.
     * Threshold 0.08 is well above the idle pulse's own ±0.10-over-4s wander per frame.
     */
    private fun flashesAfter(preset: String, afterMs: Long, noise: Double): Int {
        val settings = settingsFor(preset)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val (pcm, _) = trackThenSilence(silenceSeconds = 12.0, noiseAmplitude = noise)
        var previous = 0.0
        var flashes = 0
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            val luma = (0.2126 * result.r + 0.7152 * result.g + 0.0722 * result.b) / 255.0
            if (atMs > afterMs && luma - previous > 0.05) flashes++
            previous = luma
        }
        return flashes
    }

    /** Diagnostic: what actually happens in the seconds after the audio stops. */
    @Test
    fun `what the strip does after the music ends`() {
      for (noise in listOf(0.0, 0.002, 0.01)) {
        val settings = settingsFor("Punchy")
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val (pcm, endMs) = trackThenSilence(silenceSeconds = 12.0, noiseAmplitude = noise)
        var previous = 0.0
        val jumps = ArrayList<Pair<Long, Double>>()
        val lumaBySecond = HashMap<Long, MutableList<Double>>()
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            val luma = (0.2126 * result.r + 0.7152 * result.g + 0.0722 * result.b) / 255.0
            if (atMs > endMs && luma - previous > 0.02) jumps.add(atMs to (luma - previous))
            if (atMs > endMs - 2000) {
                lumaBySecond.getOrPut((atMs - endMs) / 1000) { ArrayList() }.add(luma)
            }
            previous = luma
        }
        println("\n=== Punchy, 20s track then 12s of room tone, noise amplitude $noise ===")
        println("luma per second relative to the end of the audio:")
        for (sec in lumaBySecond.keys.sorted()) {
            val v = lumaBySecond.getValue(sec)
            println("%+4ds  mean %.3f  max %.3f  n=%d".format(sec, v.average(), v.max(), v.size))
        }
        println("upward luma jumps > 0.02 after the audio ends: ${jumps.size}")
        jumps.take(12).forEach { println("  +%.0fms  +%.3f".format((it.first - endMs).toDouble(), it.second)) }
      }
    }

    /**
     * Diagnostic: is there anything in the spectrum that tells hiss apart from music *without*
     * relying on level? Level alone is what the shipped adaptive floor uses, and it is what fails.
     */
    @Test
    fun `does spectral shape separate room tone from music`() {
        println("\n=== Frame statistics, music vs room tone ===")
        println("%22s %12s %12s %12s".format("", "energy", "crest", "flatness"))
        for (noise in listOf(0.002, 0.01, 0.03)) {
            val (pcm, endMs) = trackThenSilence(silenceSeconds = 12.0, noiseAmplitude = noise)
            val music = ArrayList<Triple<Double, Double, Double>>()
            val tail = ArrayList<Triple<Double, Double, Double>>()
            for ((frame, atMs) in OfflineAudio.frames(pcm)) {
                var sum = 0.0; var peak = 0.0; var logSum = 0.0; var n = 0
                for (i in 1 until frame.numBins) {
                    val m = frame.magnitude[i].toDouble()
                    sum += m
                    if (m > peak) peak = m
                    logSum += Math.log(m + 1e-9)
                    n++
                }
                val mean = sum / n
                val crest = if (mean > 0) peak / mean else 1.0
                // Spectral flatness: geometric over arithmetic mean. 1.0 is white noise, near 0 is
                // tonal. Level-independent by construction, which is the whole point.
                val flatness = if (mean > 0) Math.exp(logSum / n) / mean else 1.0
                val row = Triple(sum, crest, flatness)
                if (atMs < endMs - 1000) music.add(row) else if (atMs > endMs + 3000) tail.add(row)
            }
            fun show(label: String, rows: List<Triple<Double, Double, Double>>) {
                println("%22s %12.1f %12.1f %12.4f".format(
                    label, rows.map { it.first }.average(),
                    rows.map { it.second }.average(), rows.map { it.third }.average()))
            }
            show("music (noise $noise)", music)
            show("room tone", tail)
        }
    }

    /**
     * The threshold has to survive real music, not just the synthetic track — a riser, a cymbal
     * wash or a distorted chorus is genuinely broadband, and a flatness test that calls those
     * "no music" would blank the strip mid-song. Skipped when the file is absent (it is Joe's own
     * music, deliberately not committed — see tools/feel-audio/README.md).
     */
    @Test
    fun `real music never looks like room tone`() {
        val wav = File("../tools/feel-audio/almost-there.wav")
        assumeTrue("no real-music file present", wav.exists())
        val pcm = OfflineAudio.readWav(wav)
        val flatness = ArrayList<Double>()
        for ((frame, _) in OfflineAudio.frames(pcm)) {
            var sum = 0.0; var logSum = 0.0; var n = 0
            for (i in 1 until frame.numBins) {
                val m = frame.magnitude[i].toDouble()
                sum += m; logSum += Math.log(m + 1e-9); n++
            }
            val mean = sum / n
            flatness.add(if (mean > 0) Math.exp(logSum / n) / mean else 1.0)
        }
        val sorted = flatness.sorted()
        fun pct(p: Double) = sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
        println("\n=== Spectral flatness of a real master, ${flatness.size} frames ===")
        println("median %.3f  p90 %.3f  p99 %.3f  max %.3f".format(
            pct(0.5), pct(0.9), pct(0.99), sorted.last()))
        val flatFrames = flatness.count { it > 0.55 }
        println("frames above the 0.55 room-tone threshold: $flatFrames (%.2f%%)".format(
            100.0 * flatFrames / flatness.size))
    }

    @Test
    fun `the strip stops flashing when the song does`() {
        for (preset in listOf("Punchy", "Strobe Blast", "Ambient Chill")) {
            // Three noise floors, because the bug only exists in a room that is not digitally
            // silent, and 0.03 is loud enough that room tone carries more energy than the music did.
            for (noise in listOf(0.0, 0.002, 0.01, 0.03)) {
                // One flash of grace: the beat already in flight when the audio stopped is
                // legitimate, and its decay is not what Joe is seeing.
                val flashes = flashesAfter(preset, afterMs = 20_500L, noise = noise)
                assertTrue(
                    "$preset at noise $noise kept flashing $flashes times in the 11.5s after the " +
                        "music ended",
                    flashes <= 1
                )
            }
        }
    }
}
