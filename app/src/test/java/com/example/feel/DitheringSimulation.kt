package com.example.feel

import com.example.ambiance.AmbianceDeadband
import com.example.core.protocol.DuoCoProtocol
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Weighs the three candidate fixes for near-black quantisation, which `IMPROVEMENT_PLAN.md` has
 * carried as "deferred deliberately" and the deadband simulation promoted to *the* fix.
 *
 * The problem, settled by measurement rather than argument: light ≈ (byte/255)^0.4, so at byte 6 one
 * byte is already a ~4% step in emitted light. Nothing that chooses *which* byte to send can produce
 * a smaller change than that, which is why no deadband setting fixed dark scenes and why slow colour
 * motion emits byte-identical commands for seconds. The byte grid is too coarse down there, full
 * stop.
 *
 * Three ways out were listed. This file measures all three against the same yardstick:
 *
 *  1. **Temporal dithering** — alternate between two adjacent bytes so the average lands between
 *     them. Buys resolution the grid does not have; costs wire traffic, and risks turning
 *     quantisation into flicker if the alternation is slow enough to see.
 *  2. **Skipping writes that quantise identically** — pure wire saving, no resolution gained. Cheap
 *     and safe; the question is only how much it saves.
 *  3. **Headroom scaling** — keep the colour bytes high, where the grid is fine, and use the strip's
 *     global brightness command to bring the level down. Costs nothing on the wire and buys
 *     resolution on both axes, *if* firmware brightness is finer than a byte multiply.
 *
 * Prints tables; asserts only the things that would invalidate the conclusions if they changed.
 * Run with:
 * `./gradlew :app:testDebugUnitTest --tests "com.example.feel.DitheringSimulation"`
 *
 * ## What is assumed about the strip's own PWM (Joe's question, 2026-08-17)
 *
 * The strips almost certainly dim by PWM — switching the LEDs on and off far faster than the eye
 * can follow, so that a duty cycle reads as a brightness. Nothing here models that carrier, and for
 * the flicker scoring that is correct: the carrier sits at least an order of magnitude above the
 * band this file scores, so it contributes nothing the eye integrates differently. The measured
 * response curve is also **end-to-end** — commanded byte to light in the room — so whatever mapping
 * the firmware applies between the byte and its duty cycle is already inside the 0.4 exponent, and
 * modelling PWM explicitly would double-count it.
 *
 * Two things it does change, though, and both matter more than the scoring:
 *
 *  - **It says what the response curve is telling us.** Straight PWM is *linear* in duty: 50% duty
 *    is 50% of the light. The measurement is nothing like linear (byte 32 → 42% light), so the
 *    firmware is applying its own curve before the duty cycle. That is a firmware choice sitting
 *    underneath every number here, not a property of LEDs.
 *  - **It reframes option 1 entirely.** Temporal dithering *is* PWM — the same trick of trading time
 *    for level. The strip already does it in hardware at a frequency the eye cannot see, and doing
 *    it in the app means doing it over a radio that tops out near 97Hz on one strip and 49Hz on two
 *    (measured below), which is squarely inside the band the eye is *most* sensitive to. The app
 *    would be reimplementing the hardware's dimmer at a hundredth of its frequency.
 *
 * The one place the carrier could bite is if it is unusually low — some cheap drivers run 100-250Hz
 * — in which case deep dimming might flicker on its own and beat against anything the app adds.
 * That is unmeasured, and it is cheap to settle with a motion-smear photo. **The method needs three
 * conditions this file originally failed to state**, and a first attempt on 2026-08-17 missed all
 * three and came back unreadable:
 *
 *  - **Dim the strip first** (brightness ~10-20%, or a colour byte near 24). At full white the duty
 *    cycle is ~100% and there is nothing to chop — the photo shows a continuous streak whether the
 *    driver PWMs or not.
 *  - **Do not let the LEDs clip.** PWM reads as intensity structure along the smear, and a saturated
 *    pixel has no structure. Tap-to-expose on the strip and drag the exposure slider down.
 *  - **Move the camera across the strip, not along it.** Swept along its own axis, the LED pitch and
 *    the chopping land on the same axis and cannot be told apart; swept across, each LED draws a
 *    clean line that PWM breaks into countable dashes.
 *
 * Full method in `tools/calibration/README.md`.
 */
