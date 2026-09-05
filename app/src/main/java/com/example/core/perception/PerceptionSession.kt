package com.example.core.perception

import kotlin.random.Random

/** What Joe says after seeing the two intervals. */
enum class Response { A, B, CANT_TELL }

/** One answered trial, with everything needed to re-read it later. */
data class TrialRecord(
    val kind: TrialKind,
    val baseByte: Int,
    val delta: Int,
    val intervalMs: Long,
    val isCatch: Boolean,
    val targetIsB: Boolean,
    val response: Response,
    val correct: Boolean,
    val responseMs: Long
)

/**
 * What the session concluded, and how much the conclusion can be trusted.
 *
 * [falsePositiveRate] is the load-bearing number. It comes from the catch trials, where both
 * intervals are identical and the honest answer is "can't tell". If it is high, the thresholds are
 * not thresholds - they are a record of somebody picking a side out of politeness, and should be
 * thrown away rather than designed against.
 */
data class SessionReport(
    val records: List<TrialRecord>,
    val stepThresholdByBase: Map<Int, Double?>,
    val ditherVisibleByInterval: Map<Long, Double>,
    val fadePreference: Map<String, Int>,
    val falsePositiveRate: Double,
    val catchTrials: Int
)

/**
 * Runs one sitting: which trial to show next, what the answers add up to.
 *
 * Pure and seeded, so a session can be replayed exactly and the analysis re-run without asking Joe
 * to look at anything twice.
 *
 * ## The shape of a sitting
 *
 * Three blocks, interleaved so that no block gets all of the attention while it is fresh:
 *
 *  1. **Step visibility**, one staircase per base level. Answers "how many bytes of change can he
 *     see, down there where one byte is a large fraction of the light".
 *  2. **Dither flicker**, one question per candidate write interval. Answers whether dithering is
 *     usable at all on this hardware, and above what rate.
 *  3. **Fade smoothness**, plain against dithered. The direct question, and the one whose answer
 *     decides whether any of this ships.
 *
 * About one trial in five is a catch trial with two identical intervals.
 */
