package com.example.core.audio

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Roughly where the music is right now, structurally. */
enum class MusicSection { BUILD, DROP, STEADY, BREAKDOWN }

/**
 * What the DSP should do differently this frame, given what the music has been doing.
 *
 * [intensity] can exceed 1: a drop is allowed to overdrive briefly, which is most of what makes it
 * read as a drop.
 */
data class MusicalDynamics(
    /** Where this moment sits in the loudness distribution of the recent past, 0..1. */
    val loudnessPercentile: Float,
    /** Log2 rise of the fast loudness average over the slow one — the trajectory. Diagnostic. */
    val slope: Float,
    val section: MusicSection,
    /** Master response scalar. ~0.45 in a breakdown, 1.0 for a median passage, 1.4 in a drop. */
    val intensity: Float,
    /** The quietest energy that still counts as music, learned from this material. */
    val musicFloor: Float
) {
    /**
     * The noise gate to actually use, given the preset's configured one.
     *
     * Takes the lower of the two, so a quietly-mastered track or a phone across the room still
     * drives a show — that is the D.2 complaint in one line. It can never fall below the learned
     * [musicFloor], which is itself floored at an absolute silence level, so real silence still
     * reads as silence and the idle timeout still fires.
     */
    fun effectiveNoiseGate(configured: Float): Float = min(configured, musicFloor)
}

/**
 * Tier D.2/D.3/D.4: judges loudness relative to the song rather than against fixed thresholds, and
 * says roughly what part of the song this is.
 *
 * ## The problem it exists for
 *
 * Every level in the DSP is judged against absolute numbers — `noiseGateThreshold` gates beat
 * detection, the bass contribution and the idle timer alike. So a track mastered 12dB down, or a
 * phone sitting further from the speaker, produces a dim sluggish show or none at all, and a loud
 * one pins everything at the ceiling. Auto-gain helps with level but is itself a compressor with a 0.75-2.9s time constant, so it strips
 * out the very dynamics this is trying to preserve: measured on a real master, the correlation
 * between the song's loudness and the strip's brightness was +0.03.
 *
 * A distribution answers the question a peak cannot: *how loud is this moment compared with how
 * loud this music usually is.*
 *
 * ## How
 *
 * A decaying histogram over log *passage* energy — 64 bins, each multiplied by `exp(-dt/tau)` every frame and
 * the current bin incremented. Percentile is a cumulative sum over 64 floats, so there is no
 * per-frame sort and no allocation, which is what `visualizer-review-2026-07-22.md` B5 objects to in
 * a literal percentile tracker.
 *
 * Frames below [ABSOLUTE_SILENCE] never enter the histogram. Without that, a pause between tracks
 * would teach it that silence is normal, and the learned floor would sink to meet it — which is
 * exactly how an adaptive gate breaks idle detection.
 *
 * ## Sections
 *
 * The trajectory is how far the passage level has moved over a fixed lag. Rising fast is a BUILD,
 * arriving high after a build that has stopped climbing is a DROP, low and not rising is a
 * BREAKDOWN, everything else is STEADY. Separate enter/leave thresholds and a minimum dwell keep it
 * from flapping between two labels on ordinary bar-to-bar variation.
 *
 * ## Restraint (D.4)
 *
 * [MusicalDynamics.intensity] deliberately goes *below* 1 in the quiet parts rather than only above
 * it in the loud ones. Making choruses brighter alone just raises the average and the show ends up
 * uniformly loud, which is the state Joe described. Silence and restraint are what make the loud
 * moments land, so a breakdown drops to ~0.45 and has to earn its way back up.
 *
 * The scale is centred on 1.0, not below it. An average below 1 is attenuation rather than shaping,
 * and measurably just dims everything.
 *
 * ## Scope
 *
 * The reference lives as long as the processor, which is one audio-engine run — many tracks in a
 * listening session, not one. Persisting it across app restarts was in the sketch and is
 * deliberately not built: a fresh reference converges in about thirty seconds, and a stale one from
 * last week is worse than no reference at all.
 */
class MusicalContext {