class DitheringSimulation {

    /** Dark levels, where the whole problem lives, plus a mid-tone for contrast. */
    private val levels = listOf(0, 1, 2, 4, 6, 8, 12, 24, 48, 160)

    // ------------------------------------------------------------------------------------------
    // The problem, restated in numbers so the rest has a yardstick
    // ------------------------------------------------------------------------------------------

    @Test
    fun `how coarse is the byte grid, in light`() {
        println("\n=== One byte, in emitted light ===")
        println("(the smallest change the app can command at all, at each level)")
        println("%8s %14s %16s".format("byte", "light", "step to byte+1"))
        for (level in levels) {
            val here = AmbianceDeadband.emittedLight(level.toDouble())
            val next = AmbianceDeadband.emittedLight(level + 1.0)
            println("%8d %13.1f%% %15.2f%%".format(level, here * 100, (next - here) * 100))
        }
        println("\nFor reference: a change is around the threshold of visibility at ~1% of the")
        println("light already present, so a step is objectionable when it is several times that.")
        println("\nThe top rows are extrapolation, not measurement — see the next test before")
        println("quoting them. What survives either way: the colour axis has no fine dim setting.")
        println("Byte 8 is the dimmest level anyone has measured and it already emits 11% of full")
        println("light, so the first step up from off is at most that and no choice of bytes")
        println("reaches under it. Only the brightness command goes lower.")
    }

    /**
     * Where the response curve is actually pinned by data, and where this file has been extrapolating.
     *
     * Worth its own test because the answer is uncomfortable: the fitted exponent reproduces the
     * bright end well and **misses the dimmest measured point by better than a factor of two** — the
     * rig read 11% of full light at byte 8, and `light = (byte/255)^0.4` says 25%. The fit was made
     * across the whole ramp, so the bright points dominate it.
     *
     * Every interesting number in this file, and in the deadband work already merged, sits at bytes
     * 4-24 — inside the region where the curve is least trustworthy and outside the region any
     * measurement covers. Two readings, and nothing on hand separates them:
     *
     *  - **A real toe.** LED drivers often have a minimum usable duty cycle, and PWM resolution runs
     *    out at the bottom, so the strip genuinely emits less down there than a power law predicts.
     *  - **Measurement error where it is most likely.** Byte 8 is the dimmest patch on the wall,
     *    closest to the camera's noise floor and to any error in the black level. A small offset
     *    error moves the dimmest point by the largest *relative* amount.
     *
     * Both readings point the same way for the recommendation, which is why the conclusion holds:
     * the local slope implied by the measured points between byte 8 and byte 32 is **1.7×** the
     * fitted curve's, so near-black steps are, if anything, *larger* than this file reports. What
     * neither reading supports is quoting a light value for byte 1.
     */
    @Test
    fun `where the response curve is trustworthy`() {
        println("\n=== Fitted curve against the five measured points ===")
        println("%8s %14s %14s %12s".format("byte", "measured", "fitted ^0.4", "error"))
        val measured = listOf(8 to 0.11, 32 to 0.42, 64 to 0.53, 128 to 0.84, 255 to 1.00)
        for ((byteValue, light) in measured) {
            val fitted = AmbianceDeadband.emittedLight(byteValue.toDouble())
            println("%8d %13.0f%% %13.0f%% %11.0f%%".format(
                byteValue, light * 100, fitted * 100, (fitted - light) * 100))
        }

        val measuredSlope = (0.42 - 0.11) / (32 - 8)
        val fittedSlope = (AmbianceDeadband.emittedLight(32.0) -
            AmbianceDeadband.emittedLight(8.0)) / (32 - 8)
        println("\nlocal slope, byte 8 to 32:   measured %.2f%%/byte, fitted %.2f%%/byte (%.1fx)".format(
            measuredSlope * 100, fittedSlope * 100, measuredSlope / fittedSlope))
        println("\nNothing below byte 8 was measured at all. The dark end wants its own ramp —")
        println("bytes 0 to 32 in steps of 1 — which is a short sequence and one camera run.")
    }

