package com.example.core.audio

import kotlin.math.abs

/**
 * A phase-locked clock that ticks once per beat, for as long as there is a tempo to keep.
 *
 * ## Why the flashes needed one
 *
 * Measured 2026-08-21 (`BeatAccuracyTest`, ten tracks with known beat times): the shipped
 * visualiser scores a mean F-measure of **60%**, and the shape of that failure is not missed beats.
 * Recall was 69-99% — it flashes *plenty*. Precision was 24-57%, and on six of the ten tracks the
 * grid the flashes fitted best was the **double** grid, at up to F=97%. The strip was flashing on
 * the offbeats as well as the beats, so it looked busy rather than musical.
 *
 * The cause was structural. Of the two flash paths in [AudioDspProcessor], the predictive scheduler
 * fired **0% of all flashes at the default settings** — it re-armed itself to the next beat on the
 * very frame the current one became due, so its firing window never opened. Everything Joe has seen
 * came from the fast causal trigger, which fires on any onset that clears an adaptive threshold: a
 * hat, a swung eighth, a syncopated kick. It has no notion of where the beat is, because nothing
 * asked it to have one.
 *
 * Meanwhile the tempo estimate underneath was *fine*: 127.7 against a true 128, 95.4 against 96,
 * 150.0 against 150, with confidence above the gate 100% of the time on eight of ten tracks. The
 * information was there and nothing was using it.
 *
 * ## What this does instead
 *
 * Holds a period and a phase, and ticks. Between detections it free-runs, which is what makes it
 * immune to the two things that killed the scheduler — a grid that only rebuilds every 1.5s, and a
 * predicted-beat value that rolls over the instant it comes due.
 *
 * Detections do not trigger flashes; they *steer* the clock, and only gently
 * ([PHASE_CORRECTION] of the error per beat). A tracker that snaps to every detection inherits
 * every false one, which is precisely the behaviour being replaced.
 *
 * The clock is deliberately conservative about staying locked: it keeps ticking through a passage
 * with no detections at all (a breakdown, a held chord), because a listener keeps counting through
 * those too. What stops it is the tempo lock itself going away — and, upstream, [MusicPresence]
 * deciding there is no music.
 */
class BeatClock {

    /** Beat period in ms, or 0 when the clock is not running. */
    var periodMs: Float = 0f
        private set

    /** When the next tick is due, absolute ms. */
    private var nextTickMs = 0L

    /** Beats ticked since the clock last started, for diagnostics and warm-up gating. */
    var ticks: Int = 0
        private set

    private var running = false

    /** When the tempo estimate first went weak, so a dip can be ridden out. 0 when it is healthy. */
    private var weakSinceMs = 0L

    /** When the estimate first disagreed with the held period, and what it has been saying since. */
    private var disagreeSinceMs = 0L
    private var disagreeTarget = 0f

    /** Rolling record of whether recent real onsets landed on a tick. */
    private val agreementWindow = BooleanArray(AGREEMENT_WINDOW)
    private var agreementIdx = 0
    private var agreementCount = 0
    private var agreementHits = 0

    val isRunning: Boolean get() = running

    /**
     * Whether the clock has earned the right to drive the flashing.
     *
     * Running and being right are different things, and conflating them was what made the first
     * version of this worse than what it replaced: on material where the tempo estimate is wrong
     * the clock still starts, still keeps perfect time to the wrong pulse, and — because it takes
     * the flashing over — silences the causal trigger that was at least reacting to real onsets.
     * Measured, that turned F=63% into 6% on the shuffle track while turning 65% into 92% on
     * four-on-the-floor.
     *
     * So the clock has to be *checked against the audio*, not just against the tempo estimate it
     * was built from. [observeOnset] feeds it real transients; this asks whether enough of them
     * recently landed where the clock said a beat would be. When they have not, the clock keeps
     * running and keeps being steered — it just does not get to flash, and the causal trigger
     * carries on as before until agreement comes back.
     */
    val isTrusted: Boolean
        get() = running && agreementCount >= MIN_AGREEING_ONSETS &&
            agreementHits.toFloat() / agreementCount >= MIN_AGREEMENT

    /** Share of recent onsets that landed on a tick, for diagnostics. */
    val agreement: Float
        get() = if (agreementCount == 0) 0f else agreementHits.toFloat() / agreementCount

