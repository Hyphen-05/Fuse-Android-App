package com.example.core.perception.lab

/**
 * Reading a block's answers back.
 *
 * Two rules run through all of it.
 *
 * **The controls are reported first and gate everything else.** A block with a high rate of
 * confident answers on its no-difference trials is not a weaker result, it is not a result. A block
 * whose anchors were missed says the viewer stopped watching, which matters more here than usual
 * because "saw nothing" is a legitimate outcome of three of these four blocks and would otherwise
 * be indistinguishable from inattention.
 *
 * **Nothing here concludes on Joe's behalf.** Each reading says what the answers were and what
 * would follow, and stops. The 2026-09-04 evening was lost to a simulation that scored a change as
 * an improvement and a derivation that said dark scenes were too bright, neither of which had been
 * near the wall.
 */
object LabAnalysis {

    /** How high a guessing rate is tolerated before a block's readings are discarded. */
    const val CONTROL_LIMIT = 0.20

    data class Controls(
        val catchTrials: Int,
        val catchFalsePositives: Int,
        val anchorTrials: Int,
        val anchorsMissed: Int
    ) {
        val falsePositiveRate: Double
            get() = if (catchTrials == 0) 0.0 else catchFalsePositives.toDouble() / catchTrials
        val anchorMissRate: Double
            get() = if (anchorTrials == 0) 0.0 else anchorsMissed.toDouble() / anchorTrials
        val trustworthy: Boolean
            get() = falsePositiveRate <= CONTROL_LIMIT && anchorMissRate <= CONTROL_LIMIT
    }

    fun controls(trials: List<LabTrial>, answers: List<LabAnswer>): Controls {
        val answered = trials.take(answers.size).zip(answers)
        val catches = answered.filter { it.first.isCatch }
        val anchors = answered.filter { it.first.isAnchor }
        return Controls(
            catchTrials = catches.size,
            // A catch trial's honest answer is its correctOptionId - "no change" on a step block,
            // "can't tell" on a two-interval block. Anything else is a false positive.
            catchFalsePositives = catches.count { it.second.optionId != it.first.correctOptionId },
            anchorTrials = anchors.size,
            anchorsMissed = anchors.count { it.second.optionId != it.first.correctOptionId }
        )
    }

    // --- block 0 ------------------------------------------------------------------------------------

    data class GridReading(
        val changeAtBytes: List<Int>,
        val spacings: List<Int>,
        val predictedSpacing: Int
    ) {
        val meanSpacing: Double? get() = if (spacings.isEmpty()) null else spacings.average()

        /** True when tonight's boundaries sit where `100 / brightness` says they should. */
        val agreesWithRule: Boolean
            get() = meanSpacing?.let { kotlin.math.abs(it - predictedSpacing) < 0.75 } ?: false
    }

    fun gridReading(
        trials: List<LabTrial>,
        answers: List<LabAnswer>,
        context: LabContext
    ): GridReading {
        val steps = trials.take(answers.size).zip(answers).filter { it.first.kind == "step" }
        val changed = steps.filter { it.second.optionId == "yes" }
            .mapNotNull { it.first.meta["fromByte"] }
        return GridReading(
            changeAtBytes = changed,
            spacings = changed.zipWithNext { a, b -> b - a },
            predictedSpacing = context.gridSpacing
        )
    }

    // --- block 1 ------------------------------------------------------------------------------------

