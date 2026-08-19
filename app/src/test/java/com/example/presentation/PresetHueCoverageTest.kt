package com.example.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPROVEMENT_PLAN D.1: three presets are mathematically confined to a handful of colours, and the
 * unlock toggle frees them.
 *
 * The point of testing the *arithmetic* rather than eyeballing a render is that this was never a
 * tuning problem. The anchor moves only by `hueAnchorJumpDeg`, so when that divides 360 and nothing
 * drifts, the set of reachable hues is fixed forever no matter what the audio does.
 */
class PresetHueCoverageTest {

    /** Walks the anchor the way `AudioDspProcessor` does and counts what it can actually reach. */
    private fun reachableHues(jumpDeg: Float, steps: Int = 5000): Int {
        val seen = HashSet<Int>()
        var anchor = 0f
        repeat(steps) {
            anchor = (anchor + jumpDeg) % 360f
            seen.add(Math.round(anchor * 2f))
        }
        return seen.size
    }

    @Test
    fun `the counting matches an actual walk of the anchor`() {
        // If these disagree, the closed form is wrong and every other assertion here is worthless.
        for (jump in listOf(90f, 120f, 180f, 60f, 137.5f, 93.5f, 123.5f, 174.5f)) {
            assertEquals("jump $jump", reachableHues(jump), distinctAnchorHues(jump))
        }
    }

    @Test
    fun `the three locked presets are locked, and by how much`() {
        assertEquals(4, distinctAnchorHues(90f))    // Punchy
        assertEquals(3, distinctAnchorHues(120f))   // Strobe Blast
        assertEquals(2, distinctAnchorHues(180f))   // Laser Sharp
    }

    @Test
    fun `unlocking gives all three the whole wheel`() {
        for (jump in listOf(90f, 120f, 180f)) {
            assertTrue(
                "jump $jump unlocked to ${unlockedHueJump(jump)} reaches only " +
                    "${distinctAnchorHues(unlockedHueJump(jump))}",
                distinctAnchorHues(unlockedHueJump(jump)) >= 360
            )
        }
    }

    @Test
    fun `unlocking keeps the leap that gives each preset its character`() {
        // Laser Sharp's near-flip must stay a near-flip, Punchy's quarter-turn a quarter-turn.
        // Collapsing all three onto the golden angle would have made them look like each other.
        for (jump in listOf(90f, 120f, 180f)) {
            assertTrue(Math.abs(unlockedHueJump(jump) - jump) <= 5f)
        }
    }

    @Test
    fun `drifting presets are not treated as confined`() {
        // Bass Thump and Ambient Chill both jump 60 degrees, which divides 360 — but they drift,
        // and drift walks the anchor off the lattice. They must be left alone.
        assertFalse(isHueConfined(jumpDeg = 60f, driftDegPerSec = 2f))
        assertFalse(isHueConfined(jumpDeg = 60f, driftDegPerSec = 1.5f))
        assertTrue(isHueConfined(jumpDeg = 60f, driftDegPerSec = 0f))
    }

    @Test
    fun `presets that never advance the anchor are not confined`() {
        // Smooth Flow and the idle configs use jump 0 — all their motion is continuous.
        assertFalse(isHueConfined(jumpDeg = 0f, driftDegPerSec = 0f))
    }

    @Test
    fun `beat only is already free and the toggle leaves it alone`() {
        assertFalse(isHueConfined(jumpDeg = 137.5f, driftDegPerSec = 0f))
        assertEquals(
            visualizerConfigFor("Beat Only").hueAnchorJumpDeg,
            visualizerConfigFor("Beat Only", unlockHues = true).hueAnchorJumpDeg,
            0.001f
        )
    }

    @Test
    fun `the toggle only moves the presets that need moving`() {
        val presets = listOf(
            "Punchy", "Smooth Flow", "Strobe Blast", "Ambient Chill",
            "Bass Thump", "Laser Sharp", "Beat Only"
        )
        val moved = presets.filter {
            visualizerConfigFor(it).hueAnchorJumpDeg !=
                visualizerConfigFor(it, unlockHues = true).hueAnchorJumpDeg
        }
        assertEquals(listOf("Punchy", "Strobe Blast", "Laser Sharp"), moved)
    }

    @Test
    fun `off is the default and changes nothing`() {
        for (preset in listOf("Punchy", "Strobe Blast", "Laser Sharp")) {
            assertEquals(
                visualizerConfigFor(preset).hueAnchorJumpDeg,
                visualizerConfigFor(preset, unlockHues = false).hueAnchorJumpDeg,
                0.001f
            )
        }
    }

    @Test
    fun `no preset ends up confined once unlocked`() {
        val presets = listOf(
            "Punchy", "Smooth Flow", "Strobe Blast", "Ambient Chill",
            "Bass Thump", "Laser Sharp", "Beat Only"
        )
        for (preset in presets) {
            val config = visualizerConfigFor(preset, unlockHues = true)
            assertFalse(
                "$preset is still confined after unlocking",
                isHueConfined(config.hueAnchorJumpDeg, config.hueDriftDegPerSec)
            )
        }
    }
}
