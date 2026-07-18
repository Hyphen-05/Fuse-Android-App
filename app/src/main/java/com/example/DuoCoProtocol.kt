package com.example

import java.util.UUID

object DuoCoProtocol {
    val CHARACTERISTIC_UUID: UUID get() = com.example.core.protocol.DuoCoProtocol.CHARACTERISTIC_UUID
    val overrides: java.util.concurrent.ConcurrentHashMap<String, ByteArray> get() = com.example.core.protocol.DuoCoProtocol.overrides

    fun parseHex(hex: String): ByteArray? = com.example.core.protocol.DuoCoProtocol.parseHex(hex)
    fun formatHex(bytes: ByteArray): String = com.example.core.protocol.DuoCoProtocol.formatHex(bytes)
    fun createPowerCommand(isOn: Boolean): ByteArray = com.example.core.protocol.DuoCoProtocol.createPowerCommand(isOn)
    fun createColorCommand(r: Int, g: Int, b: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createColorCommand(r, g, b)
    fun createMusicColorCommand(r: Int, g: Int, b: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createMusicColorCommand(r, g, b)
    fun createCctCommand(warm: Int, cold: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createCctCommand(warm, cold)
    fun createModeSpeedCommand(speed: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createModeSpeedCommand(speed)
    fun createSceneCommand(scene: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createSceneCommand(scene)
    fun createRgbPinSequenceCommand(r: Int, g: Int, b: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createRgbPinSequenceCommand(r, g, b)
    fun createSymphonyPointCommand(point: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createSymphonyPointCommand(point)
    fun createSystemTimeCommand(b1: Int, b2: Int, b3: Int, b4: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createSystemTimeCommand(b1, b2, b3, b4)
    fun createTimingScheduleStatusCommand(b1: Int, b2: Int, b3: Int, b4: Int, b5: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createTimingScheduleStatusCommand(b1, b2, b3, b4, b5)
    fun createPhoneMicToggleCommand(isOn: Boolean): ByteArray = com.example.core.protocol.DuoCoProtocol.createPhoneMicToggleCommand(isOn)
    fun createMicVisualizerStyleCommand(eqIndex: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createMicVisualizerStyleCommand(eqIndex)
    fun createBrightnessCommand(percentage: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createBrightnessCommand(percentage)
    fun createDefaultModeCommand(modeIndex: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createDefaultModeCommand(modeIndex)
    fun createModeCommand(modeIndex: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createModeCommand(modeIndex)
    fun createMusicPresetCommand(modeIndex: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createMusicPresetCommand(modeIndex)
    fun createDefaultMusicPresetCommand(modeIndex: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createDefaultMusicPresetCommand(modeIndex)
    fun createMusicSensitivityCommand(sensitivity: Int): ByteArray = com.example.core.protocol.DuoCoProtocol.createMusicSensitivityCommand(sensitivity)
    fun createDefaultPhoneMicCommand(): ByteArray = com.example.core.protocol.DuoCoProtocol.createDefaultPhoneMicCommand()
    fun createPhoneMicCommand(): ByteArray = com.example.core.protocol.DuoCoProtocol.createPhoneMicCommand()
}
