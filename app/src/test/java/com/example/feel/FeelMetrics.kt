package com.example.feel

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Numbers for the things a colour timeline shows but can't quantify.
 *
 * The picture answers "does this feel calm or busy"; these answer "how calm, and calmer than what".
 * Both matter — the render caught that Ebb & Flow appears to stop moving after ~8s, and only a
 * measured hue rate can say whether the colour genuinely stopped or merely wandered through a
 * stretch of the wheel where every degree looks like the last.
 */
data class FeelMetrics(
    /** Mean perceived brightness, 0..1, over the whole run. */
    val meanBrightness: Double,
    /** Highest minus lowest brightness — how far the preset swings overall. */
    val brightnessRange: Double,
    /**
     * Mean absolute brightness change per second. The pumping/strobing measure: a preset can have a
     * wide range while moving slowly (a swell) or a narrow range while moving constantly (a flicker).
     */
    val brightnessChangePerSecond: Double,
    /** Degrees of hue travelled per second, following the shorter way round each step. */
    val hueDegreesPerSecond: Double,
    /** How much of the wheel the run visits at all, as a fraction of 360°. */
    val hueCoverage: Double,
    /** Visible colour changes per second, counting only steps big enough to notice. */
    val visibleChangesPerSecond: Double
) {
    fun summary() = "brightness ${"%.2f".format(meanBrightness)} ±${"%.2f".format(brightnessRange)} | " +
        "swing ${"%.2f".format(brightnessChangePerSecond)}/s | hue ${"%.1f".format(hueDegreesPerSecond)}°/s | " +
        "covers ${"%.0f".format(hueCoverage * 100)}% of wheel"
}

object FeelAnalysis {

    /** Perceived brightness, matching the renderer's luma so picture and numbers agree. */
    fun luma(frame: StripFrame): Double =
        (0.2126 * frame.r + 0.7152 * frame.g + 0.0722 * frame.b) / 255.0

    /** Hue in degrees, or null for a frame with no colour to speak of. */
    fun hue(frame: StripFrame): Double? {
        val r = frame.r / 255.0
        val g = frame.g / 255.0
        val b = frame.b / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        if (delta < 0.004 || maxC < 0.004) return null
        val h = when (maxC) {
            r -> 60 * (((g - b) / delta) % 6)
            g -> 60 * (((b - r) / delta) + 2)
            else -> 60 * (((r - g) / delta) + 4)
        }
        return (h + 360.0) % 360.0
    }

    /** Shortest angular distance between two hues, so 359° → 1° counts as 2°, not 358°. */
    fun hueDelta(from: Double, to: Double): Double {
        val raw = abs(to - from) % 360.0
        return if (raw > 180.0) 360.0 - raw else raw
    }

    /**
     * [analyse] restricted to one stretch of the run, for comparing a chorus against a verse.
     *
     * Frames are absolute-timestamped, and the first frame of a window carries no delta, so a
     * window's change-rate metrics are measured from its own start rather than the run's.
     */
    fun analyseWindow(frames: List<StripFrame>, fromMs: Long, toMs: Long): FeelMetrics {
        val base = frames.firstOrNull()?.atMs ?: 0L
        return analyse(frames.filter { it.atMs - base >= fromMs && it.atMs - base < toMs })
    }

    fun analyse(frames: List<StripFrame>): FeelMetrics {
        if (frames.size < 2) return FeelMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val durationSec = (frames.last().atMs - frames.first().atMs) / 1000.0
        if (durationSec <= 0.0) return FeelMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val lumas = frames.map { luma(it) }
        val hues = frames.map { hue(it) }

        var brightnessTravel = 0.0
        var hueTravel = 0.0
        var visibleChanges = 0
        var previousHue: Double? = null
        val visitedBuckets = BooleanArray(36)

        for (i in frames.indices) {
            if (i > 0) {
                val dLuma = abs(lumas[i] - lumas[i - 1])
                brightnessTravel += dLuma
                val currentHue = hues[i]
                val hueStep = if (currentHue != null && previousHue != null) hueDelta(previousHue, currentHue) else 0.0
                hueTravel += hueStep
                // "Noticeable" thresholds: a few percent of brightness, or a few degrees of hue —
                // below this the strip is drifting, not changing.
                if (dLuma > 0.03 || hueStep > 4.0) visibleChanges++
            }
            hues[i]?.let {
                previousHue = it
                visitedBuckets[((it / 10.0).toInt()).coerceIn(0, 35)] = true
            }
        }

        return FeelMetrics(
            meanBrightness = lumas.average(),
            brightnessRange = (lumas.maxOrNull() ?: 0.0) - (lumas.minOrNull() ?: 0.0),
            brightnessChangePerSecond = brightnessTravel / durationSec,
            hueDegreesPerSecond = hueTravel / durationSec,
            hueCoverage = visitedBuckets.count { it } / 36.0,
            visibleChangesPerSecond = visibleChanges / durationSec
        )
    }
}
