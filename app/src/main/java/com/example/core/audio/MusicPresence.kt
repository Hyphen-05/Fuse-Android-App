package com.example.core.audio

import kotlin.math.exp
import kotlin.math.ln

/**
 * Answers "is there music here at all", from the *shape* of the spectrum rather than its level.
 *
 * ## Why level cannot answer it
 *
 * Joe, 2026-08-20: "lights keep flashing after song is finished for a while." Every gate in
 * [AudioDspProcessor] judges loudness — the preset's `noiseGateThreshold`, the learned
 * [MusicalContext] floor, the 10th-percentile spectral floor that zeroes quiet bins. None of them
 * hold when the music stops, for a reason that is measurable: the spectral floor is a percentile of
 * *recent* frames, so a few seconds after the song ends it has adapted down to the room, room tone
 * starts clearing it, and the pipeline treats hiss as material. `SongEndTest` measured the strip
 * still flashing at +0.23 luma **ten seconds** after the audio stopped.
 *
 * Raising the gates cannot fix it. In that same measurement, room tone at a realistic level carried
 * **more** total energy than the music did (5.18M against 4.95M) — any threshold that silences the
 * room also silences the song.
 *
 * ## What does answer it
 *
 * Spectral flatness: the geometric mean of the magnitudes over their arithmetic mean. It is 1.0 for
 * white noise and falls toward 0 for anything tonal, and it is scale-invariant by construction, so
 * it says nothing about how loud the room is. Measured on the same frames that defeated every level
 * gate:
 *
 * | | flatness |
 * |---|---|
 * | synthetic track | 0.116 |
 * | Joe's own master, median | 0.301 |
 * | the same master, 99th percentile | 0.490 |
 * | the same master, worst single frame | 0.605 |
 * | room tone | 0.846 |
 *
 * Only 5 frames in 3875 of real music cleared 0.55, and none of them for anywhere near a second.
 *
 * ## Why it is asymmetric
 *
 * Declaring *silence* takes [sustainMs] of continuously flat spectrum, because a riser, a cymbal
 * wash or a distorted chorus is genuinely broadband for a moment and blanking the strip mid-song
 * would be a far worse bug than the one this fixes. Declaring *music* takes a single frame below
 * [flatExitNoise], so nothing is missed when the next track starts.
 *
 * With music playing this returns true every frame, so the DSP downstream is bit-identical to what
 * it was — it only ever acts on material that has no musical structure in it at all.
 */
class MusicPresence(
    /** Above this, the spectrum looks like noise. Sits well clear of real music's 99th percentile. */
    private val flatEnterNoise: Float = 0.62f,
    /** Below this it is structured again. Hysteresis, so a borderline frame cannot chatter. */
    private val flatExitNoise: Float = 0.55f,
    /** How long the spectrum must stay flat before the show is allowed to stop. */
    private val sustainMs: Long = 1200L
) {
    private var flatSinceMs = 0L
    private var isNoise = false

    /** Last computed flatness, for diagnostics. 1.0 is white noise, near 0 is tonal. */
    var flatness: Float = 0f
        private set

    /**
     * Feeds one frame's magnitude spectrum and returns whether music is present.
     *
     * Must be given the **raw** magnitudes, before [AudioDspProcessor]'s percentile gate zeroes
     * quiet bins: zeroed bins drive the geometric mean to nothing and would make hiss read as the
     * most tonal signal imaginable.
     */
    fun update(magnitude: FloatArray, numBins: Int, nowMs: Long): Boolean {
        flatness = flatnessOf(magnitude, numBins)

        if (flatness > flatEnterNoise) {
            if (flatSinceMs == 0L) flatSinceMs = nowMs
            if (nowMs - flatSinceMs >= sustainMs) isNoise = true
        } else if (flatness < flatExitNoise) {
            flatSinceMs = 0L
            isNoise = false
        }
        return !isNoise
    }

    /** Forgets what it has seen — for a new capture session, where the room may be different. */
    fun reset() {
        flatSinceMs = 0L
        isNoise = false
        flatness = 0f
    }

    private fun flatnessOf(magnitude: FloatArray, numBins: Int): Float {
        // Bin 0 is DC and carries no musical information, but it does carry mic offset, so it would
        // bias both means.
        val last = minOf(numBins, magnitude.size)
        if (last <= 1) return 1f
        var sum = 0.0
        var logSum = 0.0
        var n = 0
        for (i in 1 until last) {
            val m = magnitude[i].toDouble()
            sum += m
            logSum += ln(m + EPSILON)
            n++
        }
        if (n == 0) return 1f
        val arithmetic = sum / n
        if (arithmetic <= 0.0) return 1f
        return (exp(logSum / n) / arithmetic).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        /** Keeps ln() finite on an exactly-zero bin without moving the ratio for any real one. */
        const val EPSILON = 1e-9
    }
}
