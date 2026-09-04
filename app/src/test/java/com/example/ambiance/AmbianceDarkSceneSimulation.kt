package com.example.ambiance

import com.example.core.color.ColorConverter
import com.example.core.pacing.BlePacing
import com.example.sim.SimulatedDevice
import com.example.sim.SimulatedRoom
import com.example.sim.TraceMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Why ambiance is not smooth in dark scenes, and which change fixes it, measured in emitted light.
 *
 * Joe's report is that dark scenes are "flickery or steppy". Reading the code offers four
 * candidate causes and no way to rank them, so this runs the real rules
 * ([AmbianceOutputRules], the shipped ease, the shipped quantisation) over synthetic dark
 * content, through the measured device chain ([SimulatedRoom]), and counts what the strip actually
 * emitted.
 *
 * The four candidates, and what this separates:
 *
 *  1. **The floor cliff.** `floorStepped` leaves a brightest channel of 2 alone and lifts 3 to 14 -
 *     0.06% to 8.45% of full light for a one-byte change in what the camera saw.
 *  2. **Everything runs at 10fps.** The capture interval is `max(1000/fps, pacing)` and pacing
 *     defaults to 100ms, so a 20fps setting is served at 10.
 *  3. **One byte is a big step down there.** At byte 14 a single byte is a 12% light change, so
 *     there is nothing to fade through.
 *  4. **The deadband is largest in dark scenes** - 20 summed bytes at black against 15 at white.
 *
 * Prints a table. The assertions are deliberately few and about direction, not exact values: the
 * numbers are a measurement of a model, and the model is only as good as the LUT behind it.
 */
class AmbianceDarkSceneSimulation {

    private val seconds = 30
    private val deadbandMultiplier = 1.0
    private val smoothnessMs = 150

    /** One knob per candidate cause, so their contributions can be told apart. */
    private data class Config(
        val name: String,
        val rampedFloor: Boolean,
        val pacingMs: Int,
        val timeBasedEase: Boolean
    )

    /** How ambiance behaved before 2026-09-04: 100ms tick, stepped floor, a flat 0.5 per tick. */
    private val before = Config("before (100ms tick, stepped floor, flat ease)", false, 100, false)

    /** What ships now. The two middle rows are there to show which change did what. */
    private val now = Config("now (+ time-based ease)", true, BlePacing.DEFAULT_MS, true)

    private val configs = listOf(
        before,
        Config("+ ramped floor", true, 100, false),
        Config("+ pacing ${BlePacing.DEFAULT_MS}ms", true, BlePacing.DEFAULT_MS, false),
        now
    )

    /**
     * A dim scene that is genuinely moving - a shot brightening and darkening again - plus capture
     * noise.
     *
     * The amplitude matters and is the thing to get right. A *static* dark scene produces no output
     * at all: under ordinary capture noise nothing clears the deadband, which the 2026-08-16
     * simulation already established and this reproduces. That is not Joe's complaint. His
     * complaint is about watching content, where the picture moves, and the question is what the
     * strip does while it follows. So the scene swings +/- 8 bytes over a 6s period, which crosses
     * the deadband and takes the dark levels through the floor region.
     */
    private fun scene(level: Int, frameMs: Long, random: Random): List<Pair<Long, Triple<Int, Int, Int>>> {
        val out = mutableListOf<Pair<Long, Triple<Int, Int, Int>>>()
        var t = 0L
        while (t < seconds * 1000L) {
            val drift = 8.0 * kotlin.math.sin(2.0 * Math.PI * t / 6000.0)
            // Per-channel gaussian noise at sd = 1.5 bytes, which is ordinary capture noise.
            fun ch() = (level + drift + random.nextGaussian() * 1.5).toInt().coerceIn(0, 255)
            out.add(t to Triple(ch(), ch(), ch()))
            t += frameMs
        }
        return out
    }

