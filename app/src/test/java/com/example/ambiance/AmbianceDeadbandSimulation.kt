package com.example.ambiance

import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Answers, by simulation, which of two opposite fixes the low-light flicker needs.
 *
 * The complaint is that ambiance flickers in dark scenes. Two causes are consistent with that, and
 * they call for opposite changes to [AmbianceDeadband.dynamicThreshold]:
 *
 *  - **Lurching.** The deadband admits a change, and because the strips are ~5× more sensitive per
 *    byte near black, that admitted change lands as a big jump in emitted light. Fix: *lower* the
 *    threshold down there so the colour moves more often in smaller steps.
 *  - **Shimmer.** Capture noise alone clears the deadband, so the strip chases noise that isn't in
 *    the scene. Fix: *raise* the threshold down there, roughly by the sensitivity ratio.
 *
 * Neither is decidable by reading code, and Joe (reasonably) can't tell by eye which he's seeing.
 * What separates them is measurable: feed the real rule a *static* scene plus realistic capture
 * noise and see whether anything gets through at all, and how big it looks when it does.
 *
 * Prints a table; asserts nothing about the outcome, because the outcome is the point.
 */
class AmbianceDeadbandSimulation {

    private val frameCount = 600          // 20fps for 30s
    private val deadbandMultiplier = 1.0

    /** One channel level standing for the whole scene, so the reasoning stays legible. */
    private val levels = listOf(6, 12, 24, 48, 96, 160, 220)

    /** Plausible per-channel capture noise, in sRGB bytes, standard deviation. */
    private val noiseLevels = listOf(0.5, 1.0, 2.0, 4.0, 8.0)

    @Test
    fun `does capture noise alone clear the deadband`() {
        println("\n=== Admitted changes per second, static scene + noise ===")
        println("(the strip should be perfectly still here — anything above 0 is chasing noise)")
        print("%8s".format("level"))
        noiseLevels.forEach { print("%10s".format("sd=$it")) }
        println("%12s".format("light/step"))

        for (level in levels) {
            print("%8d".format(level))
            var stepLightAtMidNoise = 0.0
            for (noise in noiseLevels) {
                val random = Random(level * 1000 + (noise * 10).toInt())
                var ema = level.toDouble()
                var admitted = 0
                val stepLights = mutableListOf<Double>()
                repeat(frameCount) {
                    val raw = (level + random.nextGaussian() * noise).coerceIn(0.0, 255.0)
                    val lum = AmbianceDeadband.luminance(ema.toInt(), ema.toInt(), ema.toInt())
                    val threshold = AmbianceDeadband.dynamicThreshold(lum, deadbandMultiplier)
                    // Same quantity the processor compares: summed absolute difference, and a grey
                    // scene moves all three channels together.
                    val diff = 3 * abs(raw - ema)
                    if (diff > threshold) {
                        admitted++
                        val before = AmbianceDeadband.emittedLight(ema)
                        // The processor eases toward the raw value rather than jumping to it; alpha
                        // 0.25 is representative of the shipped smoothing.
                        ema += 0.25 * (raw - ema)
                        stepLights.add(abs(AmbianceDeadband.emittedLight(ema) - before))
                    }
                }
                print("%10.1f".format(admitted / 30.0))
                if (noise == 2.0) stepLightAtMidNoise = stepLights.average().takeIf { !it.isNaN() } ?: 0.0
            }
            println("%11.1f%%".format(stepLightAtMidNoise * 100))
        }
    }

    @Test
    fun `how big is an admitted change, in light, at each level`() {
        println("\n=== Size of one just-admitted change, in emitted light ===")
        println("(a real scene change of exactly threshold size — how big does it look?)")
        println("%8s %12s %14s %14s".format("level", "threshold", "light before", "step in light"))
        for (level in levels) {
            val lum = AmbianceDeadband.luminance(level, level, level)
            val threshold = AmbianceDeadband.dynamicThreshold(lum, deadbandMultiplier)
            val perChannel = threshold / 3.0
            val before = AmbianceDeadband.emittedLight(level.toDouble())
            val after = AmbianceDeadband.emittedLight(level + perChannel)
            println("%8d %12.1f %13.1f%% %13.1f%%".format(
                level, threshold, before * 100, (after - before) * 100))
        }
    }

    @Test
    fun `proposed rule against the shipped one`() {
        println("\n=== Shipped vs proposed ===")
        println("step = how big one admitted change looks; noise = admissions/sec on a STATIC scene")
        println("%7s | %9s %8s %9s %9s | %9s %8s %9s %9s".format(
            "level", "thresh", "step", "noise@2", "noise@4", "thresh", "step", "noise@2", "noise@4"))

        for (level in levels) {
            val lum = AmbianceDeadband.luminance(level, level, level)
            val shipped = AmbianceDeadband.dynamicThreshold(lum, deadbandMultiplier)
            val proposed = AmbianceDeadband.lightStepThreshold(lum, deadbandMultiplier)
            println("%7d | %9.1f %7.1f%% %9.1f %9.1f | %9.1f %7.1f%% %9.1f %9.1f".format(
                level,
                shipped, stepLight(level, shipped) * 100,
                noiseRate(level, shipped, 2.0), noiseRate(level, shipped, 4.0),
                proposed, stepLight(level, proposed) * 100,
                noiseRate(level, proposed, 2.0), noiseRate(level, proposed, 4.0)))
        }
    }

    @Test
    fun `the rule never admits more than the shipped one did`() {
        // The whole point of taking the max: dark scenes keep their noise rejection, because the
        // alternative was measured admitting noise 7x a second on a static scene.
        for (level in 0..255) {
            val lum = AmbianceDeadband.luminance(level, level, level)
            val shipped = AmbianceDeadband.dynamicThreshold(lum, 1.0)
            val actual = AmbianceDeadband.lightStepThreshold(lum, 1.0)
            org.junit.Assert.assertTrue(
                "level $level got a looser threshold ($actual) than shipped ($shipped)",
                actual >= shipped - 1e-9
            )
        }
        // And it is strictly tighter where the shipped rule was admitting invisible changes.
        val bright = AmbianceDeadband.luminance(200, 200, 200)
        org.junit.Assert.assertTrue(
            "bright scenes should demand a bigger change than before",
            AmbianceDeadband.lightStepThreshold(bright, 1.0) >
                AmbianceDeadband.dynamicThreshold(bright, 1.0) + 1.0
        )
    }

    /** Light emitted by a change of exactly [threshold], spread over three channels. */
    private fun stepLight(level: Int, threshold: Double): Double {
        val before = AmbianceDeadband.emittedLight(level.toDouble())
        return AmbianceDeadband.emittedLight(level + threshold / 3.0) - before
    }

    /** Admissions per second on a scene that is not moving at all — pure noise chasing. */
    private fun noiseRate(level: Int, threshold: Double, noiseSd: Double): Double {
        val random = Random(level * 977 + (noiseSd * 13).toInt())
        var ema = level.toDouble()
        var admitted = 0
        repeat(frameCount) {
            val raw = (level + random.nextGaussian() * noiseSd).coerceIn(0.0, 255.0)
            if (3 * abs(raw - ema) > threshold) {
                admitted++
                ema += 0.25 * (raw - ema)
            }
        }
        return admitted / 30.0
    }

    /** Box-Muller, since kotlin.random has no Gaussian. */
    private fun Random.nextGaussian(): Double {
        var u = nextDouble(); var v = nextDouble()
        if (u < 1e-9) u = 1e-9
        if (v < 1e-9) v = 1e-9
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v)
    }
}
