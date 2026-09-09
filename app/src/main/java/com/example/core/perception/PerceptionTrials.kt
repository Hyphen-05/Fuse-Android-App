package com.example.core.perception

/**
 * The stimuli and the staircase for asking Joe what he can actually see, on his own strips.
 *
 * ## Why this exists
 *
 * The capture programme measured the strip and never measured the viewer. That gap is what let a
 * change ship on 2026-09-04 that a simulation scored as an improvement and Joe described as
 * "everything is steppy now". The missing number was never physical - it is *how small a change
 * has to be before he stops seeing it*, at the brightness he actually runs.
 *
 * With the Pixel 9 gone there is no camera rig any more, and that turns out not to matter here: a
 * camera could never have answered this question. It integrates light differently from an eye and
 * has no opinion about whether a fade looks smooth.
 *
 * ## Measured in bytes, deliberately
 *
 * Every stimulus here is defined in **commanded bytes**, not in emitted light. That sidesteps the
 * biggest hole in the data: the byte-to-light curve was measured at firmware brightness 100%, and
 * nothing has ever measured how it composes with a lower brightness setting. Asking "how many bytes
 * of change can you see" needs no such model, and bytes are the units the app actually commands, so
 * the answer is directly usable. The brightness setting in force is recorded with every trial so
 * results from different settings are never pooled by accident.
 *
 * ## What makes this data rather than an opinion
 *
 *  - **Two intervals, randomised order.** Every trial shows A then B and asks which one had the
 *    property. Which interval carries it is decided by the seed, so knowing the design does not
 *    help.
 *  - **Catch trials.** A known fraction of trials are identical in both intervals. There is no
 *    right answer, so the rate of confident answers on those is the guessing rate, and a threshold
 *    is only believable if it is low.
 *  - **A staircase, not a sweep.** Trials concentrate near the threshold instead of wasting
 *    attention on differences that are obvious or invisible.
 */

/**
 * One commanded level, held for a while. A stimulus is a list of these, played in order.
 *
 * [rgb] is null for every block up to and including 6, which measure light and not colour: those
 * command [byte] on all three channels, and saying so once here is cheaper than carrying a grey
 * triple through each of them. A block replaying recorded ambiance output sets it, because a
 * screen's colour is half of what makes that output wobble.
 */
data class StimulusStep(
    val byte: Int,
    val holdMs: Long,
    val rgb: Triple<Int, Int, Int>? = null
) {
    /** What actually goes on the wire: the colour if there is one, otherwise grey at [byte]. */
    val commanded: Triple<Int, Int, Int> get() = rgb ?: Triple(byte, byte, byte)
}

/** One thing shown in one interval of a trial. */
data class Stimulus(val label: String, val steps: List<StimulusStep>) {
    val durationMs: Long get() = steps.sumOf { it.holdMs }
}

enum class TrialKind {
    /** Can you see a step of N bytes from this base level? */
    STEP_VISIBILITY,

    /** Does dithering between two adjacent bytes read as a steady level, or as flicker? */
    DITHER_FLICKER,

    /** Which of these two fades is smoother? */
    FADE_SMOOTHNESS
}

/**
 * One question, as two intervals plus the answer that counts as "saw the difference".
 *
 * [targetIsB] is the ground truth. On a catch trial it is meaningless and [isCatch] says so.
 */
data class Trial(
    val kind: TrialKind,
    val a: Stimulus,
    val b: Stimulus,
    val targetIsB: Boolean,
    val isCatch: Boolean,
    val baseByte: Int,
    val delta: Int,
    val intervalMs: Long
)

object PerceptionTrials {

    /** Gap between the two intervals, so they are compared rather than seen as one event. */
    const val INTER_STIMULUS_MS = 600L

    /** How long each interval is held. Long enough to look at, short enough to keep trials cheap. */
    const val INTERVAL_MS = 2000L

    /** Steady hold at one byte. */
    fun steady(byte: Int, ms: Long = INTERVAL_MS) =
        Stimulus("steady_$byte", listOf(StimulusStep(byte, ms)))

    /**
     * A step up and back down, which is what a scene change looks like to the strip.
     *
     * A change that is only ever *entered* can be missed by looking away; going up and back gives
     * two chances to catch it inside one interval.
     */
    fun step(base: Int, delta: Int, ms: Long = INTERVAL_MS): Stimulus {
        val third = ms / 3
        return Stimulus(
            "step_${base}_${delta}",
            listOf(
                StimulusStep(base, third),
                StimulusStep((base + delta).coerceIn(0, 255), third),
                StimulusStep(base, ms - 2 * third)
            )
        )
    }