    // ------------------------------------------------------------------------------------------
    // Option 1 — temporal dithering
    // ------------------------------------------------------------------------------------------

    /**
     * Dithering only works if the alternation is too fast to see, so the first question is not
     * whether the app *wants* to dither fast but whether the pipeline can deliver it at all.
     *
     * Both limits are measured: a write occupies the radio 4.6ms per connected strip, and the
     * queue's latest-wins rule means anything offered while the radio is busy replaces what was
     * waiting rather than queueing behind it. So the achievable alternation rate is a property of
     * the write path, and [VirtualStrip] already models it.
     */
    @Test
    fun `what alternation rate can the write path actually deliver`() {
        println("\n=== Requested dither rate vs delivered ===")
        println("(delivered = distinct colours the strip was actually shown, per second)")
        println("%10s %10s %10s %12s %16s".format(
            "requested", "1 strip", "2 strips", "wire busy 1x", "eye sees (1 strip)"))
        for (hz in listOf(15, 30, 60, 100, 150, 200)) {
            val one = deliveredAlternationHz(hz, strips = 1)
            val two = deliveredAlternationHz(hz, strips = 2)
            val busy = hz * 4.6 / 1000.0
            println("%9dHz %9.0fHz %9.0fHz %11.0f%% %13.0f%%".format(
                hz, one, two, busy * 100, sensitivity(one / 2.0) * 100))
        }
        println("\nThe radio is the ceiling and it is shared: a dither runs continuously, even on a")
        println("scene that is not moving, so whatever it takes is taken from everything else.")
        println("\n'eye sees' is the eye's relative sensitivity at the alternation frequency (half the")
        println("write rate), as a percentage of its peak. The radio *can* just carry a carrier past")
        println("the eye — 100 writes/s alternates at 50Hz and scores 0% — so dithering is not ruled")
        println("out on rate. It is ruled out on what that costs: 46% of the radio for one strip and")
        println("87% for two, held forever, to dim one colour. Hardware PWM does the same trick orders")
        println("of magnitude faster for nothing, which is the argument for using the dimmer instead.")
    }

    /**
     * The question dithering lives or dies on: does the alternation read as an intermediate level,
     * or as flicker?
     *
     * Scored with a temporal contrast sensitivity weighting — sensitivity to a flickering light
     * peaks near 10Hz and falls away steeply above ~30Hz, which is why a 100Hz dither is invisible
     * and a 15Hz one is a strobe. [visibleFlicker] states the curve it uses and its limits.
     */
    @Test
    fun `dithering — resolution gained against flicker introduced`() {
        println("\n=== Dither at byte 6, targeting the midpoint between 6 and 7 ===")
        println("(flicker score: ~1.0 is the threshold of visibility, 10 is a strobe)")
        println("%10s %12s %14s %16s %12s".format(
            "writes/s", "alternates", "flicker (even)", "flicker (jitter)", "step won"))

        val gained = (AmbianceDeadband.emittedLight(7.0) - AmbianceDeadband.emittedLight(6.0)) / 2
        for (hz in listOf(15, 30, 60, 100)) {
            val even = ditherFlicker(level = 6, hz = hz, jitterSd = 0.0)
            val jittered = ditherFlicker(level = 6, hz = hz, jitterSd = 36.0)
            println("%9d %10.1fHz %14.1f %16.1f %11.2f%%".format(
                hz, hz / 2.0, even, jittered, gained * 100))
        }

        println("\nTwo writes make one cycle, so 'alternates' — half the write rate — is the frequency")
        println("the eye sees. 'even' is the pattern as intended; 'jitter' is the same pattern with")
        println("the measured sd 36ms delivery spread applied.")
    }

    @Test
    fun `dithering — what it costs the rest of the pipeline`() {
        println("\n=== Wire cost of holding one dithered level ===")
        println("%10s %14s %16s %16s".format("rate", "writes/s", "radio (1 strip)", "radio (2 strips)"))
        for (hz in listOf(15, 30, 60, 100)) {
            println("%9dHz %13d %15.0f%% %15.0f%%".format(
                hz, hz, hz * 4.6 / 10.0, hz * 8.7 / 10.0))
        }
        println("\nAgainst a baseline where a still scene sends nothing at all: the deadband exists")
        println("precisely so a static picture costs zero writes. Dithering inverts that.")
    }

