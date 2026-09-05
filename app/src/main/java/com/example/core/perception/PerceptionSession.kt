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

/** An answer as given, which is all that needs storing: the trial it belonged to is derivable. */
data class Answer(val response: Response, val responseMs: Long)

/**
 * The shape of a sitting.
 *
 * Separated from the session so it can be **derived from a floor calibration** rather than
 * hardcoded (see [FloorFinder.sessionConfigFor]) and stored alongside a part-finished sitting, so
 * resuming one cannot silently resume it with a different design.
 */
data class SessionConfig(
    val baseLevels: List<Int> = listOf(4, 8, 16, 40, 100),
    val ditherIntervals: List<Long> = listOf(20L, 40L, 60L, 100L),
    val ditherBase: Int = baseLevels.first(),
    val fadeFrom: Int = baseLevels.first(),
    val fadeSpan: Int = 8,
    val fadeRepeats: Int = 4,
    val reversalsToFinish: Int = 8
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
 * ## Everything is a function of the seed and the answers
 *
 * The sequence is **fully determined by `(seed, config, answers)`** — the RNG is seeded, and each
 * trial is built from state that only the answers move. Nothing else is needed to reconstruct a
 * sitting exactly, and that single property is what pays for three things Joe asked for:
 *
 *  - **Going back.** [undoLast] drops the last answer and rebuilds from the start. The trial he
 *    returns to is bit-for-bit the one he saw, because it is regenerated rather than remembered.
 *  - **Answering differently.** Because later trials are adaptive, changing an old answer *should*
 *    change what follows — and it does, correctly, rather than leaving a staircase carrying the
 *    consequences of an answer he took back.
 *  - **Surviving a crash.** Only the seed, the config and a list of answers need saving, so the
 *    on-disk record of a part-finished sitting is a few hundred bytes and cannot drift out of step
 *    with the session's internal state — there is no internal state that is not derived from it.
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
    val seed: Long,
    val config: SessionConfig = SessionConfig()
) {

    /** Every answer given so far, in order. Replaying these reproduces the sitting exactly. */
    private val answers = mutableListOf<Answer>()
    val answerCount: Int get() = answers.size
    fun answersSoFar(): List<Answer> = answers.toList()

    // --- derived state: rebuilt wholesale from `answers`, never mutated independently ----------

    private lateinit var random: Random
    private lateinit var staircases: Map<Int, PerceptionTrials.Staircase>
    private lateinit var ditherAsked: MutableMap<Long, Int>
    private var fadesAsked = 0
    private val recordsInternal = mutableListOf<TrialRecord>()
    private var pending: Trial? = null

    init {
        resetDerivedState()
    }

    val records: List<TrialRecord> get() = recordsInternal.toList()

    private fun resetDerivedState() {
        random = Random(seed)
        staircases = config.baseLevels.associateWith {
            PerceptionTrials.Staircase(
                start = maxOf(3, it / 3),
                min = 1,
                max = 60,
                reversalsToFinish = config.reversalsToFinish
            )
        }
        ditherAsked = config.ditherIntervals.associateWith { 0 }.toMutableMap()
        fadesAsked = 0
        recordsInternal.clear()
        pending = null
    }

    /**
     * Replays every stored answer from a clean state.
     *
     * The trial each answer belonged to is regenerated on the way through rather than stored,
     * which is what keeps the saved form small and impossible to desynchronise.
     */
    private fun replayAnswers() {
        val toReplay = answers.toList()
        resetDerivedState()
        for (answer in toReplay) {
            if (finished) break
            pending = buildNext()
            applyAnswer(answer)
        }
    }

    /** Roughly one trial in five carries no difference at all. */
    private fun isCatchNext() = random.nextInt(5) == 0

    val finished: Boolean
        get() = staircases.values.all { it.finished } &&
            config.ditherIntervals.all { (ditherAsked[it] ?: 0) >= DITHER_REPEATS } &&
            fadesAsked >= config.fadeRepeats

    /**
     * A crude idea of how far through the sitting we are, for a progress line.
     *
     * Deliberately an estimate and labelled as one where it is shown: a staircase's remaining
     * length depends on answers not yet given, so an exact figure does not exist.
     */
    fun progressFraction(): Double {
        val stepDone = staircases.values.sumOf { it.reversalCount.toDouble() }
        val stepTotal = (staircases.size * config.reversalsToFinish).toDouble()
        val ditherDone = ditherAsked.values.sumOf { minOf(it, DITHER_REPEATS).toDouble() }
        val ditherTotal = (config.ditherIntervals.size * DITHER_REPEATS).toDouble()
        val fadeDone = minOf(fadesAsked, config.fadeRepeats).toDouble()
        val total = stepTotal + ditherTotal + config.fadeRepeats
        if (total <= 0.0) return 1.0
        return ((stepDone + ditherDone + fadeDone) / total).coerceIn(0.0, 1.0)
    }

    /** The next question, or null when the sitting is over. */
    fun next(): Trial? {
        if (finished) return null
        pending?.let { return it }
        val trial = buildNext()
        pending = trial
        return trial
    }

    /**
     * Takes back the most recent answer and returns the trial it belonged to, ready to re-show.
     *
     * Returns null when there is nothing to undo. The staircase, the block counters and the record
     * list all move back with it, because all three are rebuilt from the shortened answer list
     * rather than unwound in place — unwinding a staircase by hand is the kind of thing that works
     * until the step size has changed underneath it.
     */
    fun undoLast(): Trial? {
        if (answers.isEmpty()) return null
        answers.removeAt(answers.lastIndex)
        replayAnswers()
        return next()
    }

    private fun buildNext(): Trial {
        val unfinishedSteps = config.baseLevels.filter { staircases.getValue(it).finished.not() }
        val ditherLeft = config.ditherIntervals.filter { (ditherAsked[it] ?: 0) < DITHER_REPEATS }
        val fadeLeft = fadesAsked < config.fadeRepeats

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
                // already invisible and there is nothing to dither for. It comes from the floor
                // calibration rather than a constant, so "low" means low *and lit*.
                val base = config.ditherBase
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
                val from = config.fadeFrom
                val to = (from + config.fadeSpan).coerceAtMost(255)
                val plain = PerceptionTrials.fadePlain(from, to, FADE_MS, FADE_WRITE_MS)
                val dith = PerceptionTrials.fadeDithered(from, to, FADE_MS, FADE_WRITE_MS)
                val other = if (catch) plain else dith
                Trial(
                    kind = TrialKind.FADE_SMOOTHNESS,
                    a = if (targetIsB) plain else other,
                    b = if (targetIsB) other else plain,
                    targetIsB = targetIsB,
                    isCatch = catch,
                    baseByte = from,
                    delta = config.fadeSpan,
                    intervalMs = FADE_WRITE_MS
                )
            }
        }
    }

    /** Files an answer to the trial [next] returned. */
    fun record(response: Response, responseMs: Long) {
        if (pending == null) return
        answers.add(Answer(response, responseMs))
        applyAnswer(Answer(response, responseMs))
    }

    /**
     * Restores a part-finished sitting from what was saved.
     *
     * Answers are replayed rather than trusted: the records, staircases and counters are all
     * recomputed, so a saved file cannot carry a state that the code would not itself have reached.
     */
    fun restore(saved: List<Answer>) {
        answers.clear()
        answers.addAll(saved)
        replayAnswers()
    }

    private fun applyAnswer(answer: Answer) {
        val trial = pending ?: return
        pending = null
        // On a catch trial there is nothing to find, so "can't tell" is the only right answer and
        // anything else is a false positive.
        val correct = if (trial.isCatch) {
            answer.response == Response.CANT_TELL
        } else {
            answer.response == (if (trial.targetIsB) Response.B else Response.A)
        }
        recordsInternal.add(
            TrialRecord(
                kind = trial.kind,
                baseByte = trial.baseByte,
                delta = trial.delta,
                intervalMs = trial.intervalMs,
                isCatch = trial.isCatch,
                targetIsB = trial.targetIsB,
                response = answer.response,
                correct = correct,
                responseMs = answer.responseMs
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
        val dither = config.ditherIntervals.associateWith { interval ->
            val rs = recordsInternal.filter {
                it.kind == TrialKind.DITHER_FLICKER && it.intervalMs == interval && !it.isCatch
            }
            if (rs.isEmpty()) 0.0 else rs.count { it.correct }.toDouble() / rs.size
        }
        val fades = recordsInternal.filter { it.kind == TrialKind.FADE_SMOOTHNESS && !it.isCatch }
        return SessionReport(
            records = records,
            stepThresholdByBase = config.baseLevels.associateWith { staircases.getValue(it).threshold() },
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
