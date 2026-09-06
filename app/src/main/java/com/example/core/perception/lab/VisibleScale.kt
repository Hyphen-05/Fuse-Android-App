package com.example.core.perception.lab

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * Block 1's answer, turned into the unit the taste blocks are spaced in.
 *
 * ## Why this type exists at all
 *
 * The first sitting placed its stimuli in commanded bytes and Joe reported that "for lots of the
 * tests one or both is just leds off". The fix was to measure the floor first and place everything
 * relative to it. Blocks 4-6 have the same problem one level up: a paired comparison between two
 * fades is only worth anything if the two are *perceptually* far apart, and a byte is not a
 * perceptual unit — one byte near the floor is an enormous change and one byte at 128 is invisible.
 *
 * So the currency is the **visible step**: the smallest change he can see, which block 1 measured
 * at six anchors on 2026-09-06. A stimulus spaced at "8 visible steps" is the same size to look at
 * wherever it sits in the range, which is what makes a comparison across anchors mean anything.
 *
 * ## What is measured and what is interpolated
 *
 * [MEASURED_2026_09_06] holds the six anchors as answered, at 100% firmware brightness where
 * commanded byte and emitted level are the same number. Everything between them is interpolated
 * log-log, because thresholds that follow Weber's law are straight lines in that space and the top
 * half of the table already does: 4/64 and 8/128 are both 6.3%.
 *
 * ## Two caveats that must travel with any number this produces
 *
 *  - **One observation per cell.** The table is monotone with no inconsistent answers, but each
 *    threshold is a single crossing. It is good enough to space stimuli by and not good enough for
 *    anything expensive to rest on. A repeat run is cheap.
 *  - **The low anchors are floored by the instrument.** One output level is the smallest change the
 *    hardware can make, so a measured threshold of 1 means "one level *or less*". Below level 32
 *    this scale therefore *under*-counts visible steps, possibly by a lot. It cannot be measured
 *    directly: the only way to make a sub-level change is dithering, and block 3 ruled that out.
 */
data class VisibleScale(
    /** Emitted level to the smallest change visible there, in emitted levels. */
    val thresholdByLevel: Map<Int, Double>
) {

    /**
     * The smallest visible change at [level], interpolated between the measured anchors.
     *
     * Never returns less than one level, because the hardware cannot make a smaller change and a
     * scale that claimed otherwise would space stimuli the strip cannot render.
     */
    fun thresholdAt(level: Int): Double {
        val anchors = thresholdByLevel.keys.sorted()
        if (anchors.isEmpty()) return 1.0
        val l = level.coerceAtLeast(1)
        val below = anchors.lastOrNull { it <= l }
        val above = anchors.firstOrNull { it >= l }
        val raw = when {
            below == null -> thresholdByLevel.getValue(anchors.first())
            above == null -> {
                // Above the top anchor, extend the Weber fraction it was measured at rather than
                // holding the absolute threshold flat - a flat threshold up here would claim the
                // bright end is far finer-grained than it is.
                val top = anchors.last()
                thresholdByLevel.getValue(top) * l / top
            }
            below == above -> thresholdByLevel.getValue(below)
            else -> {
                val tb = thresholdByLevel.getValue(below)
                val ta = thresholdByLevel.getValue(above)
                val frac = (ln(l.toDouble()) - ln(below.toDouble())) /
                    (ln(above.toDouble()) - ln(below.toDouble()))
                kotlin.math.exp(ln(tb) + frac * (ln(ta) - ln(tb)))
            }
        }
        return max(1.0, raw)
    }

    /** How many visible steps lie between two emitted levels. Order does not matter. */
    fun stepsBetween(from: Int, to: Int): Double {
        val lo = minOf(from, to).coerceAtLeast(1)
        val hi = maxOf(from, to).coerceAtLeast(1)
        var steps = 0.0
        for (l in lo until hi) steps += 1.0 / thresholdAt(l)
        return steps
    }

    /**
     * The emitted level [steps] visible steps above [from], capped at [maxLevel].
     *
     * Returns the cap when the range runs out, which is the normal case at Joe's brightness: at 25%
     * the strip reaches level 64 and a large jump simply does not fit. Callers should check what
     * they got back rather than assume the request was honoured — [stepsBetween] on the result says
     * how big the stimulus really is, and that is the number to record.
     */
    fun levelAfterVisibleSteps(from: Int, steps: Double, maxLevel: Int): Int {
        var level = from.coerceAtLeast(1)
        var remaining = steps
        while (level < maxLevel && remaining > 0.0) {
            remaining -= 1.0 / thresholdAt(level)
            level += 1
        }
        return level.coerceAtMost(maxLevel)
    }

    companion object {
        /**
         * Block 1, as answered on 2026-09-06 at 100% firmware brightness.
         *
         * Below level 48 the answer was one output level everywhere, which is the hardware's own
         * limit; above it the thresholds are close to Weber at about 6%.
         */
        val MEASURED_2026_09_06 = VisibleScale(
            mapOf(4 to 1.0, 8 to 1.0, 16 to 1.0, 32 to 1.0, 64 to 4.0, 128 to 8.0)
        )

        /**
         * Rebuild the scale from a fresh run of block 1.
         *
         * Anchors where nothing on the ladder was visible are dropped rather than treated as a huge
         * threshold: "not visible up to 16" is a bound, not a measurement, and feeding it in as one
         * would quietly stretch the scale.
         */
        fun from(reading: LabAnalysis.ScaleReading): VisibleScale? {
            val pts = reading.thresholdByAnchor.mapNotNull { (anchor, t) ->
                if (t == null) null else anchor to t.toDouble()
            }.toMap()
            return if (pts.size < 3) null else VisibleScale(pts)
        }
    }
}

/** True when two visible-step counts are close enough to call the same size to look at. */
internal fun sameVisibleSize(a: Double, b: Double, tolerance: Double = 0.25): Boolean =
    abs(a - b) <= tolerance * max(a, b)