    private companion object {
        /** Below this, it is not music, whatever the distribution has learned. Gate/idle depend on it. */
        const val ABSOLUTE_SILENCE = 2.0f

        const val BIN_COUNT = 64
        /** Bin width in log2 units — 0.5 is a third of a stop, finer than any of this needs. */
        const val BIN_WIDTH = 0.5f

        /** ~30s of history: long enough to span a verse and a chorus, short enough to follow a set. */
        const val HISTOGRAM_TAU_MS = 30_000f

        /**
         * Smoothing applied before anything looks at loudness at all.
         *
         * Without it the percentile reads the gap between two kick drums as a breakdown: measured
         * on the structured track, instantaneous frame energy swung between the 4th and 91st
         * percentile *within a bar*, and the classifier flapped BREAKDOWN/STEADY every 1.5s
         * through a perfectly steady intro. Sections are a property of passages, so the passage
         * level is what gets measured.
         */
        const val PASSAGE_TAU_MS = 2_000f

        /**
         * The trajectory is a plain difference over a fixed lag, not the gap between a fast and a
         * slow EMA.
         *
         * Two EMAs were tried first and are the wrong tool: the difference develops slowly (the
         * eight-second build was only recognised as it ended) and then *persists* while the slow
         * average catches up, so a steady chorus kept reading as a build for eight seconds after it
         * arrived. A fixed-lag difference responds within its own lag and returns to zero the moment
         * the level stops moving, which is what "is it rising right now" actually means.
         */
        const val TREND_LAG_MS = 4_000L
        const val TREND_SLOT_MS = 100L
        const val TREND_SLOTS = (TREND_LAG_MS / TREND_SLOT_MS).toInt()

        /** After a drop ends, how long before another may fire. Stops a chorus reading as a drop repeatedly. */
        const val DROP_REFRACTORY_MS = 15_000L

        /** Log2 rise over [TREND_LAG_MS] that counts as building — about a 35% lift in level. */
        const val BUILD_SLOPE = 0.45f
        /**
         * Enter/leave thresholds differ on purpose. With one threshold the label flaps whenever the
         * percentile sits near it — measured on the structured track, a perfectly steady intro
         * alternated BREAKDOWN/STEADY four times in six seconds because it happened to hover at
         * 0.25.
         */
        const val BREAKDOWN_ENTER_PERCENTILE = 0.20f
        const val BREAKDOWN_LEAVE_PERCENTILE = 0.35f
        const val DROP_PERCENTILE = 0.75f
        /** Slope below which a build is over, lower than [BUILD_SLOPE] for the same reason. */
        const val BUILD_LEAVE_SLOPE = 0.15f

        /** How long a drop keeps its overdrive before settling into steady. */
        const val DROP_HOLD_MS = 4_000L
        /** Minimum time in a section before another can be entered, so labels do not flap. */
        const val MIN_DWELL_MS = 1_500L
    }

    private val bins = FloatArray(BIN_COUNT)
    private var binTotal = 0f

    private var passageLog = 0f
    private val trend = FloatArray(TREND_SLOTS)
    private var trendIdx = 0
    private var trendFilled = 0
    private var lastTrendSampleMs = 0L
    private var seeded = false
    private var lastDropEndedMs = Long.MIN_VALUE / 2
    /** Music heard so far, for the bias correction in [emaAlpha]. */
    private var elapsedMs = 0L

    private var section = MusicSection.STEADY
    private var sectionSinceMs = 0L
    private var dropUntilMs = 0L

    /** log2(1 + energy), the domain the histogram and both EMAs work in. */
    private fun logEnergy(energy: Float): Float = (ln((1f + max(0f, energy)).toDouble()) / ln(2.0)).toFloat()

    private fun binFor(logE: Float): Int = (logE / BIN_WIDTH).toInt().coerceIn(0, BIN_COUNT - 1)

