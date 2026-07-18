package com.example.core.protocol

import java.util.UUID

object DuoCoProtocol {
    // Target write characteristic typically found under service 0000fff0 (handle 0x0009)
    val CHARACTERISTIC_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb") 

    // TODO(refactor): mutable global state, revisit in Phase 2/3 alongside manuallyDisconnected
    // Active byte overrides map (key format: "mode_<index>", "audio_preset_<index>", "audio_phone_mic")
    val overrides = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun parseHex(hex: String): ByteArray? {
        val clean = hex.replace(" ", "").replace(":", "").replace("-", "")
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { String.format("%02X", it) }
    }

    fun createPowerCommand(isOn: Boolean): ByteArray {
        return if (isOn) {
            byteArrayOf(0x7e.toByte(), 0x00.toByte(), 0x04.toByte(), 0xf0.toByte(), 0x00.toByte(), 0x01.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte())
        } else {
            byteArrayOf(0x7e.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte())
        }
    }

    fun createColorCommand(r: Int, g: Int, b: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x07.toByte(), 0x05.toByte(), 0x03.toByte(), 
            r.coerceIn(0, 255).toByte(), 
            g.coerceIn(0, 255).toByte(), 
            b.coerceIn(0, 255).toByte(), 
            0x10.toByte(), 0xef.toByte()
        )
    }

    fun createMusicColorCommand(r: Int, g: Int, b: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x07.toByte(), 0x05.toByte(), 0x03.toByte(), 
            r.coerceIn(0, 255).toByte(), 
            g.coerceIn(0, 255).toByte(), 
            b.coerceIn(0, 255).toByte(), 
            0x20.toByte(), 0xef.toByte()
        )
    }

    fun createCctCommand(warm: Int, cold: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x06.toByte(), 0x05.toByte(), 0x02.toByte(),
            warm.coerceIn(0, 255).toByte(),
            cold.coerceIn(0, 255).toByte(),
            0xff.toByte(), 0x08.toByte(), 0xef.toByte()
        )
    }

    fun createModeSpeedCommand(speed: Int): ByteArray {
        val coerced = speed.coerceIn(0, 100)
        return byteArrayOf(
            0x7e.toByte(), 0x04.toByte(), 0x02.toByte(), coerced.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createSceneCommand(scene: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x05.toByte(), 0x31.toByte(), scene.coerceIn(0, 255).toByte(),
            0x07.toByte(), 0xff.toByte(), 0xff.toByte(), 0x01.toByte(), 0xef.toByte()
        )
    }

    fun createRgbPinSequenceCommand(r: Int, g: Int, b: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x06.toByte(), 0x81.toByte(),
            r.coerceIn(0, 255).toByte(),
            g.coerceIn(0, 255).toByte(),
            b.coerceIn(0, 255).toByte(),
            0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createSymphonyPointCommand(point: Int): ByteArray {
        val low = (point and 0xFF).toByte()
        val high = ((point shr 8) and 0xFF).toByte()
        return byteArrayOf(
            0x7e.toByte(), 0x07.toByte(), 0x21.toByte(),
            low, high, 0x00.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createSystemTimeCommand(b1: Int, b2: Int, b3: Int, b4: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x07.toByte(), 0x83.toByte(),
            b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte(),
            0xff.toByte(), 0xef.toByte()
        )
    }

    fun createTimingScheduleStatusCommand(b1: Int, b2: Int, b3: Int, b4: Int, b5: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x08.toByte(), 0x82.toByte(),
            b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte(), b5.toByte(),
            0xef.toByte()
        )
    }

    fun createPhoneMicToggleCommand(isOn: Boolean): ByteArray {
        val onOff = if (isOn) 0x01 else 0x00
        return byteArrayOf(
            0x7e.toByte(), 0x04.toByte(), 0x07.toByte(), onOff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createMicVisualizerStyleCommand(eqIndex: Int): ByteArray {
        val styleByte = (0x80 + eqIndex).toByte()
        return byteArrayOf(
            0x7e.toByte(), 0x07.toByte(), 0x03.toByte(), styleByte,
            0x04.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createBrightnessCommand(percentage: Int): ByteArray {
        val coerced = percentage.coerceIn(0, 100)
        return byteArrayOf(0x7e.toByte(), 0x00.toByte(), 0x01.toByte(), coerced.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xef.toByte())
    }

    fun createDefaultModeCommand(modeIndex: Int): ByteArray {
        return byteArrayOf(
            0x7e.toByte(), 0x05.toByte(), 0x03.toByte(), modeIndex.toByte(),
            0x06.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createModeCommand(modeIndex: Int): ByteArray {
        return overrides["mode_$modeIndex"] ?: createDefaultModeCommand(modeIndex)
    }

    fun createMusicPresetCommand(modeIndex: Int): ByteArray {
        val coerced = modeIndex.coerceIn(1, 8)
        return overrides["audio_preset_$coerced"] ?: createMicVisualizerStyleCommand(coerced - 1)
    }

    fun createDefaultMusicPresetCommand(modeIndex: Int): ByteArray {
        return createMicVisualizerStyleCommand(modeIndex.coerceIn(1, 8) - 1)
    }

    fun createMusicSensitivityCommand(sensitivity: Int): ByteArray {
        val value = (sensitivity * 2.55f).toInt().coerceIn(0, 255)
        return byteArrayOf(
            0x7e.toByte(), 0x04.toByte(), 0x06.toByte(), value.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00.toByte(), 0xef.toByte()
        )
    }

    fun createDefaultPhoneMicCommand(): ByteArray {
        return createPhoneMicToggleCommand(true)
    }

    fun createPhoneMicCommand(): ByteArray {
        return overrides["audio_phone_mic"] ?: createDefaultPhoneMicCommand()
    }
}
