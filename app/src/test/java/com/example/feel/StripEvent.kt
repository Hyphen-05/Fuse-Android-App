package com.example.feel

/**
 * What a strip understands a command to mean, recovered from the bytes on the wire.
 *
 * Decoding at this boundary is what makes one harness cover the whole app: colour, CCT, scenes,
 * onboard modes, ambiance and the audio visualiser all converge on
 * `BleGattTransport.writeCommand(address, bytes)`. Anything that can reach a strip can be replayed
 * through [VirtualStrip] without the feature that produced it knowing this exists.
 */
sealed interface StripEvent {
    data class Power(val on: Boolean) : StripEvent

    /**
     * [fromMusic] distinguishes `createMusicColorCommand` from `createColorCommand` — identical
     * except for byte 7 (0x20 vs 0x10). Kept apart because a stream of music colours is the
     * high-rate case the pacing rules exist for, and mixing the two in a trace would hide that.
     */
    data class Color(val r: Int, val g: Int, val b: Int, val fromMusic: Boolean) : StripEvent

    data class Cct(val warm: Int, val cold: Int) : StripEvent
    data class Brightness(val percent: Int) : StripEvent
    data class OnboardMode(val index: Int) : StripEvent
    data class MicVisualizerStyle(val eqIndex: Int) : StripEvent
    data class ModeSpeed(val speed: Int) : StripEvent
    data class Scene(val scene: Int) : StripEvent
    data class MicToggle(val on: Boolean) : StripEvent
    data class MicSensitivity(val raw: Int) : StripEvent

    /** Well-formed but not something the model interprets (schedule, system time, symphony point). */
    data class Other(val typeByte: Int) : StripEvent

    /** Not a 0x7e…0xef frame at all — the strip would ignore it. */
    data object Malformed : StripEvent
}

/**
 * Reads a DuoCo frame back into a [StripEvent].
 *
 * This is the inverse of `DuoCoProtocol.create*Command`, and `DuoCoDecoderTest` round-trips every
 * encoder through it — if someone adds a command without teaching the decoder about it, that test
 * fails rather than the harness silently rendering a strip that ignores the new feature.
 *
 * Byte-override support (`DuoCoProtocol.getOverride`) means a user can reshape a command's bytes at
 * runtime; those decode as [StripEvent.Other] or [StripEvent.Malformed], which is honest — the
 * harness cannot know what a hand-edited frame means to the firmware.
 */
object DuoCoDecoder {

    fun decode(bytes: ByteArray): StripEvent {
        if (bytes.size != 9) return StripEvent.Malformed
        val u = bytes.map { it.toInt() and 0xFF }
        if (u[0] != 0x7e || u[8] != 0xef) return StripEvent.Malformed

        return when (u[2]) {
            0x04 -> StripEvent.Power(on = u[3] == 0xf0)
            0x01 -> StripEvent.Brightness(percent = u[3])
            0x02 -> StripEvent.ModeSpeed(speed = u[3])
            0x06 -> StripEvent.MicSensitivity(raw = u[3])
            0x07 -> StripEvent.MicToggle(on = u[3] == 0x01)
            0x31 -> StripEvent.Scene(scene = u[3])
            0x05 -> when (u[3]) {
                // Colour and CCT share the 0x05 type; byte 3 is the sub-selector, exactly as the
                // encoders write it (0x03 = RGB triplet, 0x02 = warm/cold pair).
                0x03 -> StripEvent.Color(r = u[4], g = u[5], b = u[6], fromMusic = u[7] == 0x20)
                0x02 -> StripEvent.Cct(warm = u[4], cold = u[5])
                else -> StripEvent.Other(typeByte = u[2])
            }
            // 0x03 covers both the onboard animation modes and the eight firmware mic styles.
            // createMicVisualizerStyleCommand sets the high bit of byte 3 and puts 0x04 in byte 4;
            // createDefaultModeCommand uses a plain index with 0x06.
            0x03 -> if (u[3] >= 0x80) {
                StripEvent.MicVisualizerStyle(eqIndex = u[3] - 0x80)
            } else {
                StripEvent.OnboardMode(index = u[3])
            }
            else -> StripEvent.Other(typeByte = u[2])
        }
    }
}
