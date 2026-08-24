package com.example.core.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Decodes beats from an onset-strength curve with a dynamic Bayesian network over joint
 * tempo-and-phase states — the structure behind madmom's beat tracker, without the neural net that
 * normally supplies its input.
 *
 * ## Why this rather than more autocorrelation
 *
 * [PulseTracker]'s autocorrelation front end scores F=57% against human annotations on 100 GTZAN
 * clips, and sweeping every constant it has moved that by less than three points. What limits it is
 * structural: it makes two independent hard decisions a second — a period, then a phase — and each
 * one throws away every alternative. A passage where the true tempo is briefly the second-best
 * explanation produces a wrong answer with no memory that it was ever close.
 *
 * A DBN never chooses. It carries a probability for *every* (tempo, position-in-beat) pair at once
 * and lets the evidence accumulate, so a tempo that has explained the last eight seconds well
 * survives a bar that fits something else better. That property — hysteresis that comes out of the
 * model rather than being bolted on as smoothing — is precisely what the measurements kept asking
 * for: the beat clock needed a trust gate, the period needed stickiness, the phase needed damping,
 * and all three were hand-built approximations of what a state space does for free.
 *
 * ## The model
 *
 * **States.** One state per (tempo, position) pair. Tempi are the distinct whole-frame periods
 * between [minBpm] and [maxBpm]; positions run 0 until that tempo's period. At 43 frames a second
 * and 40-215bpm that is about 2,000 states, which is a few thousand multiply-adds a frame.
 *
 * **Transitions.** Position advances by one frame, deterministically. Only at the end of a beat may
 * the tempo change, and then it pays `exp(-lambda * |new/old - 1|)` — the standard exponential
 * penalty, which makes small drifts cheap and doubling expensive. This is where the model gets its
 * resistance to octave jumps: halving is not forbidden, it just has to be worth it for several
 * beats running.
 *
 * **Observations.** The first `1/[observationLambda]` of each beat is the beat itself and is
 * explained by the onset activation; the rest of the beat is explained by its complement, spread
 * over the remaining positions. So a frame with a strong onset raises every state that thinks a
 * beat is happening now, and *lowers* every state that thinks one is not.
 *
 * **Decoding.** The forward algorithm, one frame at a time, normalised each frame. Causal by
 * construction: no Viterbi backtrace, no future frames. The beat phase is read as the circular mean
 * of the position distribution of the most probable tempo, and a beat is emitted when that phase
 * wraps — see the comment at the readout for why the argmax is the wrong thing to read here.
 */
