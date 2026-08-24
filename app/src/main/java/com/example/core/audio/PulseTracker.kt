package com.example.core.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Finds the beat: a causal tempo-and-phase tracker built to replace the estimate underneath the
 * flashing, not the flashing itself.
 *
 * ## Why a second tracker exists
 *
 * Measured on 100 GTZAN clips with human beat annotations (`GtzanBeatAccuracyTest`), the shipped
 * visualiser scores F=53%, and on 80 of those 100 clips its flashes fit the *double* grid better
 * than the true one. Three different ways of suppressing the extra flashes were built and measured;
 * every one traded recall for precision at break-even or worse. That result is what points here:
 * the policy on top was never the problem, the tempo and phase estimate underneath it was.
 *
 * [BeatDetector] is left exactly as it is. It carries a lot of tuning and a lot of history, and the
 * honest way to find out whether a different front end is better is to build one and score both.
 *
 * ## What it does
 *
 * Three stages, each the standard choice for its job:
 *
 *  1. **Onset strength — SuperFlux.** Log-compressed magnitudes, differenced against a
 *     frequency-maximum-filtered earlier frame, half-wave rectified and summed. The maximum filter
 *     is the part that matters on real music: it stops vibrato and portamento registering as onsets,
 *     which is most of what a plain spectral flux gets wrong on anything with a voice or a string
 *     section in it.
 *  2. **Tempo — autocorrelation with a comb template and a log-normal prior.** The prior is the
 *     standard defence against octave errors, and octave errors are this codebase's measured
 *     failure. Scoring a candidate period by *comb* — the sum of the autocorrelation at that lag and
 *     its multiples — rather than by the bare autocorrelation is the other half: the true beat
 *     period has energy at 2x and 3x, while the half-period impostor does not.
 *  3. **Phase — comb cross-correlation over the recent window.** Once the period is known, the
 *     phase that best lines a pulse train up with the onset curve is a search over one period.
 *
 * All three run on a rolling window, so the tracker is causal: it never looks at audio the strip has
 * not already played.
 */
