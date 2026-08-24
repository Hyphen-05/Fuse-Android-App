package com.example.feel

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Synthetic material with beat times known exactly, for measuring how often the visualiser flashes
 * on the beat.
 *
 * Real music cannot answer this without annotations, and annotated corpora ship annotations rather
 * than audio (the same wall the Tier D work hit). Generated material sidesteps it: the beat grid is
 * an input, not an estimate, so accuracy is measurable to the millisecond on the first run.
 *
 * What matters is *what is in the corpus*. A four-on-the-floor loop tells you almost nothing — every
 * tracker gets it. Each track here is a specific way real music defeats naive detection:
 *
 *  - beats that carry no onset at all (syncopation, the pad track)
 *  - onsets that are not beats (offbeat hats, swung eighths)
 *  - tempo that is a moving target (the live-drift track)
 *  - metrical ambiguity, where half or double the tempo is an equally good reading
 *  - level changes big enough to move any adaptive threshold mid-track
 *
 * **Ground truth is the metrical beat, not the drum hits.** On the syncopated and pad tracks most
 * beats have no transient on them, which is exactly the point: a listener still feels them.
 */
object BeatCorpus {

    private const val SR = OfflineAudio.SAMPLE_RATE

    /** One generated track: the audio, the beats a listener would tap, and what it is for. */
    class Track(
        val name: String,
        val pcm: ShortArray,
        val beatsMs: List<Long>,
        val bpm: Double,
        val what: String
    )

    fun all(): List<Track> = listOf(
        fourOnTheFloor(),
        slowBallad(),
        drumAndBass(),
        swungShuffle(),
        syncopatedFunk(),
        padNoDrums(),
        liveDrift(),
        quietThenLoud(),
        waltz(),
        halftimeFeel()
    )

    // --- the tracks -------------------------------------------------------------------------

    /** The easy case, and the control: if this is not near perfect nothing else means anything. */
    private fun fourOnTheFloor() = build(
        name = "four-on-the-floor 128",
        bpm = 128.0,
        seconds = 30.0,
        what = "control - a kick on every beat, a hat between"
    ) { t, _, phase ->
        var s = pad(t, 0.10)
        if (phase < 0.5) s += kick(phase, 0.9)
        if (at(phase, 0.5)) s += hat(phase - 0.5, 0.25)
        s
    }

    /** Slow and soft: the tempo sits near the bottom of the search range and onsets are gentle. */
    private fun slowBallad() = build(
        name = "ballad 68",
        bpm = 68.0,
        seconds = 30.0,
        what = "slow, soft onsets, near the bottom of the tempo range"
    ) { t, beat, phase ->
        var s = pad(t, 0.16)
        if (phase < 0.6) s += softKick(phase, 0.5)
        if (beat % 2 == 1 && phase < 0.3) s += snare(phase, 0.35)
        s
    }

    /** Fast enough that half-time is a defensible reading - the classic octave error. */
    private fun drumAndBass() = build(
        name = "drum and bass 174",
        bpm = 174.0,
        seconds = 30.0,
        what = "metrically ambiguous - 87bpm is an equally good answer"
    ) { t, beat, phase ->
        var s = pad(t, 0.08) + bassLine(t, 174.0, 0.25)
        if (beat % 4 == 0 && phase < 0.5) s += kick(phase, 0.9)
        if (beat % 4 == 2 && phase < 0.4) s += snare(phase, 0.7)
        if (at(phase, 0.5)) s += hat(phase - 0.5, 0.2)
        s
    }

    /** Swung eighths: the offbeat lands two thirds through the beat, not half, and it is loud. */
    private fun swungShuffle() = build(
        name = "swung shuffle 96",
        bpm = 96.0,
        seconds = 30.0,
        what = "triplet feel - the loudest offbeats are two thirds through the beat"
    ) { t, beat, phase ->
        var s = pad(t, 0.10)
        if (phase < 0.5) s += kick(phase, 0.75)
        if (at(phase, 2.0 / 3.0)) s += hat(phase - 2.0 / 3.0, 0.45)
        if (beat % 2 == 1 && phase < 0.3) s += snare(phase, 0.6)
        s
    }

    /** Most beats carry nothing; the kicks are deliberately off them. */
    private fun syncopatedFunk() = build(
        name = "syncopated funk 104",
        bpm = 104.0,
        seconds = 30.0,
        what = "beats with no onset on them - the kick pattern is off the grid"
    ) { t, beat, phase ->
        var s = pad(t, 0.10) + bassLine(t, 104.0, 0.3)
        val inBar = beat % 4
        // Kicks on 1, the "and" of 2, and a sixteenth into 4 - a real funk pattern, not a grid.
        if (inBar == 0 && phase < 0.5) s += kick(phase, 0.9)
        if (inBar == 1 && at(phase, 0.5)) s += kick(phase - 0.5, 0.7)
        if (inBar == 3 && at(phase, 0.25)) s += kick(phase - 0.25, 0.6)
        if (inBar == 2 && phase < 0.3) s += snare(phase, 0.7)
        s
    }

    /** No percussion at all: the only onsets are note attacks on a soft instrument. */
    private fun padNoDrums() = build(
        name = "pad, no drums 90",
        bpm = 90.0,
        seconds = 30.0,
        what = "no percussion - soft note attacks are the only evidence of tempo"
    ) { t, beat, phase ->
        val note = 220.0 * when (beat % 4) { 0 -> 1.0; 1 -> 1.25; 2 -> 1.5; else -> 1.335 }
        // A slow attack, the hardest kind of onset to see: ~40ms rise, not a transient.
        val env = if (phase < 0.9) minOf(1.0, phase * 25.0) * exp(-phase * 1.6) else 0.0
        pad(t, 0.08) + 0.5 * env * sin(2 * PI * note * t)
    }