class BeatDbn(
    private val frameIntervalMs: Double = 1024.0 * 1000.0 / 44100.0,
    minBpm: Double = 40.0,
    maxBpm: Double = 215.0,
    /** Cost of changing tempo at a beat boundary. Higher is more stubborn. */
    private val transitionLambda: Double = 100.0,
    /** Reciprocal of the share of a beat that counts as "on the beat". */
    private val observationLambda: Int = 16
) {

    /** Period of each tempo, in frames, ascending. */
    private val periods: IntArray

    /** Index into the state arrays where each tempo's positions start. */
    private val offsets: IntArray

    /** Transition weights between tempi at a beat boundary, row-normalised: [from][to]. */
    private val tempoTransition: Array<DoubleArray>

    private val forward: DoubleArray
    private val scratch: DoubleArray

    /** Tempo of the most probable state, 0 before the first frame. */
    var bpm: Float = 0f
        private set

    /**
     * Total probability mass sitting on beat states this frame.
     *
     * A real measure of whether the model believes anything: unlike an autocorrelation peak height,
     * it is a probability, and it collapses when the evidence stops supporting any tempo.
     */
    var beatProbability: Float = 0f
        private set

    private var previousPhase = 0.0
    private var hasPhase = false

    init {
        val minPeriod = (60_000.0 / maxBpm / frameIntervalMs).roundToInt().coerceAtLeast(2)
        val maxPeriod = (60_000.0 / minBpm / frameIntervalMs).roundToInt().coerceAtLeast(minPeriod + 1)
        periods = IntArray(maxPeriod - minPeriod + 1) { minPeriod + it }

        offsets = IntArray(periods.size + 1)
        for (i in periods.indices) offsets[i + 1] = offsets[i] + periods[i]
        val stateCount = offsets[periods.size]

        forward = DoubleArray(stateCount) { 1.0 / stateCount }
        scratch = DoubleArray(stateCount)

        tempoTransition = Array(periods.size) { from ->
            val row = DoubleArray(periods.size)
            var sum = 0.0
            for (to in periods.indices) {
                val ratio = periods[to].toDouble() / periods[from]
                val w = exp(-transitionLambda * abs(ratio - 1.0))
                row[to] = w
                sum += w
            }
            // Row-normalised so the model is a proper distribution and the frame-by-frame
            // normalisation below is only correcting for the observation likelihood.
            if (sum > 0) for (to in periods.indices) row[to] /= sum
            row
        }
    }

    /**
     * Advances one frame and returns true when a beat falls on it.
     *
     * [activation] is the onset strength for this frame, already normalised to 0..1 — the model
     * reads it as a probability that a beat is happening now, so anything outside that range makes
     * the arithmetic meaningless rather than merely inaccurate.
     */
    fun process(activation: Float): Boolean {
        val a = activation.coerceIn(0f, 1f).toDouble()
        java.util.Arrays.fill(scratch, 0.0)

        // Advance every position by one frame, and collect what falls off the end of each beat.
        for (i in periods.indices) {
            val period = periods[i]
            val base = offsets[i]
            for (position in 0 until period - 1) {
                scratch[base + position + 1] += forward[base + position]
            }
            val ending = forward[base + period - 1]
            if (ending > 0.0) {
                val row = tempoTransition[i]
                for (j in periods.indices) {
                    val w = row[j]
                    if (w > TRANSITION_FLOOR) scratch[offsets[j]] += ending * w
                }
            }
        }

        // Observation likelihood: the beat region is explained by the activation, the rest by its
        // complement, shared out so that a beat state and a non-beat state are being asked the same
        // question rather than merely scaled differently.
        var total = 0.0
        var beatMass = 0.0
        var bestIndex = 0
        var best = -1.0
        for (i in periods.indices) {
            val period = periods[i]
            val base = offsets[i]
            val beatStates = (period / observationLambda).coerceAtLeast(1)
            val nonBeatLikelihood = (1.0 - a) / (observationLambda - 1).coerceAtLeast(1)
            for (position in 0 until period) {
                val onBeat = position < beatStates
                val likelihood = if (onBeat) a else nonBeatLikelihood
                val value = scratch[base + position] * likelihood
                scratch[base + position] = value
                total += value
                if (onBeat) beatMass += value
                if (value > best) {
                    best = value
                    bestIndex = base + position
                }
            }
        }

        if (total <= 1e-300) {
            // Every hypothesis has been driven to nothing, which happens on a long silence. Start
            // again from an even spread rather than from whatever numerical dust is left.
            val even = 1.0 / forward.size
            java.util.Arrays.fill(forward, even)
            beatProbability = 0f
            hasPhase = false
            return false
        }

        for (k in forward.indices) forward[k] = scratch[k] / total
        beatProbability = (beatMass / total).toFloat()

        // Read the phase as the *circular mean* of the position distribution, not as the position
        // of the single most probable state.
        //
        // The posterior here is diffuse, because the activation comes from spectral flux rather
        // than from a network trained to spike on beats. With a diffuse posterior the argmax hops
        // between neighbouring tempi from frame to frame — the model is not confused, it is
        // carrying several nearly-equal hypotheses, which is the whole point of it — and each hop
        // can re-enter a beat region that another tempo has just left. Measured, reading the argmax
        // emitted 1.6 beats for every real one, and a refractory to suppress the extras cost more
        // true beats than false ones (recall 69% to 42%). The circular mean moves smoothly because
        // it is a property of the whole distribution rather than of its single tallest point.
        var tempoMass = 0.0
        var tempoIndex = 0
        for (i in periods.indices) {
            var mass = 0.0
            for (position in 0 until periods[i]) mass += scratch[offsets[i] + position]
            if (mass > tempoMass) {
                tempoMass = mass
                tempoIndex = i
            }
        }

        val period = periods[tempoIndex]
        val base = offsets[tempoIndex]
        var sin = 0.0
        var cos = 0.0
        for (position in 0 until period) {
            val angle = 2.0 * Math.PI * position / period
            val w = scratch[base + position]
            sin += w * kotlin.math.sin(angle)
            cos += w * kotlin.math.cos(angle)
        }
        bpm = (60_000.0 / (period * frameIntervalMs)).toFloat()

        var isBeat = false
        if (sin != 0.0 || cos != 0.0) {
            var phase = kotlin.math.atan2(sin, cos) / (2.0 * Math.PI)
            if (phase < 0) phase += 1.0
            // The phase advances by 1/period each frame and wraps at the beat. A wrap is a large
            // backward step, which is what distinguishes it from ordinary advance.
            if (hasPhase && phase < previousPhase - WRAP_THRESHOLD) isBeat = true
            previousPhase = phase
            hasPhase = true
        }
        return isBeat
    }

    fun reset() {
        val even = 1.0 / forward.size
        java.util.Arrays.fill(forward, even)
        hasPhase = false
        previousPhase = 0.0
        bpm = 0f
        beatProbability = 0f
    }

    private fun tempoOf(state: Int): Int {
        // Binary search over the offsets: which tempo's block does this state fall in.
        var low = 0
        var high = periods.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (offsets[mid] <= state) low = mid else high = mid - 1
        }
        return low
    }

    private companion object {
        /** Transitions below this are not worth the multiply — the penalty has already killed them. */
        const val TRANSITION_FLOOR = 1e-6

        /** How far the phase must jump backward to count as a wrap rather than as jitter. */
        const val WRAP_THRESHOLD = 0.5
    }
}
