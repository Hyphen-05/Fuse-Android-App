package com.example.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the visualiser's colour path actually emits, measured against the strip's own curve.
 *
 * ## Why this is worth a file
 *
 * Every preset in the app modulates **brightness**, and that axis is closed: across 27 tunings,
 * felt beat coupling tracked peak brightness slew at r=0.87, so on a single-colour strip there is
 * no brightness setting that is both calm and satisfying, and Joe rejected both ends of it. His
 * stated taste is the other quadrant - flat brightness, vivid hue motion - which nothing has ever
 * been built in.
 *
 * Before building anything there, two things about the existing path need checking rather than
 * assuming, because both would sabotage a hue-led preset silently:
 *
 *  1. **Does rotating hue at a fixed HSV value hold the light steady?** A saturated hue lights one
 *     channel; a hue between two primaries lights two. If the total swings, then "hue motion" has
 *     been brightness modulation all along - which would explain why hue work kept landing back on
 *     the axis that measured as uncomfortable.
 *  2. **How much of the brightness axis does the cubic in [ColorConverter.hsvToRgb] throw away?**
 *
 * These are arithmetic over a measured table, so they say what light comes out. Whether the result
 * looks better is not something this file can answer - that is what blocks 8 and 9 are for.
 *
 * ## The one assumption
 *
 * Total light is the sum of the three channels' [StripResponse] values. Each primary was swept
 * separately during calibration and the three track each other to within ~2%, which is why one LUT
 * serves all three; only white got a full 256-step ramp. So these are ratios on one curve, not
 * photometry, and the ratio is what the questions above need.
 */
class HuePathAnalysis {

    /** Total emitted light for a commanded triple, on the measured curve. */
    private fun light(rgb: Triple<Int, Int, Int>): Double =
        StripResponse.lightForByte(rgb.first) +
            StripResponse.lightForByte(rgb.second) +
            StripResponse.lightForByte(rgb.third)

    private fun sweep(saturation: Float, value: Float): List<Pair<Int, Double>> =
        (0 until 360 step 5).map { h ->
            h to light(ColorConverter.hsvToRgb(h.toFloat(), saturation, value))
        }

    @Test
    fun `rotating hue at a fixed value does not hold the light steady`() {
        println("hue sweep: emitted light against hue, at fixed HSV value")
        println("sat  value    min      max    ratio   dimmest hue  brightest hue")
        var worst = 1.0
        for (sat in listOf(1.0f, 0.7f, 0.4f)) {
            for (v in listOf(1.0f, 0.75f, 0.5f)) {
                val s = sweep(sat, v)
                val lo = s.minBy { it.second }
                val hi = s.maxBy { it.second }
                val ratio = if (lo.second > 0) hi.second / lo.second else Double.POSITIVE_INFINITY
                if (ratio.isFinite() && ratio > worst) worst = ratio
                println(
                    "%.1f  %.2f  %7.3f  %7.3f  %7.2fx  %8d     %8d"
                        .format(sat, v, lo.second, hi.second, ratio, lo.first, hi.first)
                )
            }
        }
        // Not a threshold anyone chose - it is the claim itself. If a future change makes hue
        // rotation light-flat, this test is what should fail and be rewritten.
        assertTrue(
            "a fixed-value hue rotation is expected to swing the emitted light substantially",
            worst > 1.5
        )
    }

    @Test
    fun `the cubic throws away the bottom of the brightness axis`() {
        // hsvToRgb maps value v to byte (v/255)^3 * 255 on the brightest channel, so the bottom of
        // the axis collapses into a handful of bytes and then into nothing at all.
        val white = { v: Int -> ColorConverter.hsvToRgb(0f, 0f, v / 255f) }
        val deadTop = (0..255).last { white(it).first == 0 }
        val onePercent = (0..255).first { StripResponse.lightForByte(white(it).first) >= 0.01 }
        println("cubic: requested values 0-$deadTop emit nothing at all")
        println("cubic: 1% of full light is not reached until requested value $onePercent")
        for (v in listOf(16, 32, 40, 64, 96, 128, 192, 255)) {
            val b = white(v).first
            println("  v=%3d -> byte %3d -> %6.2f%% of full light".format(v, b, StripResponse.lightForByte(b) * 100))
        }
        assertTrue("the dead zone is the finding, not a rounding artefact", deadTop >= 30)
    }

    @Test
    fun `a light-flat hue rotation is reachable, and costs headroom`() {
        // What it would take to rotate hue without the light moving: hold every hue at the light of
        // the dimmest one. This says the fix is a lookup rather than another guessed exponent -
        // and says what it costs, which is the light of the brightest hue.
        val target = sweep(1.0f, 1.0f).minOf { it.second }
        val full = sweep(1.0f, 1.0f).maxOf { it.second }
        println("light-flat rotation would sit at %.3f of the %.3f a saturated white peak reaches"
            .format(target, full))
        println("that is %.0f%% of the brightest hue's output, which is the headroom it costs"
            .format(target / full * 100))

        // Every hue can reach the target by scaling its channels, because the target is the
        // dimmest hue's own output and the curve is monotone.
        for (h in 0 until 360 step 30) {
            var best = 1.0f
            var bestErr = Double.MAX_VALUE
            var scale = 0.05f
            while (scale <= 1.0f) {
                val err = kotlin.math.abs(light(ColorConverter.hsvToRgb(h.toFloat(), 1f, scale)) - target)
                if (err < bestErr) { bestErr = err; best = scale }
                scale += 0.005f
            }
            println("  hue %3d needs value %.3f".format(h, best))
            assertTrue("every hue can be brought to the dimmest hue's light", bestErr < target * 0.05)
        }
    }

    @Test
    fun `three presets can only ever show a handful of hues`() {
        // A preset's anchor moves by hueAnchorJumpDeg and nothing else when the drift is zero, so
        // the reachable set is 360/gcd(jump, 360) - a fixed, small number no audio input can widen.
        // Breath tilts around an anchor without escaping it.
        fun reachable(jumpDeg: Double): Int {
            // Work in tenths of a degree so 137.5 is exact rather than a float accident.
            val jump = Math.round(jumpDeg * 10).toInt()
            val circle = 3600
            var a = jump; var b = circle
            while (b != 0) { val t = a % b; a = b; b = t }
            return circle / a
        }
        val confined = mapOf(
            "Punchy" to 90.0,
            "Strobe Blast" to 120.0,
            "Laser Sharp" to 180.0
        )
        assertEquals(4, reachable(confined.getValue("Punchy")))
        assertEquals(3, reachable(confined.getValue("Strobe Blast")))
        assertEquals(2, reachable(confined.getValue("Laser Sharp")))
        // The golden angle is what a jump should look like: it divides the circle 144 ways, so it
        // never visibly repeats. Beat Only already uses it.
        assertEquals(144, reachable(137.5))
        confined.forEach { (name, jump) ->
            println("$name: jump $jump deg, no drift -> ${reachable(jump)} hues, forever")
        }
    }
}
