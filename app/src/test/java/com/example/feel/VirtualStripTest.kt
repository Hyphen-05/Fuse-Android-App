package com.example.feel

import com.example.core.protocol.DuoCoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the strip model's two measured behaviours, because both replaced an earlier model that was
 * confidently wrong and nothing would have caught the swap being botched.
 *
 * The behaviours: colours are lost in the *queue* when a newer write of the same type overtakes one
 * still waiting, and never lost at the strip for arriving too fast; and what a render shows is
 * emitted light, which is far from the commanded byte on hardware this compressive.
 */
class VirtualStripTest {

    private fun colour(r: Int, g: Int, b: Int) = DuoCoProtocol.createMusicColorCommand(r, g, b)

    @Test
    fun `writes spaced wider than the in-flight window are all shown`() {
        val strip = VirtualStrip()
        // 100ms apart: past the first write's post-idle window, and far past the 5ms warm one.
        strip.write(0, colour(255, 0, 0))
        strip.write(100, colour(0, 255, 0))
        strip.write(200, colour(0, 0, 255))

        val stats = strip.stats()
        assertEquals("all three should reach the strip", 3, stats.accepted)
        assertEquals("none should be coalesced", 0, stats.coalesced)
    }

    @Test
    fun `a write overtaken in the queue is never seen`() {
        val strip = VirtualStrip()
        // The first write starts a 68ms post-idle window. The next two both land inside it, so the
        // second is replaced by the third and never goes out at all.
        strip.write(0, colour(255, 0, 0))
        strip.write(10, colour(0, 255, 0))
        strip.write(20, colour(0, 0, 255))

        val stats = strip.stats()
        assertEquals("exactly one colour should be swallowed", 1, stats.coalesced)

        // And the survivor is the last one, not the one that was overtaken.
        val frames = strip.timeline(untilMs = 400)
        val settled = frames.last()
        assertTrue("blue should have won, got (${settled.r}, ${settled.g}, ${settled.b})",
            settled.b > settled.r && settled.b > settled.g)
    }

    @Test
    fun `close spacing alone does not lose writes once the radio is warm`() {
        val strip = VirtualStrip()
        strip.write(0, colour(10, 10, 10))          // absorbs the post-idle window
        var at = 200L
        repeat(20) {
            strip.write(at, colour(255, 0, 0))
            at += 8                                  // 8ms apart — under the old 20ms "strip limit"
        }
        // The hardware rendered every write at this spacing (110/110 bursts, 6-66ms). What the model
        // may do is queue them; what it must not do is drop them for being too close together.
        val stats = strip.stats()
        assertTrue("writes at 8ms should still reach the strip, got ${stats.accepted} accepted",
            stats.accepted > 1)
    }

    @Test
    fun `renders report emitted light, not the commanded byte`() {
        val strip = VirtualStrip()
        strip.write(0, colour(32, 32, 32))
        val frame = strip.timeline(untilMs = 300).last()

        // Measured: byte 32 emits about 42% of full light. Commanded bytes would give 13%.
        val fraction = frame.r / 255.0
        assertTrue("byte 32 should emit ~42% light, got ${"%.0f".format(fraction * 100)}%",
            fraction in 0.38..0.48)

        val raw = strip.timeline(untilMs = 300, inEmittedLight = false).last()
        assertEquals("raw mode should hand back the commanded byte", 32, raw.r)
    }
}
