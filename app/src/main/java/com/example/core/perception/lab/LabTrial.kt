package com.example.core.perception.lab

import com.example.core.perception.Stimulus

/**
 * The common shape of every Perception Lab block, and the reason there is a common shape at all.
 *
 * ## The goal these serve
 *
 * The lab exists so that tuning can stop needing Joe's eyes on the strip for every change. That is
 * only earned by a **model that predicts his answers** — the quantisation probe is the standard to
 * hold to, where `emitted = ceil(byte × brightness / 100)` predicted all 74 trials individually
 * rather than fitting an average of them. A pile of measured numbers does not license tuning
 * without him; a rule that survives held-out trials does.
 *
 * So every block here is built to produce something predictive, and the battery ends with a block
 * that states its prediction before he answers and scores itself. Until that block passes, the
 * honest answer to "can you tune this without me looking" is no.
 *
 * ## Why blocks are fixed lists
 *
 * A trial list is a pure function of `(block, context, seed)` and an answer is an index into it.
 * Nothing adapts mid-run. That buys undo, resume-after-crash and answer-changing for free — the
 * same property that made [com.example.core.perception.QuantisationProbe] simple — and it costs
 * only some efficiency against an adaptive staircase.
 *
 * It also avoids the trap that cost the 2026-09-05 sitting its main result: an adaptive staircase
 * converges on *whatever* gets answered right 71% of the time, and a hardware grid does that
 * without any eye being involved. A fixed ladder with the answers read off directly cannot
 * disguise a grid as a threshold, because the raw pattern of yes and no is preserved.
 *
 * ## Controls are not optional
 *
 * Every block carries **catch trials** (no difference; the honest answer is the null one) and,
 * where a run of null answers would otherwise be ambiguous, **anchor trials** (a difference far
 * above anything under discussion; the honest answer is the positive one). Without both, "saw
 * nothing all run" and "stopped watching" are the same record — which matters enormously here,
 * because "saw nothing" is a real and expected outcome in several of these blocks.
 */

/** One answer he can give. [id] is what gets recorded; [label] is what the button says. */
data class LabOption(val id: String, val label: String)

/**
 * Whether a trial has a right answer, and if not, why not. There are **three** cases, not two.
 *
 * Collapsing them is a real hazard. The first sitting's fade block scored "picked the dithered
 * fade" as *correct*, which turned a preference into an accuracy and reported a taste as a result.
 * And a step trial asking "did anything change" at one byte has no right answer either — but for
 * the opposite reason, since there is a fact of the matter and measuring it is the entire point.
 */
enum class LabTruth {
    /** There is a right answer and it is known: catch trials, anchors, brightness orderings. */
    KNOWN,

    /** There is a fact of the matter and it is what the block is measuring. Never scored. */
    UNKNOWN,

    /** There is no fact of the matter. Taste. Counted as votes, never as a score. */
    PREFERENCE
}

/**
 * One question.
 *
 * [intervals] is one or two stimuli, played in order and announced as A and B when there are two.
 *
 * [truth] says whether the answer can be scored at all, and [correctOptionId] is non-null exactly
 * when it is [LabTruth.KNOWN]. That pairing is enforced in `init` rather than trusted, because the
 * two ways of having no right answer — measuring an unknown, and asking a taste — must never be
 * averaged together.
 *
 * [meta] carries the numbers the analysis needs — anchor level, delta, rate — so a block's reading
 * never has to re-derive them from the stimulus labels.
 */
data class LabTrial(
    val block: String,
    val kind: String,
    val intervals: List<Stimulus>,
    val question: String,
    val hint: String,
    val options: List<LabOption>,
    val truth: LabTruth,
    val correctOptionId: String?,
    val meta: Map<String, Int>,
    val brightnessPercent: Int?
) {
    init {
        require((truth == LabTruth.KNOWN) == (correctOptionId != null)) {
            "a trial has a correct answer exactly when its truth is KNOWN"
        }
        correctOptionId?.let { c ->
            require(options.any { it.id == c }) { "correct answer $c is not among the options" }
        }
    }

    val isCatch: Boolean get() = kind == KIND_CATCH
    val isAnchor: Boolean get() = kind == KIND_ANCHOR
    val isPreference: Boolean get() = truth == LabTruth.PREFERENCE
    val isScorable: Boolean get() = truth == LabTruth.KNOWN

    companion object {
        const val KIND_CATCH = "catch"
        const val KIND_ANCHOR = "anchor"
    }
}

/** An answer as given: the option id, and how long he took. */
data class LabAnswer(val optionId: String, val responseMs: Long)

/**
 * What a block needs to know about this particular strip, on this particular evening.
 *
 * Everything is measured rather than assumed, because assuming it is what made the first sitting
 * mostly invisible: base levels were hardcoded at bytes 4-100 and Joe reported "for lots of the
 * tests one or both is just leds off".
 *
 * [gridSpacing] is how many commanded bytes make one emitted level — `100 / brightnessPercent`,
 * confirmed exactly at 25% and 100% on 2026-09-05. It is what tells a dither block which byte pairs
 * can possibly do anything: only those straddling a `k × gridSpacing` boundary change the output at
 * all, and the first attempt at dithering tested a pair that could not.
 */
data class LabContext(
    val floorFirstVisible: Int,
    val floorClearlyOn: Int,
    val brightnessPercent: Int
) {
    val gridSpacing: Int
        get() = if (brightnessPercent <= 0) 1 else (100.0 / brightnessPercent).let {
            kotlin.math.round(it).toInt().coerceAtLeast(1)
        }

    /** The commanded byte that lands on the bottom of emitted level [level]. */
    fun byteForLevel(level: Int): Int =
        ((level - 1) * gridSpacing + 1).coerceIn(1, 255)

    /** The emitted level a commanded byte produces: the measured rule, not a fit. */
    fun levelForByte(byte: Int): Int =
        kotlin.math.ceil(byte * brightnessPercent / 100.0).toInt()
}

/** Standard option sets, so wording stays consistent across blocks. */
object LabOptions {
    val CHANGED = listOf(LabOption("yes", "It changed"), LabOption("no", "No change"))
    val A_B_UNSURE = listOf(
        LabOption("a", "A"),
        LabOption("b", "B"),
        LabOption("unsure", "Can't tell")
    )
}
