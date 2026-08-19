package com.example.core.color

import com.example.core.protocol.DuoCoProtocol

/**
 * Applies [PerceptualColorSplit] at the write boundary, for one device.
 *
 * ## Why here
 *
 * Colour reaches the strips from ambiance, the visualiser, scenes, manual control, calibration and
 * state restore — a dozen call sites that share exactly one choke point, `BleGattTransport
 * .writeCommand`. Transforming there covers Joe's "everything everywhere" scope without touching
 * any of them, and guarantees no future path escapes it.
 *
 * The existing `calibrate` hook is the wrong shape to carry this: it is one-byte-array-in,
 * one-out, and a split emits *two* commands.
 *
 * ## What it does to a write
 *
 * An RGB colour becomes a full-range colour plus the firmware brightness that restores its level.
 * The two are separate commands with different DuoCo type bytes (0x05 and 0x01), so they occupy
 * separate slots in [com.example.hardware.ble.DeviceWriteManager]'s queue and never evict each
 * other. Brightness is only emitted when the level actually changes, so a constant-luminance hue
 * sweep still costs one write per frame rather than two — the write-doubling cost is paid only by
 * content that is genuinely changing level.
 *
 * ## Why it intercepts brightness commands too
 *
 * Both the split level and the user's Dimming slider drive the same single firmware brightness
 * value. If the slider wrote straight through, whichever wrote last would win: either the slider
 * would stop working, or the next colour frame would undo the user's dim. So a brightness command
 * is treated as a statement of the user's intent — it updates [userDimPercent] and is re-emitted
 * composed with the level of the colour currently showing.
 *
 * ## Batched payloads stay batched
 *
 * A few paths concatenate frames into one GATT write (`syncPhysicalBulb` sends power+colour+
 * brightness; the calibration flash pulse sends brightness+colour). Those are transformed frame by
 * frame and re-concatenated, preserving the single atomic write. Only a lone colour frame expands
 * into two commands. Anything that does not parse as whole 9-byte `7e … ef` frames is passed
 * through untouched.
 *
 * State is per device and per connection: the transport drops the stage when the device
 * disconnects, and when the feature is toggled off, so nothing stale survives either.
 */
class ColourSplitStage(private val userDimmingProvider: () -> Int) {

    private companion object {
        const val FRAME_SIZE = 9
        const val FRAME_HEAD: Byte = 0x7e
        const val FRAME_TAIL: Byte = 0xef.toByte()
        const val TYPE_BRIGHTNESS: Byte = 0x01

        /** Colour/CCT family, and the sub-selector that narrows it to an RGB triplet. */
        const val TYPE_COLOUR: Byte = 0x05
        const val SUB_RGB: Byte = 0x03
        const val IDX_TYPE = 2
        const val IDX_BRIGHTNESS_PERCENT = 3
        const val IDX_R = 4
    }

    /** The user's Dimming setting, once one has passed through. Null until then. */
    @Volatile private var userDimPercent: Int? = null

    /** The colour currently showing, pre-split — what a later dim change has to re-compose against. */
    @Volatile private var lastColour: Triple<Int, Int, Int>? = null

    /** Last brightness handed to the write queue, so an unchanged level costs no write. */
    @Volatile private var lastBrightnessSent: Int? = null

    /**
     * Expands [command] into the commands that should actually be written, in order.
     *
     * Returns the input unchanged when there is nothing to split.
     */
    fun process(command: ByteArray): List<ByteArray> {
        val frames = framesOf(command) ?: return listOf(command)
        return if (frames.size == 1) processSingle(frames[0]) else listOf(processBatch(frames))
    }

    /** The lone-frame path: one command in, up to two out, each on its own queue slot. */
    private fun processSingle(frame: ByteArray): List<ByteArray> = when {
        isRgbColour(frame) -> {
            lastColour = colourOf(frame)
            val composed = composedBrightness()
            val out = ArrayList<ByteArray>(2)
            // Skipped when the level has not moved, which is what keeps a constant-luminance hue
            // sweep at one write per frame rather than two.
            if (composed != lastBrightnessSent) {
                lastBrightnessSent = composed
                out.add(DuoCoProtocol.createBrightnessCommand(composed))
            }
            out.add(normalised(frame))
            out
        }

        isBrightness(frame) -> {
            userDimPercent = percentOf(frame)
            val composed = composedBrightness()
            lastBrightnessSent = composed
            listOf(DuoCoProtocol.createBrightnessCommand(composed))
        }

        else -> listOf(frame)
    }

