package com.example.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attention signal is the only channel to Joe during a capture session — the monitor is off to
 * keep light out of frame, so the strip itself has to say "come here".
 *
 * These pin the two things that stop it working: it must not look like a measurement, and it must
 * not look like a strobe. Both are judged by eye on hardware, but neither is judgeable at all if the
 * colour rule is broken, so the rule is pinned here rather than left to a reviewer's memory.
 */
class CalibrationAttentionSignalTest {

    private val steps = (0 until CalibrationSequences.ATTENTION_STEPS)
        .map { CalibrationSequences.attentionColourAt(it) }

    @Test
    fun `never travels through white or yellow`() {
        steps.forEach { (r, g, b) ->
            assertEquals("red is pinned full — this is the fiery end of orange", 255, r)
            assertTrue("green reaching red would read as yellow: was $g", g <= 150)
            assertTrue("blue climbing with green would read as white: was $b", b <= 30)
            // Blue well under green is what separates orange from a pale wash.
            assertTrue("blue must stay far below green: g=$g b=$b", b < g / 2)
        }
    }

    @Test
    fun `breathes between the two commanded endpoints`() {
        val greens = steps.map { it.second }
        assertEquals("starts at the dim end", 70, greens.first())
        assertEquals("reaches the bright end mid-breath", 150, greens.max())
        assertTrue("returns to the dim end so the loop does not jump", greens.last() < 80)
    }

    @Test
    fun `has no corner at the turnaround`() {
        // A triangle wave visibly ticks at the top and bottom of each breath. A raised cosine
        // flattens there, so the largest per-step change sits in the middle, not at either end.
        val greens = steps.map { it.second }
        val deltas = greens.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        val biggest = deltas.indexOf(deltas.max())
        assertTrue("fastest change should be mid-breath, was at step $biggest", biggest in 5..30)
        assertTrue("turnaround should be nearly still", deltas.first() <= 2)
    }

    @Test
    fun `is a breath, not a strobe`() {
        val breathMs = CalibrationSequences.ATTENTION_STEP_MS * CalibrationSequences.ATTENTION_STEPS
        // Joe's spec, 2026-08-29: smooth but not slow. Under a second reads as a strobe or as a
        // visualiser preset; over two and it stops reading as "come here".
        assertTrue("one breath should be 1.2-1.6s, was ${breathMs}ms", breathMs in 1200..1600)
    }
}
