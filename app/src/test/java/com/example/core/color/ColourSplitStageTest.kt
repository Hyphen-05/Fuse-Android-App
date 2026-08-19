package com.example.core.color

import com.example.core.protocol.DuoCoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Covers the write-boundary behaviour of the split. The arithmetic itself is
 * [PerceptualColorSplitTest]'s job; what is checked here is what actually reaches the queue —
 * how many commands, of which types, in which order, and when a write is skipped.
 */
class ColourSplitStageTest {

    private fun stage(dim: Int = 100) = ColourSplitStage { dim }

    private fun colour(r: Int, g: Int, b: Int) = DuoCoProtocol.createColorCommand(r, g, b)
    private fun brightness(p: Int) = DuoCoProtocol.createBrightnessCommand(p)

    private fun typeOf(command: ByteArray) = command[2]
    private fun percentOf(command: ByteArray) = command[3].toInt() and 0xFF
    private fun rgbOf(command: ByteArray) = Triple(
        command[4].toInt() and 0xFF,
        command[5].toInt() and 0xFF,
        command[6].toInt() and 0xFF
    )

    /** Light a byte emits on the measured response curve, so "looks the same" can be asserted. */
    private fun light(byteValue: Int) = (byteValue / 255.0).pow(0.4)

    @Test
    fun a_colour_below_the_knee_becomes_brightness_then_full_range_colour() {
        val out = stage().process(colour(8, 4, 0))

        assertEquals(2, out.size)
        assertEquals(0x01.toByte(), typeOf(out[0]))
        assertEquals(0x05.toByte(), typeOf(out[1]))
        // Top channel normalised to 255, ratios preserved.
        assertEquals(Triple(255, 128, 0), rgbOf(out[1]))
    }

    @Test
    fun a_colour_above_the_knee_goes_out_untouched_as_one_write() {
        // The regression Joe caught on hardware: splitting up here moved level off the finer axis
        // and made smooth gradients coarser. Ambiance lives entirely in this range, so above the
        // knee the bytes must be exactly what the caller asked for, at one write per frame.
        val stage = stage()

        // The first colour of a connection also asserts the dimmer, once: nothing is known about
        // where the strip's brightness actually is until something sets it.
        val first = stage.process(colour(64, 32, 0))
        assertEquals(2, first.size)
        assertEquals(0x01.toByte(), typeOf(first[0]))
        assertEquals(100, percentOf(first[0]))
        assertTrue(first[1].contentEquals(colour(64, 32, 0)))

        // Steady state, which is what a gradient actually looks like: one write, bytes untouched.
        for (peak in listOf(63, 62, 48, 32, 20, 14)) {
            val original = colour(peak, peak / 2, 0)
            val out = stage.process(original)
            assertEquals("peak $peak should not split", 1, out.size)
            assertTrue("peak $peak should be byte-identical", out[0].contentEquals(original))
        }
    }

    @Test
    fun the_split_preserves_appearance() {
        val out = stage().process(colour(8, 4, 0))
        val dimmed = percentOf(out[0]) / 100.0

        // Each channel's emitted light after the split, scaled by the firmware dimmer, must match
        // what the original bytes emitted on their own.
        val (r, g, b) = rgbOf(out[1])
        assertTrue(abs(light(r) * dimmed - light(8)) < 0.02)
        assertTrue(abs(light(g) * dimmed - light(4)) < 0.02)
        assertTrue(abs(light(b) * dimmed - light(0)) < 0.02)
    }

    @Test
    fun music_colour_keeps_its_marker_byte() {
        val out = stage().process(DuoCoProtocol.createMusicColorCommand(2, 4, 6))

        val emitted = out.last()
        assertEquals(0x20.toByte(), emitted[7])
        assertEquals(Triple(85, 170, 255), rgbOf(emitted))
    }

    @Test
    fun unchanged_level_costs_no_brightness_write() {
        val stage = stage()
        stage.process(colour(8, 4, 0))

        // Same peak, different hue — a constant-luminance sweep. One write, not two.
        val out = stage.process(colour(0, 8, 4))
        assertEquals(1, out.size)
        assertEquals(0x05.toByte(), typeOf(out[0]))
    }