class PerceptionSession(
    seed: Long,
    private val baseLevels: List<Int> = listOf(4, 8, 16, 40, 100),
    private val ditherIntervals: List<Long> = listOf(20L, 40L, 60L, 100L),
    private val fadeRepeats: Int = 4
) {
    private val random = Random(seed)
    private val staircases = baseLevels.associateWith {
        PerceptionTrials.Staircase(start = maxOf(3, it / 3), min = 1, max = 60)
    }
    private val ditherAsked = mutableMapOf<Long, Int>()
    private var fadesAsked = 0

    private val recordsInternal = mutableListOf<TrialRecord>()
    val records: List<TrialRecord> get() = recordsInternal.toList()

    private var pending: Trial? = null

    /** Roughly one trial in five carries no difference at all. */
    private fun isCatchNext() = random.nextInt(5) == 0

    val finished: Boolean
        get() = staircases.values.all { it.finished } &&
            ditherIntervals.all { (ditherAsked[it] ?: 0) >= DITHER_REPEATS } &&
            fadesAsked >= fadeRepeats

    /** The next question, or null when the sitting is over. */
    fun next(): Trial? {
        if (finished) return null
        val trial = buildNext()
        pending = trial
        return trial
    }

    private fun buildNext(): Trial {
        val unfinishedSteps = baseLevels.filter { staircases.getValue(it).finished.not() }
        val ditherLeft = ditherIntervals.filter { (ditherAsked[it] ?: 0) < DITHER_REPEATS }
        val fadeLeft = fadesAsked < fadeRepeats

        // Pick among the blocks that still have work, at random, so fatigue lands evenly rather
        // than falling entirely on whichever block was scheduled last.
        val options = buildList {
            if (unfinishedSteps.isNotEmpty()) add(TrialKind.STEP_VISIBILITY)
            if (ditherLeft.isNotEmpty()) add(TrialKind.DITHER_FLICKER)
            if (fadeLeft) add(TrialKind.FADE_SMOOTHNESS)
        }
        return when (options[random.nextInt(options.size)]) {
            TrialKind.STEP_VISIBILITY -> {
                val base = unfinishedSteps[random.nextInt(unfinishedSteps.size)]
                val catch = isCatchNext()
                val delta = if (catch) 0 else staircases.getValue(base).current
                val targetIsB = random.nextBoolean()
                val changed = PerceptionTrials.step(base, delta)
                // The comparison interval is a step of *zero*, not a plain hold. Both then send the
                // same number of writes at the same moments, so the only thing that differs is the
                // level. A plain hold sends one write against the step's three, and a difference in
                // write pattern is a cue that has nothing to do with seeing a change.
                val flat = PerceptionTrials.step(base, 0)
                Trial(
                    kind = TrialKind.STEP_VISIBILITY,
                    a = if (targetIsB) flat else changed,
                    b = if (targetIsB) changed else flat,
                    targetIsB = targetIsB,
                    isCatch = catch,
                    baseByte = base,
                    delta = delta,
                    intervalMs = 0
                )
            }

            TrialKind.DITHER_FLICKER -> {
                val interval = ditherLeft[random.nextInt(ditherLeft.size)]
                val catch = isCatchNext()
                // A low base, because that is where dithering would be used: high up, one byte is
                // already invisible and there is nothing to dither for.
                val base = baseLevels.first()
                val targetIsB = random.nextBoolean()
                val dithered = PerceptionTrials.dither(base, 0.5, interval)
                // Duty zero: the same write cadence as the dithered interval, every write on the
                // same byte. Comparing against a single held write instead would have let the
                // dithered interval be picked out by its hundred writes rather than by its flicker,
                // which would have produced a confident result about the wrong thing.
                val steadyPulsed = PerceptionTrials.dither(base, 0.0, interval)
                val other = if (catch) PerceptionTrials.dither(base, 0.0, interval) else dithered
                Trial(
                    kind = TrialKind.DITHER_FLICKER,
                    a = if (targetIsB) steadyPulsed else other,
                    b = if (targetIsB) other else steadyPulsed,
                    targetIsB = targetIsB,
                    isCatch = catch,
                    baseByte = base,
                    delta = 1,
                    intervalMs = interval
                )
            }

            else -> {
                val catch = isCatchNext()
                val targetIsB = random.nextBoolean()
                val plain = PerceptionTrials.fadePlain(4, 12, FADE_MS, FADE_WRITE_MS)
                val dith = PerceptionTrials.fadeDithered(4, 12, FADE_MS, FADE_WRITE_MS)
                val other = if (catch) plain else dith
                Trial(
                    kind = TrialKind.FADE_SMOOTHNESS,
                    a = if (targetIsB) plain else other,
                    b = if (targetIsB) other else plain,
                    targetIsB = targetIsB,
                    isCatch = catch,
                    baseByte = 4,
                    delta = 8,
                    intervalMs = FADE_WRITE_MS
                )
            }
        }
    }

    /** Files an answer to the trial [next] returned. */
    fun record(response: Response, responseMs: Long) {
        val trial = pending ?: return
        pending = null
        // On a catch trial there is nothing to find, so "can't tell" is the only right answer and
        // anything else is a false positive.
        val correct = if (trial.isCatch) {
            response == Response.CANT_TELL
        } else {
            response == (if (trial.targetIsB) Response.B else Response.A)
        }
        recordsInternal.add(
            TrialRecord(
                kind = trial.kind,
                baseByte = trial.baseByte,
                delta = trial.delta,
                intervalMs = trial.intervalMs,
                isCatch = trial.isCatch,
                targetIsB = trial.targetIsB,
                response = response,
                correct = correct,
                responseMs = responseMs
            )
        )
        when (trial.kind) {
            // Catch trials must not move the staircase: they carry no difference, so a "can't tell"
            // is not evidence that this delta is too small.
            TrialKind.STEP_VISIBILITY -> if (!trial.isCatch) {
                staircases.getValue(trial.baseByte).record(correct)
            }
            TrialKind.DITHER_FLICKER -> if (!trial.isCatch) {
                ditherAsked[trial.intervalMs] = (ditherAsked[trial.intervalMs] ?: 0) + 1
            }
            TrialKind.FADE_SMOOTHNESS -> if (!trial.isCatch) fadesAsked++
        }
    }

    fun report(): SessionReport {
        val catches = recordsInternal.filter { it.isCatch }
        val falsePositives = catches.count { it.response != Response.CANT_TELL }
        val dither = ditherIntervals.associateWith { interval ->
            val rs = recordsInternal.filter {
                it.kind == TrialKind.DITHER_FLICKER && it.intervalMs == interval && !it.isCatch
            }
            if (rs.isEmpty()) 0.0 else rs.count { it.correct }.toDouble() / rs.size
        }
        val fades = recordsInternal.filter { it.kind == TrialKind.FADE_SMOOTHNESS && !it.isCatch }
        return SessionReport(
            records = records,
            stepThresholdByBase = baseLevels.associateWith { staircases.getValue(it).threshold() },
            ditherVisibleByInterval = dither,
            fadePreference = mapOf(
                "dithered" to fades.count { it.correct },
                "plain" to fades.count { !it.correct }
            ),
            falsePositiveRate = if (catches.isEmpty()) 0.0 else falsePositives.toDouble() / catches.size,
            catchTrials = catches.size
        )
    }

    companion object {
        /** Repeats per dither interval. Enough to tell 100% from 50% without a long sitting. */
        const val DITHER_REPEATS = 4
        const val FADE_MS = 6000L

        /**
         * Write interval for the fade trials.
         *
         * 50ms, matching the shipped pacing, so the comparison is between two ways of rendering a
         * fade the app could actually send today - not between the shipped one and a rate the write
         * path has never sustained under real load.
         */
        const val FADE_WRITE_MS = 50L
    }
}
