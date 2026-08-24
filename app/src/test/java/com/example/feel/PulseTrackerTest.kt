package com.example.feel

import com.example.core.audio.PulseTracker
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Scores [PulseTracker] against human beat annotations, on its own.
 *
 * Deliberately not through the flash pipeline. The pipeline adds decay envelopes, peak-hold, preset
 * gating and a scheduler, and every one of those is a place where a good beat estimate can be spent
 * badly — mixing them into the measurement is what made the earlier candidates so hard to rank. The
 * question here is only: does this thing know where the beat is.
 *
 * Setup is the same as [GtzanBeatAccuracyTest]; both skip when the corpus is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PulseTrackerTest {

    private val root = File(System.getenv("GTZAN_ROOT") ?: "C:/Users/attgm/gtzan")
    private val perGenre = 10

    private class Clip(val name: String, val genre: String, val wav: File, val beatsMs: List<Long>)

    private fun clips(): List<Clip> {
        val audioRoot = File(root, "Data/genres_original")
        val beatsRoot = File(root, "gtzan_tempo_beat-main/beats")
        if (!audioRoot.isDirectory || !beatsRoot.isDirectory) return emptyList()
        val out = ArrayList<Clip>()
        for (genreDir in audioRoot.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (!genreDir.isDirectory) continue
            var taken = 0
            for (wav in genreDir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()) {
                if (taken >= perGenre) break
                val parts = wav.nameWithoutExtension.split(".")
                if (parts.size != 2) continue
                val ann = File(beatsRoot, "gtzan_${parts[0]}_${parts[1]}.beats")
                if (!ann.exists()) continue
                val beats = ann.readLines().mapNotNull { line ->
                    line.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull()
                        ?.let { (it * 1000).toLong() }
                }
                if (beats.size < 8) continue
                out.add(Clip(wav.nameWithoutExtension, genreDir.name, wav, beats))
                taken++
            }
        }
        return out
    }

    private fun track(clip: Clip, tracker: PulseTracker = PulseTracker()): List<Long> {
        val pcm = OfflineAudio.readWav(clip.wav)
        val out = ArrayList<Long>()
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            if (tracker.process(frame.magnitude, frame.numBins, atMs)) out.add(atMs)
        }
        return out
    }

    /** Mean F over a set of clips for one tracker configuration. */
    private fun meanF(clips: List<Clip>, make: () -> PulseTracker): Triple<Double, Double, Double> {
        var f = 0.0; var p = 0.0; var r = 0.0
        for (clip in clips) {
            val score = BeatAccuracy.score(
                after(clip.beatsMs, WARMUP_MS), after(track(clip, make()), WARMUP_MS)
            )
            f += score.fMeasure; p += score.precision; r += score.recall
        }
        val n = clips.size.coerceAtLeast(1)
        return Triple(f / n, p / n, r / n)
    }

    /**
     * Sweeps the choices that are judgement calls rather than derivations, one axis at a time.
     *
     * Half the corpus, because a sweep is for ranking and the winner is re-scored on all of it.
     */
    @Test
    fun `sweep the tracker's judgement calls`() {
        val all = clips().filterIndexed { i, _ -> i % 2 == 0 }
        assumeTrue("GTZAN not present at $root", all.isNotEmpty())
        println("\n=== Sweeps over ${all.size} clips (F / P / R) ===")

        fun report(label: String, make: () -> PulseTracker) {
            val (f, p, r) = meanF(all, make)
            println("%34s %6d%% %6d%% %6d%%".format(
                label, BeatAccuracy.pct(f), BeatAccuracy.pct(p), BeatAccuracy.pct(r)))
        }

        println("-- octave preference (higher = keeps the shorter period) --")
        for (v in listOf(0.5, 0.6, 0.7, 0.75, 0.85, 0.95)) {
            report("octavePreferLonger=$v") { PulseTracker(octavePreferLonger = v) }
        }

        println("-- adaptive threshold --")
        for (v in listOf(true, false)) {
            report("adaptiveThreshold=$v") { PulseTracker(adaptiveThreshold = v) }
        }

        println("-- autocorrelation window (frames, 43 = 1s) --")
        for (v in listOf(172, 258, 344, 430)) {
            report("acfWindow=$v") { PulseTracker(acfWindow = v) }
        }

        println("-- tempo prior width, octaves --")
        for (v in listOf(0.5, 0.7, 0.9, 1.2, 2.0)) {
            report("priorWidthOctaves=$v") { PulseTracker(priorWidthOctaves = v) }
        }

        println("-- phase correction per estimate --")
        for (v in listOf(0.2, 0.4, 0.7, 1.0)) {
            report("phaseCorrection=$v") { PulseTracker(phaseCorrection = v) }
        }

        println("-- frequency bands the onset curve is kept in --")
        for (v in listOf(1, 2, 3, 4, 6, 8)) {
            report("bands=$v") { PulseTracker(bands = v) }
        }

        println("-- best of each axis, together --")
        report("tuned") {
            PulseTracker(
                octavePreferLonger = 0.95,
                phaseCorrection = 0.7,
                adaptiveThreshold = false,
                priorWidthOctaves = 0.6,
                bands = 4
            )
        }
    }

    /**
     * The tracker needs a few seconds of audio before it has an opinion, and scoring its silence
     * against the annotations for that stretch measures the warm-up rather than the tracker.
     * Everything is scored from [WARMUP_MS] on, for both the beats and the annotations.
     */
    private fun after(times: List<Long>, fromMs: Long) = times.filter { it >= fromMs }

    @Test
    fun `pulse tracker accuracy on real music`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root", all.isNotEmpty())

        println("\n=== PulseTracker vs human annotations, ${all.size} clips ===")
        println("%12s %8s %8s %8s %10s %8s".format("genre", "F", "P", "R", "continuity", "clips"))
        var fAll = 0.0; var pAll = 0.0; var rAll = 0.0; var cAll = 0.0
        val worst = ArrayList<Pair<String, Double>>()

        for ((genre, group) in all.groupBy { it.genre }.toSortedMap()) {
            var f = 0.0; var p = 0.0; var r = 0.0; var c = 0.0
            for (clip in group) {
                val score = BeatAccuracy.score(
                    after(clip.beatsMs, WARMUP_MS), after(track(clip), WARMUP_MS)
                )
                f += score.fMeasure; p += score.precision; r += score.recall; c += score.continuity
                worst.add(clip.name to score.fMeasure)
            }
            val n = group.size
            println("%12s %7d%% %7d%% %7d%% %9d%% %8d".format(
                genre, BeatAccuracy.pct(f / n), BeatAccuracy.pct(p / n),
                BeatAccuracy.pct(r / n), BeatAccuracy.pct(c / n), n))
            fAll += f; pAll += p; rAll += r; cAll += c
        }
        val n = all.size
        println("%12s %7d%% %7d%% %7d%% %9d%% %8d".format(
            "ALL", BeatAccuracy.pct(fAll / n), BeatAccuracy.pct(pAll / n),
            BeatAccuracy.pct(rAll / n), BeatAccuracy.pct(cAll / n), n))

        println("\nclips at or above 90%: ${worst.count { it.second >= 0.9 }}/$n")
        println("clips below 50%: ${worst.count { it.second < 0.5 }}/$n")
        println("worst ten:")
        worst.sortedBy { it.second }.take(10).forEach {
            println("  %-16s %d%%".format(it.first, BeatAccuracy.pct(it.second)))
        }
    }

    /**
     * Does the tracker know when it is lost?
     *
     * This decides the design, not just the number. A tracker that is wrong 40% of the time but
     * *knows* it can hand back to something else, or simply not flash on the beat at all, and the
     * show stays honest. A tracker that is confidently wrong cannot be gated, and its average is
     * the best it will ever look.
     */
    @Test
    fun `is confidence worth anything`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root", all.isNotEmpty())
        val rows = all.map { clip ->
            val tracker = PulseTracker()
            val pcm = OfflineAudio.readWav(clip.wav)
            val beats = ArrayList<Long>()
            var confidenceSum = 0.0
            var frames = 0
            for ((frame, atMs) in OfflineAudio.frames(pcm)) {
                if (tracker.process(frame.magnitude, frame.numBins, atMs)) beats.add(atMs)
                if (atMs >= WARMUP_MS) { confidenceSum += tracker.stability; frames++ }
            }
            val score = BeatAccuracy.score(after(clip.beatsMs, WARMUP_MS), after(beats, WARMUP_MS))
            Triple(clip.name, if (frames == 0) 0.0 else confidenceSum / frames, score.fMeasure)
        }
        println("\n=== Accuracy against the tracker's own steadiness, ${all.size} clips ===")
        println("%18s %8s %10s %12s".format("steadiness", "clips", "mean F", "share of set"))
        val buckets = listOf(
            0.0 to 0.2, 0.2 to 0.4, 0.4 to 0.6, 0.6 to 0.8,
            0.8 to 0.85, 0.85 to 0.9, 0.9 to 0.95, 0.95 to 1.01
        )
        for ((lo, hi) in buckets) {
            val group = rows.filter { it.second >= lo && it.second < hi }
            if (group.isEmpty()) continue
            println("%8.1f - %-6.1f %8d %9d%% %11d%%".format(
                lo, hi, group.size,
                BeatAccuracy.pct(group.sumOf { it.third } / group.size),
                BeatAccuracy.pct(group.size.toDouble() / rows.size)))
        }
        for (cut in listOf(0.5, 0.7, 0.8, 0.85, 0.9)) {
            val group = rows.filter { it.second >= cut }
            if (group.isEmpty()) continue
            println("above %.2f steadiness: %d%% of clips, mean F %d%%".format(
                cut, BeatAccuracy.pct(group.size.toDouble() / rows.size),
                BeatAccuracy.pct(group.sumOf { it.third } / group.size)))
        }
        val cut = 0.5
        val confident = rows.filter { it.second >= cut }
        if (false) {
            println("\nabove %.1f steadiness: %d%% of clips, mean F %d%%".format(
                cut, BeatAccuracy.pct(confident.size.toDouble() / rows.size),
                BeatAccuracy.pct(confident.sumOf { it.third } / confident.size)))
        }
    }

    /** Whether the misses are octave errors or lost pulses — they need opposite fixes. */
    @Test
    fun `which grid does the pulse tracker fit`() {
        val all = clips()
        assumeTrue("GTZAN not present at $root", all.isNotEmpty())
        val counts = sortedMapOf<String, Int>()
        for (clip in all) {
            val beats = after(clip.beatsMs, WARMUP_MS)
            val (variant, _) = BeatAccuracy.bestVariant(beats, after(track(clip), WARMUP_MS))
            counts[variant] = (counts[variant] ?: 0) + 1
        }
        println("\n=== Best-fitting grid, PulseTracker, ${all.size} clips ===")
        counts.forEach { (variant, count) ->
            println("%10s %4d clips (%d%%)".format(
                variant, count, BeatAccuracy.pct(count.toDouble() / all.size)))
        }
    }

    private companion object {
        const val WARMUP_MS = 5000L
    }
}
