package com.example.core.perception.lab

import com.example.core.perception.PerceptionTrials
import com.example.core.perception.Stimulus
import com.example.core.perception.StimulusStep
import kotlin.random.Random

/**
 * The battery, in the order it should be run.
 *
 * Ordered by **how much each one unblocks**, not by how interesting it is. A block that produces a
 * unit the later blocks need comes first even if its own answer is dull, because stimuli spaced in
 * the wrong units is the failure that cost the first sitting most of its trials.
 *
 * Blocks 0-3 are built. Blocks 4-9 are described in [PLANNED] and deliberately not implemented yet:
 * their stimuli want spacing in *visible steps*, which is exactly what block 1 produces, and
 * guessing that spacing now would repeat the original mistake one level up.
 */
object LabBlocks {

    data class BlockSpec(
        val id: String,
        val title: String,
        /** One line for the menu: what running this gives. */
        val purpose: String,
        val estimateMinutes: Int,
        /** True when the block drives firmware brightness itself and must restore it. */
        val commandsBrightness: Boolean = false
    )

    val FLOOR_GRID = BlockSpec(
        id = "floor_grid",
        title = "Floor and grid",
        purpose = "Where your strip starts lighting, and how many bytes one output step is",
        estimateMinutes = 3
    )

    val SCALE = BlockSpec(
        id = "scale",
        title = "How big is a visible step",
        purpose = "The size of the smallest visible change, all the way up the range",
        estimateMinutes = 6,
        commandsBrightness = true
    )

    val RATE = BlockSpec(
        id = "rate",
        title = "How fast must frames arrive",
        purpose = "The update rate above which a fade stops looking like separate updates",
        estimateMinutes = 4
    )

    val DITHER = BlockSpec(
        id = "dither",
        title = "Does dithering buy a level",
        purpose = "Whether alternating two bytes reads as an in-between level, or as flicker",
        estimateMinutes = 5
    )

    /** In run order. */
    val ALL = listOf(FLOOR_GRID, SCALE, RATE, DITHER)

    /**
     * The rest of the battery, recorded so the plan survives this session.
     *
     * Not implemented on purpose. Blocks 4-6 and 7-8 are preference measurements, and a preference
     * measured without consistency repeats and transitivity checks is noise — the first sitting's
     * fade block was four trials and its "result" should never have been quoted. They also want
     * their stimuli spaced in visible steps, which is [SCALE]'s output.
     */
    val PLANNED: List<String> = listOf(
        "4. Ambiance smoothing - preferred fade time constant, by paired comparison at matched " +
            "perceptual spacing. The number that was got wrong on 2026-09-04.",
        "5. Jumps - the step size above which an instant cut beats an eased one.",
        "6. Near-black - hold a floor or go dark. Taste on FLOOR_TARGET, never asked.",
        "7. Visualiser: where he sits on the measured comfort/coupling line (r=0.87, 27 tunings).",
        "8. Visualiser: brightness-modulated against hue-modulated at matched energy. Tests a " +
            "recorded belief that is steering every preset and has never been measured.",
        "9. Validation - the app states its prediction before each trial and scores itself on " +
            "held-out cases. THIS is the block that licenses tuning without his eyes."
    )

    // --- shared stimulus helpers ------------------------------------------------------------------

    /** Settle at [from], then the watch window: [from], then [to]. The probe's proven shape. */
    fun holdThenStep(from: Int, to: Int): Stimulus = Stimulus(
        "step_${from}_$to",
        listOf(
            StimulusStep(from, SETTLE_MS),
            StimulusStep(from, PRE_MS),
            StimulusStep(to, POST_MS)
        )
    )

    const val SETTLE_MS = 700L
    const val PRE_MS = 1300L
    const val POST_MS = 1500L

    // --- block 0: floor and grid --------------------------------------------------------------------

