package com.example.core.perception.lab

import com.example.core.color.StripResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Block 9 and its reading.
 *
 * The standard is the quantisation probe's: a synthetic viewer with *known* weights behind it, and
 * the reading has to hand those weights back. That says the arithmetic is right - not that Joe's eye
 * works this way, which is what the sitting is for.
 */
class ColourWeightsTest {

    private val trials = LabBlocks.colourWeightTrials(seed = 7L)

    /**
     * A viewer who sees `weight x light` per channel, calls anything within [sameWithin] "Same",
     * and otherwise names the brighter one. With [secondWhenSame], "Same" becomes "b" instead -
     * Joe's documented tie-break.
     */
    private fun viewer(
        weights: DoubleArray,
        sameWithin: Double = 1.15,
        secondWhenSame: Boolean = false
    ): List<LabAnswer> = trials.map { t ->
        val (a, b) = t.intervals.map { s ->
            val (r, g, bl) = s.steps.first().commanded
            weights[0] * StripResponse.lightForByte(r) +
                weights[1] * StripResponse.lightForByte(g) +
                weights[2] * StripResponse.lightForByte(bl)
        }
        val id = when {
            a > b * sameWithin -> "a"
            b > a * sameWithin -> "b"
            secondWhenSame -> "b"
            else -> "unsure"
        }
        LabAnswer(id, 800)
    }

    private val eye = doubleArrayOf(0.35, 1.0, 0.12)

    @Test
    fun `the reading recovers known weights`() {
        val reading = ColourWeights.read(trials, viewer(eye))
        val w = reading.weights!!
        // The ladder doubles, so a match can only be placed to within its rungs; a factor of 1.5 is
        // what interpolating between doubling rungs can promise.
        assertTrue("red ${w.first}", ColourWeights.agrees(w.first, 0.35, 1.5))
        assertTrue("blue ${w.third}", ColourWeights.agrees(w.third, 0.12, 1.5))
    }

    @Test
    fun `a consistent eye makes the three pairs agree with each other`() {
        val reading = ColourWeights.read(trials, viewer(eye))
        val chain = reading.chainAgreement!!
        assertTrue("chain $chain", ColourWeights.agrees(chain, 1.0, 1.5))
    }

    @Test
    fun `naming the second interval when unsure does not move the match`() {
        // The habit adds +1 to one order and -1 to the other at every rung he cannot call, so with
        // balanced orders it cancels rather than dragging the match towards the second arm.
        val honest = ColourWeights.read(trials, viewer(eye)).weights!!
        val habit = ColourWeights.read(trials, viewer(eye, secondWhenSame = true)).weights!!
        assertTrue(ColourWeights.agrees(honest.first, habit.first, 1.3))
        assertTrue(ColourWeights.agrees(honest.third, habit.third, 1.3))
    }

    @Test
    fun `a match outside the ladder is reported as outside, not invented`() {
        // Blue counting a hundredth as much needs 100x the light, far above the top rung.
        val reading = ColourWeights.read(trials, viewer(doubleArrayOf(0.35, 1.0, 0.01)))
        val bg = reading.pair(LabBlocks.CHANNEL_BLUE, LabBlocks.CHANNEL_GREEN)!!
        assertNull(bg.matchRatio)
        assertEquals(1, bg.outOfRange)
        assertNull(reading.weights)
    }

    @Test
    fun `a perfect viewer trips no controls, and an absent one trips the anchors`() {
        val good = ColourWeights.read(trials, viewer(eye))
        assertEquals(0, good.catchFalsePositives)
        assertEquals(0, good.anchorsMissed)

        val absent = ColourWeights.read(trials, trials.map { LabAnswer("unsure", 300) })
        assertEquals(absent.anchorTrials, absent.anchorsMissed)
        absent.pairs.forEach { assertNull(it.matchRatio) }
    }

    @Test
    fun `an unorderable pair is flagged`() {
        // Alternating answers up the ladder: no single crossover exists.
        val answers = trials.map { t ->
            if (t.kind != "match") LabAnswer("unsure", 300) else {
                val testWins = t.meta.getValue("rung") % 2 == 0
                val testFirst = t.meta.getValue("testFirst") == 1
                LabAnswer(if (testWins == testFirst) "a" else "b", 300)
            }
        }
        assertTrue(ColourWeights.read(trials, answers).pairs.all { it.nonMonotonic })
        assertFalse(ColourWeights.read(trials, viewer(eye)).pairs.any { it.nonMonotonic })
    }

    // --- the block itself ------------------------------------------------------------------------

    @Test
    fun `every rung is asked once in each order`() {
        trials.filter { it.kind == "match" }
            .groupBy { it.meta.getValue("pair") to it.meta.getValue("rung") }
            .forEach { (key, rows) ->
                assertEquals("$key", setOf(0, 1), rows.map { it.meta.getValue("testFirst") }.toSet())
                assertEquals("$key", 2, rows.size)
            }
    }

    @Test
    fun `the recorded order is the order that plays`() {
        trials.filter { it.kind == "match" }.forEach { t ->
            val testByte = t.meta.getValue("testByte")
            val testChannel = t.meta.getValue("testChannel")
            val shown = if (t.meta.getValue("testFirst") == 1) t.intervals[0] else t.intervals[1]
            assertEquals(LabBlocks.channelStimulus(testChannel, testByte).label, shown.label)
        }
    }

    @Test
    fun `the whole ladder lands on measured bytes and climbs`() {
        // Below byte 8 the table is a lower bound, not a measurement; at 255 it has run out.
        trials.filter { it.kind == "match" }.groupBy { it.meta.getValue("pair") }.values.forEach { rows ->
            val bytes = rows.sortedBy { it.meta.getValue("rung") }.map { it.meta.getValue("testByte") }.distinct()
            assertEquals(6, bytes.size)
            assertTrue("ladder $bytes", bytes.zipWithNext().all { (a, b) -> b > a })
            assertTrue("ladder $bytes", bytes.first() >= 8 && bytes.last() < 255)
        }
    }

    @Test
    fun `each stimulus lights one channel only`() {
        trials.flatMap { it.intervals }.forEach { s ->
            val (r, g, b) = s.steps.single().commanded
            assertEquals(1, listOf(r, g, b).count { it > 0 })
        }
    }

    @Test
    fun `it runs at full brightness, where the top of the ladder is reachable`() {
        assertTrue(LabBlocks.COLOUR_WEIGHTS.commandsBrightness)
        assertTrue(trials.all { it.brightnessPercent == 100 })
    }

    @Test
    fun `the sitting starts with the floor and includes this block`() {
        assertEquals(LabBlocks.FLOOR_GRID, LabBlocks.SITTING.first())
        assertTrue(LabBlocks.COLOUR_WEIGHTS in LabBlocks.SITTING)
        assertTrue(LabBlocks.SITTING.none { it.isRetired })
    }
}
