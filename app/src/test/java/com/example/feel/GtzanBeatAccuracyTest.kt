package com.example.feel

import com.example.AudioSettingsState
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.audio.AudioDspProcessor
import com.example.hardware.audio.AudioBackend
import com.example.presentation.audioSettingsReducer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Beat accuracy on **real recordings with human-annotated beats** — the GTZAN test set, ten genres,
 * 30 seconds each, with the Marchand/Fresnel/Peeters rhythm annotations.
 *
 * [BeatAccuracyTest] measures the same thing on generated audio, where the beat grid is exact by
 * construction. That is the right tool for asking *why* something fails, because every property of
 * the material is known and controllable. It is the wrong tool for asking *how often* it fails: a
 * synthetic kick is a cleaner transient than any real one, and ten hand-written tracks are ten
 * guesses about what music is like. This is the number that counts.
 *
 * ## Setting it up
 *
 * Neither the audio nor the annotations are committed — the audio is a copyrighted research corpus
 * and this repo is public. The test skips itself when they are absent. To run it:
 *
 *  - audio: the GTZAN genre collection, extracted so that
 *    `<root>/Data/genres_original/<genre>/<genre>.00000.wav` exists
 *  - annotations: https://github.com/TempoBeatDownbeat/gtzan_tempo_beat, extracted so that
 *    `<root>/gtzan_tempo_beat-main/beats/gtzan_<genre>_00000.beats` exists
 *  - point `GTZAN_ROOT` below (or the environment variable of the same name) at `<root>`
 *
 * The audio is 22.05kHz; [OfflineAudio.readWav] resamples it, and reading it raw would report every
 * tempo at double.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GtzanBeatAccuracyTest {

    private val root = File(System.getenv("GTZAN_ROOT") ?: DEFAULT_ROOT)

    /** How many tracks per genre. The whole set is 999; a tenth of it is enough to rank changes. */
    private val perGenre = 10

    private class Clip(val name: String, val genre: String, val wav: File, val beatsMs: List<Long>)

    private fun clips(): List<Clip> {
        val audioRoot = File(root, "Data/genres_original")
        val beatsRoot = File(root, "gtzan_tempo_beat-main/beats")
        if (!audioRoot.isDirectory || !beatsRoot.isDirectory) return emptyList()
        val out = ArrayList<Clip>()
        for (genreDir in audioRoot.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (!genreDir.isDirectory) continue
            val wavs = genreDir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()
            var taken = 0
            for (wav in wavs) {
                if (taken >= perGenre) break
                val stem = wav.nameWithoutExtension                 // blues.00000
                val parts = stem.split(".")
                if (parts.size != 2) continue
                val ann = File(beatsRoot, "gtzan_${parts[0]}_${parts[1]}.beats")
                if (!ann.exists()) continue
                val beats = ann.readLines().mapNotNull { line ->
                    // "<seconds>\t<position in bar>" — the position is the downbeat information,
                    // which nothing here uses yet.
                    line.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
                        ?.let { (it * 1000).toLong() }
                }
                if (beats.size < 8) continue
                out.add(Clip(stem, genreDir.name, wav, beats))
                taken++
            }
        }
        return out
    }

    private fun settingsFor(preset: String, beatClock: Boolean, refractory: Boolean = false, veto: Boolean = false, pulse: Boolean = false): AudioSettingsState =
        audioSettingsReducer(
            RgbUiState(audioSettings = AudioSettingsState()),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings.copy(
            beatClockEnabled = beatClock,
            beatRefractoryEnabled = refractory,
            beatVetoEnabled = veto,
            pulseTrackerEnabled = pulse
        )

    private fun flashes(clip: Clip, preset: String, beatClock: Boolean, refractory: Boolean = false, veto: Boolean = false, pulse: Boolean = false): List<Long> {
        val settings = settingsFor(preset, beatClock, refractory, veto, pulse)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val pcm = OfflineAudio.readWav(clip.wav)
        val out = ArrayList<Long>()
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            if (result.flashFiredThisFrame) out.add(atMs)
        }
        return out
    }

    @Test
    fun `beat accuracy on real music, by genre`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())
        println("\n=== GTZAN, Punchy, F-measure at +-70ms (${all.size} clips) ===")
        println("%12s %7s %7s %7s %7s %9s".format(
            "genre", "F off", "F on", "P off", "P on", "clips"))

        var offAll = 0.0
        var onAll = 0.0
        var offPAll = 0.0
        var onPAll = 0.0
        for ((genre, group) in all.groupBy { it.genre }.toSortedMap()) {
            var offF = 0.0; var onF = 0.0; var offP = 0.0; var onP = 0.0
            for (clip in group) {
                val off = BeatAccuracy.score(clip.beatsMs, flashes(clip, "Punchy", false))
                val on = BeatAccuracy.score(clip.beatsMs, flashes(clip, "Punchy", false, pulse = true))
                offF += off.fMeasure; onF += on.fMeasure
                offP += off.precision; onP += on.precision
            }
            val n = group.size
            println("%12s %6d%% %6d%% %6d%% %6d%% %9d".format(
                genre,
                BeatAccuracy.pct(offF / n), BeatAccuracy.pct(onF / n),
                BeatAccuracy.pct(offP / n), BeatAccuracy.pct(onP / n), n))
            offAll += offF; onAll += onF; offPAll += offP; onPAll += onP
        }
        val n = all.size
        println("%12s %6d%% %6d%% %6d%% %6d%% %9d".format(
            "ALL",
            BeatAccuracy.pct(offAll / n), BeatAccuracy.pct(onAll / n),
            BeatAccuracy.pct(offPAll / n), BeatAccuracy.pct(onPAll / n), n))
    }

    /**
     * What the flashes are fitting when they are not fitting the beat. An octave error is a
     * different problem from never finding the pulse, and only this separates them.
     */
    /** All the candidates on the same clips, so they can be ranked against one number. */
    @Test
    fun `rank every candidate on real music`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())
        val candidates = linkedMapOf<String, (Clip) -> List<Long>>(
            "shipped" to { c -> flashes(c, "Punchy", false) },
            "beat clock" to { c -> flashes(c, "Punchy", true) },
            "refractory" to { c -> flashes(c, "Punchy", false, refractory = true) },
            "offbeat veto" to { c -> flashes(c, "Punchy", false, veto = true) },
            "veto+refractory" to { c -> flashes(c, "Punchy", false, refractory = true, veto = true) },
            "pulse tracker" to { c -> flashes(c, "Punchy", false, pulse = true) }
        )
        println("\n=== Every candidate, ${all.size} real clips, Punchy ===")
        println("%18s %8s %8s %8s %10s".format("candidate", "F", "P", "R", "continuity"))
        for ((name, run) in candidates) {
            var f = 0.0; var p = 0.0; var r = 0.0; var cont = 0.0
            for (clip in all) {
                val score = BeatAccuracy.score(clip.beatsMs, run(clip))
                f += score.fMeasure; p += score.precision; r += score.recall; cont += score.continuity
            }
            val n = all.size
            println("%18s %7d%% %7d%% %7d%% %9d%%".format(
                name, BeatAccuracy.pct(f / n), BeatAccuracy.pct(p / n),
                BeatAccuracy.pct(r / n), BeatAccuracy.pct(cont / n)))
        }
    }

    /** The one change the double-grid finding points straight at. */
    @Test
    fun `does a tempo-scaled refractory stop the double flashing`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())
        println("\n=== GTZAN, tempo-scaled causal refractory (${all.size} clips) ===")
        println("%12s %16s %16s %16s".format("genre", "F off -> on", "P off -> on", "R off -> on"))
        var offF = 0.0; var onF = 0.0; var offP = 0.0; var onP = 0.0; var offR = 0.0; var onR = 0.0
        for ((genre, group) in all.groupBy { it.genre }.toSortedMap()) {
            var gOffF = 0.0; var gOnF = 0.0; var gOffP = 0.0; var gOnP = 0.0; var gOffR = 0.0; var gOnR = 0.0
            for (clip in group) {
                val off = BeatAccuracy.score(clip.beatsMs, flashes(clip, "Punchy", false, false))
                val on = BeatAccuracy.score(clip.beatsMs, flashes(clip, "Punchy", false, true))
                gOffF += off.fMeasure; gOnF += on.fMeasure
                gOffP += off.precision; gOnP += on.precision
                gOffR += off.recall; gOnR += on.recall
            }
            val n = group.size
            println("%12s %7d%% ->%5d%% %7d%% ->%5d%% %7d%% ->%5d%%".format(
                genre,
                BeatAccuracy.pct(gOffF / n), BeatAccuracy.pct(gOnF / n),
                BeatAccuracy.pct(gOffP / n), BeatAccuracy.pct(gOnP / n),
                BeatAccuracy.pct(gOffR / n), BeatAccuracy.pct(gOnR / n)))
            offF += gOffF; onF += gOnF; offP += gOffP; onP += gOnP; offR += gOffR; onR += gOnR
        }
        val n = all.size
        println("%12s %7d%% ->%5d%% %7d%% ->%5d%% %7d%% ->%5d%%".format(
            "ALL",
            BeatAccuracy.pct(offF / n), BeatAccuracy.pct(onF / n),
            BeatAccuracy.pct(offP / n), BeatAccuracy.pct(onP / n),
            BeatAccuracy.pct(offR / n), BeatAccuracy.pct(onR / n)))
    }

    @Test
    fun `which grid do the flashes actually fit`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())
        val counts = sortedMapOf<String, Int>()
        var improvement = 0.0
        for (clip in all) {
            val flashMs = flashes(clip, "Punchy", false)
            val trueScore = BeatAccuracy.score(clip.beatsMs, flashMs)
            val (variant, best) = BeatAccuracy.bestVariant(clip.beatsMs, flashMs)
            counts[variant] = (counts[variant] ?: 0) + 1
            improvement += best.fMeasure - trueScore.fMeasure
        }
        println("\n=== Best-fitting grid across ${all.size} real clips, shipped path ===")
        counts.forEach { (variant, count) ->
            println("%10s %4d clips (%d%%)".format(
                variant, count, BeatAccuracy.pct(count.toDouble() / all.size)))
        }
        println("mean F gained by scoring against the best grid instead of the true one: %d%%"
            .format(BeatAccuracy.pct(improvement / all.size)))
    }

    private companion object {
        const val DEFAULT_ROOT = "C:/Users/attgm/gtzan"
    }
}
