package com.example.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor calibration exists because the first sitting ran most of its trials in the dark.
 *
 * These check the two things that would put it back there: a floor that reads below where light
 * actually starts, and a session config that places trials below the floor it was handed.
 */
class FloorFinderTest {

    @Test
    fun `the ladder starts at one, rises, and reaches a level any strip can show`() {
        val ladder = FloorFinder.LADDER
        assertEquals("the bottom rung is the dimmest commandable non-zero byte", 1, ladder.first())
        assertTrue("the ladder must be strictly increasing", ladder.zipWithNext().all { it.first < it.second })
        assertTrue("the top rung should be well clear of the floor on any setup", ladder.last() >= 100)
    }

    @Test
    fun `the floor sits between the two crossings, not on either one`() {
        // Ascending reads high and descending reads low; taking either alone biases the floor in a
        // known direction, which is the whole reason for doing both passes.
        val seenGoingUp = 12
        val darkGoingDown = 7
        val floor = FloorFinder.combineCrossings(seenGoingUp, darkGoingDown)
        assertTrue("floor $floor should sit between 8 and 12", floor in 8..12)
        assertTrue("and strictly inside, given the two disagree", floor < seenGoingUp)
    }

    @Test
    fun `a floor of one byte is handled rather than clamped to zero`() {
        // He can see byte 1 and there is nothing below it. The floor must still be a level the
        // strip can be commanded to, not 0.
        assertEquals(1, FloorFinder.combineCrossings(seenAscendingAt = 1, darkDescendingAt = 0))
    }

    @Test
    fun `no trial is ever placed below the level he called clearly lit`() {
        // The failure this whole class exists to prevent: a base level the strip cannot show.
        for (clear in listOf(1, 2, 5, 14, 40, 90)) {
            val floor = FloorFinder.FloorResult(firstVisible = maxOf(1, clear - 2), clearlyOn = clear)
            val config = FloorFinder.sessionConfigFor(floor)
            assertTrue(
                "base levels ${config.baseLevels} must all sit at or above the clearly-lit byte $clear",
                config.baseLevels.all { it >= clear }
            )
            assertTrue(
                "the dither base $clear must be lit too",
                config.ditherBase >= clear
            )
            assertTrue(
                "the fade must start at or above it",
                config.fadeFrom >= clear
            )
            assertTrue(
                "a fade needs somewhere to travel, got span ${config.fadeSpan}",
                config.fadeSpan >= 6
            )
        }
    }

    @Test
    fun `a high floor does not push base levels off the end of the range`() {
        val config = FloorFinder.sessionConfigFor(FloorFinder.FloorResult(90, 100))
        assertTrue("levels ${config.baseLevels} must stay commandable", config.baseLevels.all { it in 1..255 })
        assertEquals(
            "levels that collide after clamping should not be asked twice",
            config.baseLevels.distinct().size,
            config.baseLevels.size
        )
    }

    @Test
    fun `the shortened sitting is the one that gets configured`() {
        // Joe asked for something he can sit through. The original design simulated at a median of
        // 195 trials; three staircases at six reversals is the shape that replaced it.
        val config = FloorFinder.sessionConfigFor(FloorFinder.FloorResult(4, 6))
        assertEquals(3, config.baseLevels.size)
        assertEquals(6, config.reversalsToFinish)
    }
}