    @Test
    fun changed_level_writes_brightness_again() {
        val stage = stage()
        stage.process(colour(12, 12, 12))

        val out = stage.process(colour(3, 3, 3))
        assertEquals(2, out.size)
        assertEquals(0x01.toByte(), typeOf(out[0]))
        assertTrue(percentOf(out[0]) < 100)
    }

    @Test
    fun dimming_slider_is_composed_in_rather_than_overwriting_the_level() {
        val stage = stage()
        val atFull = percentOf(stage.process(colour(8, 4, 0))[0])

        // The user drags Dimming to 50%. The emitted brightness must be the level *scaled*, not a
        // bare 50 that throws the split level away.
        val out = stage.process(brightness(50))
        assertEquals(1, out.size)
        assertEquals(0x01.toByte(), typeOf(out[0]))
        assertTrue(abs(percentOf(out[0]) - atFull * 0.5) <= 1.0)
    }

    @Test
    fun a_later_colour_respects_the_dim_the_user_set() {
        val stage = stage()
        stage.process(colour(255, 255, 255))
        stage.process(brightness(40))

        val out = stage.process(colour(8, 4, 0))
        assertTrue(percentOf(out[0]) <= 40)
        assertEquals(Triple(255, 128, 0), rgbOf(out[1]))
    }

    @Test
    fun brightness_before_any_colour_passes_through_at_face_value() {
        val out = stage().process(brightness(70))

        assertEquals(1, out.size)
        assertEquals(70, percentOf(out[0]))
    }

    @Test
    fun the_seeded_dimming_is_used_until_a_brightness_command_arrives() {
        val out = ColourSplitStage { 50 }.process(colour(255, 255, 255))

        // Full-white at a 50% slider is level 1.0 x 50%.
        assertEquals(50, percentOf(out[0]))
    }

    @Test
    fun black_keeps_the_users_dim_so_turning_the_colour_back_up_is_not_stale() {
        val stage = stage()
        stage.process(brightness(60))

        // Black has no level to move, so the dimmer stays where the user put it — and because it
        // is already there, the write is skipped rather than resent.
        val out = stage.process(colour(0, 0, 0))
        assertEquals(1, out.size)
        assertEquals(Triple(0, 0, 0), rgbOf(out[0]))

        // Proven by what comes next: a mid colour dips below the user's setting, and full white
        // comes back to exactly it rather than to a stale level.
        val mid = stage.process(colour(6, 6, 6))
        assertEquals(0x01.toByte(), typeOf(mid[0]))
        assertTrue(percentOf(mid[0]) < 60)

        val back = stage.process(colour(255, 255, 255))
        assertEquals(60, percentOf(back[0]))
    }

    @Test
    fun a_batched_payload_stays_one_write() {
        // syncPhysicalBulb's shape: power + colour + brightness in a single GATT write.
        val batched = DuoCoProtocol.createPowerCommand(true) +
            colour(8, 4, 0) +
            brightness(80)

        val out = stage().process(batched)

        assertEquals(1, out.size)
        // Power frame untouched, colour normalised in place, and no frame added or dropped.
        assertEquals(batched.size, out[0].size)
        assertTrue(out[0].copyOfRange(0, 9).contentEquals(DuoCoProtocol.createPowerCommand(true)))
        assertEquals(Triple(255, 128, 0), rgbOf(out[0].copyOfRange(9, 18)))
    }

    @Test
    fun a_payload_that_is_not_whole_frames_passes_through_untouched() {
        val odd = byteArrayOf(0x7e, 0x00, 0x05, 0x03, 0x40)
        val out = stage().process(odd)

        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(odd))
    }

    @Test
    fun non_colour_commands_are_left_alone() {
        val mode = DuoCoProtocol.createModeCommand(7)
        val out = stage().process(mode)

        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(mode))
    }

    @Test
    fun cct_is_not_treated_as_an_rgb_colour() {
        // Shares the 0x05 type byte with colour; only the sub-selector separates them.
        val cct = DuoCoProtocol.createCctCommand(200, 55)
        val out = stage().process(cct)

        assertEquals(1, out.size)
        assertTrue(out[0].contentEquals(cct))
    }
}