    /**
     * Alternates [byte] and [byte] + 1 to land, on average, between them.
     *
     * This is the whole proposition behind dithering: the strip only takes whole bytes, so a level
     * between two of them can only be produced over *time*. Whether that reads as an intermediate
     * level or as flicker depends on how fast the alternation is, which is exactly what
     * [intervalMs] is here to sweep - it is the variable, not a constant to be guessed.
     *
     * [duty] is the fraction of time spent on the upper byte, so 0.5 sits halfway between them.
     */
    fun dither(byte: Int, duty: Double, intervalMs: Long, ms: Long = INTERVAL_MS): Stimulus {
        require(duty in 0.0..1.0) { "duty is a fraction" }
        val steps = mutableListOf<StimulusStep>()
        var elapsed = 0L
        // Error-diffused rather than a fixed pattern: a strict ABAB can only express duties of
        // 0.5, and a repeating pattern at a fixed period is the easiest kind of flicker to see.
        var accumulator = 0.0
        while (elapsed < ms) {
            accumulator += duty
            val useUpper = accumulator >= 1.0
            if (useUpper) accumulator -= 1.0
            val hold = minOf(intervalMs, ms - elapsed)
            steps.add(StimulusStep(if (useUpper) byte + 1 else byte, hold))
            elapsed += hold
        }
        return Stimulus("dither_${byte}_${(duty * 100).toInt()}_$intervalMs", steps)
    }

    /**
     * A fade between two levels, rendered the way the app renders it: whole bytes only.
     *
     * Each write lands on the nearest byte to where the fade currently is, so wherever the curve is
     * steep the output holds still and then jumps - which is the complaint this whole exercise
     * started from.
     */
    fun fadePlain(from: Int, to: Int, ms: Long, writeIntervalMs: Long): Stimulus {
        val steps = mutableListOf<StimulusStep>()
        var t = 0L
        while (t < ms) {
            val frac = t.toDouble() / ms
            val level = from + (to - from) * frac
            steps.add(StimulusStep(Math.round(level).toInt().coerceIn(0, 255), minOf(writeIntervalMs, ms - t)))
            t += writeIntervalMs
        }
        return Stimulus("fade_plain_${from}_${to}", steps)
    }

    /**
     * The same fade with the fractional part dithered across successive writes.
     *
     * Where [fadePlain] rounds 6.4 to 6 every time, this spends 60% of its writes on 6 and 40% on
     * 7, so the average tracks the fade continuously. If dithering works on this hardware at this
     * write rate, this is the stimulus that should look smoother; if it does not, this is the one
     * that will look like flicker.
     */
    fun fadeDithered(from: Int, to: Int, ms: Long, writeIntervalMs: Long): Stimulus {
        val steps = mutableListOf<StimulusStep>()
        var t = 0L
        var error = 0.0
        while (t < ms) {
            val frac = t.toDouble() / ms
            val level = from + (to - from) * frac + error
            val emitted = Math.round(level).toInt().coerceIn(0, 255)
            error = level - emitted
            steps.add(StimulusStep(emitted, minOf(writeIntervalMs, ms - t)))
            t += writeIntervalMs
        }
        return Stimulus("fade_dithered_${from}_${to}", steps)
    }

    /**
     * An adaptive staircase over the size of the difference being asked about.
     *
     * One wrong answer makes the next trial easier; **two** consecutive right answers make it
     * harder. That asymmetry is what makes it converge on a level detected about 71% of the time
     * rather than on one detected every time - the point where the difference is on the edge of
     * being visible, which is the number a design needs.
     *
     * The run ends after [reversalsToFinish] direction changes, and the threshold is the mean of
     * all but the first two reversals - the early ones are still travelling from the starting
     * guess and would drag the estimate toward it.
     */
    class Staircase(
        private val start: Int,
        private val min: Int,
        private val max: Int,
        private val reversalsToFinish: Int = 8
    ) {
        var current: Int = start.coerceIn(min, max)
            private set

        private var consecutiveCorrect = 0
        private var lastDirection = 0
        private val reversals = mutableListOf<Int>()

        val reversalCount: Int get() = reversals.size
        val finished: Boolean get() = reversals.size >= reversalsToFinish

        /** Step size shrinks after the first couple of reversals, to home in rather than oscillate. */
        private fun stepSize(): Int = if (reversals.size < 2) maxOf(2, current / 3) else 1

        fun record(correct: Boolean) {
            if (finished) return
            val direction: Int
            if (correct) {
                consecutiveCorrect++
                if (consecutiveCorrect < 2) return
                consecutiveCorrect = 0
                direction = -1
            } else {
                consecutiveCorrect = 0
                direction = 1
            }
            if (lastDirection != 0 && direction != lastDirection) reversals.add(current)
            lastDirection = direction
            current = (current + direction * stepSize()).coerceIn(min, max)
        }

        /** The estimate, or null while there are too few reversals to mean anything. */
        fun threshold(): Double? {
            val usable = reversals.drop(2)
            return if (usable.isEmpty()) null else usable.average()
        }
    }
}