    /**
     * The smallest visible change at each anchor, and the perceptual scale that falls out of it.
     *
     * [thresholdByAnchor] is the smallest delta answered "changed" at that anchor. Null means
     * nothing on the ladder was visible there, which is a real finding and not missing data.
     *
     * [visibleStepsPerByte] is one over that threshold: the local density of visible steps. Its
     * running sum across the range is the scale the taste blocks want their stimuli spaced in.
     */
    data class ScaleReading(
        val thresholdByAnchor: Map<Int, Int?>,
        val inconsistentAnchors: List<Int>
    ) {
        val visibleStepsPerByte: Map<Int, Double>
            get() = thresholdByAnchor.mapValues { (_, t) -> if (t == null) 0.0 else 1.0 / t }

        /**
         * Weber's law says the threshold should grow in proportion to the level.
         *
         * Reported rather than assumed, because the last time a threshold came back **flat** it was
         * the hardware's grid being measured and not an eye. At 100% brightness the grid is one
         * byte, so that particular confound is gone - but the check is cheap and the failure was
         * expensive.
         */
        fun proportionality(): Double? {
            val pts = thresholdByAnchor.entries.mapNotNull { (a, t) -> t?.let { a.toDouble() to it.toDouble() } }
            if (pts.size < 3) return null
            val ratios = pts.map { it.second / it.first }
            return ratios.average()
        }

        val looksFlat: Boolean
            get() {
                val ts = thresholdByAnchor.values.filterNotNull()
                return ts.size >= 3 && ts.max() - ts.min() <= 1
            }
    }

    fun scaleReading(trials: List<LabTrial>, answers: List<LabAnswer>): ScaleReading {
        val jnd = trials.take(answers.size).zip(answers).filter { it.first.kind == "jnd" }
        val byAnchor = jnd.groupBy { it.first.meta["anchor"] ?: 0 }
        val thresholds = mutableMapOf<Int, Int?>()
        val inconsistent = mutableListOf<Int>()
        for ((anchor, rows) in byAnchor) {
            val seen = rows.filter { it.second.optionId == "yes" }.mapNotNull { it.first.meta["delta"] }
            val unseen = rows.filter { it.second.optionId == "no" }.mapNotNull { it.first.meta["delta"] }
            thresholds[anchor] = seen.minOrNull()
            // A delta larger than one he saw, reported as invisible, is not physically possible on a
            // monotonic response. It means an inattentive trial, and the anchor is flagged rather
            // than quietly averaged in.
            val smallestSeen = seen.minOrNull()
            if (smallestSeen != null && unseen.any { it > smallestSeen }) inconsistent.add(anchor)
        }
        return ScaleReading(thresholds.toSortedMap(), inconsistent.sorted())
    }

    // --- block 2 ------------------------------------------------------------------------------------

    /**
     * Per rate, how often the faster reference was picked out as smoother.
     *
     * Near 0.5 means the two are indistinguishable, so **that rate is enough** and paying for more
     * buys nothing visible. Near 1.0 means the slower one is visibly worse.
     *
     * "Can't tell" counts as not distinguishing rather than being dropped: an answer of "these look
     * the same" is the observation this block exists to collect, and discarding it would bias every
     * rate toward looking worse than it is.
     */
    data class RateReading(val pickedFasterByRate: Map<Int, Double>, val trialsByRate: Map<Int, Int>) {
        /** The lowest rate that was not reliably told apart from the reference. */
        fun sufficientRate(): Int? =
            pickedFasterByRate.entries.filter { it.value <= INDISTINGUISHABLE }
                .minByOrNull { it.key }?.key

        companion object {
            /** Chance on a three-option forced choice is well below this; 0.65 is a lenient bar. */
            const val INDISTINGUISHABLE = 0.65
        }
    }

    fun rateReading(trials: List<LabTrial>, answers: List<LabAnswer>): RateReading {
        val rows = trials.take(answers.size).zip(answers).filter { it.first.kind == "rate" }
        val byRate = rows.groupBy { it.first.meta["rateHz"] ?: 0 }
        return RateReading(
            pickedFasterByRate = byRate.mapValues { (_, rs) ->
                rs.count { it.second.optionId == it.first.correctOptionId }.toDouble() / rs.size
            }.toSortedMap(),
            trialsByRate = byRate.mapValues { it.value.size }.toSortedMap()
        )
    }

    // --- block 3 ------------------------------------------------------------------------------------

