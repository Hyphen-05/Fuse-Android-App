package com.example.core.audio

/**
 * Continuous audio-to-light coupling, with no beat detection anywhere in it.
 *
 * ## Why this exists
 *
 * Every visualiser preset before this one drove the strip from **discrete flash events**: detect a
 * beat, fire a flash. That is a binary bet, and a bet can be lost — measured at 56% precision, so
 * roughly half the flashes landed on nothing. Irregular flashing on nothing is what Joe reported on
 * 2026-08-25 as "way too flashy... it all becomes a mess", and no amount of better detection fixes
 * the shape of the problem, only its frequency.
 *
 * MilkDrop and projectM — which Joe rates as "pretty much perfect, genuinely feels like it's going
 * to the song" — do not do this. They expose `bass`/`mid`/`treb` as continuously normalised band
 * energies, auto-gained so they average around 0.65 whatever the track's loudness, and the visuals
 * read them every frame. Discrete beat detection exists there only for coarse things like switching
 * presets. **A continuous mapping can never flash on the wrong beat, because it never claims a beat
 * exists.** That is the whole trick, and it is what this class copies.
 *
 * ## How a flash happens without a flash trigger
 *
 * A flash, physically, is the light rising fast. So rather than deciding *when* to flash, this runs
 * two envelopes over the same signal — one quick, one slow — and reports the gap between them as
 * [Drive.punch]. A kick makes the fast envelope leap while the slow one lags, and the gap is large
 * for a moment; a sustained note moves both together and the gap stays near zero. Feed `punch` into
 * brightness and transients pop by themselves, proportionally to how sharply they arrived, with no
 * threshold to cross and nothing to be wrong about.
 *
 * Everything here is pure and frame-rate independent (all smoothing is expressed as a time constant
 * against `dtMs`), so `ContinuousDriveTest` can drive it with synthetic envelopes.
 */
class ContinuousDrive(private val tuning: Tuning = Tuning()) {

    /**
     * The tunable shape of the mapping, injectable so `GtzanBeatAccuracyTest` can sweep it against
     * measured lift and movement instead of the constants being guessed once and frozen.
     * Defaults are the tuned values; see that test for the sweep that chose them.
     */
    data class Tuning(
        val fastReleaseTauMs: Float = FAST_RELEASE_TAU_MS,
        val slowAttackTauMs: Float = SLOW_ATTACK_TAU_MS,
        val slowReleaseTauMs: Float = SLOW_RELEASE_TAU_MS,
        /**
         * Share of the brightness range given to the slow body, the rest left for the transient.
         * Below 1.0 the light sits dimmer between hits so a pulse has somewhere to go — with the
         * body claiming everything, brightness parks near the top and beats cannot read at all
         * (measured: bodyShare 1.0 gave a lift of 1.035, barely above flat).
         */
        val bodyShare: Float = 0.25f,
        /**
         * How hard a transient pushes into the range the body left free.
         *
         * Peaks around 1.5 and *falls* above it: `punch` fires on every transient rather than on
         * beats, so past the optimum more gain adds off-beat brightness as readily as on-beat and
         * the tracking ratio drops while movement keeps climbing. 1.0 rather than the peak because
         * 1.5 costs 30% more movement for 4% more tracking, and movement is the complaint.
         */
        val punchGain: Float = 1.0f
    )

    companion object {
        /**
         * What each band's auto-gain normalises its running average to. 0.65 is MilkDrop's figure,
         * kept deliberately: it leaves headroom above for transients to exceed 1.0 before clamping,
         * which is what stops loud passages sitting pinned at full brightness.
         */
        const val TARGET_LEVEL = 0.65f

        /**
         * Time constant of the auto-gain's running average, in ms. Long on purpose — this is meant
         * to track "how loud is this song", not "how loud is this bar". Too short and it normalises
         * away the dynamics we are trying to show, flattening quiet passages up to match loud ones.
         */
        const val GAIN_TAU_MS = 4_000f

        /** Rise time of the fast envelope. Short enough that a kick is most of the way up in a frame. */
        const val FAST_ATTACK_TAU_MS = 12f

        /**
         * Fall time of the fast envelope — a transient's visible tail length, so the main "how
         * flashy" knob. 100ms measured best in the sweep: 80ms tracked marginally harder but cost
         * movement, and 160ms lost tracking without buying much calm. Still far longer than the
         * 70-90ms decays the old strobe presets used.
         */
        const val FAST_RELEASE_TAU_MS = 100f

        /** Rise/fall of the slow envelope: the body of the light, and the reference `punch` is measured against. */
        const val SLOW_ATTACK_TAU_MS = 220f
        const val SLOW_RELEASE_TAU_MS = 420f

        /** Below this normalised level a band is treated as silent, so room tone cannot drive the light. */
        const val SILENCE_LEVEL = 0.04f
    }

    /**
     * @param level the slow envelope of the mix — the body of the brightness, what the music is
     *   *doing* rather than what just happened.
     * @param punch how far the fast envelope is ahead of the slow one, i.e. how sharply the sound
     *   just rose. 0 through a sustained passage, large on a kick. This is the emergent flash.
     * @param tilt spectral balance in -1..1, treble-heavy positive and bass-heavy negative. Drives
     *   hue continuously so colour follows the music's texture instead of jumping on beats.
     */
    data class Drive(val level: Float, val punch: Float, val tilt: Float)

