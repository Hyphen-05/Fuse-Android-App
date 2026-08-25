package com.example.feel

import com.example.AudioSettingsState
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.audio.AudioDspProcessor
import com.example.core.audio.ContinuousDrive
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

    private fun settingsFor(preset: String, beatClock: Boolean, refractory: Boolean = false, veto: Boolean = false, pulse: Boolean = false, cap: Boolean = false): AudioSettingsState =
        audioSettingsReducer(
            RgbUiState(audioSettings = AudioSettingsState()),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings.copy(
            beatClockEnabled = beatClock,
            beatRefractoryEnabled = refractory,
            beatVetoEnabled = veto,
            pulseTrackerEnabled = pulse,
            oneFlashPerBeatEnabled = cap
        )

    private fun flashes(clip: Clip, preset: String, beatClock: Boolean, refractory: Boolean = false, veto: Boolean = false, pulse: Boolean = false, cap: Boolean = false): List<Long> {
        val settings = settingsFor(preset, beatClock, refractory, veto, pulse, cap)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val pcm = OfflineAudio.readWav(clip.wav)
        val out = ArrayList<Long>()
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            if (result.flashFiredThisFrame) out.add(atMs)
        }
        return out
    }

    /** Per-frame brightness for a preset, as (timeMs, value). */
    private fun brightness(
        clip: Clip,
        preset: String,
        tuning: ContinuousDrive.Tuning = ContinuousDrive.Tuning()
    ): List<Pair<Long, Float>> {
        val settings = settingsFor(preset, beatClock = false)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD, tuning)
        val pcm = OfflineAudio.readWav(clip.wav)
        val out = ArrayList<Pair<Long, Float>>()
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            out.add(atMs to result.value)
        }
        return out
    }

    /**
     * Two numbers for a mapping that has no discrete events to score.
     *
     * `lift` is mean brightness within +-80ms of an annotated beat divided by mean brightness
     * everywhere else. Above 1.0 means the light is genuinely brighter on beats — the continuous
     * equivalent of "it flashes on the beat", and the thing that should make it feel locked to the
     * song. It cannot be gamed by flashing more, which is the point.
     *
     * `movement` is mean |change in brightness| per second: how busy the light is. It is the
     * continuous counterpart of flashes/beat, and the number to watch when the complaint is
     * "too flashy".
     *
     * Diagnostic. There is no pass mark — these exist to tune against.
     */
    /**
     * lift, movement and mean brightness for one series against a clip's annotated beats.
     *
     * Mean is here because lift is a *ratio*: a preset that sits almost dark and pulses dimly scores
     * beautifully on it while looking feeble on a wall. Any tuning decision needs all three.
     */
    private fun trackingOf(series: List<Pair<Long, Float>>, beats: List<Long>): Triple<Double, Double, Double>? {
        if (series.size < 2 || beats.isEmpty()) return null
        val sorted = beats.sorted()
        var onSum = 0.0; var onN = 0
        var offSum = 0.0; var offN = 0
        for ((atMs, v) in series) {
            val near = sorted.minOf { kotlin.math.abs(it - atMs) }
            if (near <= 80L) { onSum += v; onN++ } else { offSum += v; offN++ }
        }
        if (onN == 0 || offN == 0 || offSum <= 0.0) return null
        var delta = 0.0
        for (i in 1 until series.size) delta += kotlin.math.abs(series[i].second - series[i - 1].second)
        val spanSec = (series.last().first - series.first().first).coerceAtLeast(1L) / 1000.0
        val mean = (onSum + offSum) / (onN + offN)
        return Triple((onSum / onN) / (offSum / offN), delta / spanSec, mean)
    }

    /**
     * Sweeps [ContinuousDrive.Tuning] against measured lift and movement.
     *
     * Runs the real pipeline under each candidate rather than a model of it, so what is tuned is
     * what ships. A subset of clips, because this is the slow one — the winner gets re-measured on
     * the whole set by the test above.
     *
     * What to look for: the highest `lift` whose `movement` still sits well under Punchy's 2.96,
     * which is the "too flashy" end of the scale Joe rejected.
     */
    @Test
    fun `sweep continuous tuning`() {
        val all = clips().filterIndexed { i, _ -> i % 3 == 0 }
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())

        // Refinement round. The first sweep established the shape: lift rises as bodyShare falls,
        // and punchGain *costs* lift while adding movement, because punch fires on every transient
        // rather than on beats. So this explores the low-body, low-punch, short-release corner where
        // the frontier actually is.
        val candidates = buildList {
            for (body in listOf(0.35f, 0.25f, 0.15f)) {
                for (punch in listOf(0.5f, 1.0f, 1.5f)) {
                    add(ContinuousDrive.Tuning(bodyShare = body, punchGain = punch, fastReleaseTauMs = 100f))
                }
            }
            add(ContinuousDrive.Tuning(bodyShare = 0.25f, punchGain = 1.0f, fastReleaseTauMs = 80f))
            add(ContinuousDrive.Tuning(bodyShare = 0.25f, punchGain = 1.0f, fastReleaseTauMs = 160f))
            add(ContinuousDrive.Tuning(bodyShare = 0.25f, punchGain = 1.0f, slowAttackTauMs = 140f))
            add(ContinuousDrive.Tuning(bodyShare = 0.25f, punchGain = 1.0f, slowAttackTauMs = 320f))
        }

        println("")
        println("=== Continuous tuning sweep (${all.size} clips) ===")
        println("%6s %6s %7s %7s %8s %11s %7s".format(
            "body", "punch", "fastRel", "slowAtt", "lift", "movement/s", "mean"))
        for (t in candidates) {
            var liftSum = 0.0; var moveSum = 0.0; var meanSum = 0.0; var n = 0
            for (clip in all) {
                val (lift, move, mean) = trackingOf(brightness(clip, "Live Wire", t), clip.beatsMs) ?: continue
                liftSum += lift; moveSum += move; meanSum += mean; n++
            }
            if (n == 0) continue
            println("%6.2f %6.2f %7.0f %7.0f %8.3f %11.2f %7.3f".format(
                t.bodyShare, t.punchGain, t.fastReleaseTauMs, t.slowAttackTauMs,
                liftSum / n, moveSum / n, meanSum / n))
        }
    }

    @Test
    fun `continuous drive tracks beats without flashing`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())

        val presets = listOf("Live Wire", "Punchy", "Smooth Flow", "Ambient Chill")
        println("")
        println("=== Continuous tracking (${all.size} clips) ===")
        println("%14s %8s %11s %7s".format("preset", "lift", "movement/s", "mean"))

        for (preset in presets) {
            var liftSum = 0.0
            var moveSum = 0.0
            var counted = 0
            var meanSum = 0.0
            for (clip in all) {
                val (lift, move, mean) = trackingOf(brightness(clip, preset), clip.beatsMs) ?: continue
                liftSum += lift; moveSum += move; meanSum += mean; counted++
            }
            if (counted == 0) continue
            println("%14s %8.3f %11.2f %7.3f".format(
                preset, liftSum / counted, moveSum / counted, meanSum / counted))
        }
    }

    /**
     * How many times the strip flashes per beat the music actually has.
     *
     * Joe's complaint on 2026-08-25, in his words: "if you have 10 flashes or stuff that looks like
     * flashes per beat then it all becomes a mess not a satisfying visualiser". F-measure cannot
     * express that — it weighs a missed beat exactly as heavily as a spurious flash, so a change
     * that halves the flashing and drops a few real beats scores as a loss while being precisely
     * what he asked for. This reports the ratio directly. 1.0 is one flash per beat; 2.0 is the
     * double-rate flashing already documented on 80 of 100 clips.
     *
     * Diagnostic, not an assertion — there is no agreed target yet.
     */
    @Test
    fun `flash density per beat, by configuration`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())

        val configs = listOf<Pair<String, (Clip) -> List<Long>>>(
            "shipped" to { c -> flashes(c, "Punchy", false) },
            "pulse" to { c -> flashes(c, "Punchy", false, pulse = true) },
            "refractory" to { c -> flashes(c, "Punchy", false, refractory = true) },
            "veto" to { c -> flashes(c, "Punchy", false, veto = true) },
            "pulse+refractory" to { c -> flashes(c, "Punchy", false, refractory = true, pulse = true) },
            "clock" to { c -> flashes(c, "Punchy", true) },
            "CAP" to { c -> flashes(c, "Punchy", false, cap = true) },
            "CAP+pulse" to { c -> flashes(c, "Punchy", false, pulse = true, cap = true) },
            "CAP+pulse+refr" to { c -> flashes(c, "Punchy", false, refractory = true, pulse = true, cap = true) }
        )

        println("")
        println("=== Flashes per annotated beat (${all.size} clips) ===")
        println("%18s %14s %10s %10s".format("config", "flashes/beat", "precision", "recall"))
        for ((name, run) in configs) {
            var ratioSum = 0.0
            var pSum = 0.0
            var rSum = 0.0
            for (clip in all) {
                val f = run(clip)
                ratioSum += if (clip.beatsMs.isEmpty()) 0.0 else f.size.toDouble() / clip.beatsMs.size
                val s = BeatAccuracy.score(clip.beatsMs, f)
                pSum += s.precision
                rSum += s.recall
            }
            val n = all.size
            println("%18s %13.2f %9d%% %9d%%".format(
                name, ratioSum / n, BeatAccuracy.pct(pSum / n), BeatAccuracy.pct(rSum / n)))
        }
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