    /**
     * Feeds one frame in and reports what to do with it.
     *
     * [dtMs] is the real interval since the previous frame, so the time constants mean the same
     * thing on both capture backends — the 20Hz Visualizer path and the ~43Hz AudioRecord one.
     */
    fun update(totalEnergy: Float, dtMs: Long, nowMs: Long): MusicalDynamics {
        val isMusic = totalEnergy >= ABSOLUTE_SILENCE
        val logE = logEnergy(totalEnergy)

        if (isMusic) {
            if (!seeded) {
                passageLog = logE
                seeded = true
                sectionSinceMs = nowMs
                lastTrendSampleMs = nowMs
            } else {
                elapsedMs += dtMs
                passageLog += (logE - passageLog) * emaAlpha(dtMs, PASSAGE_TAU_MS)
            }

            if (nowMs - lastTrendSampleMs >= TREND_SLOT_MS) {
                trend[trendIdx] = passageLog
                trendIdx = (trendIdx + 1) % TREND_SLOTS
                if (trendFilled < TREND_SLOTS) trendFilled++
                lastTrendSampleMs = nowMs
            }

            // The histogram holds passage levels, not frames, for the same reason: it is the
            // distribution of "how loud is this part of the song", not of individual transients.
            val decay = if (dtMs > 0) exp(-dtMs.toFloat() / HISTOGRAM_TAU_MS) else 1f
            binTotal = 0f
            for (i in bins.indices) {
                bins[i] *= decay
                binTotal += bins[i]
            }
            bins[binFor(passageLog)] += 1f
            binTotal += 1f
        }

        val percentile = if (seeded) percentileOf(passageLog) else 0.5f
        val floor = learnedMusicFloor()
        val nextSection = classify(percentile, nowMs)

        return MusicalDynamics(
            loudnessPercentile = percentile,
            slope = trendSlope(),
            section = nextSection,
            intensity = intensityFor(nextSection, percentile),
            musicFloor = floor
        )
    }

    /**
     * EMA weight for one frame, bias-corrected while the average is younger than its own time
     * constant.
     *
     * The correction is the whole reason the slope works. Seeding an EMA from a single frame anchors
     * it to whatever that frame happened to be, and frame one of a track lands on a downbeat: the
     * 25s average then sat ~0.8 log2 too high for its entire time constant, so the slope read
     * *negative* through an eight-second build and the classifier called the chorus a drop and the
     * breakdown a build. Early on this returns `dt/elapsed`, making the average a true running mean
     * of everything heard so far; it relaxes into the ordinary exponential weight as history
     * accumulates.
     */
    private fun emaAlpha(dtMs: Long, tauMs: Float): Float {
        if (dtMs <= 0) return 0f
        val exponential = (1f - exp(-dtMs.toFloat() / tauMs))
        val runningMean = dtMs.toFloat() / max(dtMs.toFloat(), elapsedMs.toFloat())
        return max(exponential, runningMean).coerceIn(0f, 1f)
    }

    /**
     * How far the passage level has risen over the last [TREND_LAG_MS], in log2 units.
     *
     * Zero until the buffer has filled: reporting a trend from two seconds of history would make
     * every track open with a spurious build.
     */
    private fun trendSlope(): Float {
        if (trendFilled < TREND_SLOTS) return 0f
        return passageLog - trend[trendIdx]
    }

    /** Fraction of recent history quieter than [logE], counting half of its own bin. */
    private fun percentileOf(logE: Float): Float {
        if (binTotal <= 0f) return 0.5f
        val target = binFor(logE)
        var below = 0f
        for (i in 0 until target) below += bins[i]
        return ((below + bins[target] * 0.5f) / binTotal).coerceIn(0f, 1f)
    }

    /**
     * The energy at roughly the 15th percentile of what this material does — its own quiet end,
     * never below [ABSOLUTE_SILENCE].
     */
    private fun learnedMusicFloor(): Float {
        if (binTotal <= 0f) return ABSOLUTE_SILENCE
        var cumulative = 0f
        for (i in bins.indices) {
            cumulative += bins[i]
            if (cumulative / binTotal >= 0.15f) {
                val energy = Math.pow(2.0, (i * BIN_WIDTH).toDouble()).toFloat() - 1f
                return max(ABSOLUTE_SILENCE, energy)
            }
        }
        return ABSOLUTE_SILENCE
    }

