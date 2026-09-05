package com.example.core.perception

/**
 * Finds where Joe's strips actually start emitting light, before any trial is run.
 *
 * ## Why this exists
 *
 * The first version hardcoded its base levels at bytes 4, 8, 16, 40 and 100. Against the measured
 * curve those bottom levels are already close to nothing at firmware brightness 100%, and Joe runs
 * at **22%** — how the two compose has never been measured, and with the Pixel 9 gone it cannot be
 * filmed. He ran a sitting on 2026-09-05 and reported that "for lots of the tests one or both is
 * just leds off", which is that hole arriving exactly where it was predicted to.
 *
 * Defining the stimuli in commanded bytes removed the need to know *how much* light comes out. It
 * did not remove the need for *some* to come out. This asks the strip, at whatever brightness is
 * actually set, rather than assuming.
 *
 * ## The method
 *
 * Method of limits, coarse up then fine down:
 *
 *  1. **Ascending**, along [LADDER], which is spaced roughly geometrically so a dozen rungs cover
 *     the whole useful range. Two marks are collected on the way up: the first rung Joe can see at
 *     all, and the first rung that is *clearly* lit. Two marks rather than one because a level
 *     sitting exactly on the threshold of visibility is a bad place to base a trial — half the
 *     time it is not there.
 *  2. **Descending**, one byte at a time from just below the first-seen rung, until it goes dark.
 *     The ladder is coarse, so ascending alone would put the floor anywhere inside a rung gap; this
 *     pins it to a byte. It also corrects for the anticipation that makes a pure ascending run read
 *     low — the two crossings bracket the truth rather than one guessing at it.
 *
 * Everything downstream is placed relative to [FloorResult.clearlyOn], so no trial is ever run at a
 * level the strip cannot render at the brightness in use.
 */
object FloorFinder {

    /**
     * Rungs for the ascending pass.
     *
     * Geometric-ish rather than linear: one byte matters enormously at the bottom of the measured
     * curve and almost not at all by byte 100, so equal *ratios* are closer to equal perceptual
     * distance than equal differences are. Twenty rungs is a few seconds of tapping.
     */
    val LADDER: List<Int> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 18, 22, 27, 32, 40, 48, 58, 70, 85, 100)

    /**
     * What the calibration concluded, in commanded bytes.
     *
     * [firstVisible] is the dimmest byte Joe reported any light at. [clearlyOn] is the dimmest he
     * called properly lit. The gap between them is itself informative — a wide one means the bottom
     * of the range is mush at this brightness.
     */
    data class FloorResult(val firstVisible: Int, val clearlyOn: Int) {
        init {
            require(firstVisible in 0..255) { "firstVisible is a byte" }
            require(clearlyOn in firstVisible..255) { "clearlyOn is at or above firstVisible" }
        }
    }

    /**
     * Combines the two crossings into one floor.
     *
     * [seenAscendingAt] is the rung Joe first saw light at; [darkDescendingAt] is the byte he
     * reported dark on the way back down, so the dimmest still-visible byte is one above it. The
     * true threshold lies between the two crossings, and the midpoint is the standard estimate —
     * ascending runs read high (he is waiting for it to appear), descending runs read low (he knows
     * where it was).
     */
    fun combineCrossings(seenAscendingAt: Int, darkDescendingAt: Int): Int {
        val lowestStillVisible = darkDescendingAt + 1
        return ((seenAscendingAt + lowestStillVisible) / 2.0).toInt().coerceIn(1, 255)
    }

    /**
     * The session shape to run at this floor.
     *
     * Three base levels rather than five, and six reversals per staircase rather than eight. The
     * original design took a simulated median of **195 trials**, some 25-35 minutes; Joe asked for
     * something he can actually sit through, and a short sitting run twice is worth more than a
     * long one run once and resented.
     *
     * Levels are spread by ratio from [FloorResult.clearlyOn], for the same reason [LADDER] is:
     * doubling the byte near the floor is a large change in light, and near the top it is not.
     */
    fun sessionConfigFor(floor: FloorResult): SessionConfig {
        val base = floor.clearlyOn.coerceAtLeast(1)
        val levels = listOf(base, base * 2, base * 5)
            .map { it.coerceAtMost(200) }
            .distinct()
        // A fade needs somewhere to travel. Below about six bytes of span there is nothing for
        // dithering to smooth and the trial would compare two identical-looking ramps.
        val span = (base).coerceAtLeast(6)
        return SessionConfig(
            baseLevels = levels,
            ditherBase = base,
            fadeFrom = base,
            fadeSpan = span,
            reversalsToFinish = 6
        )
    }
}