    /**
     * The batched path: frames are rewritten in place and stay one write, because the caller
     * concatenated them to get exactly one.
     *
     * The dim is taken from the batch before any colour is composed against it, so the order the
     * caller happened to use does not matter — `syncPhysicalBulb` puts brightness last, the
     * calibration flash pulse puts it first, and both must land on the same value. A batch carrying
     * a colour but no brightness frame gains one; that is the only case where a batch changes size.
     * Several colour frames in one batch would be a contradiction (only the last one can be
     * showing) and none of the callers do it — the last one sets the level.
     */
    private fun processBatch(frames: List<ByteArray>): ByteArray {
        frames.lastOrNull { isBrightness(it) }?.let { userDimPercent = percentOf(it) }
        frames.lastOrNull { isRgbColour(it) }?.let { lastColour = colourOf(it) }

        val composed = composedBrightness()
        val carriesColour = frames.any { isRgbColour(it) }
        val carriesBrightness = frames.any { isBrightness(it) }

        val out = ArrayList<ByteArray>(frames.size + 1)
        for (frame in frames) {
            out.add(
                when {
                    isRgbColour(frame) -> normalised(frame)
                    isBrightness(frame) -> DuoCoProtocol.createBrightnessCommand(composed)
                    else -> frame
                }
            )
        }
        if (carriesColour && !carriesBrightness) out.add(DuoCoProtocol.createBrightnessCommand(composed))
        if (carriesColour || carriesBrightness) lastBrightnessSent = composed

        return concat(out)
    }

    /** The firmware brightness that restores the current colour's level at the current dim. */
    private fun composedBrightness(): Int {
        val colour = lastColour ?: return dimming()
        return PerceptualColorSplit.split(colour.first, colour.second, colour.third, dimming())
            .brightnessPercent
    }

    /**
     * The same frame with its channels scaled up to full range. Copied rather than rebuilt so the
     * trailing marker byte survives — it is what distinguishes a music colour (0x20) from a plain
     * one (0x10).
     */
    private fun normalised(frame: ByteArray): ByteArray {
        val (r, g, b) = colourOf(frame)
        val split = PerceptualColorSplit.split(r, g, b, dimming())
        val out = frame.copyOf()
        out[IDX_R] = split.r.toByte()
        out[IDX_R + 1] = split.g.toByte()
        out[IDX_R + 2] = split.b.toByte()
        return out
    }

    private fun colourOf(frame: ByteArray) = Triple(
        frame[IDX_R].toInt() and 0xFF,
        frame[IDX_R + 1].toInt() and 0xFF,
        frame[IDX_R + 2].toInt() and 0xFF
    )

    private fun percentOf(frame: ByteArray) =
        (frame[IDX_BRIGHTNESS_PERCENT].toInt() and 0xFF).coerceIn(0, 100)

    private fun dimming(): Int = (userDimPercent ?: userDimmingProvider()).coerceIn(0, 100)

    /** Splits a payload into whole 9-byte frames, or null if it is not that shape. */
    private fun framesOf(command: ByteArray): List<ByteArray>? {
        if (command.isEmpty() || command.size % FRAME_SIZE != 0) return null
        val frames = ArrayList<ByteArray>(command.size / FRAME_SIZE)
        for (offset in command.indices step FRAME_SIZE) {
            if (command[offset] != FRAME_HEAD || command[offset + FRAME_SIZE - 1] != FRAME_TAIL) return null
            frames.add(command.copyOfRange(offset, offset + FRAME_SIZE))
        }
        return frames
    }

    // Same predicate as WriteDedupe.isRgbColour, restated rather than imported: this package is
    // core and that one is hardware.
    private fun isRgbColour(frame: ByteArray) =
        frame[IDX_TYPE] == TYPE_COLOUR && frame[IDX_TYPE + 1] == SUB_RGB

    private fun isBrightness(frame: ByteArray) = frame[IDX_TYPE] == TYPE_BRIGHTNESS

    private fun concat(frames: List<ByteArray>): ByteArray {
        val out = ByteArray(frames.sumOf { it.size })
        var at = 0
        for (frame in frames) {
            System.arraycopy(frame, 0, out, at, frame.size)
            at += frame.size
        }
        return out
    }
}
