package com.example.core.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the public command-builder surface of DuoCoProtocol beyond what
 * core/CoreExtractionTest.kt already exercises (createPowerCommand, createColorCommand,
 * parseHex/formatHex). This is the highest-risk untested code in the app per the post-Phase-4
 * audit: pure logic that directly encodes real BLE hardware commands, with 14 of ~20 public
 * functions previously untested — including the mutable override state the file's own TODO
 * flags as a risk area.
 */
class DuoCoProtocolTest {

    // overrides is process-wide mutable state on the DuoCoProtocol singleton; clear every key
    // this test touches so runs don't leak into each other or into other test classes.
    @After
    fun clearOverrides() {
        listOf("mode_2", "audio_preset_3", "audio_phone_mic").forEach {
            DuoCoProtocol.clearOverride(it)
        }
    }

    @Test
    fun createMusicColorCommand_encodesRgbWithMusicFlag() {
        val cmd = DuoCoProtocol.createMusicColorCommand(10, 20, 30)
        assertEquals("7E 07 05 03 0A 14 1E 20 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createMusicColorCommand_coercesOutOfRangeChannels() {
        val cmd = DuoCoProtocol.createMusicColorCommand(-10, 300, 128)
        assertEquals("7E 07 05 03 00 FF 80 20 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createCctCommand_encodesWarmAndCold() {
        val cmd = DuoCoProtocol.createCctCommand(100, 200)
        assertEquals("7E 06 05 02 64 C8 FF 08 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createModeSpeedCommand_encodesSpeed() {
        val cmd = DuoCoProtocol.createModeSpeedCommand(50)
        assertEquals("7E 04 02 32 FF FF FF 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createModeSpeedCommand_coercesAboveAndBelowRange() {
        assertEquals("7E 04 02 64 FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createModeSpeedCommand(150)))
        assertEquals("7E 04 02 00 FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createModeSpeedCommand(-10)))
    }

    @Test
    fun createSceneCommand_encodesScene() {
        val cmd = DuoCoProtocol.createSceneCommand(5)
        assertEquals("7E 05 31 05 07 FF FF 01 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createSceneCommand_coercesAboveRange() {
        val cmd = DuoCoProtocol.createSceneCommand(300)
        assertEquals("7E 05 31 FF 07 FF FF 01 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createRgbPinSequenceCommand_encodesRgb() {
        val cmd = DuoCoProtocol.createRgbPinSequenceCommand(255, 128, 0)
        assertEquals("7E 06 81 FF 80 00 FF 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createSymphonyPointCommand_splitsPointIntoLowHighBytes() {
        val cmd = DuoCoProtocol.createSymphonyPointCommand(300)
        // 300 = 0x012C -> low=0x2C, high=0x01
        assertEquals("7E 07 21 2C 01 00 FF 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createSymphonyPointCommand_zeroPoint() {
        val cmd = DuoCoProtocol.createSymphonyPointCommand(0)
        assertEquals("7E 07 21 00 00 00 FF 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createSystemTimeCommand_encodesFourBytes() {
        val cmd = DuoCoProtocol.createSystemTimeCommand(1, 2, 3, 4)
        assertEquals("7E 07 83 01 02 03 04 FF EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createTimingScheduleStatusCommand_encodesFiveBytes() {
        val cmd = DuoCoProtocol.createTimingScheduleStatusCommand(1, 2, 3, 4, 5)
        assertEquals("7E 08 82 01 02 03 04 05 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createPhoneMicToggleCommand_onAndOff() {
        assertEquals("7E 04 07 01 FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createPhoneMicToggleCommand(true)))
        assertEquals("7E 04 07 00 FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createPhoneMicToggleCommand(false)))
    }

    @Test
    fun createMicVisualizerStyleCommand_offsetsEqIndexBy0x80() {
        assertEquals("7E 07 03 80 04 FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createMicVisualizerStyleCommand(0)))
        assertEquals("7E 07 03 83 04 FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createMicVisualizerStyleCommand(3)))
    }

    @Test
    fun createBrightnessCommand_encodesPercentage() {
        val cmd = DuoCoProtocol.createBrightnessCommand(75)
        assertEquals("7E 00 01 4B 00 00 00 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createBrightnessCommand_coercesAboveRange() {
        val cmd = DuoCoProtocol.createBrightnessCommand(150)
        assertEquals("7E 00 01 64 00 00 00 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createDefaultModeCommand_encodesModeIndex() {
        val cmd = DuoCoProtocol.createDefaultModeCommand(2)
        assertEquals("7E 05 03 02 06 FF FF 00 EF", DuoCoProtocol.formatHex(cmd))
    }

    @Test
    fun createMusicSensitivityCommand_scalesPercentageTo0to255() {
        assertEquals("7E 04 06 00 FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createMusicSensitivityCommand(0)))
        // 50 * 2.55 = 127.5 -> truncated to 127 (0x7F) by .toInt()
        assertEquals("7E 04 06 7F FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createMusicSensitivityCommand(50)))
        assertEquals("7E 04 06 FF FF FF FF 00 EF", DuoCoProtocol.formatHex(DuoCoProtocol.createMusicSensitivityCommand(100)))
    }

    @Test
    fun createDefaultPhoneMicCommand_isAnOnToggle() {
        assertArrayEquals(
            DuoCoProtocol.createPhoneMicToggleCommand(true),
            DuoCoProtocol.createDefaultPhoneMicCommand()
        )
    }

    @Test
    fun createDefaultMusicPresetCommand_coercesModeIndexInto1to8() {
        // modeIndex 5 -> coerced stays 5 -> visualizer style eqIndex = 5 - 1 = 4 -> styleByte 0x84
        assertEquals(
            "7E 07 03 84 04 FF FF 00 EF",
            DuoCoProtocol.formatHex(DuoCoProtocol.createDefaultMusicPresetCommand(5))
        )
        // Below range coerces to 1 -> eqIndex 0 -> styleByte 0x80
        assertEquals(
            "7E 07 03 80 04 FF FF 00 EF",
            DuoCoProtocol.formatHex(DuoCoProtocol.createDefaultMusicPresetCommand(0))
        )
        // Above range coerces to 8 -> eqIndex 7 -> styleByte 0x87
        assertEquals(
            "7E 07 03 87 04 FF FF 00 EF",
            DuoCoProtocol.formatHex(DuoCoProtocol.createDefaultMusicPresetCommand(20))
        )
    }

    // --- Override state (getOverride/setOverride/clearOverride) ---

    @Test
    fun getOverride_returnsNullWhenNotSet() {
        assertNull(DuoCoProtocol.getOverride("mode_2"))
    }

    @Test
    fun setOverride_thenGetOverride_returnsStoredValue() {
        val custom = byteArrayOf(0x01, 0x02, 0x03)
        DuoCoProtocol.setOverride("mode_2", custom)

        assertArrayEquals(custom, DuoCoProtocol.getOverride("mode_2"))
    }

    @Test
    fun clearOverride_removesStoredValue() {
        DuoCoProtocol.setOverride("mode_2", byteArrayOf(0x01))
        DuoCoProtocol.clearOverride("mode_2")

        assertNull(DuoCoProtocol.getOverride("mode_2"))
    }

    // --- Override-aware command builders (createModeCommand / createMusicPresetCommand /
    // createPhoneMicCommand) ---

    @Test
    fun createModeCommand_fallsBackToDefaultWhenNoOverride() {
        assertArrayEquals(
            DuoCoProtocol.createDefaultModeCommand(2),
            DuoCoProtocol.createModeCommand(2)
        )
    }

    @Test
    fun createModeCommand_usesOverrideWhenSet() {
        val custom = byteArrayOf(0x7e, 0x01, 0x02)
        DuoCoProtocol.setOverride("mode_2", custom)

        assertArrayEquals(custom, DuoCoProtocol.createModeCommand(2))
    }

    @Test
    fun createMusicPresetCommand_fallsBackToDefaultWhenNoOverride() {
        assertArrayEquals(
            DuoCoProtocol.createMicVisualizerStyleCommand(2), // coerced index 3 -> eqIndex 2
            DuoCoProtocol.createMusicPresetCommand(3)
        )
    }

    @Test
    fun createMusicPresetCommand_usesOverrideWhenSet() {
        val custom = byteArrayOf(0x7e, 0x03, 0x04)
        DuoCoProtocol.setOverride("audio_preset_3", custom)

        assertArrayEquals(custom, DuoCoProtocol.createMusicPresetCommand(3))
    }

    @Test
    fun createMusicPresetCommand_coercesModeIndexInto1to8() {
        // modeIndex 0 coerces to 1 -> override key "audio_preset_1", not "audio_preset_3", so the
        // "audio_preset_3" override set in other tests must not leak here.
        assertArrayEquals(
            DuoCoProtocol.createMicVisualizerStyleCommand(0),
            DuoCoProtocol.createMusicPresetCommand(0)
        )
    }

    @Test
    fun createPhoneMicCommand_fallsBackToDefaultWhenNoOverride() {
        assertArrayEquals(
            DuoCoProtocol.createDefaultPhoneMicCommand(),
            DuoCoProtocol.createPhoneMicCommand()
        )
    }

    @Test
    fun createPhoneMicCommand_usesOverrideWhenSet() {
        val custom = byteArrayOf(0x7e, 0x05)
        DuoCoProtocol.setOverride("audio_phone_mic", custom)

        assertArrayEquals(custom, DuoCoProtocol.createPhoneMicCommand())
    }

    // --- parseHex/formatHex edge cases beyond CoreExtractionTest's happy path ---

    @Test
    fun parseHex_rejectsOddLengthHex() {
        assertNull(DuoCoProtocol.parseHex("7E0"))
    }

    @Test
    fun parseHex_rejectsNonHexCharacters() {
        assertNull(DuoCoProtocol.parseHex("ZZ"))
    }

    @Test
    fun parseHex_acceptsColonAndDashSeparators() {
        val fromColon = DuoCoProtocol.parseHex("7E:00:EF")
        val fromDash = DuoCoProtocol.parseHex("7E-00-EF")
        val fromSpace = DuoCoProtocol.parseHex("7E 00 EF")

        assertArrayEquals(fromSpace, fromColon)
        assertArrayEquals(fromSpace, fromDash)
    }
}
