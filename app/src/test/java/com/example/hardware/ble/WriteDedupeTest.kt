package com.example.hardware.ble

import com.example.core.protocol.DuoCoProtocol
import org.junit.Assert.assertFalse
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

    @Test
    fun `a malformed short command is never redundant`() {
        val short = byteArrayOf(0x7e, 0x00, 0x05)
        assertFalse(WriteDedupe.isRedundantColour(short, short))
    }
}