    /** Tempo wanders the way a band does - no click track, plus or minus 8bpm over the take. */
    private fun liveDrift(): Track {
        val seconds = 30.0
        val out = ShortArray((seconds * SR).toInt())
        val beats = ArrayList<Long>()
        var t = 0.0
        var beatIndex = 0
        while (t < seconds) {
            val bpm = 100.0 + 8.0 * sin(2 * PI * t / 22.0)
            val period = 60.0 / bpm
            beats.add((t * 1000).toLong())
            val start = (t * SR).toInt()
            val end = minOf(((t + period) * SR).toInt(), out.size)
            for (n in start until end) {
                val time = n.toDouble() / SR
                val phase = (n - start).toDouble() / (end - start).coerceAtLeast(1)
                var s = pad(time, 0.10)
                if (phase < 0.5) s += kick(phase, 0.85)
                if (beatIndex % 2 == 1 && phase < 0.3) s += snare(phase, 0.6)
                out[n] = clip(s)
            }
            t += period
            beatIndex++
        }
        return Track("live drift 100+-8", out, beats, 100.0, "no click track - the tempo moves under you")
    }

    /** A quiet intro and a loud chorus, so every adaptive threshold has to move mid-track. */
    private fun quietThenLoud() = build(
        name = "quiet intro then chorus 120",
        bpm = 120.0,
        seconds = 40.0,
        what = "a large level change mid-track, which moves every adaptive threshold"
    ) { t, _, phase ->
        val level = if (t < 15.0) 0.25 else 1.0
        var s = pad(t, 0.10 * level)
        if (phase < 0.5) s += level * kick(phase, 0.85)
        if (at(phase, 0.5)) s += level * hat(phase - 0.5, 0.25)
        s
    }

    /** Three beats to the bar: anything assuming four will drift its downbeats. */
    private fun waltz() = build(
        name = "waltz 150 in 3",
        bpm = 150.0,
        seconds = 30.0,
        what = "three beats to the bar, with 2 and 3 much softer than 1"
    ) { t, beat, phase ->
        var s = pad(t, 0.12)
        if (beat % 3 == 0 && phase < 0.5) s += kick(phase, 0.9)
        else if (phase < 0.3) s += snare(phase, 0.3)
        s
    }

    /** Half-time drums over a full-rate hat: both readings are defensible. */
    private fun halftimeFeel() = build(
        name = "halftime 140",
        bpm = 140.0,
        seconds = 30.0,
        what = "drums at half time, hats at full - the other classic octave trap"
    ) { t, beat, phase ->
        var s = pad(t, 0.10)
        if (beat % 4 == 0 && phase < 0.5) s += kick(phase, 0.95)
        if (beat % 4 == 2 && phase < 0.4) s += snare(phase, 0.75)
        if (at(phase, 0.0) || at(phase, 0.5)) {
            s += hat(if (phase < 0.5) phase else phase - 0.5, 0.3)
        }
        s
    }

    // --- synthesis --------------------------------------------------------------------------

    /**
     * Renders a fixed-tempo track from a per-sample voice function, and returns the beat grid with
     * it. [voice] receives absolute time, the beat index, and the position within the beat as 0..1.
     */
    private fun build(
        name: String,
        bpm: Double,
        seconds: Double,
        what: String,
        voice: (t: Double, beat: Int, phase: Double) -> Double
    ): Track {
        val total = (seconds * SR).toInt()
        val out = ShortArray(total)
        val beatSamples = 60.0 / bpm * SR
        val beats = ArrayList<Long>()
        var nextBeat = 0.0
        while (nextBeat < total) {
            beats.add((nextBeat / SR * 1000).toLong())
            nextBeat += beatSamples
        }
        for (n in 0 until total) {
            val beat = (n / beatSamples).toInt()
            val phase = (n % beatSamples) / beatSamples
            out[n] = clip(voice(n.toDouble() / SR, beat, phase))
        }
        return Track(name, out, beats, bpm, what)
    }

    private var noiseState = 0x2F6E2B1
    private fun noise(): Double {
        noiseState = noiseState * 1103515245 + 12345
        return ((noiseState ushr 16) and 0x7FFF) / 16384.0 - 1.0
    }

    /** True just after [offset] (0..1 within the beat), for a short window - where a hit sits. */
    private fun at(phase: Double, offset: Double) = phase >= offset && phase < offset + 0.06

    private fun kick(phase: Double, gain: Double): Double {
        val env = exp(-phase * 22.0)
        // A little pitch drop, which is what makes a kick read as a transient rather than a tone.
        return gain * env * sin(2 * PI * (55.0 + 40.0 * env) * phase)
    }

    private fun softKick(phase: Double, gain: Double): Double =
        gain * exp(-phase * 9.0) * sin(2 * PI * 60.0 * phase)

    private fun snare(phase: Double, gain: Double): Double =
        gain * exp(-phase * 26.0) * (0.7 * noise() + 0.3 * sin(2 * PI * 190.0 * phase))

    private fun hat(into: Double, gain: Double): Double =
        gain * exp(-into * 120.0) * noise()

    private fun pad(t: Double, gain: Double): Double =
        gain * (sin(2 * PI * 110.0 * t) + 0.8 * sin(2 * PI * 164.81 * t))

    private fun bassLine(t: Double, bpm: Double, gain: Double): Double {
        val step = (t / (60.0 / bpm)).toInt() % 4
        val hz = 55.0 * when (step) { 0 -> 1.0; 1 -> 1.0; 2 -> 1.335; else -> 1.5 }
        return gain * sin(2 * PI * hz * t)
    }

    private fun clip(sample: Double) =
        (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort()
}
