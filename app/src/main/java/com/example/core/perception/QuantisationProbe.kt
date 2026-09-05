package com.example.core.perception

import kotlin.random.Random

/**
 * Asks whether the strip's *emitted* level moves on a grid, and how coarse that grid is.
 *
 * ## Why this exists
 *
 * The 2026-09-05 sitting measured a step "threshold" of about three commanded bytes that was
 * **flat** across bases 5, 10 and 25. A perceptual threshold scales with the level it sits on;
 * those bases predict roughly 1, 2 and 5 bytes. Constant spacing is what a *grid* does, not an eye.
 * Joe reached the same place from the other side, describing the trials as "either could very
 * clearly tell something changed or nothing at all, no like just about" — a threshold is defined by
 * having a just-about band, and he reports none.
 *
 * The hypothesis is that the firmware multiplies the colour byte by the brightness setting and
 * keeps an integer, so at his **22%** the emitted level only moves every `1 / 0.22 ≈ 4.5` commanded
 * bytes. That would make the "threshold" an artefact of the instrument, and it would also mean the
 * dither block measured nothing at all — dithering between two commanded bytes that map to the same
 * emitted level emits one steady level.
 *
 * ## Why this is not a staircase
 *
 * A staircase is what disguised a grid as a threshold in the first place: it converges on whatever
 * step size gets answered right 71% of the time, and a grid produces exactly such a number without
 * anyone's eye being involved. This walks the commanded byte up **one at a time** and asks only
 * "did anything change". **The spacing between the yes answers is the grid**, read straight off the
 * answers with no fitting and no model in between.
 *
 * ## The two outcomes are both informative, and they do not look alike
 *
 * Each trial compares a level against the one commanded byte above it — never against a level seen
 * several trials ago — so a change too small to resolve is never accumulated into a visible one.
 *
 *  - **A grid** makes most one-byte increments emit *nothing at all* and occasionally makes one
 *    cross a boundary, which jumps a whole emitted level and is obvious. That reads as **periodic
 *    yes answers at regular spacing**, and the spacing is the grid.
 *  - **A genuine perceptual threshold above one byte** makes *every* one-byte increment
 *    subthreshold, so it reads as **no answer anywhere**. That refutes the grid and leaves the
 *    three-byte figure standing as a real threshold.
 *
 * Because "no everywhere" is a meaningful result, it has to be distinguishable from inattention.
 * Hence two kinds of control, not one:
 *
 *  - **Catch trials** repeat the same byte in both intervals. The honest answer is "no change", and
 *    saying otherwise is a false positive.
 *  - **Anchor trials** step by [ANCHOR_DELTA], which is far above anything under discussion. The
 *    honest answer is "changed". A run of no answers with the anchors missed is a tired viewer, not
 *    a measurement.
 *
 * ## Brightness is the independent variable
 *
 * The plan runs the same low segment twice: once at whatever brightness Joe has set, and once at
 * **100%**. If the grid is an integer multiply, its spacing collapses toward one byte at 100% and
 * the arithmetic is then known rather than guessed. If the spacing does not move, the multiply
 * hypothesis is wrong whatever else is going on. **His brightness is restored afterwards** — it is
 * his setting, not the probe's.
 */
object QuantisationProbe {

    /** Step size for the attention anchors: unmissable under either hypothesis. */
    const val ANCHOR_DELTA = 12

    /** Roughly one trial in six carries no change at all. */
    const val CATCH_IN = 6

    /** Roughly one trial in nine is an anchor. */
    const val ANCHOR_IN = 9

    /** Steps per segment. Sized so the low pass spans several grid periods under the hypothesis. */
    const val LOW_STEPS = 24
    const val HIGH_STEPS = 20
    const val FULL_STEPS = 16

    /** What a trial is asking, which decides what the honest answer is. */
    enum class ProbeTrialKind {
        /** A real one-byte increment. The answer is the datum. */
        STEP,

        /** Both intervals identical. "No change" is correct; anything else is a false positive. */
        CATCH,

        /** A large increment. "Changed" is correct; missing it means attention lapsed. */
        ANCHOR
    }

    /**
     * One segment of the walk.
     *
     * [brightnessPercent] is null to leave Joe's own setting alone, or a percentage the probe
     * should command before the segment runs.
     */
    data class Segment(
        val label: String,
        val startByte: Int,
        val steps: Int,
        val brightnessPercent: Int?
    )

    /** One question: hold [fromByte], then hold [toByte], and ask whether anything changed. */
    data class ProbeTrial(
        val segment: String,
        val kind: ProbeTrialKind,
        val fromByte: Int,
        val toByte: Int,
        val brightnessPercent: Int?,
        /** Index of this trial's increment within its segment's walk, or -1 for a control. */
        val walkIndex: Int
    )