    // ------------------------------------------------------------------------------------------
    // Option 2 — skip writes that quantise identically
    // ------------------------------------------------------------------------------------------

    /**
     * The cheapest of the three and the only one with no downside: if the colour rounds to the bytes
     * already on the strip, the write changes nothing and can be dropped before it reaches the
     * queue — where it would otherwise displace a colour that *does* differ.
     *
     * That second part is the real prize. The queue is latest-wins, so an identical write is not
     * merely wasted; it can evict a distinct colour that was waiting.
     */
    @Test
    fun `skipping identical writes — what it saves on a slow fade`() {
        println("\n=== A 10s fade from byte 24 to byte 4, computed at 60fps ===")
        println("%16s %10s %14s %16s".format("", "writes", "distinct", "identical (%)"))

        val frames = 600
        var identical = 0
        var previous = -1
        val distinctBytes = sortedSetOf<Int>()
        repeat(frames) { i ->
            val byteValue = (24 - 20.0 * i / frames).roundToInt()
            distinctBytes.add(byteValue)
            if (byteValue == previous) identical++
            previous = byteValue
        }
        println("%16s %10d %14d %15.0f%%".format(
            "as computed", frames, distinctBytes.size, identical * 100.0 / frames))
        println("%16s %10d %14d %15s".format(
            "after skipping", frames - identical, distinctBytes.size, "-"))
        println("\nThe fade has $frames frames of intent and only ${distinctBytes.size} bytes to say it with.")
        println("Everything above that is traffic the strip cannot distinguish.")
    }

    // ------------------------------------------------------------------------------------------
    // Option 3 — headroom scaling
    // ------------------------------------------------------------------------------------------

    /**
     * The strip takes a global brightness percentage alongside the colour triplet. So a dim colour
     * can be commanded two ways: small bytes at full brightness, or large bytes scaled down.
     *
     * Both land at the same light level. They do not land with the same *resolution*: at byte 6
     * there are six levels between the colour and black and hue is quantised to a handful of ratios,
     * while at byte 96 there are ninety-six and the hue is smooth. If the firmware applies
     * brightness with more precision than an 8-bit multiply, the second route is strictly better and
     * costs one extra command.
     *
     * That "if" is the whole risk, and it is a hardware question. The model here assumes the
     * pessimistic case ([VirtualStrip] scales the byte, then applies the response curve) so the
     * resolution numbers below are what the *colour grid* buys, independent of it.
     *
     * PWM is the reason to expect the optimistic case. A brightness percentage is almost certainly a
     * duty cycle, and duty cycles are not stored at the resolution of the colour byte — so the
     * hardware is very likely already doing, properly and invisibly, the exact trick option 1 wants
     * to do badly over the radio. Using the dimmer that exists beats rebuilding it at 50Hz.
     */
    @Test
    fun `headroom scaling — resolution of the two routes to the same dim colour`() {
        println("\n=== Two ways to command the same dim white ===")
        println("%22s %10s %12s %14s %14s".format("route", "bytes", "brightness", "light", "hue steps"))

        for (target in listOf(0.08, 0.15, 0.25)) {
            println("target %.0f%% light:".format(target * 100))
            val direct = AmbianceDeadband.byteForLight(target).roundToInt()
            val directLight = AmbianceDeadband.emittedLight(direct.toDouble())
            val scaled = 96
            // Brightness is a whole percent, so pick the nearest reachable duty rather than assuming
            // the exact one is available — the grid is coarse here too, just far less coarse.
            val percent = (1..100).minByOrNull {
                abs(AmbianceDeadband.emittedLight(scaled * it / 100.0) - target)
            } ?: 100
            println("%22s %10d %11d%% %13.1f%% %14d".format(
                "small bytes", direct, 100, directLight * 100, direct))
            println("%22s %10d %11d%% %13.1f%% %14d".format(
                "headroom + brightness", scaled, percent,
                AmbianceDeadband.emittedLight(scaled * percent / 100.0) * 100, scaled))
            if (direct <= 1) {
                println("%22s %s".format("", "<- the colour axis cannot reach this level at all"))
            }
            println()
        }
        println("'hue steps' is how many distinct ratios the three channels can express at that")
        println("level — the quantity that collapses when a slow colour sweep stops moving.")
    }

