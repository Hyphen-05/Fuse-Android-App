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
        if (!isRgbColour(candidate)) return false
        return candidate.contentEquals(lastIssued)
    }

    /**
     * Whether this frame is one the dedupe tracks at all — i.e. whether issuing it should become the
     * new baseline to compare future frames against.
     */
    fun isRgbColour(command: ByteArray): Boolean =
        command.size >= 4 && command[2] == TYPE_COLOUR && command[3] == SUB_RGB
}
