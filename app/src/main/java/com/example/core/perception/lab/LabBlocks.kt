package com.example.core.perception.lab

import com.example.core.perception.PerceptionTrials
import com.example.core.perception.Stimulus
import com.example.core.color.ColorConverter
import com.example.core.color.StripResponse
import com.example.core.perception.StimulusStep
import kotlin.random.Random

/**
 * The battery, in the order it should be run.
 *
 * Ordered by **how much each one unblocks**, not by how interesting it is. A block that produces a
 * unit the later blocks need comes first even if its own answer is dull, because stimuli spaced in
 * the wrong units is the failure that cost the first sitting most of its trials.
 *
 * Blocks 0-1 and 3-6 are built; block 2 is retired in place. Blocks 7-9 are described in [PLANNED].
 * Blocks 4-6 could only be written once block 1 had run, because their stimuli are spaced in
 * *visible steps* — guessing that spacing would have repeated the hardcoded-base-level mistake one
 * level up.
 */
object LabBlocks {

    data class BlockSpec(
        val id: String,
        val title: String,
        /** One line for the menu: what running this gives. */
        val purpose: String,
        val estimateMinutes: Int,
        /** True when the block drives firmware brightness itself and must restore it. */
        val commandsBrightness: Boolean = false,
        /**
         * Why this block can no longer answer its question, if it cannot.
         *
         * A retired block keeps its place in [ALL] so the numbering does not shift, and the menu
         * refuses to run it. Leaving it runnable would cost a sitting to re-collect numbers that are
         * already known to mean nothing — which is worse than deleting it, because the answers would
         * look like data.
         */
        val retiredBecause: String? = null,
        /**
         * What to read before the first trial: what the question means, and how to tell.
         *
         * The runner shows these as a screen he taps through, once per sitting. A one-line hint
         * under the question is the wrong place to explain a question - it is read while he is
         * trying to answer, and block 8's 2026-09-11 run shows what happens when the question is
         * not understood before the strip starts moving. Empty for blocks whose question needs no
         * explaining.
         */
        val briefing: List<String> = emptyList()
    ) {
        val isRetired: Boolean get() = retiredBecause != null
    }

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
        estimateMinutes = 4,
        retiredBecause = "As designed this cannot work. A fade's step count is set by the number " +
            "of output levels it crosses, which is identical at every update rate, so both " +
            "intervals contain the same visible steps and the question has no answer. Joe ran it " +
            "on 2026-09-06 and said it was hard \"because none of them were smooth\" - correctly. " +
            "Rate's real payoff is latency, which needs a reference event to compare against and " +
            "so needs a different block."
    )

    val DITHER = BlockSpec(
        id = "dither",
        title = "Does dithering buy a level",
        purpose = "Whether alternating two bytes reads as an in-between level, or as flicker",
        estimateMinutes = 5
    )

    val SMOOTHING = BlockSpec(
        id = "smoothing",
        title = "How fast should a change settle",
        purpose = "Your preferred settling speed when the picture changes - the 2026-09-04 number",
        estimateMinutes = 6
    )

    val JUMPS = BlockSpec(
        id = "jumps",
        title = "Cut or ease",
        purpose = "How big a change has to be before an instant cut beats an eased one",
        estimateMinutes = 5
    )

    val NEAR_BLACK = BlockSpec(
        id = "near_black",
        title = "Dark scenes",
        purpose = "Where the floor should sit, and whether dim scenes should be lifted to be smooth",
        estimateMinutes = 6,
        commandsBrightness = true
    )

    val AMBIANCE_FALL = BlockSpec(
        id = "ambiance_fall",
        title = "Ambiance: dark scenes",
        purpose = "Whether removing the smoother's downward bias makes a dark scene steadier",
        estimateMinutes = 5,
        briefing = listOf(
            "This plays a few seconds of a film on the phone, twice - A, then B - with the strip " +
                "lit from it exactly as ambiance would light it.",
            "It is the same few seconds of film both times, and the same moment of it. What " +
                "differs is only the rule deciding how fast the lights follow the picture down " +
                "when a scene gets darker.",
            "Watch the film. Let the strip sit at the edge of your vision, the way it does when " +
                "you are actually watching something - that is the whole point of the picture " +
                "being there.",
            "Then say which one held steadier: which was less twitchy, less inclined to jitter " +
                "or flicker as the scene moved. Not which was brighter, and not which you liked " +
                "the colour of.",
            "If they looked the same, say Can't tell. On some of these there is genuinely nothing " +
                "in it.",
            "Clips are from Tears of Steel, (CC) Blender Foundation, mango.blender.org."
        )
    )

    val HUE_MOTION = BlockSpec(
        id = "hue_motion",
        title = "Hue motion: does it pulse",
        purpose = "Whether the brightness swing built into a hue sweep is visible to you",
        estimateMinutes = 4,
        briefing = listOf(
            "You will see the strip cycle through every colour twice - first A, then B. Both take " +
                "the same four seconds and go through the same colours at the same speed.",
            "The question is only about BRIGHTNESS. The colour changes in both of them, all the " +
                "way round, and that is not what is being asked about.",
            "One of them may get brighter and dimmer as it goes round - a throb, several times " +
                "per lap, riding on top of the colour change. The other may hold a steady level " +
                "throughout.",
            "So: did either one pulse? If neither seemed to change brightness, say Neither - that " +
                "is a real answer and quite possibly the right one. Do not go looking for a " +
                "difference that is not there.",
            "Watch the strip, not the phone. Some laps have no difference in them at all."
        )
    )

    /**
     * In run order, and the index here is the block number everything else refers to.
     *
     * [RATE] stays in the list at position 2 although it is retired, because the numbering is how
     * the results files, the docs and Joe all name these. Renumbering to close a gap would silently
     * rename block 3's data.
     */
    val ALL = listOf(
        FLOOR_GRID, SCALE, RATE, DITHER, SMOOTHING, JUMPS, NEAR_BLACK, AMBIANCE_FALL, HUE_MOTION
    )

    /**
     * The rest of the battery, recorded so the plan survives this session.
     *
     * Blocks 8-9 want a running visualiser to modulate, which is a larger piece of wiring than the
     * steady-level stimuli everything so far has used. Block 10 wants blocks 4-7 answered first: it
     * scores the model those produce, so there is nothing for it to predict until they have run.
     *
     * The visualiser and validation blocks shifted up by one when [AMBIANCE_FALL] was added at 7.
     * That is free only because none of them has ever run: renumbering a block that has results
     * would silently rename its data, which is why [RATE] keeps its slot despite being retired.
     */
    val PLANNED: List<String> = listOf(
        "9. Visualiser: where he sits on the measured comfort/coupling line (r=0.87, 27 tunings). " +
            "Needs a running visualiser to modulate, unlike block 8.",
        "10. Validation - the app states its prediction before each trial and scores itself on " +
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

    // --- blocks 4-6: the taste blocks -----------------------------------------------------------------

    /*
     * What changes for blocks 4-6, and why they needed blocks 0-3 first.
     *
     * Blocks 0-3 asked what he *can see*. These three ask what he *wants*, which is a different kind
     * of question and needs a different kind of trial. Three rules follow, and the first sitting's
     * four-trial fade block broke all three:
     *
     *  - `truth = PREFERENCE`, never scored. There is no right answer. That sitting scored "picked
     *    the dithered fade" as correct, which reported a taste as an accuracy.
     *  - Consistency repeats. A pair is asked twice, with the arms re-randomised between intervals.
     *    If he does not agree with himself the block has measured nothing, and that has to be
     *    visible in the reading rather than averaged away.
     *  - Transitivity. Preferring A to B and B to C but C to A means the arms are not on one axis,
     *    usually because the comparison is confounded by something the design did not mean to vary.
     *    `LabAnalysis.preferenceReading` counts those violations.
     *
     * And the stimuli are spaced in visible steps ([VisibleScale]), not bytes. A byte near the floor
     * is an enormous change and a byte at 128 is invisible, so "the same jump size" in bytes is not
     * the same question at two anchors.
     */

    /**
     * The tick every taste block renders at, pinned so it is never the variable under test.
     *
     * 30Hz. Block 2 established that extra frames buy no smoothness — a fade's step count is set by
     * the output levels it crosses — so this only has to be fast enough not to be the bottleneck. It
     * is comfortably inside what the link sustains with pacing bypassed (nothing lost below 89Hz)
     * and well short of the rate at which over-driving starts costing throughput.
     */
    const val TICK_MS = 33L

    /** Long enough that the slowest arm on offer has arrived before the window closes. */
    const val EASE_WINDOW_MS = 2400L

    /** What ships: `AmbianceOutputInterpolator.HALF_LIFE_MS`, calibrated to the pre-2026-09-04 fade. */
    const val SHIPPED_HALF_LIFE_MS = 50L

    /**
     * A move from [from] to [to] covering half the remaining distance every [halfLifeMs].
     *
     * This is the shape `AmbianceOutputInterpolator` actually produces — half the distance per 50ms
     * tick, which is a 50ms half-life — so the arm at 50 is the shipped behaviour and the others are
     * candidates against it. A half-life of zero is an instant cut, which is what block 5 needs.
     *
     * The window is the same length for every half-life on purpose. Ending each arm as soon as it
     * settled would make duration a second difference between the intervals; a slow arm still
     * travelling when the window closes is exactly the complaint a slow arm earns.
     */
    fun easedMove(
        from: Int,
        to: Int,
        halfLifeMs: Long,
        windowMs: Long = EASE_WINDOW_MS,
        tickMs: Long = TICK_MS
    ): Stimulus {
        val target = to.coerceIn(0, 255)
        if (halfLifeMs <= 0L) {
            return Stimulus("cut_${from}_$to", listOf(StimulusStep(target, windowMs)))
        }
        val steps = mutableListOf<StimulusStep>()
        var t = 0L
        while (t < windowMs) {
            val remaining = Math.pow(0.5, t.toDouble() / halfLifeMs)
            val level = target + (from - target) * remaining
            steps.add(StimulusStep(Math.round(level).toInt().coerceIn(0, 255), minOf(tickMs, windowMs - t)))
            t += tickMs
        }
        // Pinned, so two half-lives never differ in where they finished as well as in how they got
        // there — the confound `fadeAt` was written to remove in block 2.
        steps[steps.size - 1] = StimulusStep(target, steps.last().holdMs)
        return Stimulus("ease_${from}_${to}_${halfLifeMs}ms", steps)
    }

    /** Settle, hold, then the move under test: the probe's shape with an eased step in place of a jump. */
    fun settleThenMove(from: Int, to: Int, halfLifeMs: Long): Stimulus {
        val move = easedMove(from, to, halfLifeMs)
        return Stimulus(
            "settled_" + move.label,
            listOf(StimulusStep(from, SETTLE_MS), StimulusStep(from, PRE_MS)) + move.steps
        )
    }

    /**
     * A scene, as a walk through several levels rather than one step.
     *
     * A single step cannot ask whether a dark scene is pleasant to *watch*, only whether one
     * transition is. Ambiance's real output wanders, and wandering near the floor is where the
     * steppiness complaint lives.
     */
    fun contentWalk(
        levels: List<Int>,
        context: LabContext,
        halfLifeMs: Long = SHIPPED_HALF_LIFE_MS
    ): Stimulus {
        val bytes = levels.map { if (it <= 0) 0 else context.byteForLevel(it) }
        val steps = mutableListOf(StimulusStep(bytes.first(), SETTLE_MS))
        for ((from, to) in bytes.zipWithNext()) {
            steps.addAll(easedMove(from, to, halfLifeMs, WALK_LEG_MS).steps)
        }
        return Stimulus("walk_${levels.first()}_${levels.last()}_${levels.size}", steps)
    }

    const val WALK_LEG_MS = 900L

    /** The highest emitted level a brightness setting can reach: `ceil(255 x B / 100)`. */
    fun maxLevel(context: LabContext): Int = context.levelForByte(255)

    // --- block 4: settling speed ----------------------------------------------------------------------

    /**
     * Which settling speed he prefers when the picture changes, by paired comparison.
     *
     * ## This is the number that was got wrong on 2026-09-04
     *
     * The ease was retimed to a 150ms constant when the shipped behaviour was a 72ms one, making
     * every fade about 1.8x slower, and it went out on a simulation's say-so. It was reverted and
     * the half-life is now pinned by a test at the shipped value — but **nobody has ever asked
     * whether the shipped value is the one he wants**. The revert restored a number; it did not
     * justify one.
     *
     * ## Two anchors, matched in visible steps
     *
     * A dark change and a brighter one, both [JUMP_VISIBLE_STEPS] visible steps so they are the same
     * size *to look at*. They are asked separately because the answer may well differ: block 1 says
     * the dark one crosses about one output level per visible step with nothing in between, while
     * the brighter one has several levels inside each step. One answer at both anchors is one
     * number for the app; two answers means the app needs two, and this is the only way that would
     * ever be found out.
     */
    fun smoothingTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)
        val scale = VisibleScale.MEASURED_2026_09_06
        val main = mutableListOf<LabTrial>()
        val tail = mutableListOf<LabTrial>()
        for (anchor in smoothingAnchors(context, scale)) {
            val toLevel = scale.levelAfterVisibleSteps(anchor, JUMP_VISIBLE_STEPS, maxLevel(context))
            val from = context.byteForLevel(anchor)
            val to = context.byteForLevel(toLevel)
            val meta = mapOf(
                "anchorLevel" to anchor,
                "toLevel" to toLevel,
                "visibleSteps" to Math.round(scale.stepsBetween(anchor, toLevel)).toInt()
            )
            val pairs = allPairs(HALF_LIVES_MS)
            for ((a, b) in pairs) {
                main.add(
                    preferencePair(
                        SMOOTHING.id, "smoothing",
                        settleThenMove(from, to, a), a,
                        settleThenMove(from, to, b), b,
                        SMOOTHING_QUESTION, SMOOTHING_HINT, random, context, meta
                    )
                )
            }
            // The consistency repeats sit at the end of the block on purpose. Asked back to back
            // with the original they would measure memory of the last answer rather than agreement.
            // They mirror the original's interval order rather than re-randomising it - see
            // [mirrored] for why that matters more than it sounds.
            val originals = main.filter { it.block == SMOOTHING.id && it.kind == "smoothing" }
            for (original in originals.shuffled(random).take(CONSISTENCY_REPEATS)) {
                tail.add(mirrored(original, mapOf("repeat" to 1)))
            }
            main.add(
                catchPair(
                    SMOOTHING.id, settleThenMove(from, to, SHIPPED_HALF_LIFE_MS), SHIPPED_HALF_LIFE_MS,
                    SMOOTHING_QUESTION, SMOOTHING_HINT, context, meta
                )
            )
        }
        return main.shuffled(random) + tail.shuffled(random)
    }

    /** A dark anchor just above the floor, and the brightest one a full-sized jump still fits under. */
    fun smoothingAnchors(context: LabContext, scale: VisibleScale): List<Int> {
        val dark = context.levelForByte(context.floorClearlyOn).coerceAtLeast(1)
        val ceiling = maxLevel(context)
        var bright = ceiling
        while (bright > dark && scale.stepsBetween(bright, ceiling) < JUMP_VISIBLE_STEPS) bright -= 1
        // Two anchors only a few levels apart are the same question asked twice, and cost a third of
        // the sitting to find that out.
        return if (bright - dark < MIN_ANCHOR_SEPARATION) listOf(dark) else listOf(dark, bright)
    }

    /** Half-lives in ms. 50 is what ships; 300 is roughly what 2026-09-04 shipped by mistake. */
    val HALF_LIVES_MS = listOf(25L, 50L, 120L, 300L)

    const val JUMP_VISIBLE_STEPS = 8.0
    const val CONSISTENCY_REPEATS = 3
    const val MIN_ANCHOR_SEPARATION = 4

    private const val SMOOTHING_QUESTION = "Which change felt better?"
    private const val SMOOTHING_HINT =
        "The same change, arriving at two different speeds. There is no right answer here - pick " +
            "the one you would rather have on the wall, and say \"can't tell\" if they match."

    // --- block 5: cut or ease --------------------------------------------------------------------------

    /**
     * How big a change has to be before an instant cut beats an eased one.
     *
     * ## Why this is not obvious
     *
     * Easing exists to hide a jump. But block 1 says that below level 48 **every output level a fade
     * passes through is individually visible**, so an ease down there is not a smooth transition —
     * it is the same staircase taken more slowly. It is entirely possible that a cut is simply
     * better in the dark, and the app currently eases everything.
     *
     * Sizes climb by ratio in visible steps, and the top rung doubles as the attention check: a
     * 32-step change is unmissable, so "can't tell" there is about attention rather than taste.
     */
    fun jumpTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)
        val scale = VisibleScale.MEASURED_2026_09_06
        val out = mutableListOf<LabTrial>()
        val fromLevel = context.levelForByte(context.floorClearlyOn).coerceAtLeast(1)
        val from = context.byteForLevel(fromLevel)
        val ceiling = maxLevel(context)
        val sizes = JUMP_LADDER_STEPS
            .map { it to scale.levelAfterVisibleSteps(fromLevel, it, ceiling) }
            // Two rungs that both ran out of range land on the same level and would be one trial
            // asked twice under two different labels.
            .distinctBy { it.second }
            .filter { it.second > fromLevel }
        for ((steps, toLevel) in sizes) {
            val to = context.byteForLevel(toLevel)
            val meta = mapOf(
                "anchorLevel" to fromLevel,
                "toLevel" to toLevel,
                "visibleSteps" to Math.round(scale.stepsBetween(fromLevel, toLevel)).toInt(),
                "requestedSteps" to Math.round(steps).toInt()
            )
            // One asked each way round, plus a free one, so every rung carries its own order
            // control rather than relying on the shuffle to provide one.
            val first = preferencePair(
                JUMPS.id, "cut_or_ease",
                settleThenMove(from, to, 0L), 0L,
                settleThenMove(from, to, SHIPPED_HALF_LIFE_MS), SHIPPED_HALF_LIFE_MS,
                JUMP_QUESTION, JUMP_HINT, random, context, meta
            )
            out.add(first)
            out.add(mirrored(first))
            repeat(JUMP_REPEATS - 2) {
                out.add(
                    preferencePair(
                        JUMPS.id, "cut_or_ease",
                        settleThenMove(from, to, 0L), 0L,
                        settleThenMove(from, to, SHIPPED_HALF_LIFE_MS), SHIPPED_HALF_LIFE_MS,
                        JUMP_QUESTION, JUMP_HINT, random, context, meta
                    )
                )
            }
        }
        sizes.take(CATCHES_PER_BLOCK).forEach { (_, toLevel) ->
            val to = context.byteForLevel(toLevel)
            out.add(
                catchPair(
                    JUMPS.id, settleThenMove(from, to, SHIPPED_HALF_LIFE_MS), SHIPPED_HALF_LIFE_MS,
                    JUMP_QUESTION, JUMP_HINT, context, mapOf("anchorLevel" to fromLevel, "toLevel" to toLevel)
                )
            )
        }
        return out.shuffled(random)
    }

    val JUMP_LADDER_STEPS = listOf(2.0, 4.0, 8.0, 16.0, 32.0)
    const val JUMP_REPEATS = 3
    const val CATCHES_PER_BLOCK = 3
    private const val JUMP_QUESTION = "Which suited the change better?"
    private const val JUMP_HINT =
        "One arrives all at once, the other slides into place. Same start, same finish. Taste only."

    // --- block 6: dark scenes ---------------------------------------------------------------------------

    /**
     * Two questions about the bottom of the range. Both are taste, and neither has ever been asked.
     *
     * ## The floor
     *
     * `AmbianceProcessor` lifts dim content to a floor of byte 14 and nobody has been asked whether
     * that is right. Below emitted level ~5 the strip has a handful of enormous steps and then
     * darkness, so a floor is a real choice: hold a dim glow that cannot follow the content, or let
     * it go out.
     *
     * ## The lift, which is the live question
     *
     * Blocks 0-3 concluded that a smooth dark fade is not achievable on this hardware — the steps
     * are visible, there is nothing between them, and nothing can be put between them. **The one
     * remaining route to a smooth dark scene is to stop making it dark**: map dim content up into
     * the region above level ~64, where several output levels fit inside one visible step.
     *
     * That is a trade, not a fix, and it has to be put as one. He rejected a superficially similar
     * proposal on 2026-09-04 — a brightness correction for the strip curve — from the wall, on the
     * grounds that dark scenes do not read as too bright. **This is not that proposal.** That one
     * was arithmetic telling him what his LEDs looked like; this one shows him both and asks which
     * he wants. The cost is exactly the thing it buys, which is why only he can answer it.
     *
     * ## Why the whole block runs at 100% firmware brightness
     *
     * The smooth region starts around level 64, and at his 25% that is the *top* of the range — the
     * lifted arm could not be played at all. At 100% the dim arm is unaffected: emitted level 8 is
     * the same light however it was commanded, so the faithful arm is exactly what he normally sees,
     * and only the lifted arm becomes reachable.
     */
    fun nearBlackTrials(seed: Long): List<LabTrial> {
        val random = Random(seed)
        val context = FULL_BRIGHTNESS_CONTEXT
        val main = mutableListOf<LabTrial>()
        val tail = mutableListOf<LabTrial>()

        val floorPairs = allPairs(FLOOR_LEVELS.map { it.toLong() })
        for ((a, b) in floorPairs) {
            main.add(
                preferencePair(
                    NEAR_BLACK.id, "floor",
                    fadeToFloor(a.toInt(), context), a,
                    fadeToFloor(b.toInt(), context), b,
                    FLOOR_QUESTION, FLOOR_HINT, random, context, emptyMap()
                )
            )
        }
        for (original in main.filter { it.kind == "floor" }.shuffled(random).take(CONSISTENCY_REPEATS)) {
            tail.add(mirrored(original, mapOf("repeat" to 1)))
        }

        val liftPairs = allPairs(LIFT_TOPS.map { it.toLong() })
        for ((a, b) in liftPairs) {
            val first = preferencePair(
                NEAR_BLACK.id, "lift",
                dimWalkTopping(a.toInt(), context), a,
                dimWalkTopping(b.toInt(), context), b,
                LIFT_QUESTION, LIFT_HINT, random, context, emptyMap()
            )
            main.add(first)
            // Every lift pair is asked in both orders. On 2026-09-07 its one decisive disagreement
            // came from a pair that happened to be asked twice the same way round, so there was no
            // way to tell "no preference" from "picked the second one twice".
            main.add(mirrored(first))
        }

        main.add(
            catchPair(
                NEAR_BLACK.id, fadeToFloor(FLOOR_LEVELS[1], context), FLOOR_LEVELS[1].toLong(),
                FLOOR_QUESTION, FLOOR_HINT, context, emptyMap()
            )
        )
        LIFT_TOPS.take(2).forEach { top ->
            main.add(
                catchPair(
                    NEAR_BLACK.id, dimWalkTopping(top, context), top.toLong(),
                    LIFT_QUESTION, LIFT_HINT, context, emptyMap()
                )
            )
        }
        return main.shuffled(random) + tail.shuffled(random)
    }

    /** A scene going out: down from a modest level, stopping at [floorLevel]. Zero goes all the way. */
    fun fadeToFloor(floorLevel: Int, context: LabContext): Stimulus =
        contentWalk(listOf(FLOOR_FADE_FROM_LEVEL, floorLevel.coerceAtLeast(0)), context)

    /**
     * The same dim scene, scaled so its brightest moment lands on [topLevel].
     *
     * The *shape* is held and only the placement moves, so the two arms differ in where they sit and
     * not in what they do. Scaling happens in emitted levels, which is where light lives — scaling
     * in bytes would change the shape as well as the height.
     */
    fun dimWalkTopping(topLevel: Int, context: LabContext): Stimulus {
        val span = DIM_WALK_SHAPE.max().toDouble()
        val levels = DIM_WALK_SHAPE.map {
            Math.round(it / span * topLevel).toInt().coerceAtLeast(1)
        }
        return Stimulus("lift_$topLevel", contentWalk(levels, context).steps)
    }

    /** Floors to compare, in emitted levels. 0 lets it go out; 10 is roughly the shipped byte 14. */
    val FLOOR_LEVELS = listOf(0, 2, 5, 10)
    const val FLOOR_FADE_FROM_LEVEL = 24

    /**
     * Where the dim scene's brightest moment sits: as-is, halfway up, and inside the smooth region.
     *
     * 20 is roughly what dim content actually produces, 44 is a compromise, and 96 is above the
     * level-64 mark where several output levels start fitting inside one visible step.
     */
    val LIFT_TOPS = listOf(20, 44, 96)
    const val LIFT_REPEATS = 2

    /** A dim scene's shape, in arbitrary units, scaled to whichever top level an arm is testing. */
    val DIM_WALK_SHAPE = listOf(4, 11, 7, 20, 9, 14, 5)

    private const val FLOOR_QUESTION = "Which ending looked right?"
    private const val FLOOR_HINT =
        "A scene fading out. One may stop at a dim glow, the other may go dark. Taste only - " +
            "there is no correct ending."
    private const val LIFT_QUESTION = "Which would you rather have on the wall?"
    private const val LIFT_HINT =
        "The same dim scene, played at two brightnesses. The brighter one moves in " +
            "smaller-looking steps; the dimmer one is truer to the picture. A trade, not a test."

    // --- construction helpers ------------------------------------------------------------------------

    /** Every unordered pair, in a stable order so a seed reproduces a block exactly. */
    fun <T> allPairs(items: List<T>): List<Pair<T, T>> {
        val out = mutableListOf<Pair<T, T>>()
        for (i in items.indices) for (j in i + 1 until items.size) out.add(items[i] to items[j])
        return out
    }

    /**
     * One taste question: two arms, in a randomised order, recorded by which arm was in which
     * interval rather than by which won.
     *
     * `armFirst` and `armSecond` are what make the answer readable at all. The options are "a" and
     * "b", which say nothing about what was being compared once the order has been shuffled, so the
     * arm identities have to travel with the trial.
     */
    /**
     * The same comparison again, with the two intervals swapped.
     *
     * A consistency repeat is only worth its minute if it controls for *order*, and the first
     * version re-randomised instead — so about half of them repeated the original order and told us
     * nothing new. Mirroring makes every repeat a three-way diagnostic:
     *
     *  - names the **same arm** both times: a credible preference.
     *  - names the **same interval letter** both times: an order effect, not a preference.
     *  - names neither: genuine inconsistency.
     *
     * That split is not hypothetical. Across 2026-09-06/07, six of six decisive answers on catch
     * trials — two *identical* intervals — named the second one, while comparisons with a real
     * difference tracked the arm across a flip six times out of seven. He breaks ties by saying
     * "the second one" rather than "can't tell", and only the mirrored repeat can see that.
     */
    private fun mirrored(trial: LabTrial, extraMeta: Map<String, Int> = emptyMap()): LabTrial =
        trial.copy(
            intervals = trial.intervals.reversed(),
            meta = trial.meta + extraMeta + mapOf(
                "armFirst" to trial.meta.getValue("armSecond"),
                "armSecond" to trial.meta.getValue("armFirst")
            )
        )

    private fun preferencePair(
        block: String,
        kind: String,
        a: Stimulus,
        armA: Long,
        b: Stimulus,
        armB: Long,
        question: String,
        hint: String,
        random: Random,
        context: LabContext,
        meta: Map<String, Int>,
        /**
         * Pin which arm goes first, instead of leaving it to the seed.
         *
         * Consistency repeats pass `false` so a repeat always arrives in the **opposite** order to
         * the original. That turns the repeat into a three-way diagnostic rather than a coin:
         * naming the same arm both times is a credible preference; naming the same *interval*
         * letter is an order effect; naming neither is genuine inconsistency. Randomising the
         * repeat too, as the first version did, mixed all three into one number.
         */
        aFirst: Boolean? = null
    ): LabTrial {
        val aIsFirst = aFirst ?: random.nextBoolean()
        return LabTrial(
            block = block,
            kind = kind,
            intervals = if (aIsFirst) listOf(a, b) else listOf(b, a),
            question = question,
            hint = hint,
            options = LabOptions.A_B_UNSURE,
            truth = LabTruth.PREFERENCE,
            correctOptionId = null,
            meta = meta + mapOf(
                "armFirst" to (if (aIsFirst) armA else armB).toInt(),
                "armSecond" to (if (aIsFirst) armB else armA).toInt()
            ),
            // The runner drives firmware brightness from this field per trial, so a block that
            // builds its stimuli in a 100% context and leaves this null gets them played at Joe's
            // own setting with every emitted level divided by four. That is what happened to block
            // 6 on 2026-09-06: its lifted arm was meant to sit above level 64 and arrived at 24,
            // so the one question it existed to ask was never put.
            brightnessPercent = if (context.brightnessPercent == 100) 100 else null
        )
    }

    /**
     * The same arm in both intervals.
     *
     * On a taste block a catch is not measuring whether he can *see* a difference — it is measuring
     * whether he will invent a preference between two identical things. A high rate here means the
     * block's votes are habit and not taste.
     */
    private fun catchPair(
        block: String,
        stimulus: Stimulus,
        arm: Long,
        question: String,
        hint: String,
        context: LabContext,
        meta: Map<String, Int>
    ): LabTrial = LabTrial(
        block = block,
        kind = LabTrial.KIND_CATCH,
        intervals = listOf(stimulus, stimulus),
        question = question,
        hint = hint,
        options = LabOptions.A_B_UNSURE,
        truth = LabTruth.KNOWN,
        correctOptionId = "unsure",
        meta = meta + mapOf("armFirst" to arm.toInt(), "armSecond" to arm.toInt()),
        brightnessPercent = if (context.brightnessPercent == 100) 100 else null
    )


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

    /**
     * Builds a block's trials.
     *
     * [context] is ignored by [SCALE] and [NEAR_BLACK], which both run at 100% firmware brightness
     * and use [FULL_BRIGHTNESS_CONTEXT] instead — [SCALE] so the whole 255-rung ladder is
     * addressable, [NEAR_BLACK] so its lifted arm is reachable at all.
     */

    // --- block 7: ambiance's downward bias ----------------------------------------------------

    /**
     * Two recordings of the same dark scene, differing in one line of the smoother.
     *
     * ## What is being asked
     *
     * `AmbianceFrameAnalyser` uses a larger smoothing alpha when the picture gets *darker* than the
     * strip currently is - `1-(1-alpha)^2.2`, which at the shipped settings makes falls about 1.8x
     * faster than rises. On noisy dark content that rectifies noise into a sawtooth: downward wobble
     * is followed immediately, upward wobble is followed slowly, so the output ratchets down and
     * creeps back up. `AmbianceVideoBench` measures 36% more emitted-level reversals with the
     * asymmetry than without, and multi-level jumps on 13% of transitions against 1%.
     *
     * ## Why it is a recording rather than a live capture
     *
     * Both arms have to be the *same content*, frame for frame, or the comparison is between two
     * scenes rather than two rules. Precomputing them from the same film frames guarantees that,
     * and guarantees the write cadence is identical too - same number of writes at the same
     * moments, which is the control blocks 3 and 4 needed and could only get by construction.
     * [AmbianceTraces] is generated by the bench, so what he sees is what was measured.
     *
     * The excerpts are the worst four seconds of each clip **under the shipped rule**, chosen by
     * counting reversals rather than by eye. Picking the stretch where the complaint lives is the
     * point; picking it by hand would have been picking the answer.
     *
     * ## Why the answer is a preference and not a score
     *
     * The bench already knows which arm has fewer reversals. What it cannot know is whether fewer
     * reversals is what "steadier" means to him, or whether the slower fall reads as sluggish and
     * costs more than the shimmer did. That trade is the whole question, and only he can settle it -
     * the same reason block 6 had to be asked rather than derived.
     *
     * ## Plays at his own brightness, deliberately
     *
     * The traces are commanded bytes, computed with no brightness assumption, so the block pins
     * nothing and they play at whatever he runs. That is the honest setting here: the complaint is
     * about his normal viewing, and a dark scene lifted to 100% would not be the thing he
     * complained about.
     */
    fun ambianceFallTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)
        val main = mutableListOf<LabTrial>()
        val tail = mutableListOf<LabTrial>()

        AmbianceTraces.ALL.forEachIndexed { index, pair ->
            // Both arms play the same film, which is what makes them the same scene rendered two
            // ways rather than two scenes. It also means the picture cannot be used to tell them
            // apart - the same control the matched write cadence gives on the strip.
            val shipped = traceStimulus("${pair.id}_shipped", pair.stepMs, pair.shipped, pair.id)
            val symmetric = traceStimulus("${pair.id}_symmetric", pair.stepMs, pair.symmetricFall, pair.id)
            val first = preferencePair(
                AMBIANCE_FALL.id, "fall",
                shipped, ARM_SHIPPED,
                symmetric, ARM_SYMMETRIC,
                FALL_QUESTION, FALL_HINT, random, context, mapOf("clip" to index)
            )
            main.add(first)
            // Both orders for every pair, for the reason block 6 learned the hard way: without the
            // swap there is no way to separate "no preference" from "named the second one twice".
            main.add(mirrored(first))

            // A catch on each clip: the same arm in both intervals, so a decisive answer here is
            // the tie-breaking habit rather than a difference seen.
            main.add(
                catchPair(
                    AMBIANCE_FALL.id, shipped, ARM_SHIPPED,
                    FALL_QUESTION, FALL_HINT, context, mapOf("clip" to index)
                )
            )
        }

        // The anchor. One arm is the scene's own average held perfectly still, which is steadier
        // than anything the pipeline can produce - so an attentive answer is forced, and a run of
        // "can't tell" can be told apart from having stopped watching. It is scorable because
        // "which was steadier" has a fact of the matter when one of them does not move at all.
        val pair = AmbianceTraces.ALL.first()
        main.add(
            anchorSteadier(
                traceStimulus("${pair.id}_shipped", pair.stepMs, pair.shipped, pair.id),
                flatOf("${pair.id}_flat", pair.stepMs, pair.shipped, pair.id),
                context, random
            )
        )

        for (original in main.filter { it.kind == "fall" }.shuffled(random).take(CONSISTENCY_REPEATS)) {
            tail.add(mirrored(original, mapOf("repeat" to 1)))
        }
        return main.shuffled(random) + tail.shuffled(random)
    }

    /** Arm ids, recorded per trial so the reading knows which rule won without re-deriving it. */
    const val ARM_SHIPPED = 0L
    const val ARM_SYMMETRIC = 1L

    private const val FALL_QUESTION = "Which one was steadier?"
    private const val FALL_HINT =
        "Same seconds of film both times. Which strip held steadier - less twitchy, less jitter?"

    /** A recorded trace as a stimulus: flat RGB triples at a fixed step, beside its own film. */
    fun traceStimulus(label: String, stepMs: Long, flat: List<Int>, clip: String? = null): Stimulus {
        require(flat.size % 3 == 0) { "a trace is whole RGB triples" }
        val steps = ArrayList<StimulusStep>(flat.size / 3)
        for (i in flat.indices step 3) {
            val rgb = Triple(flat[i], flat[i + 1], flat[i + 2])
            // byte carries the brightest channel so anything reading stimuli in grey - the floor
            // guard among them - still sees a sensible level rather than zero.
            steps.add(StimulusStep(maxOf(rgb.first, rgb.second, rgb.third), stepMs, rgb))
        }
        return Stimulus(label, steps, clip)
    }

    /** The same trace's mean colour, held still for the same duration with the same write count. */
    fun flatOf(label: String, stepMs: Long, flat: List<Int>, clip: String? = null): Stimulus {
        val n = flat.size / 3
        val mean = Triple(
            (0 until n).sumOf { flat[it * 3] } / n,
            (0 until n).sumOf { flat[it * 3 + 1] } / n,
            (0 until n).sumOf { flat[it * 3 + 2] } / n
        )
        val steps = (0 until n).map {
            StimulusStep(maxOf(mean.first, mean.second, mean.third), stepMs, mean)
        }
        return Stimulus(label, steps, clip)
    }

    private fun anchorSteadier(
        moving: Stimulus,
        still: Stimulus,
        context: LabContext,
        random: Random
    ): LabTrial {
        val stillFirst = random.nextBoolean()
        return LabTrial(
            block = AMBIANCE_FALL.id,
            kind = LabTrial.KIND_ANCHOR,
            intervals = if (stillFirst) listOf(still, moving) else listOf(moving, still),
            question = FALL_QUESTION,
            hint = FALL_HINT,
            options = LabOptions.A_B_UNSURE,
            truth = LabTruth.KNOWN,
            correctOptionId = if (stillFirst) "a" else "b",
            meta = mapOf(
                "armFirst" to (if (stillFirst) ARM_STILL else ARM_SHIPPED).toInt(),
                "armSecond" to (if (stillFirst) ARM_SHIPPED else ARM_STILL).toInt()
            ),
            brightnessPercent = if (context.brightnessPercent == 100) 100 else null
        )
    }

    /** Not a rule under test - the anchor's motionless arm. */
    const val ARM_STILL = 2L


    // --- block 8: does hue motion pulse -------------------------------------------------------

    /**
     * A hue sweep, with and without the brightness swing that is built into it.
     *
     * ## The measured fact behind this block
     *
     * Rotating hue at a fixed HSV value swings the emitted light by **exactly 2x**
     * (`HuePathAnalysis`): a pure primary lights one channel and a colour between two primaries
     * lights two, and on the measured curve that is a factor of two, at every value and at every
     * saturation above about 0.4. A full revolution therefore contains **six brightness pulses**.
     *
     * That matters because of what is already known about the other axis. Across 27 tunings, felt
     * beat coupling tracked peak brightness slew at r=0.87, and Joe rejected both ends of that line.
     * Every preset in the app modulates brightness; the quadrant he actually asked for - flat
     * brightness, vivid hue motion - has never been built. If a hue sweep pulses at 2x on its own,
     * then "hue motion" has been brightness modulation all along, and building the quadrant needs
     * this fixed first.
     *
     * ## What the two arms are
     *
     * Both rotate hue at the same rate through the same hues. `raw` is what the visualiser does
     * today. `flat` solves, per hue, for the value that emits the rotation's **mean** light, using
     * [StripResponse] as an invertible lookup rather than another guessed exponent.
     *
     * Matching the *mean* rather than the primaries is the control that makes the pair fair: a flat
     * arm pinned to the dim end would simply be dimmer, and "which looks better" would collect a
     * preference for brightness. Matched means, the arms differ only in whether the light moves.
     *
     * ## Two questions, and only one of them is taste
     *
     * `pump` asks whether the swing is visible at all - a fact with an answer, measured rather than
     * scored, the same footing as a step-visibility trial. `prefer` asks which he wants. Asking the
     * second without the first would leave "no preference" and "cannot see the difference"
     * indistinguishable, which is the failure block 2 died of.
     */
    fun hueMotionTrials(context: LabContext, seed: Long): List<LabTrial> {
        val random = Random(seed)

        val raw = hueSweep(flat = false)
        val flat = hueSweep(flat = true)

        // Balanced by construction rather than by coin. Three of each order means that if he
        // answers by position - and the 2026-09-11 run says he does when a question is hard - the
        // votes come out 3-3 and read as "nothing seen", which is the truth. Drawing each order at
        // random would let the same habit produce a lopsided score that looks like a finding.
        val orders = List(HUE_REPEATS / 2) { true } + List(HUE_REPEATS / 2) { false }
        val main = orders.shuffled(random).mapIndexed { i, rawFirst ->
            LabTrial(
                block = HUE_MOTION.id,
                kind = "pump",
                intervals = if (rawFirst) listOf(raw, flat) else listOf(flat, raw),
                question = PUMP_QUESTION,
                hint = PUMP_HINT,
                options = LabOptions.A_B_NEITHER,
                // Whether he can see a 2x swing riding on a hue sweep is the measurement. It is
                // not scored: a "neither" here is a result, not a mistake.
                truth = LabTruth.UNKNOWN,
                correctOptionId = null,
                // Drawn once, with both the intervals and the meta derived from it. The first
                // version drew the order inside `intervals` and wrote the arms as a constant pair,
                // so every trial claimed raw-then-flat whichever way it played - and the three
                // answers from 2026-09-11 cannot be read because of it.
                meta = mapOf(
                    "armFirst" to (if (rawFirst) ARM_RAW else ARM_FLAT).toInt(),
                    "armSecond" to (if (rawFirst) ARM_FLAT else ARM_RAW).toInt()
                ) + hueOrderMeta(i),
                brightnessPercent = null
            )
        }.toMutableList()

        // The catch: the flattened sweep against itself. A decisive answer here is a pulse invented
        // between two identical things, which is the habit rather than a difference seen.
        main.add(
            LabTrial(
                block = HUE_MOTION.id,
                kind = LabTrial.KIND_CATCH,
                intervals = listOf(flat, flat),
                question = PUMP_QUESTION,
                hint = PUMP_HINT,
                options = LabOptions.A_B_NEITHER,
                truth = LabTruth.KNOWN,
                correctOptionId = "unsure",
                meta = mapOf("armFirst" to ARM_FLAT.toInt(), "armSecond" to ARM_FLAT.toInt()),
                brightnessPercent = null
            )
        )

        // The anchor, and it is asked in this block's own words. The 2026-09-11 run inherited block
        // 7's "which one was steadier?" into a block that asks about pulsing either side of it, and
        // he named the moving arm in 835ms - which is the right answer to the question the block had
        // been putting to him for the previous three trials. An anchor has to be the block's own
        // question with an unmissable answer, not a different question borrowed from elsewhere.
        val pumpedFirst = random.nextBoolean()
        val pumped = hueSweepPumped()
        main.add(
            LabTrial(
                block = HUE_MOTION.id,
                kind = LabTrial.KIND_ANCHOR,
                intervals = if (pumpedFirst) listOf(pumped, flat) else listOf(flat, pumped),
                question = PUMP_QUESTION,
                hint = PUMP_HINT,
                options = LabOptions.A_B_NEITHER,
                truth = LabTruth.KNOWN,
                correctOptionId = if (pumpedFirst) "a" else "b",
                meta = mapOf(
                    "armFirst" to (if (pumpedFirst) ARM_PUMPED else ARM_FLAT).toInt(),
                    "armSecond" to (if (pumpedFirst) ARM_FLAT else ARM_PUMPED).toInt()
                ),
                brightnessPercent = null
            )
        )

        return main.shuffled(random)
    }

    const val ARM_RAW = 0L
    const val ARM_FLAT = 1L

    /** The anchor's deliberately over-pumped sweep. Never an answer to anything, only a check. */
    const val ARM_PUMPED = 2L

    private const val PUMP_QUESTION = "Did either one pulse in brightness?"
    private const val PUMP_HINT =
        "Not the colour changing - the colour changes in both. Whether the light gets " +
            "brighter and dimmer as it goes round."

    /**
     * How many times the visibility question is asked. Half in each order, so [HUE_REPEATS] is even.
     *
     * Six rather than the original three because this is now the block's only question, and because
     * three could not be split evenly between the two orders.
     */
    const val HUE_REPEATS = 6

    private fun hueOrderMeta(index: Int): Map<String, Int> = mapOf("rep" to index)

    /**
     * One full revolution of hue at a constant rate, optionally light-flattened.
     *
     * [HUE_BASE_VALUE] sets where the rotation sits. It is not a floor and is not near one - it is
     * chosen so the flattened arm has headroom to *raise* the primaries to the mean without
     * clipping, which is the constraint that decides it. `LabBlocksTest` pins that it does.
     */
    fun hueSweep(flat: Boolean): Stimulus {
        val steps = HUE_STEPS
        val hues = (0 until steps).map { it * 360.0 / steps }
        val raw = hues.map { ColorConverter.hsvToRgb(it.toFloat(), 1f, HUE_BASE_VALUE) }
        val target = raw.sumOf { totalLight(it) } / steps

        val out = hues.mapIndexed { i, hue ->
            val rgb = if (!flat) raw[i] else valueForLight(hue.toFloat(), target)
            StimulusStep(maxOf(rgb.first, rgb.second, rgb.third), HUE_STEP_MS, rgb)
        }
        return Stimulus(if (flat) "hue_flat" else "hue_raw", out)
    }

    /**
     * The anchor's sweep: flattened, then deliberately swung far past what the raw sweep does.
     *
     * Built on the flat arm rather than the raw one so the pulse is the *only* thing in it - the
     * raw sweep's own 2x swing is tied to hue and would add a second, differently-timed movement
     * on top. [ANCHOR_SWING] cycles the target light between roughly a third and full over the
     * revolution, which is far larger and far slower than the 2x-at-six-per-revolution being
     * measured. Missing this means not watching.
     */
    fun hueSweepPumped(): Stimulus {
        val steps = HUE_STEPS
        val hues = (0 until steps).map { it * 360.0 / steps }
        val mean = hues.map { ColorConverter.hsvToRgb(it.toFloat(), 1f, HUE_BASE_VALUE) }
            .sumOf { totalLight(it) } / steps
        val out = hues.mapIndexed { i, hue ->
            val phase = 2.0 * Math.PI * ANCHOR_CYCLES * i / steps
            val scale = 1.0 - ANCHOR_SWING * (1.0 - kotlin.math.cos(phase)) / 2.0
            val rgb = valueForLight(hue.toFloat(), mean * scale)
            StimulusStep(maxOf(rgb.first, rgb.second, rgb.third), HUE_STEP_MS, rgb)
        }
        return Stimulus("hue_pumped", out)
    }

    /** How far down the anchor's light swings, as a fraction of the rotation's mean. */
    const val ANCHOR_SWING = 0.65

    /** How many brightness cycles the anchor fits into one revolution. Slow enough to be obvious. */
    const val ANCHOR_CYCLES = 2

    /** Emitted light for a commanded triple, summed over channels on the measured curve. */
    fun totalLight(rgb: Triple<Int, Int, Int>): Double =
        StripResponse.lightForByte(rgb.first) +
            StripResponse.lightForByte(rgb.second) +
            StripResponse.lightForByte(rgb.third)

    /**
     * The commanded colour at [hue] whose emitted light is closest to [target].
     *
     * A search over the HSV value rather than a formula, for the same reason
     * `StripResponse.byteForLight` is a search: the curve is a measured table, and inverting it by
     * fitting an exponent is what produced the numbers this project spent a month unpicking.
     */
    fun valueForLight(hue: Float, target: Double): Triple<Int, Int, Int> {
        var best = ColorConverter.hsvToRgb(hue, 1f, 1f)
        var bestErr = Double.MAX_VALUE
        var v = 0.05f
        while (v <= 1.0f) {
            val rgb = ColorConverter.hsvToRgb(hue, 1f, v)
            val err = kotlin.math.abs(totalLight(rgb) - target)
            if (err < bestErr) { bestErr = err; best = rgb }
            v += 0.002f
        }
        return best
    }

    /** One revolution in 4s at 50ms a step - the same cadence as the ambiance recordings. */
    const val HUE_STEPS = 80
    const val HUE_STEP_MS = 50L

    /**
     * Where the rotation sits on the value axis.
     *
     * High enough that the flattened arm can raise a primary to the rotation's mean light without
     * running out of byte, low enough that it is not sitting at the top of the range. The cubic in
     * `ColorConverter.hsvToRgb` is still in the path for both arms, deliberately: the arms differ in
     * flattening and in nothing else.
     */
    const val HUE_BASE_VALUE = 0.8f

    fun trialsFor(spec: BlockSpec, context: LabContext, seed: Long): List<LabTrial> = when (spec.id) {
        FLOOR_GRID.id -> floorGridTrials(context, seed)
        SCALE.id -> scaleTrials(seed)
        RATE.id -> rateTrials(context, seed)
        DITHER.id -> ditherTrials(context, seed)
        SMOOTHING.id -> smoothingTrials(context, seed)
        AMBIANCE_FALL.id -> ambianceFallTrials(context, seed)
        HUE_MOTION.id -> hueMotionTrials(context, seed)
        JUMPS.id -> jumpTrials(context, seed)
        NEAR_BLACK.id -> nearBlackTrials(seed)
        else -> emptyList()
    }
}
