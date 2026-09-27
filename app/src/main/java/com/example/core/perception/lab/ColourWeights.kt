package com.example.core.perception.lab

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Reads block 9: where each colour pair turns over from "the reference looked brighter" to "the
 * test did", and what that says about how much each channel's light counts to the eye.
 *
 * ## How a rung is scored
 *
 * Each rung is asked twice, once in each order. An answer naming the test interval scores +1, one
 * naming the reference -1, "Same" 0, and the rung's score is the mean. Because the two orders are
 * balanced, a habit of naming the second interval adds +1 to one answer and -1 to the other and
 * cancels, which is the point of asking both ways round.
 *
 * ## The match
 *
 * The crossover is where the score passes through zero, interpolated in log ratio between the two
 * rungs that straddle it. The test channel's weight relative to the reference's is the reciprocal:
 * needing 3x the light to look equal means each unit of it counts a third as much.
 *
 * If the score never changes sign, the match is outside the ladder and the reading says which side
 * rather than inventing a number. If it changes sign more than once, the answers were not monotonic
 * and the reading says so - that is a pair he could not order, not a noisy match to average over.
 */
object ColourWeights {

    data class PairReading(
        val testChannel: Int,
        val refChannel: Int,
        /** Mean score per rung, in ladder order, with the ratio actually played. */
        val rungs: List<Pair<Double, Double>>,
        /** Test light needed to look as bright as the reference, or null when outside the ladder. */
        val matchRatio: Double?,
        /** -1 when the test looked brighter at every rung, +1 when dimmer at every rung, else 0. */
        val outOfRange: Int,
        /** True when the score changed sign more than once. */
        val nonMonotonic: Boolean
    ) {
        /** How much a unit of the test channel's light counts, relative to the reference's. */
        val relativeWeight: Double? get() = matchRatio?.let { 1.0 / it }
    }

    data class Reading(
        val pairs: List<PairReading>,
        val catchFalsePositives: Int,
        val catchTrials: Int,
        val anchorsMissed: Int,
        val anchorTrials: Int
    ) {
        fun pair(test: Int, ref: Int): PairReading? =
            pairs.firstOrNull { it.testChannel == test && it.refChannel == ref }

        /**
         * Blue-against-green predicted from the other two pairs, over the one measured directly.
         *
         * 1.0 means the three matches agree and a single weight per channel describes them. Far
         * from 1 means they do not, and the weights are not safe to build on. Null when any of the
         * three fell outside its ladder.
         */
        val chainAgreement: Double?
            get() {
                val rg = pair(LabBlocks.CHANNEL_RED, LabBlocks.CHANNEL_GREEN)?.matchRatio ?: return null
                val br = pair(LabBlocks.CHANNEL_BLUE, LabBlocks.CHANNEL_RED)?.matchRatio ?: return null
                val bg = pair(LabBlocks.CHANNEL_BLUE, LabBlocks.CHANNEL_GREEN)?.matchRatio ?: return null
                return (rg * br) / bg
            }

        /**
         * Weights with green as 1, from the red-green and blue-green matches directly.
         *
         * The chained route is not averaged in: it is the check on these, and folding a check into
         * the thing it checks leaves nothing to check with.
         */
        val weights: Triple<Double, Double, Double>?
            get() {
                val r = pair(LabBlocks.CHANNEL_RED, LabBlocks.CHANNEL_GREEN)?.relativeWeight ?: return null
                val b = pair(LabBlocks.CHANNEL_BLUE, LabBlocks.CHANNEL_GREEN)?.relativeWeight ?: return null
                return Triple(r, 1.0, b)
            }
    }

    fun read(trials: List<LabTrial>, answers: List<LabAnswer>): Reading {
        val answered = trials.zip(answers)

        val catches = answered.filter { it.first.isCatch }
        val anchors = answered.filter { it.first.isAnchor }

        val pairs = answered
            .filter { it.first.kind == "match" }
            .groupBy { it.first.meta.getValue("pair") }
            .toSortedMap()
            .map { (_, rows) -> readPair(rows) }

        return Reading(
            pairs = pairs,
            catchFalsePositives = catches.count { it.second.optionId != it.first.correctOptionId },
            catchTrials = catches.size,
            anchorsMissed = anchors.count { it.second.optionId != it.first.correctOptionId },
            anchorTrials = anchors.size
        )
    }

    private fun readPair(rows: List<Pair<LabTrial, LabAnswer>>): PairReading {
        val first = rows.first().first.meta
        val rungs = rows
            .groupBy { it.first.meta.getValue("rung") }
            .toSortedMap()
            .map { (_, rs) ->
                val k = rs.first().first.meta.getValue("kMilli") / 1000.0
                val score = rs.map { (t, a) -> score(t, a) }.average()
                k to score
            }

        val signs = rungs.map { it.second }.filter { it != 0.0 }.map { it > 0 }
        val changes = signs.zipWithNext().count { (a, b) -> a != b }

        // Between the last rung where the reference won and the first rung after it where the test
        // won, linear in log ratio. Any "Same" rungs in between sit inside that span, so a run of
        // them centres the match on the run rather than pinning it to one end.
        val lastRef = rungs.indexOfLast { it.second < 0 }
        val firstTest = rungs.indices.firstOrNull { it > lastRef && rungs[it].second > 0 }
        val match: Double? = when {
            lastRef >= 0 && firstTest != null -> {
                val (k0, s0) = rungs[lastRef]
                val (k1, s1) = rungs[firstTest]
                val t = -s0 / (s1 - s0)
                exp(ln(k0) + t * (ln(k1) - ln(k0)))
            }
            else -> null
        }

        val outOfRange = when {
            match != null -> 0
            // The reference never won: the test looked at least as bright even at the bottom rung.
            lastRef < 0 -> -1
            else -> 1
        }

        return PairReading(
            testChannel = first.getValue("testChannel"),
            refChannel = first.getValue("refChannel"),
            rungs = rungs,
            matchRatio = match,
            outOfRange = outOfRange,
            nonMonotonic = changes > 1
        )
    }

    /** +1 if he named the test interval brighter, -1 the reference, 0 "Same". */
    private fun score(trial: LabTrial, answer: LabAnswer): Double {
        val testFirst = trial.meta.getValue("testFirst") == 1
        return when (answer.optionId) {
            "a" -> if (testFirst) 1.0 else -1.0
            "b" -> if (testFirst) -1.0 else 1.0
            else -> 0.0
        }
    }

    /** True when two ratios agree to within [tolerance] as a factor, e.g. 1.5 for "within 50%". */
    fun agrees(a: Double, b: Double, tolerance: Double): Boolean =
        abs(ln(a / b)) <= ln(tolerance)
}