    /**
     * Whether a dithered pair reads as an in-between level, and whether it flickers.
     *
     * [landsBetween] is the fraction of the brightness comparisons answered correctly. High means
     * the dither really does sit between the two levels, which is the whole proposition. Chance
     * means it is indistinguishable from the steady levels either side - it emits one of them, and
     * dithering buys nothing here.
     *
     * [flickerVisibleByHold] is the fraction of trials where the dithered interval was picked out
     * as flickering, per alternation period. **Low is good**: it means the level is bought without
     * a visible cost. This is the one block where the two readings must be taken together - a
     * dither that lands between *and* does not flicker is the result that would change the app.
     */
    data class DitherReading(
        val landsBetween: Double,
        val betweenTrials: Int,
        val flickerVisibleByHold: Map<Long, Double>
    )

    // --- blocks 4-6: the taste blocks ---------------------------------------------------------------

    /**
     * A paired-comparison block, read back.
     *
     * All three taste blocks share this because they share a shape: two arms, randomised into two
     * intervals, and a vote. What differs is only what an arm *is* — a half-life in ms, a floor
     * level, the top of a lifted scene — so the arm is carried as an integer in `armFirst`/
     * `armSecond` and the reading never needs to know which block it is looking at.
     *
     * ## Why the three health numbers come before the winner
     *
     * A preference block can fail in three ways that all produce a confident-looking ranking:
     *
     *  - **[consistency]** — he was asked some pairs twice and did not agree with himself. Below
     *    [CONSISTENT_ENOUGH] the votes are noise wearing a ranking's clothes.
     *  - **[transitivityViolations]** — he preferred A to B, B to C and C to A. That is not a bad
     *    answer; it means the arms are not on one axis, so a *single* number cannot summarise them
     *    and the design has a confound in it.
     *  - **[unsureRate]** — most of the trials were "can't tell". A ranking built from the handful
     *    that were not is a ranking of the trials he could separate, which is a different question
     *    from the one asked.
     *
     * That last one is the failure block 2 walked into: 10 "can't tell" out of 15, which read
     * naively said one rate was as good as another and actually said none of them were any good.
     */
    data class PreferenceReading(
        val kind: String,
        val votes: Map<Int, Int>,
        val comparisons: Int,
        val unsureCount: Int,
        val repeatedPairs: Int,
        val repeatedPairsAgreeing: Int,
        val transitivityViolations: Int,
        val hardFlips: Int,
        val orderFlipPairs: Int,
        val orderFlipSameArm: Int,
        val orderFlipSameLetter: Int
    ) {
        val unsureRate: Double
            get() = if (comparisons == 0) 0.0 else unsureCount.toDouble() / comparisons

        /** Null when no pair was asked twice, which is itself a reason to distrust the block. */
        val consistency: Double?
            get() = if (repeatedPairs == 0) null
            else repeatedPairsAgreeing.toDouble() / repeatedPairs

        /**
         * Disagreements that actually contradict, as a share of the repeats.
         *
         * [consistency] counts "can't tell once, picked A the other time" as a disagreement, and
         * that is the wrong reading: it is one answer at the edge of visibility, not a reversal.
         * Only a **hard flip** — decisive both times, naming opposite winners — says the vote was a
         * coin.
         *
         * The distinction is not academic. Block 4's first run scored 0.5 consistency, which reads
         * as a failed block; two of its three disagreements were hard flips and both sat at the one
         * anchor where he had no preference to be consistent about. At the other anchor his answers
         * agreed and were unanimous. A single number over both anchors hid the actual result.
         */
        val hardFlipRate: Double?
            get() = if (repeatedPairs == 0) null else hardFlips.toDouble() / repeatedPairs

        /** The arms in vote order. Only worth reading when [trustworthy] holds. */
        val ranking: List<Int>
            get() = votes.entries.sortedByDescending { it.value }.map { it.key }

        /**
         * Of the pairs asked in both orders and answered decisively both times, the share where he
         * named the **same arm**.
         *
         * This is the block's real credibility number, and it is a better instrument than the catch
         * trials for a taste question. A catch shows two identical intervals, so a decisive answer
         * there could be inattention *or* a habit of breaking ties by picking one of them — and
         * those call for opposite responses. The order flip separates them: an answer that survives
         * a swap is about the stimulus, and one that follows the interval position is not.
         *
         * Null when no pair was asked both ways with two decisive answers.
         */
        val orderRobustness: Double?
            get() = if (orderFlipPairs == 0) null
            else orderFlipSameArm.toDouble() / orderFlipPairs

        val trustworthy: Boolean
            get() = comparisons > 0 &&
                unsureRate <= MOSTLY_UNSURE &&
                transitivityViolations == 0 &&
                (hardFlipRate ?: 1.0) <= MAX_HARD_FLIPS &&
                (orderRobustness ?: 1.0) >= ORDER_ROBUST_ENOUGH

        companion object {
            /** Agreeing with yourself on 3 of 4 repeats is the floor for reading a ranking at all. */
            const val CONSISTENT_ENOUGH = 0.75

            /**
             * How many outright contradictions a readable block may contain.
             *
             * The gate is on [hardFlipRate] rather than [consistency] because a block where he was
             * often unsure is a block whose arms were too close together, and that is a fact about
             * the *stimuli* — it belongs in [unsureRate], where it already is. Counting it twice
             * fails blocks that produced a clean answer wherever they asked a real question.
             */
            const val MAX_HARD_FLIPS = 0.25

            /**
             * How often a decisive answer must survive swapping the intervals.
             *
             * Measured on 2026-09-06/07 at 6 of 7, so this bar is where his real answers already
             * sit rather than an aspiration.
             */
            const val ORDER_ROBUST_ENOUGH = 0.75

            /** Above this share of "can't tell", the block did not manage to ask its question. */
            const val MOSTLY_UNSURE = 0.5
        }
    }

