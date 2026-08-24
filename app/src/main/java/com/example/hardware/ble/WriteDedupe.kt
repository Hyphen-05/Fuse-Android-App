package com.example.hardware.ble

/**
 * Decides whether a write would tell the strip something it already knows.
 *
 * Split out from [DeviceWriteManager] on the same principle as `AmbianceDeadband` and
 * `PacingAutoTuneEngine`: the decision is pure and testable, the GATT plumbing stays where the
 * hardware is.
 *
 * ## Why this exists
 *
 * Measured 2026-08-16/17: the strips are strongly compressive (light ≈ (byte/255)^0.4), so almost
 * the whole usable range sits below byte ~96 where 8-bit quantisation is coarsest. A slow fade
 * computed at 60fps therefore emits the *same bytes* over and over — a 10s fade from byte 24 to
 * byte 4 is 600 frames of intent carrying 21 distinct values, **97% redundant**.
 *
 * Dropping those is not merely a wire saving, and that is the part worth understanding.
 * [DeviceWriteManager.updateCommand] keeps at most one queued command per type and *removes* the
 * queued one when a newer arrives. So a redundant write does not queue harmlessly behind a real
 * colour — **it evicts it**. The strip then shows neither, until the next frame happens to survive.
 * The staircase run measured only 32% of first writes reaching the strip through that mechanism.
 *
 * ## Why only colour
 *
 * Redundancy is a high-rate problem, and the high-rate path is colour. Power, brightness, mode and
 * scene commands arrive when a user touches something, so suppressing them saves nothing worth
 * having — while carrying a real failure mode: writes go out `WRITE_TYPE_NO_RESPONSE`, so if the app
 * believes the strip is already off and it is not, suppressing the "off" would leave a button that
 * visibly does nothing. Colour has no such idiom; the next frame is along in milliseconds.
 *
 * The comparison is against the last colour actually *issued* to the radio, not the last enqueued,
 * because the queue's latest-wins rule means enqueued and issued are different things.
 */
object WriteDedupe {

    /** DuoCo type byte for the colour/CCT family. */
    private const val TYPE_COLOUR: Byte = 0x05

    /** Sub-selector distinguishing an RGB triplet from a warm/cold CCT pair. */
    private const val SUB_RGB: Byte = 0x03

    /**
     * True when [candidate] is an RGB colour command byte-identical to [lastIssued].
     *
     * Deliberately narrow: anything that is not an RGB colour write, and anything with no previous
     * colour to compare against, is never redundant.
     */
    fun isRedundantColour(candidate: ByteArray, lastIssued: ByteArray?): Boolean {
        if (lastIssued == null) return false
        // A batch is never dropped whole — suppressing it would take its power or brightness frames
        // with it, and those are exactly the writes a user is waiting on.
        if (candidate.size != FRAME_SIZE) return false
        if (!isRgbColour(candidate)) return false
        return candidate.contentEquals(lastIssued)
    }

    /**
     * Whether this payload is, on its own, an RGB colour write.
     *
     * Only true of a lone frame: a batch is identified by [colourBaselineOf] instead, because its
     * leading frame is usually something else.
     */
    fun isRgbColour(command: ByteArray): Boolean =
        command.size >= 4 && command[2] == TYPE_COLOUR && command[3] == SUB_RGB

    /**
     * The colour this payload leaves the strip showing, or null if it does not set one — i.e. the
     * new baseline to compare future frames against once this write has been issued.
     *
     * **Batched payloads are why this exists.** A few paths concatenate whole 9-byte frames into a
     * single GATT write: `syncPhysicalBulb` sends power+colour+brightness, the calibration flash
     * sends brightness+white, and `ColourSplitStage` re-emits batches frame by frame. A payload-
     * level type check reads bytes 2 and 3 of the *first* frame, so it sees the brightness and
     * misses the colour riding behind it — the baseline then goes stale, and the next lone colour
     * is compared against something the strip stopped showing several writes ago.
     *
     * That is not theoretical: it dropped the calibration flash's "off" leg from the second beat
     * onward, because the black being compared was the *previous* pulse's black. The strip flashed
     * once and stayed white (reported 2026-08-20, pinned in `WriteDedupeTest`).
     *
     * Several colour frames in one batch would be a contradiction — only the last can be showing —
     * so the last one wins, matching `ColourSplitStage`'s own rule.
     */
    fun colourBaselineOf(command: ByteArray): ByteArray? {
        if (isRgbColour(command) && command.size == FRAME_SIZE) return command
        val frames = framesOf(command) ?: return if (isRgbColour(command)) command else null
        return frames.lastOrNull { isRgbColour(it) }
    }

    /** Splits a payload into whole 9-byte frames, or null if it is not that shape. */
    private fun framesOf(command: ByteArray): List<ByteArray>? {
        if (command.isEmpty() || command.size % FRAME_SIZE != 0) return null
        val frames = ArrayList<ByteArray>(command.size / FRAME_SIZE)
        for (offset in command.indices step FRAME_SIZE) {
            if (command[offset] != FRAME_HEAD || command[offset + FRAME_SIZE - 1] != FRAME_TAIL) {
                return null
            }
            frames.add(command.copyOfRange(offset, offset + FRAME_SIZE))
        }
        return frames
    }

    private const val FRAME_SIZE = 9
    private const val FRAME_HEAD: Byte = 0x7e
    private const val FRAME_TAIL: Byte = 0xef.toByte()
}