    private fun Random.nextGaussian(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    /** Runs one configuration over one scene and returns the light the strip emitted. */
    private fun simulate(config: Config, level: Int): SimulatedDevice {
        val random = Random(level * 7919 + config.name.hashCode())
        // Capture interval, exactly as AmbianceProcessor computes it: the user's 20fps cap, floored
        // by the slowest device's pacing.
        val captureMs = maxOf(1000L / 20, config.pacingMs.toLong())
        // Interpolator tick, exactly as AmbianceOutputInterpolator computes it.
        val tickMs = config.pacingMs.toLong().coerceAtLeast(20L)

        val room = SimulatedRoom(listOf(SimulatedDevice("Fireworks", config.pacingMs)))
        val device = room.devices[0]

        var emaLinR = 0.0; var emaLinG = 0.0; var emaLinB = 0.0
        var seeded = false
        var curLinR = 0.0; var curLinG = 0.0; var curLinB = 0.0
        var hasTarget = false
        var tgtLinR = 0.0; var tgtLinG = 0.0; var tgtLinB = 0.0
        var lastWritten: Triple<Int, Int, Int>? = null
        var nextTick = tickMs

        for ((t, raw) in scene(level, captureMs, random)) {
            // Every interpolator tick that falls before this capture runs first, in order, so the
            // room's clock only ever moves forward. Ticking after the capture instead would let the
            // ease see a target it could not have had yet.
            while (nextTick <= t) {
                room.advanceTo(nextTick)
                val alpha = if (config.timeBasedEase)
                    AmbianceOutputRules.easeAlpha(tickMs, smoothnessMs) else 0.5
                curLinR += alpha * (tgtLinR - curLinR)
                curLinG += alpha * (tgtLinG - curLinG)
                curLinB += alpha * (tgtLinB - curLinB)
                val out = Triple(
                    ColorConverter.linearToSrgb(curLinR),
                    ColorConverter.linearToSrgb(curLinG),
                    ColorConverter.linearToSrgb(curLinB)
                )
                if (hasTarget && out != lastWritten) {
                    device.offer(nextTick, out.first, out.second, out.third)
                    lastWritten = out
                }
                nextTick += tickMs
            }
            room.advanceTo(t)
            val (rawR, rawG, rawB) = raw

            // --- AmbianceProcessor: EMA behind a deadband -------------------------------------
            val emaR = ColorConverter.linearToSrgb(emaLinR)
            val emaG = ColorConverter.linearToSrgb(emaLinG)
            val emaB = ColorConverter.linearToSrgb(emaLinB)
            if (!seeded) {
                emaLinR = ColorConverter.srgbToLinear(rawR)
                emaLinG = ColorConverter.srgbToLinear(rawG)
                emaLinB = ColorConverter.srgbToLinear(rawB)
                seeded = true
            } else {
                val lum = AmbianceOutputRules.luminance(emaR, emaG, emaB)
                val threshold = AmbianceOutputRules.dynamicThreshold(lum, deadbandMultiplier)
                if (AmbianceOutputRules.diff(rawR, rawG, rawB, emaR, emaG, emaB) > threshold) {
                    val alpha = 0.5
                    emaLinR += alpha * (ColorConverter.srgbToLinear(rawR) - emaLinR)
                    emaLinG += alpha * (ColorConverter.srgbToLinear(rawG) - emaLinG)
                    emaLinB += alpha * (ColorConverter.srgbToLinear(rawB) - emaLinB)
                }
            }
            val sR = ColorConverter.linearToSrgb(emaLinR)
            val sG = ColorConverter.linearToSrgb(emaLinG)
            val sB = ColorConverter.linearToSrgb(emaLinB)
            val floored = if (config.rampedFloor) AmbianceOutputRules.floorRamped(sR, sG, sB)
            else AmbianceOutputRules.floorStepped(sR, sG, sB)

            tgtLinR = ColorConverter.srgbToLinear(floored.first)
            tgtLinG = ColorConverter.srgbToLinear(floored.second)
            tgtLinB = ColorConverter.srgbToLinear(floored.third)
            if (!hasTarget) {
                curLinR = tgtLinR; curLinG = tgtLinG; curLinB = tgtLinB
                hasTarget = true
            }

        }
        room.advanceTo(seconds * 1000L + 200L)
        return device
    }

    @Test
    fun `what makes dark scenes steppy, and what fixes it`() {
        val levels = listOf(6, 10, 14, 20, 40, 120)
        var beforeSteps = 0
        var nowSteps = 0
        var beforeLargest = 0.0
        var nowLargest = 0.0
        println("\n=== Light the strip emitted, ${seconds}s of a slowly drifting scene ===")
        println("(a 'visible step' is a jump of 2% of full output or more between two writes)\n")

        for (config in configs) {
            println(config.name)
            for (level in levels) {
                val device = simulate(config, level)
                val trace = device.strip.trace
                println(
                    "  scene byte %3d :  %s".format(
                        level, TraceMetrics.summary(trace, seconds * 1000L)
                    )
                )
                if (config === before) {
                    beforeSteps += TraceMetrics.visibleJumps(trace)
                    beforeLargest = maxOf(beforeLargest, TraceMetrics.largestJump(trace))
                }
                if (config === now) {
                    nowSteps += TraceMetrics.visibleJumps(trace)
                    nowLargest = maxOf(nowLargest, TraceMetrics.largestJump(trace))
                }
            }
            println()
        }

        // The point of the change, stated as something that can fail. Not exact values - the
        // numbers are a measurement of a model - but the direction and the order of magnitude.
        assertTrue(
            "the old configuration should show visible steps to fix ($beforeSteps found)",
            beforeSteps >= 10
        )
        assertEquals(
            "what ships now should show no visible steps at any level",
            0, nowSteps
        )
        assertTrue(
            "the largest single jump should have at least halved: $beforeLargest -> $nowLargest",
            nowLargest < beforeLargest / 2
        )
    }

    @Test
    fun `the stepped floor snaps between off and lit, and the ramped one does not`() {
        // The cliff in isolation: walk the brightest channel through the boundary one byte at a
        // time and look at the emitted light either side.
        val steppedJumps = (0..16).map { c ->
            val a = AmbianceOutputRules.floorStepped(c, c, c)
            val b = AmbianceOutputRules.floorStepped(c + 1, c + 1, c + 1)
            abs(lightOf(b) - lightOf(a))
        }
        val rampedJumps = (0..16).map { c ->
            val a = AmbianceOutputRules.floorRamped(c, c, c)
            val b = AmbianceOutputRules.floorRamped(c + 1, c + 1, c + 1)
            abs(lightOf(b) - lightOf(a))
        }
        println("\n=== Largest one-byte light jump across the dark floor ===")
        println("  stepped: %.1f%% of full output".format(steppedJumps.max() * 100))
        println("  ramped:  %.1f%% of full output".format(rampedJumps.max() * 100))

        assertTrue(
            "the shipped stepped floor should show a large cliff",
            steppedJumps.max() > 0.05
        )
        assertTrue(
            "the ramped floor should have no cliff anywhere near that size",
            rampedJumps.max() < steppedJumps.max() / 3
        )
    }

    @Test
    fun `the time-based ease preserves the shipped feel at the shipped tick rate`() {
        // The whole point of the change is that fades stop depending on tick rate. It must not
        // retune the default: at the shipped 100ms tick and 150ms smoothness the alpha has to come
        // out at the shipped flat 0.5.
        val atShipped = AmbianceOutputRules.easeAlpha(100L, 150)
        assertTrue(
            "alpha at the shipped tick should be near 0.5, was $atShipped",
            abs(atShipped - 0.5) < 0.02
        )
        // And five times the tick rate must close the same distance over the same wall time.
        val fast = (1..5).fold(1.0) { remaining, _ -> remaining * (1 - AmbianceOutputRules.easeAlpha(20L, 150)) }
        val slow = 1.0 - AmbianceOutputRules.easeAlpha(100L, 150)
        assertTrue(
            "100ms of easing should be the same whether taken in one tick or five, $fast vs $slow",
            abs(fast - slow) < 0.01
        )
    }

    private fun lightOf(c: Triple<Int, Int, Int>): Double =
        0.2126 * com.example.core.color.StripResponse.lightForByte(c.first) +
            0.7152 * com.example.core.color.StripResponse.lightForByte(c.second) +
            0.0722 * com.example.core.color.StripResponse.lightForByte(c.third)
}
