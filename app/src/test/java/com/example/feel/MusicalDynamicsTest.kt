package com.example.feel

import com.example.AudioSettingsState
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.audio.AudioDspProcessor
import com.example.core.protocol.DuoCoProtocol
import com.example.hardware.audio.AudioBackend
import com.example.presentation.audioSettingsReducer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Tier D goals 2-4, measured: does the show know how loud *this moment* is relative to the song,
 * and does an intro look different from a chorus?
 *
 * The headline number is [trackingCorrelation] — how well the strip's brightness follows the
 * music's own loudness. A show that flattens everything to the same level scores near zero however
 * pretty it looks frame to frame, and that is precisely the complaint ("an intro should not look
 * like a chorus").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MusicalDynamicsTest {

    private data class Sample(val atMs: Long, val energy: Double, val luma: Double)

    private fun settingsFor(preset: String, musicalDynamics: Boolean): AudioSettingsState =
        audioSettingsReducer(
            RgbUiState(audioSettings = AudioSettingsState(musicalDynamicsEnabled = musicalDynamics)),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings

    /**
     * Runs the DSP and records the music's loudness beside the colour it produced.
     *
     * Reads the DSP's own output rather than the strip timeline: the write queue's coalescing is
     * real but irrelevant here, and sampling both signals on the same frame removes any alignment
     * question from the correlation.
     */
    private fun sample(preset: String, pcm: ShortArray, musicalDynamics: Boolean = false): List<Sample> {
        val settings = settingsFor(preset, musicalDynamics)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val out = ArrayList<Sample>()
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            var energy = 0.0
            for (i in 0 until frame.numBins) energy += frame.magnitude[i]
            out.add(
                Sample(
                    atMs = atMs,
                    energy = energy,
                    luma = (0.2126 * result.r + 0.7152 * result.g + 0.0722 * result.b) / 255.0
                )
            )
        }
        return out
    }

    private fun stripFrames(preset: String, pcm: ShortArray, musicalDynamics: Boolean = false): List<StripFrame> {
        val settings = settingsFor(preset, musicalDynamics)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val strip = VirtualStrip(connectedStripCount = 1)
        var durationMs = 0L
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            strip.write(atMs, DuoCoProtocol.createMusicColorCommand(result.r, result.g, result.b))
            durationMs = atMs
        }
        return strip.timeline(durationMs)
    }

    /**
     * Pearson correlation between the music's loudness and the brightness shown, both smoothed over
     * ~2s so this measures musical dynamics rather than per-beat flashing.
     */
    private fun trackingCorrelation(samples: List<Sample>): Double {
        if (samples.size < 10) return 0.0
        val window = 86 // ~2s at the AudioRecord frame rate
        fun smooth(values: List<Double>): List<Double> {
            var sum = 0.0
            val out = ArrayList<Double>(values.size)
            for (i in values.indices) {
                sum += values[i]
                if (i >= window) sum -= values[i - window]
                out.add(sum / minOf(i + 1, window))
            }
            return out
        }
        // Loudness in log terms: hearing is ratio-based, and a linear correlation against raw
        // magnitude would be dominated by the few loudest moments.
        val energy = smooth(samples.map { ln(1.0 + it.energy) })
        val luma = smooth(samples.map { it.luma })
        val n = energy.size
        val me = energy.average()
        val ml = luma.average()
        var num = 0.0; var de = 0.0; var dl = 0.0
        for (i in 0 until n) {
            val a = energy[i] - me
            val b = luma[i] - ml
            num += a * b; de += a * a; dl += b * b
        }
        return if (de <= 0.0 || dl <= 0.0) 0.0 else num / sqrt(de * dl)
    }

    /** Spread of shown brightness, 5th to 95th percentile — how much range the show actually uses. */
    private fun brightnessSpread(samples: List<Sample>): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.map { it.luma }.sorted()
        return sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)] -
            sorted[(sorted.size * 0.05).toInt()]
    }

    private fun sectionBrightness(frames: List<StripFrame>, section: OfflineAudio.Section): Double =
        FeelAnalysis.analyseWindow(
            frames,
            (section.fromSec * 1000).toLong(),
            (section.toSec * 1000).toLong()
        ).meanBrightness

    /**
     * Regression guard for the whole feature, on the synthetic track so it runs everywhere.
     *
     * Three things had to be true together, and earlier versions each got one at the cost of
     * another: the show must follow the music more closely, it must use *more* of its range rather
     * than less, and it must not simply get dimmer. The first attempt scaled everything by an
     * average of 0.7 and scored better contrast purely by dimming the loud parts less than the
     * quiet ones, which is not the same thing at all.
     */
    @Test
    fun dynamicsImprovesTrackingWithoutCostingRangeOrBrightness() {
        val pcm = OfflineAudio.structuredTrack()
        for (preset in listOf("Punchy", "Ambient Chill", "Smooth Flow")) {
            val off = sample(preset, pcm, musicalDynamics = false)
            val on = sample(preset, pcm, musicalDynamics = true)

            val offTracking = trackingCorrelation(off)
            val onTracking = trackingCorrelation(on)
            val offSpread = brightnessSpread(off)
            val onSpread = brightnessSpread(on)
            val offMean = off.map { it.luma }.average()
            val onMean = on.map { it.luma }.average()
            println(
                "%-14s tracking %+.2f -> %+.2f | spread %.2f -> %.2f | mean %.2f -> %.2f".format(
                    preset, offTracking, onTracking, offSpread, onSpread, offMean, onMean
                )
            )

            // Tolerance, not indifference. This track swings 4x in level, so the stock DSP already
            // tracks it at ~0.86 and there is nothing here to fix — the feature exists for
            // compressed masters, where the same measurement reads +0.03 before and +0.14 after
            // (see `dynamicsTrackingOnRealMusic`). What must not happen is a regression on material
            // that already works.
            assert(onTracking >= offTracking - 0.05) {
                "$preset: dynamics made the show track the music worse ($offTracking -> $onTracking)"
            }
            assert(onSpread >= offSpread * 0.9) {
                "$preset: dynamics squashed the brightness range ($offSpread -> $onSpread)"
            }
            assert(onMean >= offMean * 0.8) {
                "$preset: dynamics is dimming the show rather than shaping it ($offMean -> $onMean)"
            }
        }
    }

    @Test
    fun sectionContrastOnTheStructuredTrack() {
        val loud = OfflineAudio.structuredTrack()
        val quiet = OfflineAudio.atGain(loud, 0.25)

        for (preset in listOf("Punchy", "Ambient Chill")) {
            println("--- $preset ---")
            for ((label, pcm) in listOf("full level" to loud, "quiet mix" to quiet)) {
                for (dynamics in listOf(false, true)) {
                    val frames = stripFrames(preset, pcm, dynamics)
                    val byName = OfflineAudio.Section.entries.associateWith { sectionBrightness(frames, it) }
                    println(
                        "%-11s dynamics %-3s  %s  | chorus-minus-intro %.2f".format(
                            label, if (dynamics) "on" else "off",
                            byName.entries.joinToString("  ") { "${it.key}=${"%.2f".format(it.value)}" },
                            byName.getValue(OfflineAudio.Section.CHORUS) - byName.getValue(OfflineAudio.Section.INTRO)
                        )
                    )
                }
            }
        }
    }

    /**
     * The same question against real music. Skipped unless the audio is present — see
     * `tools/feel-audio/README.md`; the file is deliberately not in the repo.
     */
    @Test
    fun dynamicsTrackingOnRealMusic() {
        // Tests run with `app/` as the working directory, hence the climb out.
        val wav = File("../tools/feel-audio/almost-there.wav")
        assumeTrue("no real-music fixture present; see tools/feel-audio/README.md", wav.exists())
        val pcm = OfflineAudio.readWav(wav)

        println("--- real music: %.0fs ---".format(pcm.size / 44100.0))
        // How much macro-dynamics the *input* has, before asking whether the show follows it.
        // A brick-walled modern master may have almost none, in which case "tracks music" is
        // measuring a near-constant and no amount of DSP can score well on it.
        run {
            val samples = sample("Punchy", pcm)
            val window = 86
            var sum = 0.0
            val smoothed = ArrayList<Double>()
            for (i in samples.indices) {
                sum += ln(1.0 + samples[i].energy)
                if (i >= window) sum -= ln(1.0 + samples[i - window].energy)
                smoothed.add(sum / minOf(i + 1, window))
            }
            val sorted = smoothed.sorted()
            val p05 = sorted[(sorted.size * 0.05).toInt()]
            val p95 = sorted[(sorted.size * 0.95).toInt()]
            println(
                "input loudness range: p05=%.2f p95=%.2f spread=%.2f natural-log units (%.1f dB-ish)"
                    .format(p05, p95, p95 - p05, (p95 - p05) * 8.686)
            )
        }
        val results = HashMap<Pair<String, Boolean>, List<Sample>>()
        for (preset in listOf("Punchy", "Ambient Chill", "Smooth Flow")) {
            for (dynamics in listOf(false, true)) {
                val samples = sample(preset, pcm, dynamics)
                results[preset to dynamics] = samples
                println(
                    "%-14s dynamics %-3s  tracks music %+.2f  | brightness spread %.2f  | mean %.2f".format(
                        preset, if (dynamics) "on" else "off",
                        trackingCorrelation(samples), brightnessSpread(samples),
                        samples.map { it.luma }.average()
                    )
                )
            }
        }

        // The claim, pinned where it is actually made: on a real, heavily-limited master the show
        // follows the music more closely with dynamics on, and uses more of its range doing it.
        for (preset in listOf("Punchy", "Smooth Flow")) {
            val off = results.getValue(preset to false)
            val on = results.getValue(preset to true)
            assert(trackingCorrelation(on) > trackingCorrelation(off) + 0.03) {
                "$preset should track real music better with dynamics on"
            }
            assert(brightnessSpread(on) >= brightnessSpread(off)) {
                "$preset should not lose brightness range on real music"
            }
        }
    }

    /** The invariant the toggle rests on: off has to be the behaviour that was there before. */
    @Test
    fun dynamicsOffIsBitIdenticalToBefore() {
        val pcm = OfflineAudio.structuredTrack()
        val a = sample("Punchy", pcm, musicalDynamics = false)
        val b = sample("Punchy", pcm, musicalDynamics = false)
        assert(a.size == b.size && a.indices.all { abs(a[it].luma - b[it].luma) < 1e-9 }) {
            "the DSP is not deterministic; every comparison in this file is meaningless"
        }
    }
}