    @Test
    fun `headroom scaling — how fine is the combined grid`() {
        println("\n=== Distinct light levels reachable below 25% light ===")

        val direct = (0..255).map { AmbianceDeadband.emittedLight(it.toDouble()) }
            .filter { it <= 0.25 }.distinct()
        val combined = sortedSetOf<Double>()
        for (byteValue in 0..255) {
            for (percent in 1..100) {
                val light = AmbianceDeadband.emittedLight(byteValue * percent / 100.0)
                if (light <= 0.25) combined.add((light * 10000).roundToInt() / 10000.0)
            }
        }
        println("%28s %10d".format("colour byte alone", direct.size))
        println("%28s %10d".format("colour byte x brightness", combined.size))
        println("\nfinest step, byte alone:      %.2f%%".format(smallestGap(direct.sorted()) * 100))
        println("finest step, combined:        %.2f%%".format(smallestGap(combined.toList()) * 100))
        println("\nThis is the model's pessimistic assumption at work — brightness scaling the byte")
        println("before the response curve. It still beats the byte grid, because the products land")
        println("between the integers. Whether the firmware does better than this needs the camera.")
    }

    // ------------------------------------------------------------------------------------------
    // The one thing that must not silently change
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a dither is only invisible if it outruns the eye`() {
        // Guards the argument rather than an implementation: if the flicker model is ever retuned,
        // the ordering it produces is what the recommendation rests on.
        val slow = ditherFlicker(level = 6, hz = 15, jitterSd = 0.0)
        val fast = ditherFlicker(level = 6, hz = 100, jitterSd = 0.0)
        org.junit.Assert.assertTrue(
            "a 15Hz dither should score far worse than a 100Hz one, got $slow vs $fast",
            slow > fast * 3
        )
        org.junit.Assert.assertTrue(
            "a 15Hz dither at byte 6 should be plainly visible, got $slow",
            slow > 1.0
        )
    }

    @Test
    fun `delivery jitter is off unless a test asks for it`() {
        // The rest of the harness was calibrated without jitter; leaving it on by default would move
        // every render for reasons unrelated to whatever was being judged.
        org.junit.Assert.assertEquals(0.0, StripLimits().deliveryJitterSdMs, 1e-9)
    }

    // ------------------------------------------------------------------------------------------
    // Machinery
    // ------------------------------------------------------------------------------------------

    /** Runs a two-level alternation through the real write path and reports what got through. */
    private fun deliveredAlternationHz(requestedHz: Int, strips: Int): Double {
        val strip = VirtualStrip(connectedStripCount = strips)
        val periodMs = 1000.0 / requestedHz
        val durationMs = 3000L
        // Absorb the post-idle window first, so this measures sustained rate and not the warm-up.
        strip.write(0, DuoCoProtocol.createMusicColorCommand(6, 6, 6))
        var i = 0
        var t = 300L
        while (t < durationMs) {
            val level = if (i % 2 == 0) 6 else 7
            strip.write(t, DuoCoProtocol.createMusicColorCommand(level, level, level))
            i++
            t = 300 + (i * periodMs).toLong()
        }
        val accepted = strip.stats().accepted - 1
        return accepted * 1000.0 / (durationMs - 300)
    }

    /**
     * Emitted-light waveform of a dither, scored for how visible its flicker is.
     */
    private fun ditherFlicker(level: Int, hz: Int, jitterSd: Double): Double {
        val strip = VirtualStrip(limits = StripLimits(deliveryJitterSdMs = jitterSd))
        val periodMs = 1000.0 / hz
        val durationMs = 3000L
        strip.write(0, DuoCoProtocol.createMusicColorCommand(level, level, level))
        var i = 0
        var t = 300L
        while (t < durationMs) {
            val value = if (i % 2 == 0) level else level + 1
            strip.write(t, DuoCoProtocol.createMusicColorCommand(value, value, value))
            i++
            t = 300 + (i * periodMs).toLong()
        }
        val samples = strip.timeline(untilMs = durationMs, stepMs = 1)
            .filter { it.atMs >= 500 }
            .map { it.r / 255.0 }
        return visibleFlicker(samples, sampleRateHz = 1000.0)
    }

    /**
     * How visible is the flicker in a light waveform, as a multiple of the visibility threshold.
     *
     * Modulation at each frequency is measured against the mean level (Weber contrast, which is what
     * the eye responds to) and weighted by temporal contrast sensitivity: a band-pass curve peaking
     * near 10Hz and falling away above it, the standard shape from the de Lange flicker literature.
     * The threshold is taken as ~0.5% modulation at the peak, so a score of 1.0 means "about at the
     * edge of noticing" and 10 means "obvious".
     *
     * **What this is not.** It is a textbook curve, not a measurement of Joe's eyes in his room, and
     * sensitivity also falls with the light level — a dim strip fuses at a lower frequency than a
     * bright one, which if anything makes dithering easier than scored here. Use it to rank options,
     * not to certify one.
     */
    private fun visibleFlicker(samples: List<Double>, sampleRateHz: Double): Double =
        flickerLines(samples, sampleRateHz).maxOfOrNull { it.second } ?: 0.0

    /**
     * Every frequency's flicker score, so a surprising total can be traced to the line that caused it.
     *
     * Two details the first version of this got wrong, both of which manufactured flicker that was
     * not there: the **mean has to come out before the transform** (a signal sitting at 23% of full
     * light leaks its DC across every bin — it showed as a 25% modulation at 1Hz, larger than the
     * dither itself), and the grid has to be **finer than 1Hz**, because writes alternating at 15Hz
     * produce a 7.5Hz square wave that lands exactly between two integer bins and gets smeared
     * across both. A Hann window keeps what is left of the leakage local; its coherent gain of 0.5
     * is compensated in the amplitude.
     */
    private fun flickerLines(samples: List<Double>, sampleRateHz: Double): List<Pair<Double, Double>> {
        if (samples.size < 32) return emptyList()
        val mean = samples.average()
        if (mean <= 1e-9) return emptyList()

        val n = samples.size
        val windowed = samples.mapIndexed { i, value ->
            (value - mean) * (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)))
        }

        val out = mutableListOf<Pair<Double, Double>>()
        var hz = 0.5
        while (hz <= 90.0) {
            var re = 0.0
            var im = 0.0
            windowed.forEachIndexed { i, value ->
                val phase = 2.0 * PI * hz * i / sampleRateHz
                re += value * cos(phase)
                im += value * sin(phase)
            }
            val amplitude = 2.0 * sqrt(re * re + im * im) / n / HANN_COHERENT_GAIN
            val modulation = amplitude / mean
            out.add(hz to modulation * sensitivity(hz) / THRESHOLD_MODULATION)
            hz += 0.25
        }
        return out
    }

    /**
     * Relative temporal contrast sensitivity: band-pass, peak ~10Hz, effectively nothing left by
     * 60Hz. Normalised so the peak is 1.0.
     */
    private fun sensitivity(hz: Double): Double = rawSensitivity(hz) / PEAK_SENSITIVITY

    private fun rawSensitivity(hz: Double): Double {
        val lowCut = 1.0 - exp(-hz / 3.0)          // insensitive to very slow modulation
        val highCut = exp(-(hz / 22.0) * (hz / 22.0) * ln(2.0) * 2.5)
        return lowCut * highCut
    }

    private fun smallestGap(sorted: List<Double>): Double {
        var smallest = Double.MAX_VALUE
        for (i in 1 until sorted.size) {
            val gap = abs(sorted[i] - sorted[i - 1])
            if (gap > 1e-9 && gap < smallest) smallest = gap
        }
        return if (smallest == Double.MAX_VALUE) 0.0 else smallest
    }

    private companion object {
        /** Modulation depth at the eye's most sensitive frequency that is just noticeable. */
        const val THRESHOLD_MODULATION = 0.005

        /** A Hann window passes half the signal's amplitude; divide it back out. */
        const val HANN_COHERENT_GAIN = 0.5
    }

    /** Peak of the unnormalised curve, scanned rather than hard-coded so retuning stays honest. */
    private val PEAK_SENSITIVITY: Double =
        (10..900).maxOf { rawSensitivity(it / 10.0) }
}