    private var bassAvg = 0f
    private var midAvg = 0f
    private var highAvg = 0f

    private var fastEnv = 0f
    private var slowEnv = 0f

    private var bassNormSmoothed = 0f
    private var highNormSmoothed = 0f

    /** One-pole coefficient for a time constant, guarding dt <= 0 and the first frame. */
    private fun alpha(dtMs: Long, tauMs: Float): Float =
        if (dtMs <= 0L) 1f else (dtMs.toFloat() / tauMs).coerceIn(0f, 1f)

    /**
     * Normalises one band against its own running average.
     *
     * The average is only updated while there is something to average — a band sitting at silence
     * would otherwise drag its own reference to zero, and the moment the music restarted the ratio
     * would explode. Returns 0 rather than a huge number when the reference is not yet established.
     */
    private fun normalise(raw: Float, avg: Float, dtMs: Long): Pair<Float, Float> {
        val safeRaw = raw.coerceAtLeast(0f)
        val newAvg = if (safeRaw > 0f) avg + (safeRaw - avg) * alpha(dtMs, GAIN_TAU_MS) else avg
        val norm = if (newAvg > 1e-4f) (safeRaw / newAvg) * TARGET_LEVEL else 0f
        return norm.coerceIn(0f, 4f) to newAvg
    }

    /**
     * One frame. [bassRaw], [midRaw] and [highRaw] are the same per-band magnitudes the rest of the
     * DSP uses, before any of its smoothing — this stage does its own, because the existing
     * attack/decay pair is shaped for driving a flash trigger rather than for direct display.
     */
    fun process(bassRaw: Float, midRaw: Float, highRaw: Float, dtMs: Long): Drive {
        val (bassNorm, newBassAvg) = normalise(bassRaw, bassAvg, dtMs)
        val (midNorm, newMidAvg) = normalise(midRaw, midAvg, dtMs)
        val (highNorm, newHighAvg) = normalise(highRaw, highAvg, dtMs)
        bassAvg = newBassAvg
        midAvg = newMidAvg
        highAvg = newHighAvg

        // Bass-weighted, because that is where a kick lives and the kick is what should read as a
        // pulse. Mid carries most of the musical body; treble is deliberately a small share, or
        // cymbals and hiss would drive the brightness as hard as the groove does.
        val mix = bassNorm * 0.6f + midNorm * 0.3f + highNorm * 0.1f

        // Asymmetric envelopes: rising and falling use different time constants, which is what makes
        // a transient a shape rather than a symmetrical bump.
        fastEnv += (mix - fastEnv) *
            alpha(dtMs, if (mix > fastEnv) FAST_ATTACK_TAU_MS else tuning.fastReleaseTauMs)
        slowEnv += (mix - slowEnv) *
            alpha(dtMs, if (mix > slowEnv) tuning.slowAttackTauMs else tuning.slowReleaseTauMs)

        val punch = (fastEnv - slowEnv).coerceAtLeast(0f)

        // Hue follows the *smoothed* balance, not the instantaneous one: colour chasing per-frame
        // spectral noise is its own kind of mess, and the tilt is a texture, not an event.
        bassNormSmoothed += (bassNorm - bassNormSmoothed) * alpha(dtMs, tuning.slowAttackTauMs)
        highNormSmoothed += (highNorm - highNormSmoothed) * alpha(dtMs, tuning.slowAttackTauMs)
        val sum = bassNormSmoothed + highNormSmoothed
        val tilt = if (sum > 1e-4f) ((highNormSmoothed - bassNormSmoothed) / sum).coerceIn(-1f, 1f) else 0f

        val silent = slowEnv < SILENCE_LEVEL && fastEnv < SILENCE_LEVEL
        return if (silent) Drive(0f, 0f, tilt) else Drive(slowEnv, punch, tilt)
    }

    /**
     * Maps a [Drive] onto a 0..1 brightness, given the preset's floor and gamma.
     *
     * Lives here rather than in `AudioDspProcessor` so the sweep measures exactly what the app
     * renders — a tuning harness that reimplements the mapping tunes the wrong thing.
     */
    fun brightness(drive: Drive, minBrightness: Float, gamma: Float, flashStrength: Float): Float {
        val shaped = Math.pow(drive.level.coerceIn(0f, 1f).toDouble(), gamma.toDouble()).toFloat()
        val body = tuning.bodyShare * shaped
        val transient = tuning.punchGain * drive.punch * flashStrength
        return (minBrightness + (1f - minBrightness) * (body + transient)).coerceIn(0f, 1f)
    }

    /** Drops all history. Called when a run starts so a previous song cannot bias the auto-gain. */
    fun reset() {
        bassAvg = 0f
        midAvg = 0f
        highAvg = 0f
        fastEnv = 0f
        slowEnv = 0f
        bassNormSmoothed = 0f
        highNormSmoothed = 0f
    }
}