    /**
     * Advances the clock one frame.
     *
     * @param bpm the detector's current tempo estimate, 0 when it has none
     * @param bpmConfidence 0..1
     * @param beatReferenceMs a beat position in the music's own time — past or future — to steer
     *   the phase toward, or null when there is none. Must **not** be a detection frame timestamp:
     *   the centred detector reports beats ~180ms after they happen, and steering by that puts
     *   every tick a fifth of a beat late.
     * @return true on the frame a beat is due
     */
    fun update(
        nowMs: Long,
        bpm: Float,
        bpmConfidence: Float,
        beatReferenceMs: Long?
    ): Boolean {
        val weak = bpm <= 0f || bpmConfidence < MIN_CONFIDENCE
        if (weak) {
            // Hold through a dip rather than dropping on the first weak frame. Confidence wobbles
            // constantly on real material — measured on the corpus, resetting instantly cost half
            // the ticks on five of ten tracks, because every restart also had to re-acquire phase.
            // A listener does not stop counting because one bar was ambiguous.
            if (!running) return false
            if (weakSinceMs == 0L) weakSinceMs = nowMs
            if (nowMs - weakSinceMs > HOLD_THROUGH_WEAK_MS) {
                reset()
                return false
            }
        } else {
            weakSinceMs = 0L
        }

        val target = if (bpm > 0f) {
            (60_000f / bpm).coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
        } else {
            periodMs
        }
        if (!running) {
            // Start in phase with the grid rather than wherever this frame fell, and do not tick on
            // the starting frame — that tick would be at an arbitrary position by construction.
            if (beatReferenceMs == null) return false
            periodMs = target
            var first = beatReferenceMs
            while (first < nowMs) first += target.toLong()
            nextTickMs = first
            running = true
            ticks = 0
            return false
        }

        // Period follows the estimate smoothly *while the estimate agrees with it*, and refuses to
        // follow a jump until the jump has proved itself.
        //
        // This is the part that decides whether the clock is any use. The tempo estimate is not
        // stable frame to frame: on the shuffle track it alternates between the beat and the
        // triplet subdivision, and a clock that follows every frame ends up keeping neither — 68
        // ticks against 48 beats, most of them between the beats. Requiring a disagreeing estimate
        // to hold for [RELOCK_MS] before the period moves is what turns "the tempo estimate wobbles"
        // into "the tempo changed", which are different events and deserve different responses.
        val disagrees = abs(target - periodMs) > periodMs * PERIOD_AGREEMENT
        if (disagrees) {
            if (disagreeSinceMs == 0L || abs(target - disagreeTarget) > disagreeTarget * PERIOD_AGREEMENT) {
                disagreeSinceMs = nowMs
                disagreeTarget = target
            }
            if (nowMs - disagreeSinceMs >= RELOCK_MS) {
                // The new tempo has held long enough to be believed. Take it, and re-acquire phase
                // from the reference rather than dragging the old phase into the new period.
                periodMs = target
                disagreeSinceMs = 0L
                if (beatReferenceMs != null) {
                    var first = beatReferenceMs
                    while (first < nowMs) first += periodMs.toLong()
                    nextTickMs = first
                }
            }
        } else {
            disagreeSinceMs = 0L
            periodMs += (target - periodMs) * PERIOD_FOLLOW
        }

        if (nowMs >= nextTickMs) {
            // Never emit a burst to catch up: if several ticks are overdue (a stalled frame, a
            // process pause) the beats in between are gone and flashing them now would be noise.
            nextTickMs += periodMs.toLong()
            if (nextTickMs <= nowMs) nextTickMs = nowMs + periodMs.toLong()
            ticks++
            // Correct **once per tick**, not once per frame. Correcting every frame applies the
            // same error forty-odd times a beat, which walks the phase backwards fast enough to
            // emit a second tick before the beat is out: measured 86 ticks against 64 beats on the
            // four-on-the-floor track, a median tick rate of 235bpm against a true 128. A phase-
            // locked loop corrects at its own boundary for exactly this reason.
            if (beatReferenceMs != null) correctPhase(beatReferenceMs, nowMs)
            return true
        }
        return false
    }

    /**
     * How far [atMs] sits from the nearest tick, as a share of a period (0 on the beat, 0.5 exactly
     * between two). Returns null when the clock has no time to keep.
     *
     * This is the clock used as a *referee* rather than as a driver: the causal trigger still
     * decides when something happened, and this says whether it happened on a beat.
     */
    fun distanceFromTick(atMs: Long): Float? {
        if (!running || periodMs <= 0f) return null
        val previousTick = nextTickMs - periodMs.toLong()
        var error = (atMs - previousTick).toFloat()
        while (error > periodMs / 2f) error -= periodMs
        while (error < -periodMs / 2f) error += periodMs
        return abs(error) / periodMs
    }

