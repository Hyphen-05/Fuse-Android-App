package com.example.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Checks the instrument before it is used to measure anything.
 *
 * The lesson from 2026-09-04 is that a measuring tool nobody validated is just an opinion with
 * arithmetic on it. So: the staircase is driven by a simulated observer with a *known* threshold
 * and has to find it, and the dither builders have to actually produce the average level they
 * claim.
 */
class PerceptionTrialsTest {

    /**
     * An observer who reliably sees differences above [trueThreshold], never sees them below, and
     * is unreliable in between - which is what a real threshold looks like.
     */
    private fun observer(trueThreshold: Double, delta: Int, random: Random): Boolean {
        val z = (delta - trueThreshold) / (trueThreshold * 0.25 + 0.5)
        val p = 1.0 / (1.0 + kotlin.math.exp(-z))
        return random.nextDouble() < p
    }

    @Test
    fun `the staircase finds a threshold it was not told`() {
        // Several true thresholds, each recovered from scratch. A staircase that only works for one
        // starting guess is a staircase that is really just reporting its starting guess.
        for (truth in listOf(2.0, 5.0, 12.0)) {
            val estimates = (0 until 40).map { seed ->
                val random = Random(seed * 31 + truth.toInt())
                val staircase = PerceptionTrials.Staircase(start = 20, min = 1, max = 60)
                var guard = 0
                while (!staircase.finished && guard++ < 500) {
                    staircase.record(observer(truth, staircase.current, random))
                }
                staircase.threshold()
            }.filterNotNull()

            assertTrue("every run should reach a threshold for truth=$truth", estimates.size >= 38)
            val median = estimates.sorted()[estimates.size / 2]
            assertTrue(
                "median estimate $median should be near the true threshold $truth",
                abs(median - truth) <= maxOf(1.5, truth * 0.4)
            )
        }
    }

    @Test
    fun `the staircase stays inside its bounds`() {
        // An observer who can see nothing at all drives it to the ceiling; one who sees everything
        // drives it to the floor. Neither may run off the end.
        val blind = PerceptionTrials.Staircase(start = 10, min = 1, max = 30)
        repeat(200) { blind.record(false) }
        assertTrue("must not exceed max", blind.current <= 30)

        val eagle = PerceptionTrials.Staircase(start = 10, min = 1, max = 30)
        repeat(200) { eagle.record(true) }
        assertTrue("must not go below min", eagle.current >= 1)
    }

    @Test
    fun `dither spends the commanded fraction of its time on the upper byte`() {
        for (duty in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val s = PerceptionTrials.dither(byte = 6, duty = duty, intervalMs = 20, ms = 4000)
            val upperMs = s.steps.filter { it.byte == 7 }.sumOf { it.holdMs }
            val measured = upperMs.toDouble() / s.durationMs
            assertEquals("duty $duty should be honoured", duty, measured, 0.02)
        }
    }

    @Test
    fun `dither only ever uses the two bytes it was given`() {
        // A dither that strays outside the pair is not dithering, it is a third level nobody asked
        // for - and at the bottom of the curve a third level is a visible jump.
        val s = PerceptionTrials.dither(byte = 6, duty = 0.4, intervalMs = 20, ms = 2000)
        assertTrue("only bytes 6 and 7", s.steps.all { it.byte == 6 || it.byte == 7 })
    }

    @Test
    fun `dither reaches levels between two bytes that no single byte can`() {
        // The whole proposition, at its simplest. The strip takes whole bytes, so a steady level of
        // 6.5 is not commandable at all - plain rounding gives 6 or 7 and is out by half a byte
        // forever. At the bottom of the measured curve half a byte is several percent of the light,
        // which is well inside what anyone can see.
        for (target in listOf(6.25, 6.5, 6.75)) {
            val duty = target - 6
            val s = PerceptionTrials.dither(byte = 6, duty = duty, intervalMs = 20, ms = 3000)
            val mean = s.steps.sumOf { it.byte * it.holdMs } / s.durationMs.toDouble()
            assertEquals("dither should average to $target", target, mean, 0.02)
        }
    }

    @Test
    fun `dither earns its place where the signal moves slower than one byte per window`() {
        // And this is the boundary, which is worth knowing before building anything on it.
        //
        // Over a *fast* fade, plain rounding already alternates between adjacent bytes by itself,
        // so its running average is fine and dithering adds nothing measurable. Dithering only pays
        // where the fade is slow enough that plain rounding parks on one byte for the whole window
        // - which is precisely dark, slowly-changing content, where one byte is also the largest
        // fraction of the light. Both halves of the problem live in the same place.
        fun worstWindowError(s: Stimulus, from: Int, to: Int, ms: Long, windowMs: Long): Double {
            var t = 0L
            var worst = 0.0
            var i = 0
            val steps = s.steps
            while (i < steps.size) {
                var acc = 0.0
                var held = 0L
                var j = i
                while (j < steps.size && held < windowMs) {
                    acc += steps[j].byte * steps[j].holdMs
                    held += steps[j].holdMs
                    j++
                }
                if (held > 0) {
                    val ideal = from + (to - from) * ((t + held / 2.0) / ms)
                    worst = maxOf(worst, abs(acc / held - ideal))
                }
                t += held
                i = j
            }
            return worst
        }

        // Slow: 4 bytes over 6 seconds. Plain rounding holds each byte for ~1.5s.
        val slowMs = 6000L
        val plainSlow = worstWindowError(PerceptionTrials.fadePlain(4, 8, slowMs, 50), 4, 8, slowMs, 300)
        val ditherSlow = worstWindowError(PerceptionTrials.fadeDithered(4, 8, slowMs, 50), 4, 8, slowMs, 300)
        assertTrue(
            "on a slow fade dithering should track much closer: $ditherSlow vs $plainSlow",
            ditherSlow < plainSlow * 0.6
        )

        // Fast: 16 bytes over 3 seconds. Here plain rounding is already good and the two are level,
        // which is the honest limit of the technique rather than a failure of it.
        val fastMs = 3000L
        val plainFast = worstWindowError(PerceptionTrials.fadePlain(4, 20, fastMs, 50), 4, 20, fastMs, 300)
        val ditherFast = worstWindowError(PerceptionTrials.fadeDithered(4, 20, fastMs, 50), 4, 20, fastMs, 300)
        assertTrue(
            "on a fast fade there is little left to win: $ditherFast vs $plainFast",
            ditherFast <= plainFast + 0.01
        )
    }

    @Test
    fun `a step goes up and comes back, so it can be caught either way`() {
        val s = PerceptionTrials.step(base = 10, delta = 4)
        assertEquals(listOf(10, 14, 10), s.steps.map { it.byte })
        assertEquals(PerceptionTrials.INTERVAL_MS, s.durationMs)
    }

    @Test
    fun `a zero step is indistinguishable from steady, which is what makes catch trials work`() {
        val s = PerceptionTrials.step(base = 10, delta = 0)
        assertTrue("no level changes at all", s.steps.all { it.byte == 10 })
    }
}