    private fun classify(percentile: Float, nowMs: Long): MusicSection {
        if (!seeded) return MusicSection.STEADY

        val slope = trendSlope()
        if (section == MusicSection.DROP && nowMs >= dropUntilMs) lastDropEndedMs = nowMs
        val dropAllowed = nowMs - lastDropEndedMs >= DROP_REFRACTORY_MS
        // Thresholds depend on where we already are — see BREAKDOWN_ENTER/LEAVE.
        val breakdownThreshold =
            if (section == MusicSection.BREAKDOWN) BREAKDOWN_LEAVE_PERCENTILE else BREAKDOWN_ENTER_PERCENTILE
        val buildThreshold = if (section == MusicSection.BUILD) BUILD_LEAVE_SLOPE else BUILD_SLOPE
        val candidate = when {
            // The hold yields to a genuine collapse: if the music drops out mid-hold, the drop is
            // over, whatever the timer says. Otherwise a breakdown arriving two seconds after a
            // drop keeps overdriving into silence.
            nowMs < dropUntilMs &&
                !(percentile <= BREAKDOWN_ENTER_PERCENTILE && slope < 0f) -> MusicSection.DROP
            // A drop is the *arrival*, not the ascent: it needs the climb to have actually stopped
            // (the leave threshold, not the enter one) and the build to have lasted. Without the
            // slope test it fires partway up every build, because a rising level is immediately the
            // loudest thing in the recent distribution and so scores a high percentile; without the
            // *leave* threshold specifically, one wobble of the slope across the enter threshold was
            // enough to fire it two seconds into an eight-second climb.
            percentile >= DROP_PERCENTILE && section == MusicSection.BUILD &&
                slope < BUILD_LEAVE_SLOPE && nowMs - sectionSinceMs >= MIN_DWELL_MS &&
                dropAllowed -> MusicSection.DROP
            slope >= buildThreshold -> MusicSection.BUILD
            percentile <= breakdownThreshold && slope <= 0f -> MusicSection.BREAKDOWN
            else -> MusicSection.STEADY
        }

        if (candidate == section) return section
        // A drop announces itself immediately; everything else has to hold to count, or the label
        // flickers between STEADY and BUILD on ordinary bar-to-bar variation.
        if (candidate != MusicSection.DROP && nowMs - sectionSinceMs < MIN_DWELL_MS) return section

        section = candidate
        sectionSinceMs = nowMs
        if (candidate == MusicSection.DROP) dropUntilMs = nowMs + DROP_HOLD_MS
        return section
    }

    /**
     * The scalar the DSP multiplies its response by.
     *
     * **Centred on 1.0, not below it.** The first version spanned 0.30-1.15 and averaged about 0.7,
     * which is not dynamics but attenuation: measured on real music it dimmed every preset and cut
     * Ambient Chill's brightness range almost in half. A steady passage at the median loudness now
     * scores exactly 1.0 and is therefore untouched; quiet parts go below it and loud parts above.
     *
     * The top of the range can clamp against the preset's own ceiling, which is fine — measured
     * mean brightness across these presets is 0.05-0.13, so there is a great deal of headroom and
     * almost nothing ever reaches the clamp.
     *
     * Spread deliberately wide at the bottom: the point is that a breakdown *looks* like a
     * breakdown, which cannot happen if every section lands within a few percent of the others.
     */
    private fun intensityFor(section: MusicSection, percentile: Float): Float = when (section) {
        MusicSection.BREAKDOWN -> 0.45f + 0.25f * percentile
        // Above STEADY at the same percentile, deliberately: a build should feel like it is going
        // somewhere. An earlier version put it below, which made the run-up to a chorus dimmer than
        // the verse before it — the opposite of the intent.
        MusicSection.BUILD -> 0.85f + 0.50f * percentile
        MusicSection.DROP -> 1.40f
        MusicSection.STEADY -> 0.75f + 0.50f * percentile
    }
}