class PulseTracker(
    /** Milliseconds between analysis frames. 1024 samples at 44.1kHz by default. */
    private val frameIntervalMs: Double = 1024.0 * 1000.0 / 44100.0,
    /** See [OCTAVE_PREFER_LONGER]. Exposed so the corpus can be swept for it. */
    private val octavePreferLonger: Double = OCTAVE_PREFER_LONGER,
    /** Share of a phase disagreement applied per estimate. See [applyPhase]. */
    private val phaseCorrection: Double = PHASE_CORRECTION,
    /** How far a new period may sit from the held one before it is treated as a tempo change. */
    private val periodAgreement: Double = PERIOD_AGREEMENT,
    /** Whether the onset curve is adaptively thresholded before the autocorrelation. */
    private val adaptiveThreshold: Boolean = false,
    /** How many frames of onset curve the tempo estimate looks at. */
    private val acfWindow: Int = ACF_WINDOW,
    /** Width of the log-normal tempo prior, in octaves. */
    private val priorWidthOctaves: Double = PRIOR_WIDTH_OCTAVES,
    /**
     * How many frequency bands the onset curve is kept in.
     *
     * At 1 the whole spectrum is one curve, which is what most of the literature's simplest
     * trackers do. Above 1 each band is autocorrelated separately and the results summed, so a
     * kick drum's periodicity is not diluted by a vocal line that has none — the bands are split on
     * a log scale, because that is how the interesting content is distributed.
     */
    private val bands: Int = 2,
    /**
     * Which decoder turns the onset curve into beats.
     *
     * The curve is the same either way, which is the point of the switch — it is the only way to
     * find out whether the front end or the decoder is what limits accuracy.
     */
    private val decoder: Decoder = Decoder.AUTOCORRELATION,
    /** Window over which onset strength is normalised into a 0..1 activation for the DBN. */
    private val activationWindow: Int = ACTIVATION_WINDOW,
    /** How many standard deviations above the local mean counts as a full-strength onset. */
    private val activationScale: Double = ACTIVATION_SCALE,
    /** DBN only: cost of changing tempo at a beat boundary. */
    private val transitionLambda: Double = 100.0,
    /** DBN only: reciprocal of the share of a beat that counts as on the beat. */
    private val observationLambda: Int = 16,
    /**
     * DBN only: whether the activation is peak-picked before the decoder sees it.
     *
     * The DBN's observation model asks "is a beat happening right now", and the answer it expects
     * looks like what a trained network emits — near zero almost everywhere, near one on beats.
     * A raw flux z-score is nothing like that: it is high at *every* onset, so the model gets
     * equally strong evidence for a beat on the offbeat hat as on the beat.
     */
    private val peakPickedActivation: Boolean = false
) {

    enum class Decoder { AUTOCORRELATION, DBN }

    /** Current tempo estimate, 0 before there is one. */
    var bpm: Float = 0f
        private set

    /** 0..1, how peaked the tempo evidence is. */
    var confidence: Float = 0f
        private set

    /**
     * How steady the tracker's own opinion has been, 0..1.
     *
     * Distinct from [confidence], which measures how peaked the tempo evidence is *this* estimate
     * and measured almost useless as a guide to whether the answer is right (53% mean F in the
     * lowest confidence bucket against 65% in the highest — barely a signal). Steadiness is a
     * different question: a tracker that has been saying the same thing for several seconds, and
     * has not had to jump its phase, is usually saying it because it is true.
     */
    var stability: Float = 0f
        private set

    /** Onset strength for the frame just processed, after normalisation. */
    var onsetStrength: Float = 0f
        private set

    private var previousLog: FloatArray? = null
    private var frameIndex = 0

    private val dbn = if (decoder == Decoder.DBN) {
        BeatDbn(
            frameIntervalMs = frameIntervalMs,
            transitionLambda = transitionLambda,
            observationLambda = observationLambda
        )
    } else {
        null
    }

    /** Rolling mean and mean-square of onset strength, for the activation normalisation. */
    private var activationMean = 0.0
    private var activationSquareMean = 0.0
    private var previousFlux = 0f
    private var risingFlux = false

    /** The 0..1 activation the DBN was last given. */
    var activation: Float = 0f
        private set

    /** Rolling onset-strength curves, one per band. 512 frames is ~12s. */
    private val odf = Array(bands) { FloatArray(HISTORY) }
    private var odfCount = 0

    /** Bin index where each band starts, plus a final entry for the end. Log-spaced. */
    private val bandEdges: IntArray by lazy(LazyThreadSafetyMode.NONE) {
        IntArray(bands + 1) { i ->
            if (i == 0) 1
            else (2.0.pow(ln(512.0) / ln(2.0) * i / bands)).roundToInt().coerceIn(2, 512)
        }
    }

    private var periodFrames = 0.0
    private var phaseFrame = 0.0
    private var lastTempoFrame = -TEMPO_INTERVAL_FRAMES
    private var lastPeriodForStability = 0.0
    private var instability = 1.0
    private var nextBeatFrame = Double.MAX_VALUE

    /**
     * Feeds one frame of magnitude spectrum and returns true when a beat falls on it.
     *
     * [magnitude] must be raw magnitudes, not gated or zeroed — the whole point of the log
     * compression below is to give quiet detail a voice, and a pre-gated spectrum has already
     * thrown it away.
     */
    fun process(magnitude: FloatArray, numBins: Int, nowMs: Long): Boolean {
        val flux = superFlux(magnitude, minOf(numBins, magnitude.size))
        for (b in 0 until bands) odf[b][frameIndex % HISTORY] = flux[b]
        if (odfCount < HISTORY) odfCount++
        onsetStrength = flux.sum()

        val summedFlux = onsetStrength
        var isBeat = false

        if (decoder == Decoder.DBN) {
            val dbnDecoder = dbn!!
            activation = normaliseActivation(summedFlux)
            isBeat = dbnDecoder.process(activation)
            bpm = dbnDecoder.bpm
            confidence = dbnDecoder.beatProbability
            // The DBN carries no single period, so steadiness is read off the tempo it currently
            // considers most likely rather than off a fresh estimate.
            if (frameIndex - lastTempoFrame >= TEMPO_INTERVAL_FRAMES) {
                lastTempoFrame = frameIndex
                if (bpm > 0f) updateStability(60_000.0 / bpm / frameIntervalMs)
            }
        } else {
            if (frameIndex - lastTempoFrame >= TEMPO_INTERVAL_FRAMES && odfCount >= MIN_FRAMES_FOR_TEMPO) {
                lastTempoFrame = frameIndex
                estimateTempoAndPhase()
            }
            if (periodFrames > 0.0 && frameIndex >= nextBeatFrame) {
                isBeat = true
                nextBeatFrame += periodFrames
                // A stalled or skipped frame must not produce a burst of catch-up beats.
                if (nextBeatFrame <= frameIndex) nextBeatFrame = frameIndex + periodFrames
            }
        }

        frameIndex++
        return isBeat
    }

    fun reset() {
        previousLog = null
        frameIndex = 0
        odfCount = 0
        periodFrames = 0.0
        phaseFrame = 0.0
        lastTempoFrame = -TEMPO_INTERVAL_FRAMES
        nextBeatFrame = Double.MAX_VALUE
        bpm = 0f
        confidence = 0f
        stability = 0f
        instability = 1.0
        lastPeriodForStability = 0.0
    }

    // --- stage 1: onset strength -------------------------------------------------------------

    /**
     * SuperFlux: the positive change in log magnitude against a frequency-maximum-filtered previous
     * frame.
     *
     * The maximum filter is what separates this from plain spectral flux. A note with vibrato, or a
     * singer sliding between pitches, moves energy between neighbouring bins every frame; plain flux
     * reads each of those movements as an onset and the resulting curve is a mess of false peaks
     * exactly on the material — vocals, strings — where the beat is hardest to find anyway. Taking
     * the maximum over a few neighbouring bins of the *earlier* frame before differencing means a
     * small frequency movement has nothing to contribute.
     */
    private fun superFlux(magnitude: FloatArray, bins: Int): FloatArray {
        val current = FloatArray(bins)
        for (k in 0 until bins) {
            // Log compression, the standard log(1 + gamma*x). Without it the loudest few bins own
            // the curve and a quiet snare cannot register at all next to a bass note.
            current[k] = ln(1.0 + LOG_GAMMA * magnitude[k]).toFloat()
        }

        val previous = previousLog
        previousLog = current
        val out = FloatArray(bands)
        if (previous == null || previous.size != bins) return out

        for (k in 0 until bins) {
            var maxPrevious = previous[k]
            for (d in 1..MAX_FILTER_BINS) {
                if (k - d >= 0) maxPrevious = max(maxPrevious, previous[k - d])
                if (k + d < bins) maxPrevious = max(maxPrevious, previous[k + d])
            }
            val diff = current[k] - maxPrevious
            if (diff > 0f) out[bandOf(k, bins)] += diff
        }
        return out
    }

    private fun bandOf(bin: Int, bins: Int): Int {
        if (bands == 1) return 0
        for (b in 0 until bands) {
            if (bin < bandEdges[b + 1]) return b
        }
        return bands - 1
    }

    /**
     * Turns raw onset strength into something that can be read as a probability.
     *
     * The DBN's observation model treats the activation as "how likely is it that a beat is
     * happening right now", so the scale has to be meaningful and local: absolute flux varies by
     * orders of magnitude between a quiet folk recording and a limited master, and a fixed mapping
     * would hand the model near-zero activations for one and saturated ones for the other.
     * Standardising against a rolling mean and spread makes it the same question in both.
     */
    private fun normaliseActivation(flux: Float): Float {
        val alpha = 1.0 / activationWindow
        activationMean += (flux - activationMean) * alpha
        activationSquareMean += (flux * flux - activationSquareMean) * alpha
        val variance = (activationSquareMean - activationMean * activationMean).coerceAtLeast(0.0)
        val spread = kotlin.math.sqrt(variance)
        if (spread < 1e-6) return 0f
        val z = (flux - activationMean) / (spread * activationScale)
        val strength = z.coerceIn(0.0, 1.0).toFloat()
        if (!peakPickedActivation) return strength

        // Causal peak picking: report strength on the frame the curve stops rising, and nothing on
        // the way up or down. One frame of delay (23ms), which is well inside the tolerance a beat
        // is judged by, in exchange for an activation that means "an onset happened here" rather
        // than "energy is currently changing".
        val wasRising = risingFlux
        risingFlux = flux > previousFlux
        val peaked = wasRising && !risingFlux
        previousFlux = flux
        return if (peaked) strength else 0f
    }

    // --- stages 2 and 3: tempo and phase ------------------------------------------------------

    private fun estimateTempoAndPhase() {
        val window = minOf(odfCount, acfWindow)
        if (window < MIN_FRAMES_FOR_TEMPO) return

        // Most recent `window` frames of each band, oldest first, mean removed. Removing the mean
        // is what makes the autocorrelation measure periodicity rather than loudness.
        val perBand = Array(bands) { FloatArray(window) }
        for (b in 0 until bands) {
            var bandSum = 0.0
            for (i in 0 until window) {
                val at = frameIndex - window + 1 + i
                val v = odf[b][((at % HISTORY) + HISTORY) % HISTORY]
                perBand[b][i] = v
                bandSum += v
            }
            val bandMean = (bandSum / window).toFloat()
            for (i in 0 until window) perBand[b][i] -= bandMean
        }

        // The summed curve is what the phase is read from: phase is a property of the music, not of
        // a band, and summing gives the strongest evidence for it.
        val x = FloatArray(window)
        var sum = 0.0
        for (i in 0 until window) {
            var v = 0f
            for (b in 0 until bands) v += perBand[b][i]
            x[i] = v
            sum += v
        }
        // Adaptive thresholding before the autocorrelation, which is the standard conditioning step
        // and was missing: subtract a short moving average and keep only what is above it.
        //
        // Without it the curve carries the arrangement's loudness contour as well as its onsets, and
        // the autocorrelation spends its dynamic range on the fact that the chorus is louder than
        // the verse rather than on the spacing of the hits. Half-wave rectifying afterwards is what
        // makes it a train of peaks - which is the only thing an autocorrelation should be asked to
        // find a period in.
        val smoothed = if (adaptiveThreshold) FloatArray(window) else FloatArray(0)
        if (adaptiveThreshold) {
        val half = (SMOOTHING_FRAMES / 2).coerceAtLeast(1)
        var running = 0.0
        var count = 0
        for (i in 0 until window) {
            // Rolling sum over [i-half, i+half], grown and shrunk rather than recomputed.
            if (i == 0) {
                for (j in 0..minOf(half, window - 1)) { running += x[j]; count++ }
            } else {
                val add = i + half
                if (add < window) { running += x[add]; count++ }
                val drop = i - half - 1
                if (drop >= 0) { running -= x[drop]; count-- }
            }
            smoothed[i] = (running / count).toFloat()
        }
        for (i in 0 until window) x[i] = max(0f, x[i] - smoothed[i])
        }

        var sum2 = 0.0
        for (v in x) sum2 += v
        val mean = (sum2 / window).toFloat()
        for (i in 0 until window) x[i] -= mean

        var energy = 0.0
        for (v in x) energy += (v * v).toDouble()
        if (energy < 1e-9) {
            confidence = 0f
            return
        }

        val minLag = (MIN_PERIOD_MS / frameIntervalMs).roundToInt().coerceAtLeast(2)
        val maxLag = (MAX_PERIOD_MS / frameIntervalMs).roundToInt().coerceAtMost(window / 2)
        if (maxLag <= minLag) return

        // One autocorrelation per band, summed after normalising each. A band with no rhythmic
        // content contributes a flat curve rather than swamping one that does.
        val acf = DoubleArray(maxLag + 1)
        for (b in 0 until bands) {
            val band = perBand[b]
            var bandEnergy = 0.0
            for (v in band) bandEnergy += (v * v).toDouble()
            if (bandEnergy < 1e-9) continue
            for (lag in minLag..maxLag) {
                var acc = 0.0
                for (i in lag until window) acc += (band[i] * band[i - lag]).toDouble()
                acf[lag] += acc / bandEnergy
            }
        }
        if (bands > 1) for (lag in minLag..maxLag) acf[lag] /= bands

        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var scoreSum = 0.0
        var scoreCount = 0
        for (lag in minLag..maxLag) {
            val score = acf[lag] * tempoPrior(lag)
            scoreSum += score
            scoreCount++
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return
        bestLag = correctOctave(acf, bestLag, maxLag)

        // Parabolic interpolation around the winner, so the period is not quantised to whole frames
        // — at 23ms a frame is 5% of a 200bpm beat, which is a phase error of a whole beat in twenty.
        val refined = interpolatePeak(acf, bestLag, minLag, maxLag)

        val meanScore = if (scoreCount > 0) scoreSum / scoreCount else 0.0
        confidence = if (meanScore > 1e-9) {
            ((bestScore / meanScore - 1.0) / PEAKINESS_FOR_FULL_CONFIDENCE).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

        updateStability(refined)
        applyPeriod(refined)
        bpm = (60_000.0 / (periodFrames * frameIntervalMs)).toFloat()
        estimatePhase(x, window, periodFrames)
    }

    /**
     * Walks the winner up to a longer period while the longer one is nearly as well supported.
     *
     * The measured failure of this app is halving — flashes fitting the double grid on 80 of 100
     * real clips — and halving is what a bare autocorrelation does whenever anything sits on the
     * offbeat, because a hat between the beats puts a peak at P/2 that can edge out the peak at P.
     *
     * A comb that sums the autocorrelation at multiples of the candidate, which is the usual first
     * answer, does not fix this and measured F=47%: the true period is *itself* a multiple of the
     * half-period impostor, so the comb rewards the impostor too. What separates them is asymmetric.
     * If a candidate's double is nearly as strong as the candidate, then the candidate is very
     * likely the subdivision and the double is the beat — a real pulse at P implies a peak at 2P,
     * but a pulse at P does not imply a comparable peak at P/2 unless the offbeat is as loud as the
     * beat, which is rare. So the test only ever runs upward, and requires the longer period to be
     * *nearly* as strong rather than stronger.
     */
    private fun correctOctave(acf: DoubleArray, lag: Int, maxLag: Int): Int {
        var current = lag
        while (true) {
            val doubled = current * 2
            if (doubled > maxLag) return current
            val here = acf[current] * tempoPrior(current)
            val there = acf[doubled] * tempoPrior(doubled)
            if (there < octavePreferLonger * here) return current
            current = doubled
        }
    }

    /**
     * A log-normal preference for ordinary tempi, centred at 120bpm.
     *
     * Standard in every classical tracker, and it earns its place for the same reason a listener
     * does not hear a 40bpm pulse in a dance track: both readings fit the audio, and one of them is
     * how people actually count.
     */
    private fun tempoPrior(lag: Int): Double {
        val periodMs = lag * frameIntervalMs
        val octavesFromCentre = log2(periodMs / PRIOR_CENTRE_MS)
        return exp(-0.5 * (octavesFromCentre / priorWidthOctaves).pow(2))
    }

    private fun interpolatePeak(acf: DoubleArray, lag: Int, minLag: Int, maxLag: Int): Double {
        if (lag <= minLag || lag >= maxLag) return lag.toDouble()
        val a = acf[lag - 1]
        val b = acf[lag]
        val c = acf[lag + 1]
        val denominator = a - 2 * b + c
        if (abs(denominator) < 1e-12) return lag.toDouble()
        val offset = 0.5 * (a - c) / denominator
        return lag + offset.coerceIn(-0.5, 0.5)
    }

    /**
     * Where in the period the beats sit: the offset whose pulse train collects the most onset
     * strength over the recent window.
     *
     * Searched at whole-frame resolution and then refined the same way the period is, because the
     * phase is what the ear judges — a correct tempo on the wrong phase is worse than useless, it
     * flashes confidently on every offbeat.
     */
    private fun estimatePhase(x: FloatArray, window: Int, period: Double) {
        val periodInt = period.roundToInt().coerceAtLeast(2)
        var bestOffset = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (offset in 0 until periodInt) {
            var score = 0.0
            var pulse = 0
            var position = window - 1.0 - offset
            while (position >= 0 && pulse < PHASE_PULSES) {
                // A frame either side, because the period is fractional and the pulse train drifts
                // off the frame grid as it walks back; without this the older pulses in the window
                // score against whatever happens to be next to the beat rather than the beat.
                val at = position.roundToInt()
                var best = x[at].toDouble()
                if (at - 1 >= 0) best = max(best, x[at - 1].toDouble())
                if (at + 1 < window) best = max(best, x[at + 1].toDouble())
                // Recent pulses count for more: the phase now is what the next beat is predicted
                // from, and a bar four seconds ago may be a bar of a different phrase.
                score += best * PHASE_RECENCY.pow(pulse)
                position -= period
                pulse++
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = offset
            }
        }

        // bestOffset counts back from the newest frame, so the most recent beat was that many
        // frames ago and the next is one period after it.
        val lastBeatFrame = frameIndex - bestOffset.toDouble()
        var proposed = lastBeatFrame + period
        while (proposed <= frameIndex) proposed += period
        applyPhase(proposed, period)
    }

    /**
     * Tracks how much the estimate moves from one look to the next, as a smoothed relative change.
     *
     * Deliberately measured on the *raw* estimate rather than the smoothed period: the smoothing in
     * [applyPeriod] exists to hide exactly this wobble from the output, so reading steadiness after
     * it would be reading the smoother, not the music.
     */
    private fun updateStability(candidate: Double) {
        if (lastPeriodForStability > 0.0) {
            val change = abs(candidate - lastPeriodForStability) / lastPeriodForStability
            instability += (change - instability) * STABILITY_FOLLOW
            stability = (1.0 - instability / STABILITY_FULL_SCALE).coerceIn(0.0, 1.0).toFloat()
        }
        lastPeriodForStability = candidate
    }

    /**
     * Takes a new period, but only gradually unless it is a real tempo change.
     *
     * The estimate is recomputed twice a second and it is never exactly the same number twice.
     * Writing each one straight in moves every future beat, which is why continuity — the longest
     * unbroken run of correctly-hit beats — measured only 24% while the F-measure was already 55%:
     * the tracker was right on average and unsteady from bar to bar, and unsteady is what a person
     * actually sees.
     */
    private fun applyPeriod(candidate: Double) {
        if (periodFrames <= 0.0) {
            periodFrames = candidate
            return
        }
        val disagreement = abs(candidate - periodFrames) / periodFrames
        periodFrames = if (disagreement > periodAgreement) {
            // A jump this large is a different tempo, not noise on the same one - take it whole.
            candidate
        } else {
            periodFrames + (candidate - periodFrames) * PERIOD_FOLLOW
        }
    }

    /**
     * Nudges the next beat toward the newly estimated phase instead of jumping to it.
     *
     * Same reasoning as [applyPeriod], and the same measured symptom. A phase estimate that
     * disagrees by more than half a period is not a correction, it is a different reading of where
     * the beat is - taken whole, since easing into it would spend several bars flashing between the
     * two.
     */
    private fun applyPhase(proposed: Double, period: Double) {
        if (nextBeatFrame == Double.MAX_VALUE || period <= 0.0) {
            nextBeatFrame = proposed
            return
        }
        var error = proposed - nextBeatFrame
        while (error > period / 2) error -= period
        while (error < -period / 2) error += period
        nextBeatFrame = if (abs(error) > period * PHASE_JUMP_THRESHOLD) {
            proposed
        } else {
            nextBeatFrame + error * phaseCorrection
        }
        while (nextBeatFrame <= frameIndex) nextBeatFrame += period
    }

    private companion object {
        /** ~12s of onset curve at the default frame interval. */
        const val HISTORY = 512

        /** How much of it the tempo estimate looks at: ~8s, four bars at 120bpm. */
        const val ACF_WINDOW = 344

        /** Enough curve to autocorrelate at all: ~3s. */
        const val MIN_FRAMES_FOR_TEMPO = 129

        /** How often the tempo is re-estimated, ~0.5s. */
        const val TEMPO_INTERVAL_FRAMES = 21

        /** 40-200bpm, the range `BeatDetector` already searches. */
        const val MIN_PERIOD_MS = 300.0
        const val MAX_PERIOD_MS = 1500.0

        /** log(1 + gamma*x) compression. */
        const val LOG_GAMMA = 1000.0

        /** Neighbouring bins the maximum filter spans, either side. */
        const val MAX_FILTER_BINS = 3

        /**
         * How well supported a doubled period must be, relative to the candidate, to be taken
         * instead of it. Below 1.0 because the doubled period only has to be *competitive* - see
         * [correctOctave] for why the test is deliberately asymmetric.
         */
        const val OCTAVE_PREFER_LONGER = 0.95

        /** Centre and width of the log-normal tempo prior. 500ms is 120bpm. */
        const val PRIOR_CENTRE_MS = 500.0
        const val PRIOR_WIDTH_OCTAVES = 0.6

        /** How many pulses back the phase search accumulates over. */
        const val PHASE_PULSES = 16

        /** Weight decay per pulse going back, so recent evidence dominates the phase. */
        const val PHASE_RECENCY = 0.92

        /** How far above the mean the winning tempo score must sit to count as fully confident. */
        const val PEAKINESS_FOR_FULL_CONFIDENCE = 4.0

        /** Share of a phase disagreement applied per estimate, when it is small enough to be one. */
        const val PHASE_CORRECTION = 0.7

        /** Beyond this share of a period, a phase estimate is a different reading, not a nudge. */
        const val PHASE_JUMP_THRESHOLD = 0.3

        /** How far a new period may sit from the held one and still be eased into. */
        const val PERIOD_AGREEMENT = 0.1

        /** Share of a small period disagreement applied per estimate. */
        const val PERIOD_FOLLOW = 0.3

        /** Frames the activation normalisation averages over. ~2s. */
        const val ACTIVATION_WINDOW = 86

        /** Spreads above the local mean that count as a full-strength onset. */
        const val ACTIVATION_SCALE = 4.0

        /** How fast the steadiness measure follows a change in the estimate. */
        const val STABILITY_FOLLOW = 0.25

        /** The relative wobble that counts as completely unsteady. */
        const val STABILITY_FULL_SCALE = 0.08

        /** Span of the adaptive-threshold moving average, in frames. ~0.35s. */
        const val SMOOTHING_FRAMES = 15
    }
}
