package com.example.feel

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How well a set of flashes matches a set of beats.
 *
 * The metric is the standard beat-tracking one (MIREX F-measure, ±70ms window), for two reasons:
 * it is what every published number is quoted in, so results here are comparable to the literature;
 * and 70ms is roughly where a flash stops reading as "on the beat" to a person anyway.
 *
 * [continuity] is reported alongside it because F-measure alone hides the failure that actually
 * looks worst: a tracker that is right half the time in alternating fashion scores the same as one
 * that is right for the first half of the song and lost for the second, and only the second is
 * forgivable.
 */
object BeatAccuracy {

    const val TOLERANCE_MS = 70L

    class Score(
        val fMeasure: Double,
        val precision: Double,
        val recall: Double,
        /** Longest run of consecutive beats hit, as a fraction of all beats. */
        val continuity: Double,
        /** Median signed offset of a matched flash from its beat, in ms. Negative is early. */
        val medianOffsetMs: Long,
        val matched: Int,
        val beats: Int,
        val flashes: Int
    ) {
        override fun toString() =
            "F=%.2f P=%.2f R=%.2f cont=%.2f offset=%+dms (%d/%d beats, %d flashes)".format(
                fMeasure, precision, recall, continuity, medianOffsetMs, matched, beats, flashes
            )
    }

    /**
     * Greedy one-to-one matching inside the tolerance window: each beat may claim the nearest
     * unclaimed flash. One-to-one is what stops a preset that strobes continuously from scoring
     * perfect recall — it would otherwise "hit" every beat by accident.
     */
    fun score(beatsMs: List<Long>, flashesMs: List<Long>, toleranceMs: Long = TOLERANCE_MS): Score {
        val used = BooleanArray(flashesMs.size)
        val offsets = ArrayList<Long>()
        val hit = BooleanArray(beatsMs.size)

        for ((i, beat) in beatsMs.withIndex()) {
            var bestIdx = -1
            var bestDist = Long.MAX_VALUE
            for ((j, flash) in flashesMs.withIndex()) {
                if (used[j]) continue
                val d = abs(flash - beat)
                if (d <= toleranceMs && d < bestDist) {
                    bestDist = d
                    bestIdx = j
                }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true
                hit[i] = true
                offsets.add(flashesMs[bestIdx] - beat)
            }
        }

        val matched = offsets.size
        val precision = if (flashesMs.isEmpty()) 0.0 else matched.toDouble() / flashesMs.size
        val recall = if (beatsMs.isEmpty()) 0.0 else matched.toDouble() / beatsMs.size
        val f = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)

        var longest = 0
        var run = 0
        for (h in hit) {
            if (h) { run++; if (run > longest) longest = run } else run = 0
        }

        val median = if (offsets.isEmpty()) 0L else offsets.sorted()[offsets.size / 2]
        return Score(
            fMeasure = f,
            precision = precision,
            recall = recall,
            continuity = if (beatsMs.isEmpty()) 0.0 else longest.toDouble() / beatsMs.size,
            medianOffsetMs = median,
            matched = matched,
            beats = beatsMs.size,
            flashes = flashesMs.size
        )
    }

    /**
     * The same score against the metrical variants a listener would also accept: half tempo, double
     * tempo, and the offbeat.
     *
     * Reported separately because the two failures need opposite responses. Scoring badly on the
     * true grid but well on a variant is an *octave* error — the tracker found the pulse and picked
     * the wrong multiple of it, which is a decision problem. Scoring badly on all of them means it
     * never found the pulse at all, which is a detection problem.
     */
    fun bestVariant(beatsMs: List<Long>, flashesMs: List<Long>): Pair<String, Score> {
        val variants = linkedMapOf(
            "true" to beatsMs,
            "half" to beatsMs.filterIndexed { i, _ -> i % 2 == 0 },
            "double" to doubled(beatsMs),
            "offbeat" to offbeat(beatsMs)
        )
        return variants
            .map { (name, grid) -> name to score(grid, flashesMs) }
            .maxByOrNull { it.second.fMeasure }!!
    }

    private fun doubled(beatsMs: List<Long>): List<Long> {
        if (beatsMs.size < 2) return beatsMs
        val out = ArrayList<Long>(beatsMs.size * 2)
        for (i in 0 until beatsMs.size - 1) {
            out.add(beatsMs[i])
            out.add((beatsMs[i] + beatsMs[i + 1]) / 2)
        }
        out.add(beatsMs.last())
        return out
    }

    private fun offbeat(beatsMs: List<Long>): List<Long> {
        if (beatsMs.size < 2) return beatsMs
        return (0 until beatsMs.size - 1).map { (beatsMs[it] + beatsMs[it + 1]) / 2 }
    }

    /** Detected tempo as a ratio of the true one, rounded to the nearest sensible musical factor. */
    fun octaveOf(detectedBpm: Double, trueBpm: Double): String {
        if (detectedBpm <= 0.0) return "none"
        val ratio = detectedBpm / trueBpm
        val candidates = listOf(0.25 to "quarter", 1.0 / 3 to "third", 0.5 to "half", 1.0 to "true",
            2.0 to "double", 3.0 to "triple", 4.0 to "quadruple")
        val best = candidates.minByOrNull { abs(ratio - it.first) / it.first }!!
        return if (abs(ratio - best.first) / best.first < 0.1) {
            best.second
        } else {
            "off (%.2fx)".format(ratio)
        }
    }

    /** Rounds to a whole number for compact printing without pulling in a formatter everywhere. */
    fun pct(v: Double) = (v * 100).roundToInt()
}