    /**
     * Reports a real onset the detector found, so the clock can be checked against the audio.
     *
     * [onsetMs] must be the time the transient actually happened, not the frame the centred
     * detector announced it on — those differ by the detector's lookahead, and comparing the
     * announcement to the clock would read a perfectly locked clock as a fifth of a beat out.
     */
    fun observeOnset(onsetMs: Long) {
        if (!running || periodMs <= 0f) return
        val previousTick = nextTickMs - periodMs.toLong()
        var error = (onsetMs - previousTick).toFloat()
        while (error > periodMs / 2f) error -= periodMs
        while (error < -periodMs / 2f) error += periodMs
        val onBeat = abs(error) <= periodMs * ONSET_TOLERANCE

        if (agreementWindow[agreementIdx] && agreementCount == AGREEMENT_WINDOW) agreementHits--
        agreementWindow[agreementIdx] = onBeat
        if (onBeat) agreementHits++
        agreementIdx = (agreementIdx + 1) % AGREEMENT_WINDOW
        if (agreementCount < AGREEMENT_WINDOW) agreementCount++
    }

    /** Drops the lock. The next detection with a confident tempo starts a new one. */
    fun reset() {
        running = false
        weakSinceMs = 0L
        disagreeSinceMs = 0L
        disagreeTarget = 0f
        agreementIdx = 0
        agreementCount = 0
        agreementHits = 0
        java.util.Arrays.fill(agreementWindow, false)
        periodMs = 0f
        nextTickMs = 0L
        ticks = 0
    }

    /**
     * Nudges the next tick toward a detected beat, in proportion to how far off it was.
     *
     * Errors beyond [MAX_PHASE_ERROR] of a period are treated as a reference on a different
     * metrical position — an offbeat hat, a syncopation — and ignored. That single rule is what
     * stops the clock being dragged onto the offbeat by material where the offbeat is louder than
     * the beat, which is what the shuffle and funk tracks in the corpus exist to test.
     */
    private fun correctPhase(beatReferenceMs: Long, nowMs: Long) {
        if (periodMs <= 0f) return
        val previousTick = nextTickMs - periodMs.toLong()
        var error = (beatReferenceMs - previousTick).toFloat()
        // Wrap into ±half a period, so a detection just after a tick and one just before the next
        // are both read as small errors in opposite directions.
        while (error > periodMs / 2f) error -= periodMs
        while (error < -periodMs / 2f) error += periodMs
        if (abs(error) > periodMs * MAX_PHASE_ERROR) return
        val corrected = nextTickMs + (error * PHASE_CORRECTION).toLong()
        // A correction must never pull the next tick into the past, or the following frame fires
        // again immediately and the beat is doubled.
        nextTickMs = maxOf(corrected, nowMs + (periodMs * MIN_NEXT_TICK_FRACTION).toLong())
    }

    private companion object {
        /** Below this the tempo estimate is not worth keeping time to. */
        const val MIN_CONFIDENCE = 0.15f

        /** 40-200bpm, matching `BeatDetector`'s own search range. */
        const val MIN_PERIOD_MS = 300f
        const val MAX_PERIOD_MS = 1500f

        /** Share of a detection's phase error applied per beat. */
        const val PHASE_CORRECTION = 0.25f

        /** Share of a period beyond which a detection is assumed to be on a different subdivision. */
        const val MAX_PHASE_ERROR = 0.25f

        /** How fast the period follows the tempo estimate, per frame. */
        const val PERIOD_FOLLOW = 0.05f

        /** How long a weak or absent tempo estimate is ridden out before the lock is dropped. */
        const val HOLD_THROUGH_WEAK_MS = 2500L

        /** How far the estimate may sit from the held period and still be followed straight away. */
        const val PERIOD_AGREEMENT = 0.08f

        /** How long a disagreeing estimate must hold before the period jumps to it. */
        const val RELOCK_MS = 3000L

        /** The soonest the next tick may be placed, as a share of a period from now. */
        const val MIN_NEXT_TICK_FRACTION = 0.5f

        /** How many recent onsets the trust decision is made over. */
        const val AGREEMENT_WINDOW = 8

        /** How many of them there must be before the question can be answered at all. */
        const val MIN_AGREEING_ONSETS = 4

        /** Share of them that must have landed on a tick for the clock to drive the flashing. */
        const val MIN_AGREEMENT = 0.5f

        /** How close to a tick an onset counts as landing on it, as a share of a period. */
        const val ONSET_TOLERANCE = 0.15f
    }
}