    /** What one segment's answers add up to. */
    data class SegmentResult(
        val label: String,
        val brightnessPercent: Int?,
        /** The `fromByte` of every increment answered "changed", in order. */
        val changeAtBytes: List<Int>,
        /** Gaps between consecutive [changeAtBytes]. The grid, if there is one. */
        val spacings: List<Int>,
        val stepTrials: Int
    ) {
        val meanSpacing: Double? get() = if (spacings.isEmpty()) null else spacings.average()

        /**
         * True when the segment produced no change anywhere.
         *
         * Only meaningful alongside the anchor hit rate: with the anchors missed it says the viewer
         * was not watching, and with the anchors hit it says one commanded byte is genuinely
         * invisible here — which is a real result, and the one that refutes the grid.
         */
        val sawNothing: Boolean get() = changeAtBytes.isEmpty() && stepTrials > 0
    }

    data class ProbeReport(
        val segments: List<SegmentResult>,
        val catchTrials: Int,
        val catchFalsePositives: Int,
        val anchorTrials: Int,
        val anchorsMissed: Int
    ) {
        val falsePositiveRate: Double
            get() = if (catchTrials == 0) 0.0 else catchFalsePositives.toDouble() / catchTrials
        val anchorMissRate: Double
            get() = if (anchorTrials == 0) 0.0 else anchorsMissed.toDouble() / anchorTrials
    }

    /**
     * The three segments to run, placed relative to the floor calibration.
     *
     * The low segment is where a grid bites hardest and where Ambiance's steppiness was reported,
     * so it is the one repeated at full brightness. The high segment exists to separate the two
     * shapes a grid can have: an integer multiply gives **constant** spacing in commanded bytes all
     * the way up, while anything proportional to the level would widen with it. Neither of those is
     * distinguishable from one segment.
     *
     * The first two segments deliberately command no brightness at all — the point of them is to
     * measure the strip as Joe actually runs it.
     */
    fun planFor(floor: FloorFinder.FloorResult): List<Segment> {
        val base = floor.clearlyOn.coerceIn(1, 200)
        return listOf(
            Segment("low", startByte = base, steps = LOW_STEPS, brightnessPercent = null),
            Segment(
                "high",
                startByte = (base * 6).coerceIn(48, 180),
                steps = HIGH_STEPS,
                brightnessPercent = null
            ),
            Segment("low_full", startByte = base, steps = FULL_STEPS, brightnessPercent = 100)
        )
    }

    /**
     * Expands a plan into the exact trial list, controls included.
     *
     * Deterministic in [seed], and — unlike the staircases in [PerceptionSession] — **independent
     * of the answers**, because nothing here adapts. That makes going back a trial, changing an old
     * answer and resuming after a crash all trivially correct: the sequence is a pure function of
     * `(seed, plan)`, and an answer is just an index into it.
     */
    fun buildTrials(plan: List<Segment>, seed: Long): List<ProbeTrial> {
        val random = Random(seed)
        val trials = mutableListOf<ProbeTrial>()
        for (segment in plan) {
            for (i in 0 until segment.steps) {
                val from = segment.startByte + i
                // Controls are emitted before the increment they precede, so a segment can never
                // end on one and the walk is never left half-finished.
                if (random.nextInt(CATCH_IN) == 0) {
                    trials.add(
                        ProbeTrial(
                            segment.label, ProbeTrialKind.CATCH,
                            fromByte = from, toByte = from,
                            brightnessPercent = segment.brightnessPercent, walkIndex = -1
                        )
                    )
                }
                if (random.nextInt(ANCHOR_IN) == 0) {
                    trials.add(
                        ProbeTrial(
                            segment.label, ProbeTrialKind.ANCHOR,
                            fromByte = from, toByte = (from + ANCHOR_DELTA).coerceAtMost(255),
                            brightnessPercent = segment.brightnessPercent, walkIndex = -1
                        )
                    )
                }
                trials.add(
                    ProbeTrial(
                        segment.label, ProbeTrialKind.STEP,
                        fromByte = from, toByte = (from + 1).coerceAtMost(255),
                        brightnessPercent = segment.brightnessPercent, walkIndex = i
                    )
                )
            }
        }
        return trials
    }

    /**
     * Reads a run's answers back as spacings.
     *
     * [answers] are in trial order and may be shorter than [trials]; a part-finished run reports on
     * what it has. `true` means Joe said something changed.
     */
    fun report(
        plan: List<Segment>,
        trials: List<ProbeTrial>,
        answers: List<Boolean>
    ): ProbeReport {
        val answered = trials.take(answers.size).zip(answers)
        val segments = plan.map { segment ->
            val steps = answered.filter { (t, _) ->
                t.segment == segment.label && t.kind == ProbeTrialKind.STEP
            }
            val changeAt = steps.filter { it.second }.map { it.first.fromByte }
            SegmentResult(
                label = segment.label,
                brightnessPercent = segment.brightnessPercent,
                changeAtBytes = changeAt,
                spacings = changeAt.zipWithNext { a, b -> b - a },
                stepTrials = steps.size
            )
        }
        val catches = answered.filter { it.first.kind == ProbeTrialKind.CATCH }
        val anchors = answered.filter { it.first.kind == ProbeTrialKind.ANCHOR }
        return ProbeReport(
            segments = segments,
            catchTrials = catches.size,
            catchFalsePositives = catches.count { it.second },
            anchorTrials = anchors.size,
            anchorsMissed = anchors.count { !it.second }
        )
    }
}
