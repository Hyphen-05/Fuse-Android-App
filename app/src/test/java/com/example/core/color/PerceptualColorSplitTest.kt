package com.example.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The split is applied to every colour the app sends, so the property that matters most is that it
 * is **invisible**: same emitted light, finer steps. These tests pin that, plus the two things that
 * would quietly break if someone refactored it - hue drift and the Dimming slider.
 */
class PerceptualColorSplitTest {

    /** What the strip emits for a split colour: the scaled bytes, dimmed by the brightness duty. */
    private fun emitted(s: SplitColor): Triple<Double, Double, Double> {
        val d = s.brightnessPercent / 100.0
        return Triple(
            PerceptualColorSplit.emittedLight(s.r) * d,
            PerceptualColorSplit.emittedLight(s.g) * d,
            PerceptualColorSplit.emittedLight(s.b) * d
        )
    }

    @Test
    fun `emitted light is preserved across the whole range`() {
        val colours = listOf(
            Triple(255, 255, 255), Triple(128, 64, 32), Triple(96, 96, 96),
            Triple(24, 12, 6), Triple(8, 8, 8), Triple(6, 3, 1), Triple(255, 0, 0),
            Triple(0, 40, 80), Triple(3, 0, 0)
        )
        for ((r, g, b) in colours) {
            val split = PerceptualColorSplit.split(r, g, b)
            val (er, eg, eb) = emitted(split)
            val wantR = PerceptualColorSplit.emittedLight(r)
            val wantG = PerceptualColorSplit.emittedLight(g)
            val wantB = PerceptualColorSplit.emittedLight(b)
            // 2% of full light: brightness is whole percents, so exact equality is not available
            // and is not needed - this is far below the byte grid it replaces.
            assertTrue(
                "($r,$g,$b) -> $split emitted ($er,$eg,$eb) want ($wantR,$wantG,$wantB)",
                abs(er - wantR) < 0.02 && abs(eg - wantG) < 0.02 && abs(eb - wantB) < 0.02
            )
        }
    }

    @Test
    fun `hue survives the rescale`() {
        val split = PerceptualColorSplit.split(12, 6, 3)
        assertEquals(255, split.r)
        assertEquals(2.0, split.r.toDouble() / split.g, 0.05)
        assertEquals(4.0, split.r.toDouble() / split.b, 0.1)
    }

    @Test
    fun `a colour above the knee is left exactly as it is`() {
        // The colour axis is finer than the dimmer up here, so splitting would cost resolution
        // rather than buy it. Byte-identical output is the whole point — see SPLIT_KNEE.
        val split = PerceptualColorSplit.split(24, 12, 6, userDimmingPercent = 80)
        assertEquals(24, split.r)
        assertEquals(12, split.g)
        assertEquals(6, split.b)
        assertEquals("and the dimmer keeps the user's setting", 80, split.brightnessPercent)
    }

    @Test
    fun `the knee does not make light jump`() {
        // Either side of the boundary the emitted light must still land on the same curve.
        val below = emitted(PerceptualColorSplit.split(13, 13, 13))
        val above = emitted(PerceptualColorSplit.split(14, 14, 14))
        assertTrue("below=$below above=$above", above.first - below.first in 0.0..0.02)
    }

    @Test
    fun `a dim colour gets the full byte range back`() {
        val split = PerceptualColorSplit.split(6, 3, 1)
        assertEquals("top channel should be pushed to full scale", 255, split.r)
        assertTrue("and the level moves to brightness", split.brightnessPercent < 30)
    }

    @Test
    fun `the dimming slider still works`() {
        val full = PerceptualColorSplit.split(200, 200, 200, userDimmingPercent = 100)
        val half = PerceptualColorSplit.split(200, 200, 200, userDimmingPercent = 50)
        assertEquals(full.r, half.r)
        assertEquals("half dimming should halve the brightness duty",
            (full.brightnessPercent / 2).toDouble(), half.brightnessPercent.toDouble(), 1.0)
    }

    @Test
    fun `black stays black and leaves the user's dimming alone`() {
        val split = PerceptualColorSplit.split(0, 0, 0, userDimmingPercent = 70)
        assertEquals(0, split.r); assertEquals(0, split.g); assertEquals(0, split.b)
        assertEquals("otherwise turning colour back up restores a stale level", 70, split.brightnessPercent)
    }

    @Test
    fun `dimming to zero turns the strip off rather than stopping at one percent`() {
        val split = PerceptualColorSplit.split(255, 128, 0, userDimmingPercent = 0)
        assertEquals("the slider's bottom end has to still mean off", 0, split.brightnessPercent)
    }

    @Test
    fun `a lit colour never rounds down to off`() {
        val split = PerceptualColorSplit.split(1, 0, 0)
        assertTrue("got ${split.brightnessPercent}", split.brightnessPercent >= 1)
    }

    @Test
    fun `full-scale colours are left where they are`() {
        val split = PerceptualColorSplit.split(255, 128, 0)
        assertEquals(255, split.r)
        assertEquals(128, split.g)
        assertEquals(0, split.b)
        assertEquals(100, split.brightnessPercent)
    }
}
