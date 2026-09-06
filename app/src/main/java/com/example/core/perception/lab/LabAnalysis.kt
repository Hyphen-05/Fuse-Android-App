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