    /**
     * A short confirmation that the grid is where the rule says it is, at tonight's brightness.
     *
     * Twelve one-byte steps covers three grid periods at 25%, which is enough to see a spacing and
     * not enough to be a chore. It is not trying to re-establish the rule — 2026-09-05 did that
     * across 74 trials — only to check that this evening's brightness setting puts the boundaries
     * where `100 / brightness` says, before three other blocks are placed relative to them.
     */
    fun floorGridTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)
        val out = mutableListOf<LabTrial>()
        var byte = context.floorClearlyOn
        repeat(12) {
            if (random.nextInt(5) == 0) out.add(changedTrial(FLOOR_GRID.id, LabTrial.KIND_CATCH, byte, byte, context))
            if (random.nextInt(7) == 0) out.add(changedTrial(FLOOR_GRID.id, LabTrial.KIND_ANCHOR, byte, byte + 4 * context.gridSpacing, context))
            out.add(changedTrial(FLOOR_GRID.id, "step", byte, byte + 1, context))
            byte += 1
        }
        return out
    }

    // --- block 1: the perceptual scale ---------------------------------------------------------------

    /**
     * How large a change has to be to be visible, at points all the way up the range.
     *
     * ## Why this one runs at 100% firmware brightness
     *
     * Two reasons, and the second is the important one.
     *
     * At 100% the grid is one byte, so **commanded byte and emitted level are the same number** and
     * the whole 255-rung ladder is addressable. At Joe's own brightness only 64 rungs exist, and a
     * threshold finer than one rung cannot be measured at all — the instrument would floor it, which
     * is exactly what happened on 2026-09-05.
     *
     * And the answer **transfers**: the light emitted by level N is the same however that level was
     * commanded, so a threshold measured in emitted levels applies at any brightness setting. Byte
     * 10 at 100% and byte 40 at 25% are the same rung and the same light.
     *
     * ## What it produces
     *
     * The smallest visible delta at each anchor. One over that is the local density of visible
     * steps, and its running sum is a **perceptual scale** — the thing that lets "smooth" be
     * measured at all, and the currency the later taste blocks need their stimuli spaced in.
     *
     * ## The large deltas are the attention check
     *
     * A 16-byte step at anchor 4 is unmissable under any hypothesis, so it does the job a separate
     * anchor trial would. That is why the ladder is not trimmed per anchor even where the top of it
     * is obvious: those trials are load-bearing as controls.
     *
     * **Caveat to carry into the reading:** at 100% the strip is four times brighter than he
     * normally runs it, so his adaptation state is not his usual one. Thresholds are ratios and
     * should mostly survive that, but "mostly" is doing work. The cross-check is free: the probe
     * already established that every emitted level from 2 to 17 is visible at 25%, so anchors 4, 8
     * and 16 here must come back with a threshold of one. If they do not, the transfer assumption
     * is wrong and this block's scale should not be used.
     */
    fun scaleTrials(seed: Long): List<LabTrial> {
        val random = Random(seed)
        val context = FULL_BRIGHTNESS_CONTEXT
        val out = mutableListOf<LabTrial>()
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (anchor in SCALE_ANCHORS) for (delta in SCALE_DELTAS) pairs.add(anchor to delta)
        // Shuffled so a run of invisible trials at one anchor is not also a run in time - fatigue
        // and adaptation would otherwise land entirely on whichever anchor was scheduled last.
        pairs.shuffle(random)
        for ((anchor, delta) in pairs) {
            if (random.nextInt(6) == 0) {
                out.add(changedTrial(SCALE.id, LabTrial.KIND_CATCH, anchor, anchor, context, mapOf("anchor" to anchor, "delta" to 0)))
            }
            out.add(
                changedTrial(
                    SCALE.id, "jnd", anchor, (anchor + delta).coerceAtMost(255), context,
                    mapOf("anchor" to anchor, "delta" to delta)
                )
            )
        }
        return out
    }

    /** Anchors spread by ratio, because equal ratios are closer to equal perceptual distance. */
    val SCALE_ANCHORS = listOf(4, 8, 16, 32, 64, 128)

    /** Deltas spread the same way. The top of the ladder doubles as the attention check. */
    val SCALE_DELTAS = listOf(1, 2, 4, 8, 16)

    /** At 100% brightness the grid is one byte, so byte and emitted level coincide. */
    val FULL_BRIGHTNESS_CONTEXT = LabContext(
        floorFirstVisible = 1,
        floorClearlyOn = 2,
        brightnessPercent = 100
    )

    // --- block 2: update rate -------------------------------------------------------------------------

    /**
     * The same fade, rendered at different write rates, against a fast reference.
     *
     * ## Why this is worth asking rather than assuming
     *
     * Pacing defaults to 50ms, which is a 15Hz delivery ceiling, and that number is a survivor of a
     * removal that the 2026-08-26 rollback undid rather than a measurement of anything. Measured
     * separately: nothing is lost at any pacing below the ceiling, and the strip itself does not
     * start dropping writes until somewhere between 89Hz and 108Hz. So there is a large free
     * improvement available and no measurement saying how much of it is worth having.
     *
     * The stimuli deliberately span many emitted levels, so what differs between the two intervals
     * is **when the updates arrive and how big each jump is**, not how many distinct levels the
     * fade can reach. Comparing a short fade at two rates would have confounded the two.
     *
     * The reference is 60Hz rather than the strip's limit: it is comfortably inside what the link
     * sustains with pacing bypassed, and overdriving is separately measured to be worse than asking
     * politely - 502Hz offered delivered 67Hz with 441 stalls over a second.
     */
    fun rateTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)
        val out = mutableListOf<LabTrial>()
        val from = context.floorClearlyOn
        val to = (from + FADE_SPAN_BYTES).coerceAtMost(255)
        val jobs = mutableListOf<Int>()
        RATES_HZ.forEach { rate -> repeat(RATE_REPEATS) { jobs.add(rate) } }
        jobs.shuffle(random)
        for (rate in jobs) {
            if (random.nextInt(6) == 0) {
                out.add(
                    preferenceTrial(
                        RATE.id, LabTrial.KIND_CATCH,
                        fade(from, to, REFERENCE_HZ), fade(from, to, REFERENCE_HZ),
                        RATE_QUESTION, RATE_HINT, random, context,
                        mapOf("rateHz" to REFERENCE_HZ), correctIsSecond = null
                    )
                )
            }
            // The faster interval is the objectively better-resolved one, so this trial has a right
            // answer and is a discrimination, not a taste question.
            val slow = fade(from, to, rate)
            val fast = fade(from, to, REFERENCE_HZ)
            val fastIsB = random.nextBoolean()
            out.add(
                LabTrial(
                    block = RATE.id,
                    kind = "rate",
                    intervals = if (fastIsB) listOf(slow, fast) else listOf(fast, slow),
                    question = RATE_QUESTION,
                    hint = RATE_HINT,
                    options = LabOptions.A_B_UNSURE,
                    truth = LabTruth.KNOWN,
                    correctOptionId = if (fastIsB) "b" else "a",
                    meta = mapOf("rateHz" to rate, "referenceHz" to REFERENCE_HZ),
                    brightnessPercent = null
                )
            )
        }
        return out
    }

    val RATES_HZ = listOf(10, 15, 20, 30, 45)
    const val REFERENCE_HZ = 60
    const val RATE_REPEATS = 3
    const val FADE_SPAN_BYTES = 80
    const val FADE_MS = 1500L
    private const val RATE_QUESTION = "Which fade was smoother?"
    private const val RATE_HINT =
        "Both climb the same amount over the same time. One may arrive in visible jumps."

    /**
     * A fade that lands on both endpoints exactly, whatever the rate.
     *
     * [PerceptionTrials.fadePlain] samples at a fixed interval and stops wherever it happens to be,
     * so two rates over the same span end on different bytes - a difference in *final level* that
     * the comparison would pick up as if it were a difference in smoothness. Here the endpoints are
     * pinned and only the number of updates between them varies, which is the one thing under test.
     */
    fun fadeAt(from: Int, to: Int, ms: Long, rateHz: Int): Stimulus {
        val updates = maxOf(2, Math.round(ms * rateHz / 1000.0).toInt())
        val hold = ms / updates
        val steps = (0 until updates).map { i ->
            val frac = i.toDouble() / (updates - 1)
            val level = from + (to - from) * frac
            StimulusStep(
                Math.round(level).toInt().coerceIn(0, 255),
                if (i == updates - 1) ms - hold * (updates - 1) else hold
            )
        }
        return Stimulus("fade_${from}_${to}_${rateHz}hz", steps)
    }

    private fun fade(from: Int, to: Int, rateHz: Int): Stimulus = fadeAt(from, to, FADE_MS, rateHz)

    // --- block 3: dithering ---------------------------------------------------------------------------

    /**
     * Whether alternating two commanded bytes across a grid boundary produces an in-between level.
     *
     * ## The first attempt tested a pair that could not work
     *
     * The 2026-09-05 sitting dithered bytes 5 and 6. At 25% both are emitted level 2, so the
     * "dithered" interval emitted one steady level and was correctly reported as indistinguishable
     * from a steady level. Fifteen "can't tell" answers out of sixteen, and none of them about
     * dithering. **Dithering has still never been tested on this hardware.**
     *
     * Only pairs straddling a `k × gridSpacing` boundary change the output at all, and those are
     * derivable now that the grid rule is known.
     *
     * ## Two separate questions, and they need different trials
     *
     *  - **Does it land in between?** Compare the dithered pair against each of the two steady
     *    levels it sits between. If dithering works it reads brighter than the lower and dimmer
     *    than the upper, and both of those have a right answer. If it does not work it is
     *    indistinguishable from one of them - which is also a clean result.
     *  - **Does it flicker?** Compare it against a steady level **at matched write cadence**, which
     *    is what `duty = 0.0` is for. Against a single held write the dithered interval could be
     *    picked out by its hundred writes rather than by any flicker, giving a confident answer to
     *    the wrong question.
     *
     * Two boundaries, low and mid, because whether it works may well depend on how large one level
     * is as a fraction of the light - which near the floor is enormous.
     */
    fun ditherTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)
        val out = mutableListOf<LabTrial>()
        val boundaries = ditherBoundaries(context)
        for (lower in boundaries) {
            val upper = lower + 1
            repeat(DITHER_REPEATS) {
                for (against in listOf(lower, upper)) {
                    val dithered = PerceptionTrials.dither(lower, 0.5, DITHER_HOLD_MS)
                    val steady = PerceptionTrials.dither(against, 0.0, DITHER_HOLD_MS)
                    // Ground truth: the dithered pair averages above `lower` and below `upper`.
                    val ditherIsBrighter = against == lower
                    val ditherIsB = random.nextBoolean()
                    val brighterIsB = if (ditherIsBrighter) ditherIsB else !ditherIsB
                    out.add(
                        LabTrial(
                            block = DITHER.id,
                            kind = "between",
                            intervals = if (ditherIsB) listOf(steady, dithered) else listOf(dithered, steady),
                            question = "Which was brighter?",
                            hint = "Two steady-looking levels, very close together. If they look " +
                                "identical, say so - that is a real answer here.",
                            options = LabOptions.A_B_UNSURE,
                            truth = LabTruth.KNOWN,
                            correctOptionId = if (brighterIsB) "b" else "a",
                            meta = mapOf("lowerByte" to lower, "againstByte" to against),
                            brightnessPercent = null
                        )
                    )
                }
            }
            for (holdMs in DITHER_HOLD_RATES) {
                if (random.nextInt(6) == 0) {
                    val a = PerceptionTrials.dither(lower, 0.0, holdMs)
                    val b = PerceptionTrials.dither(lower, 0.0, holdMs)
                    out.add(
                        LabTrial(
                            block = DITHER.id, kind = LabTrial.KIND_CATCH,
                            intervals = listOf(a, b),
                            question = "Which one flickered?",
                            hint = FLICKER_HINT,
                            options = LabOptions.A_B_UNSURE,
                            truth = LabTruth.KNOWN,
                            correctOptionId = "unsure",
                            meta = mapOf("lowerByte" to lower, "holdMs" to holdMs.toInt()),
                            brightnessPercent = null
                        )
                    )
                }
                val dithered = PerceptionTrials.dither(lower, 0.5, holdMs)
                val steady = PerceptionTrials.dither(lower, 0.0, holdMs)
                val ditherIsB = random.nextBoolean()
                out.add(
                    LabTrial(
                        block = DITHER.id, kind = "flicker",
                        intervals = if (ditherIsB) listOf(steady, dithered) else listOf(dithered, steady),
                        question = "Which one flickered?",
                        hint = FLICKER_HINT,
                        options = LabOptions.A_B_UNSURE,
                        // The dithered interval really is the one alternating, so "which flickered"
                        // has a right answer. Failing to find it is the good outcome here.
                        truth = LabTruth.KNOWN,
                        correctOptionId = if (ditherIsB) "b" else "a",
                        meta = mapOf("lowerByte" to lower, "holdMs" to holdMs.toInt()),
                        brightnessPercent = null
                    )
                )
            }
        }
        return out
    }

    /**
     * The two boundaries to test: one just above the floor, one several levels up.
     *
     * Both are `k × gridSpacing`, so `lower` and `lower + 1` are the last byte of one emitted level
     * and the first byte of the next. Anything else in the range is a pair that cannot differ.
     */
    fun ditherBoundaries(context: LabContext): List<Int> {
        val g = context.gridSpacing
        val floorLevel = context.levelForByte(context.floorClearlyOn).coerceAtLeast(1)
        return listOfNotNull(
            boundaryAtOrAbove(context, (floorLevel + 1) * g),
            boundaryAtOrAbove(context, (floorLevel + 6) * g)
        ).distinct()
    }

    /**
     * The first byte at or above [target] whose successor emits a different level.
     *
     * Searched against the rule rather than computed from [LabContext.gridSpacing], because that
     * spacing is a rounded average and the boundaries are only evenly spaced when the brightness
     * divides 100. At 22% they alternate four and five bytes apart, and a pair placed by the
     * average lands inside a level about half the time - which is precisely the bug that made the
     * first dither block measure nothing.
     */
    fun boundaryAtOrAbove(context: LabContext, target: Int): Int? {
        for (b in target.coerceAtLeast(1)..250) {
            if (context.levelForByte(b) != context.levelForByte(b + 1)) return b
        }
        return null
    }

    const val DITHER_REPEATS = 2
    val DITHER_HOLD_RATES = listOf(20L, 40L, 60L, 100L)
    const val DITHER_HOLD_MS = 40L
    private const val FLICKER_HINT =
        "One holds a single level. The other alternates between two of them. If neither " +
            "flickers, \"can't tell\" is the right answer."

    // --- construction helpers ------------------------------------------------------------------------

    private fun changedTrial(
        block: String,
        kind: String,
        from: Int,
        to: Int,
        context: LabContext,
        extraMeta: Map<String, Int> = emptyMap()
    ): LabTrial = LabTrial(
        block = block,
        kind = kind,
        intervals = listOf(holdThenStep(from, to.coerceAtMost(255))),
        question = "Did anything change?",
        hint = "Only the change matters, not how big it was. Most of these will be nothing at " +
            "all - \"no change\" is expected and is not a failure to notice.",
        options = LabOptions.CHANGED,
        // Catches and anchors have right answers; a real one-byte step does not - whether it is
        // visible is the thing being measured, so it is UNKNOWN and must never be scored as
        // accuracy. Getting that wrong is how a preference became a score in the first sitting.
        truth = if (from == to || kind == LabTrial.KIND_ANCHOR) LabTruth.KNOWN else LabTruth.UNKNOWN,
        correctOptionId = when {
            from == to -> "no"
            kind == LabTrial.KIND_ANCHOR -> "yes"
            else -> null
        },
        meta = mapOf("fromByte" to from, "toByte" to to) + extraMeta,
        brightnessPercent = if (context.brightnessPercent == 100) 100 else null
    )

    private fun preferenceTrial(
        block: String,
        kind: String,
        a: Stimulus,
        b: Stimulus,
        question: String,
        hint: String,
        random: Random,
        context: LabContext,
        meta: Map<String, Int>,
        correctIsSecond: Boolean?
    ): LabTrial = LabTrial(
        block = block,
        kind = kind,
        intervals = if (random.nextBoolean()) listOf(a, b) else listOf(b, a),
        question = question,
        hint = hint,
        options = LabOptions.A_B_UNSURE,
        truth = LabTruth.KNOWN,
        correctOptionId = when (correctIsSecond) {
            null -> "unsure"
            true -> "b"
            false -> "a"
        },
        meta = meta,
        brightnessPercent = if (context.brightnessPercent == 100) 100 else null
    )

    /** Builds a block's trials. [context] is ignored by [SCALE], which sets its own brightness. */
    fun trialsFor(spec: BlockSpec, context: LabContext, seed: Long): List<LabTrial> = when (spec.id) {
        FLOOR_GRID.id -> floorGridTrials(context, seed)
        SCALE.id -> scaleTrials(seed)
        RATE.id -> rateTrials(context, seed)
        DITHER.id -> ditherTrials(context, seed)
        else -> emptyList()
    }
}
