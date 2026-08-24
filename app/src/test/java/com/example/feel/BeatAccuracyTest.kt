package com.example.feel

import com.example.AudioSettingsState
import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.audio.AudioDspProcessor
import com.example.hardware.audio.AudioBackend
import com.example.presentation.audioSettingsReducer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How often does the strip actually flash on the beat?
 *
 * Joe, 2026-08-20: "the biggest issue with the visualiser atm might actually be beat detection."
 * Nothing in this repo had ever measured it — `BeatDetectorTest` pins behaviours (it does not fire
 * on silence, it locks a steady metronome) but never asks what fraction of real beats get a flash.
 *
 * The measurement is deliberately end-to-end: ground-truth beats against the frames where a flash
 * actually rendered (`flashFiredThisFrame`), not against `isBeat`. Since the P1 predictive
 * scheduler landed, those are different frames by design — detection confirms a flash that already
 * fired — and the one Joe sees is the flash.
 *
 * [BeatCorpus] says what each track is for. The headline number is the mean F-measure across all
 * of them; the per-track table is where the information is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BeatAccuracyTest {

    private fun settingsFor(preset: String, beatClock: Boolean = false): AudioSettingsState =
        audioSettingsReducer(
            RgbUiState(audioSettings = AudioSettingsState()),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings.copy(beatClockEnabled = beatClock)

    /** Every frame where a flash actually rendered. */
    private fun flashes(track: BeatCorpus.Track, preset: String, beatClock: Boolean = false): List<Long> {
        val settings = settingsFor(preset, beatClock)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val out = ArrayList<Long>()
        for ((frame, atMs) in OfflineAudio.frames(track.pcm)) {
            val result = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
            if (result.flashFiredThisFrame) out.add(atMs)
        }
        return out
    }

    /**
     * The tempo the *strip* is keeping, from the median gap between flashes.
     *
     * Taken from the flashes rather than from the detector's own bpm on purpose: a detector that
     * locks 128 and then flashes twice a beat is showing the viewer 256, and it is the shown tempo
     * that either matches the music or does not.
     */
    private fun flashedBpm(flashesMs: List<Long>): Double {
        if (flashesMs.size < 4) return 0.0
        val gaps = flashesMs.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (gaps.isEmpty()) return 0.0
        val median = gaps[gaps.size / 2]
        return if (median <= 0) 0.0 else 60_000.0 / median
    }

    /**
     * Why the flashes are where they are: how much of the time there is a tempo lock at all, how
     * confident it is, and what share of flashes the predictive scheduler produced.
     */
    /**
     * The predictive scheduler fires only when a Bluetooth delay offset is configured — or so the
     * reading of the code says. This measures it: same tracks, same everything, only
     * `flashTimingOffsetMs` differs.
     */
    @Test
    fun `does the predictive scheduler need a delay offset to fire at all`() {
        println("\n=== Share of flashes from the predictive scheduler, by delay offset ===")
        println("%28s %10s %10s %10s %10s".format("track", "0ms", "40ms", "120ms", "F at 120ms"))
        for (track in BeatCorpus.all()) {
            val shares = listOf(0, 40, 120).map { offset ->
                val settings = settingsFor("Punchy").copy(flashTimingOffsetMs = offset)
                val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
                var flashes = 0
                var predictive = 0
                val at = ArrayList<Long>()
                for ((frame, atMs) in OfflineAudio.frames(track.pcm)) {
                    val r = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
                    if (r.flashFiredThisFrame) {
                        flashes++
                        at.add(atMs + offset)
                        if (r.predictiveFlash) predictive++
                    }
                }
                Triple(offset, if (flashes == 0) 0.0 else predictive.toDouble() / flashes, at)
            }
            val fAt120 = BeatAccuracy.score(track.beatsMs, shares.last().third)
            println("%28s %9d%% %9d%% %9d%% %9d%%".format(
                track.name,
                BeatAccuracy.pct(shares[0].second),
                BeatAccuracy.pct(shares[1].second),
                BeatAccuracy.pct(shares[2].second),
                BeatAccuracy.pct(fAt120.fMeasure)))
        }
    }

    /** Where the beat clock is actually able to run, and at what rate when it does. */
    @Test
    fun `beat clock coverage`() {
        println("\n=== Beat clock coverage, Punchy ===")
        println("%28s %10s %10s %10s %12s".format(
            "track", "grid%", "clock%", "ticks", "tick bpm vs true"))
        for (track in BeatCorpus.all()) {
            val settings = settingsFor("Punchy", beatClock = true)
            val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
            var frames = 0
            var grid = 0
            var running = 0
            val ticks = ArrayList<Long>()
            for ((frame, atMs) in OfflineAudio.frames(track.pcm)) {
                val r = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
                frames++
                if (r.hasBeatGrid) grid++
                if (r.beatClockRunning) running++
                if (r.predictiveFlash) ticks.add(atMs)
            }
            val gaps = ticks.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
            val tickBpm = if (gaps.isEmpty()) 0.0 else 60_000.0 / gaps[gaps.size / 2]
            println("%28s %9d%% %9d%% %10d %6.0f vs %.0f".format(
                track.name,
                BeatAccuracy.pct(grid.toDouble() / frames.coerceAtLeast(1)),
                BeatAccuracy.pct(running.toDouble() / frames.coerceAtLeast(1)),
                ticks.size, tickBpm, track.bpm))
        }
    }

    @Test
    fun `is there ever a tempo lock`() {
        println("\n=== Tempo lock across the corpus, Punchy ===")
        println("%28s %8s %10s %12s %10s %12s".format(
            "track", "trueBpm", "medianBpm", "medianConf", "conf>0.15", "predictive%"))
        for (track in BeatCorpus.all()) {
            val settings = settingsFor("Punchy")
            val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
            val bpms = ArrayList<Double>()
            val confs = ArrayList<Double>()
            var flashes = 0
            var predictive = 0
            for ((frame, atMs) in OfflineAudio.frames(track.pcm)) {
                val r = processor.process(frame, settings, atMs, effectivePacingMs = 50) ?: continue
                // Skip the first 5s: no tracker has an opinion yet, and including it just dilutes.
                if (atMs > 5000) {
                    bpms.add(r.bpm.toDouble())
                    confs.add(r.bpmConfidence.toDouble())
                }
                if (r.flashFiredThisFrame) {
                    flashes++
                    if (r.predictiveFlash) predictive++
                }
            }
            val medianBpm = bpms.sorted().getOrElse(bpms.size / 2) { 0.0 }
            val medianConf = confs.sorted().getOrElse(confs.size / 2) { 0.0 }
            val aboveGate = confs.count { it > 0.15 }.toDouble() / confs.size.coerceAtLeast(1)
            println("%28s %8.0f %10.1f %12.2f %9d%% %11d%%".format(
                track.name, track.bpm, medianBpm, medianConf,
                BeatAccuracy.pct(aboveGate),
                if (flashes == 0) 0 else BeatAccuracy.pct(predictive.toDouble() / flashes)))
        }
    }

    @Test
    fun `does the beat clock help`() {
        println("\n=== Shipped vs beat clock, mean F-measure ===")
        for (preset in listOf("Punchy", "Strobe Blast", "Ambient Chill")) {
            println("\n--- $preset ---")
            println("%28s %16s %16s %16s".format("track", "F off -> on", "P off -> on", "R off -> on"))
            var offSum = 0.0
            var onSum = 0.0
            val tracks = BeatCorpus.all()
            for (track in tracks) {
                val off = BeatAccuracy.score(track.beatsMs, flashes(track, preset, false))
                val on = BeatAccuracy.score(track.beatsMs, flashes(track, preset, true))
                offSum += off.fMeasure
                onSum += on.fMeasure
                println("%28s %7d%% ->%5d%% %7d%% ->%5d%% %7d%% ->%5d%%".format(
                    track.name,
                    BeatAccuracy.pct(off.fMeasure), BeatAccuracy.pct(on.fMeasure),
                    BeatAccuracy.pct(off.precision), BeatAccuracy.pct(on.precision),
                    BeatAccuracy.pct(off.recall), BeatAccuracy.pct(on.recall)))
            }
            println("mean F: %d%% -> %d%%".format(
                BeatAccuracy.pct(offSum / tracks.size), BeatAccuracy.pct(onSum / tracks.size)))
        }
    }

    @Test
    fun `beat accuracy across the corpus`() {
        for (preset in listOf("Punchy", "Strobe Blast", "Ambient Chill")) {
            println("\n=== Beat accuracy, $preset (F-measure, +-70ms) ===")
            println("%28s %6s %6s %6s %6s %9s %10s %s".format(
                "track", "F", "P", "R", "cont", "offset", "octave", "best grid"))
            var fSum = 0.0
            val tracks = BeatCorpus.all()
            for (track in tracks) {
                val flashMs = flashes(track, preset)
                val bpm = flashedBpm(flashMs)
                val score = BeatAccuracy.score(track.beatsMs, flashMs)
                val (variant, variantScore) = BeatAccuracy.bestVariant(track.beatsMs, flashMs)
                fSum += score.fMeasure
                println("%28s %5d%% %5d%% %5d%% %5d%% %+8dms %10s %s".format(
                    track.name,
                    BeatAccuracy.pct(score.fMeasure),
                    BeatAccuracy.pct(score.precision),
                    BeatAccuracy.pct(score.recall),
                    BeatAccuracy.pct(score.continuity),
                    score.medianOffsetMs,
                    BeatAccuracy.octaveOf(bpm, track.bpm),
                    if (variant == "true") "-" else "$variant (F=${BeatAccuracy.pct(variantScore.fMeasure)}%)"
                ))
            }
            println("mean F-measure: %d%%".format(BeatAccuracy.pct(fSum / tracks.size)))
        }
    }

    @Test
    fun `what each track is for`() {
        println("\n=== The corpus ===")
        for (track in BeatCorpus.all()) {
            println("%28s  %.0f bpm, %d beats — %s".format(
                track.name, track.bpm, track.beatsMs.size, track.what))
        }
    }
}
