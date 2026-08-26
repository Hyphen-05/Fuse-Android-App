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

    private fun settingsFor(preset: String, beatClock: Boolean, veto: Boolean = false, pulse: Boolean = false, cap: Boolean = false): AudioSettingsState =
        audioSettingsReducer(
            RgbUiState(audioSettings = AudioSettingsState()),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings.copy(
            beatClockEnabled = beatClock,
            beatVetoEnabled = veto,
            pulseTrackerEnabled = pulse,
            oneFlashPerBeatEnabled = cap
        )

    private fun flashes(clip: Clip, preset: String, beatClock: Boolean, veto: Boolean = false, pulse: Boolean = false, cap: Boolean = false): List<Long> {
        val settings = settingsFor(preset, beatClock, veto, pulse, cap)
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
    /**
     * @param lift how much brighter the light is on beats than off them.
     * @param movement mean |change in brightness| per second — how busy the light is on average.
     * @param mean average brightness, so a preset that scores well by sitting nearly dark is visible
     *   as such.
     * @param peakSlew the 95th percentile of |change in brightness| per second across frames.
     * @param contrast p95 minus p5 of brightness: how far the light actually swings.
     */
    private data class Tracking(
        val lift: Double,
        val movement: Double,
        val mean: Double,
        val peakSlew: Double,
        val contrast: Double,
        val bestLift: Double,
        val bestLagMs: Long
    )

    /** Distance from [atMs] to the nearest beat in [sorted], by binary search. */
    private fun nearestBeat(sorted: LongArray, atMs: Long): Long {
        if (sorted.isEmpty()) return Long.MAX_VALUE
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] < atMs) lo = mid + 1 else hi = mid
        }
        var best = kotlin.math.abs(sorted[lo] - atMs)
        if (lo > 0) best = minOf(best, kotlin.math.abs(sorted[lo - 1] - atMs))
        return best
    }

    /** on-beat mean over off-beat mean, with the series shifted back by [lagMs]. */
    private fun liftAtLag(series: List<Pair<Long, Float>>, sorted: LongArray, lagMs: Long): Double? {
        var onSum = 0.0; var onN = 0
        var offSum = 0.0; var offN = 0
        for ((atMs, v) in series) {
            if (nearestBeat(sorted, atMs - lagMs) <= 80L) { onSum += v; onN++ } else { offSum += v; offN++ }
        }
        if (onN == 0 || offN == 0 || offSum <= 0.0) return null
        return (onSum / onN) / (offSum / offN)
    }

    /**
     * `peakSlew` and `contrast` exist because `movement` is a *mean* and Joe's complaint is not
     * about the mean. On 2026-08-26 he called Live Wire "way too flashy or jumpy... very
     * uncomfortable on the eyes" — and Live Wire fires no flashes at all, so what he is describing
     * is the brightness itself moving too far, too fast. Discomfort tracks the worst excursions, not
     * the average busyness: a mapping that is calm for 90% of a track and slams the strip on every
     * kick has a respectable `movement` and is still unwatchable. Tuning against the mean alone
     * would repeat the F-measure mistake in a new costume — optimising a number that averages away
     * exactly the events being complained about.
     */
    private fun trackingOf(series: List<Pair<Long, Float>>, beats: List<Long>): Tracking? {
        if (series.size < 2 || beats.isEmpty()) return null
        val sorted = beats.sorted()
        var onSum = 0.0; var onN = 0
        var offSum = 0.0; var offN = 0
        for ((atMs, v) in series) {
            val near = sorted.minOf { kotlin.math.abs(it - atMs) }
            if (near <= 80L) { onSum += v; onN++ } else { offSum += v; offN++ }
        }
        if (onN == 0 || offN == 0 || offSum <= 0.0) return null

        // `lift` measures brightness inside +-80ms of the beat, which silently assumes the light
        // rises *with* the beat. Slowing the attack deliberately delays the peak, so a calm tuning
        // is penalised for lag rather than for failing to follow the music — the metric would rule
        // out the exact change being tested. `bestLift` sweeps the window back over the plausible
        // lag range and reports the best fit and where it was: a consistent 60ms delay is not
        // something an eye objects to, whereas no coupling at any lag genuinely is a lava lamp.
        val beatArray = sorted.toLongArray()
        var bestLift = 0.0
        var bestLag = 0L
        for (lag in 0L..200L step 20L) {
            val l = liftAtLag(series, beatArray, lag) ?: continue
            if (l > bestLift) { bestLift = l; bestLag = lag }
        }

        var delta = 0.0
        // Per-frame slew in brightness per *second*, so a rate is comparable across frame intervals.
        val slews = ArrayList<Double>(series.size)
        for (i in 1 until series.size) {
            val d = kotlin.math.abs(series[i].second - series[i - 1].second)
            delta += d
            val dtSec = (series[i].first - series[i - 1].first).coerceAtLeast(1L) / 1000.0
            slews.add(d / dtSec)
        }
        slews.sort()
        val values = series.map { it.second.toDouble() }.sorted()
        fun pct(list: List<Double>, q: Double): Double =
            if (list.isEmpty()) 0.0
            else list[((list.size - 1) * q).toInt().coerceIn(0, list.size - 1)]
        val spanSec = (series.last().first - series.first().first).coerceAtLeast(1L) / 1000.0
        val mean = (onSum + offSum) / (onN + offN)
        return Tracking(
            lift = (onSum / onN) / (offSum / offN),
            movement = delta / spanSec,
            mean = mean,
            peakSlew = pct(slews, 0.95),
            contrast = pct(values, 0.95) - pct(values, 0.05),
            bestLift = bestLift,
            bestLagMs = bestLag
        )
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

        // Comfort round, 2026-08-26. Joe judged the shipped tuning on hardware and rejected it as
        // "very uncomfortable on the eyes" — on a preset that fires no flashes, so the brightness
        // itself is what is jumping. The earlier rounds explored the low-body corner chasing lift;
        // this one walks the opposite way, because the frontier that matters now is calm at
        // acceptable lift, not lift at acceptable movement.
        //
        // `fastAttack` is the new axis and the one to watch. At the shipped 12ms the fast envelope
        // is fully up within a single 50ms frame, so every kick is a step change no matter what the
        // gain is — lowering punchGain shrinks such a step but cannot slow it down.
        val candidates = buildList {
            add(ContinuousDrive.Tuning())   // shipped, as the baseline row
            for (attack in listOf(12f, 45f, 90f)) {
                for (body in listOf(0.25f, 0.45f, 0.65f)) {
                    for (punch in listOf(1.0f, 0.6f, 0.3f)) {
                        add(ContinuousDrive.Tuning(
                            fastAttackTauMs = attack, bodyShare = body, punchGain = punch))
                    }
                }
            }
            // Longer tails on the calmest attack: a slower fall is the other half of a soft edge.
            add(ContinuousDrive.Tuning(fastAttackTauMs = 90f, bodyShare = 0.45f, punchGain = 0.6f, fastReleaseTauMs = 180f))
            add(ContinuousDrive.Tuning(fastAttackTauMs = 90f, bodyShare = 0.45f, punchGain = 0.6f, fastReleaseTauMs = 260f))
        }

        println("")
        println("=== Continuous tuning sweep (${all.size} clips) ===")
        println("%7s %6s %6s %7s %8s %9s %6s %11s %10s %9s %7s".format(
            "fastAtt", "body", "punch", "fastRel", "lift", "bestLift", "lag", "movement/s",
            "peakSlew", "contrast", "mean"))
        for (t in candidates) {
            var liftSum = 0.0; var moveSum = 0.0; var meanSum = 0.0
            var slewSum = 0.0; var contrastSum = 0.0; var bestSum = 0.0; var lagSum = 0.0; var n = 0
            for (clip in all) {
                val m = trackingOf(brightness(clip, "Live Wire", t), clip.beatsMs) ?: continue
                liftSum += m.lift; moveSum += m.movement; meanSum += m.mean
                slewSum += m.peakSlew; contrastSum += m.contrast
                bestSum += m.bestLift; lagSum += m.bestLagMs; n++
            }
            if (n == 0) continue
            println("%7.0f %6.2f %6.2f %7.0f %8.3f %9.3f %6.0f %11.2f %10.2f %9.3f %7.3f".format(
                t.fastAttackTauMs, t.bodyShare, t.punchGain, t.fastReleaseTauMs,
                liftSum / n, bestSum / n, lagSum / n, moveSum / n, slewSum / n,
                contrastSum / n, meanSum / n))
        }
    }

    @Test
    fun `continuous drive tracks beats without flashing`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root — see the class comment", all.isNotEmpty())

        val presets = listOf("Live Wire", "Punchy", "Smooth Flow", "Ambient Chill")
        println("")
        println("=== Continuous tracking (${all.size} clips) ===")
        println("%14s %8s %9s %6s %11s %10s %9s %7s".format(
            "preset", "lift", "bestLift", "lag", "movement/s", "peakSlew", "contrast", "mean"))

        for (preset in presets) {
            var liftSum = 0.0
            var moveSum = 0.0
            var counted = 0
            var meanSum = 0.0
            var slewSum = 0.0
            var contrastSum = 0.0
            var bestSum = 0.0
            var lagSum = 0.0
            for (clip in all) {
                val m = trackingOf(brightness(clip, preset), clip.beatsMs) ?: continue
                liftSum += m.lift; moveSum += m.movement; meanSum += m.mean
                slewSum += m.peakSlew; contrastSum += m.contrast
                bestSum += m.bestLift; lagSum += m.bestLagMs; counted++
            }
            if (counted == 0) continue
            println("%14s %8.3f %9.3f %6.0f %11.2f %10.2f %9.3f %7.3f".format(
                preset, liftSum / counted, bestSum / counted, lagSum / counted,
                moveSum / counted, slewSum / counted, contrastSum / counted, meanSum / counted))
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
            "veto" to { c -> flashes(c, "Punchy", false, veto = true) },
            "clock" to { c -> flashes(c, "Punchy", true) },
            "CAP" to { c -> flashes(c, "Punchy", false, cap = true) },
            "CAP+pulse" to { c -> flashes(c, "Punchy", false, pulse = true, cap = true) }
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
            "offbeat veto" to { c -> flashes(c, "Punchy", false, veto = true) },
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