    /**
     * What makes two trials the same question: the arms, and the scope they were asked in.
     */
    private data class PairKey(val anchorLevel: Int?, val toLevel: Int?, val arms: Pair<Int, Int>)

    /**
     * The same reading, split by one meta key — the anchor a comparison was asked at, usually.
     *
     * Block 4's first run is the case for this existing. Pooled across both anchors it scored 0.5
     * consistency and looked like a failed block; split, one anchor was unanimous and the other had
     * no preference in it at all, which is a result and an actionable one.
     */
    fun preferenceReadingsBy(
        trials: List<LabTrial>,
        answers: List<LabAnswer>,
        kind: String,
        metaKey: String
    ): Map<Int, PreferenceReading> = trials.take(answers.size)
        .filter { it.kind == kind && !it.isCatch }
        .mapNotNull { it.meta[metaKey] }
        .distinct()
        .sorted()
        .associateWith { preferenceReading(trials, answers, kind, metaKey to it) }

    /**
     * Read one [kind] of comparison out of a taste block — "smoothing", "floor", "lift",
     * "cut_or_ease".
     *
     * Catch trials are excluded here and read by [controls] instead: on a taste block a catch is
     * measuring whether he invents a preference between two identical things, which is a fact about
     * the *block* rather than a vote for either arm — and one that [PreferenceReading.orderRobustness]
     * reads better.
     */
    fun preferenceReading(
        trials: List<LabTrial>,
        answers: List<LabAnswer>,
        kind: String,
        only: Pair<String, Int>? = null
    ): PreferenceReading {
        val rows = trials.take(answers.size).zip(answers)
            .filter { it.first.kind == kind && !it.first.isCatch }
            .filter { only == null || it.first.meta[only.first] == only.second }
        val votes = mutableMapOf<Int, Int>()
        // Keyed by the unordered pair, so the same two arms asked in either order collate. The
        // presented order is kept alongside the winner, because telling a preference from an order
        // effect needs both.
        val byPair = mutableMapOf<PairKey, MutableList<Shown>>()
        var unsure = 0
        for ((trial, answer) in rows) {
            val first = trial.meta["armFirst"] ?: continue
            val second = trial.meta["armSecond"] ?: continue
            votes.putIfAbsent(first, 0)
            votes.putIfAbsent(second, 0)
            val winner = when (answer.optionId) {
                "a" -> first
                "b" -> second
                else -> null
            }
            if (winner == null) unsure++ else votes[winner] = votes.getValue(winner) + 1
            // The scope is part of the key. The same two arms asked at a dark anchor and at a
            // bright one are two different questions, and collating them invents contradictions
            // out of a preference that simply depends on level — which is the thing block 4 asks
            // two anchors in order to find.
            val arms = if (first <= second) first to second else second to first
            val key = PairKey(trial.meta["anchorLevel"], trial.meta["toLevel"], arms)
            byPair.getOrPut(key) { mutableListOf() }
                .add(Shown(first = first, second = second, winner = winner, option = answer.optionId))
        }
        val repeated = byPair.filterValues { it.size > 1 }
        var flipPairs = 0
        var flipSameArm = 0
        var flipSameLetter = 0
        for (shown in repeated.values) {
            for (i in shown.indices) for (j in i + 1 until shown.size) {
                val x = shown[i]
                val y = shown[j]
                // Only a genuine swap, answered decisively both times, can separate a preference
                // from an order effect. Same-order repeats and unsure answers say nothing here.
                if (x.first == y.first) continue
                if (x.winner == null || y.winner == null) continue
                flipPairs++
                if (x.winner == y.winner) flipSameArm++ else if (x.option == y.option) flipSameLetter++
            }
        }
        return PreferenceReading(
            kind = kind,
            votes = votes.toSortedMap(),
            comparisons = rows.size,
            unsureCount = unsure,
            repeatedPairs = repeated.size,
            // "Can't tell" both times is agreement: he gave the same answer twice, and that answer
            // was that there is nothing to choose between them.
            repeatedPairsAgreeing = repeated.count { (_, ws) -> ws.map { it.winner }.distinct().size == 1 },
            transitivityViolations = transitivityViolations(byPair),
            hardFlips = repeated.count { (_, ws) -> ws.mapNotNull { it.winner }.distinct().size > 1 },
            orderFlipPairs = flipPairs,
            orderFlipSameArm = flipSameArm,
            orderFlipSameLetter = flipSameLetter
        )
    }

