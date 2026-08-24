package com.example.hardware.ble

import com.example.core.protocol.DuoCoProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two halves of the dedupe rule: it must drop colours the strip is already showing, and it
 * must never drop anything a user is waiting on.
 *
 * The second half is the one worth guarding. Writes go out `WRITE_TYPE_NO_RESPONSE`, so a suppressed
 * power or brightness command that the strip never actually received leaves a control that visibly
 * does nothing — and unlike a colour frame, nothing arrives milliseconds later to cover for it.
 */
class WriteDedupeTest {

    private fun colour(r: Int, g: Int, b: Int) = DuoCoProtocol.createColorCommand(r, g, b)
    private fun musicColour(r: Int, g: Int, b: Int) = DuoCoProtocol.createMusicColorCommand(r, g, b)

    @Test
    fun `an identical colour is redundant`() {
        assertTrue(WriteDedupe.isRedundantColour(colour(12, 12, 12), colour(12, 12, 12)))
    }

    @Test
    fun `a colour differing by one byte is not redundant`() {
        // The whole point: one byte near black is a large step in emitted light, so this must go.
        assertFalse(WriteDedupe.isRedundantColour(colour(13, 12, 12), colour(12, 12, 12)))
    }

    @Test
    fun `the first colour after a connection is never redundant`() {
        assertFalse(WriteDedupe.isRedundantColour(colour(0, 0, 0), null))
    }

    @Test
    fun `music and manual colours are told apart`() {
        // They differ only in byte 7 (0x20 vs 0x10) and mean different things to the strip, so one
        // must never suppress the other.
        assertFalse(WriteDedupe.isRedundantColour(musicColour(9, 9, 9), colour(9, 9, 9)))
    }

    @Test
    fun `power commands are never suppressed`() {
        val off = DuoCoProtocol.createPowerCommand(false)
        assertFalse("an off that never arrived must be resendable",
            WriteDedupe.isRedundantColour(off, off))
    }

    @Test
    fun `brightness commands are never suppressed`() {
        val dim = DuoCoProtocol.createBrightnessCommand(30)
        assertFalse(WriteDedupe.isRedundantColour(dim, dim))
    }

    @Test
    fun `cct commands are never suppressed`() {
        // Shares the 0x05 type byte with colour but a different sub-selector, so the narrow check
        // has to look at both bytes rather than the type alone.
        val cct = DuoCoProtocol.createCctCommand(200, 55)
        assertFalse(WriteDedupe.isRedundantColour(cct, cct))
        assertFalse(WriteDedupe.isRgbColour(cct))
    }

    @Test
    fun `only rgb colour updates the baseline`() {
        assertTrue(WriteDedupe.isRgbColour(colour(1, 2, 3)))
        assertTrue(WriteDedupe.isRgbColour(musicColour(1, 2, 3)))
        assertFalse(WriteDedupe.isRgbColour(DuoCoProtocol.createPowerCommand(true)))
        assertFalse(WriteDedupe.isRgbColour(ByteArray(0)))
    }

    /**
     * The calibration flash sends brightness+white as one batched write, then black 120ms later.
     * The batch's leading frame is a brightness command, so a payload-level type check does not see
     * the white inside it — and if the baseline is not updated from within the batch, the *next*
     * pulse's black is compared against the previous pulse's black, found redundant, and dropped.
     * The strip flashes white once and stays white, which is exactly what Joe reported on
     * 2026-08-20.
     */
    @Test
    fun `the calibration flash pulse still goes dark on the second beat`() {
        val pulseOn = DuoCoProtocol.createBrightnessCommand(100) + colour(255, 255, 255)
        var baseline: ByteArray? = null

        repeat(3) { beat ->
            assertFalse(
                "beat $beat: the white pulse must never be suppressed",
                WriteDedupe.isRedundantColour(pulseOn, baseline)
            )
            baseline = WriteDedupe.colourBaselineOf(pulseOn) ?: baseline

            val off = colour(0, 0, 0)
            assertFalse(
                "beat $beat: the strip is white, so black is not redundant",
                WriteDedupe.isRedundantColour(off, baseline)
            )
            baseline = WriteDedupe.colourBaselineOf(off) ?: baseline
        }
    }

    @Test
    fun `a batched payload takes its baseline from the colour frame inside it`() {
        val batch = DuoCoProtocol.createBrightnessCommand(100) + colour(9, 8, 7)
        assertArrayEquals(colour(9, 8, 7), WriteDedupe.colourBaselineOf(batch))
    }

    @Test
    fun `a payload carrying no colour leaves the baseline alone`() {
        assertNull(WriteDedupe.colourBaselineOf(DuoCoProtocol.createBrightnessCommand(50)))
        assertNull(WriteDedupe.colourBaselineOf(DuoCoProtocol.createPowerCommand(true)))
        assertNull(WriteDedupe.colourBaselineOf(byteArrayOf(0x7e, 0x00, 0x05)))
    }

    /** Only the last colour can be showing, so that is the one the baseline has to track. */
    @Test
    fun `the last colour in a batch wins`() {
        val batch = colour(1, 1, 1) + DuoCoProtocol.createBrightnessCommand(20) + colour(2, 2, 2)
        assertArrayEquals(colour(2, 2, 2), WriteDedupe.colourBaselineOf(batch))
    }

    /** A batch is never dropped whole: suppressing it would take its other frames with it. */
    @Test
    fun `a batch is never redundant, even when its colour is`() {
        val batch = DuoCoProtocol.createBrightnessCommand(100) + colour(4, 4, 4)
        assertFalse(WriteDedupe.isRedundantColour(batch, colour(4, 4, 4)))
    }

    @Test
    fun `a malformed short command is never redundant`() {
        val short = byteArrayOf(0x7e, 0x00, 0x05)
        assertFalse(WriteDedupe.isRedundantColour(short, short))
    }
}