    /** One presentation of a pair: which arm went first, and what he said. */
    private data class Shown(val first: Int, val second: Int, val winner: Int?, val option: String)

    /**
     * Cycles in the preference graph: A over B, B over C, C over A.
     *
     * Only pairs with a clear majority winner contribute an edge. A pair he could not separate says
     * nothing about ordering, and treating it as an edge in either direction would manufacture
     * violations out of indifference.
     */
    private fun transitivityViolations(byPair: Map<PairKey, List<Shown>>): Int {
        val beats = mutableSetOf<Pair<Int, Int>>()
        for ((pair, shown) in byPair) {
            val (lo, hi) = pair.arms
            val loWins = shown.count { it.winner == lo }
            val hiWins = shown.count { it.winner == hi }
            when {
                loWins > hiWins -> beats.add(lo to hi)
                hiWins > loWins -> beats.add(hi to lo)
                else -> Unit
            }
        }
        val arms = beats.flatMap { listOf(it.first, it.second) }.distinct().sorted()
        var violations = 0
        for (a in arms) for (b in arms) for (c in arms) {
            if (a == b || b == c || a == c) continue
            // Counted once per cycle rather than once per rotation of it.
            if (a < b && a < c && (a to b) in beats && (b to c) in beats && (c to a) in beats) {
                violations++
            }
        }
        return violations
    }

    fun ditherReading(trials: List<LabTrial>, answers: List<LabAnswer>): DitherReading {
        val rows = trials.take(answers.size).zip(answers)
        val between = rows.filter { it.first.kind == "between" }
        val flicker = rows.filter { it.first.kind == "flicker" }
        return DitherReading(
            landsBetween = if (between.isEmpty()) 0.0
            else between.count { it.second.optionId == it.first.correctOptionId }.toDouble() / between.size,
            betweenTrials = between.size,
            flickerVisibleByHold = flicker.groupBy { (it.first.meta["holdMs"] ?: 0).toLong() }
                .mapValues { (_, rs) ->
                    rs.count { it.second.optionId == it.first.correctOptionId }.toDouble() / rs.size
                }.toSortedMap()
        )
    }
}
