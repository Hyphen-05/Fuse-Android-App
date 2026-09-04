package com.example.ambiance

import com.example.core.color.ColorConverter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The decisions ambiance makes between "the camera saw this" and "send this byte", lifted out of
 * [AmbianceProcessor] and [AmbianceOutputInterpolator] so they can be simulated without a screen,
 * a strip or a dark room.
 *
 * Same split `PacingAutoTuneEngine` already uses: the decision is pure and testable, the capture
 * and the handler thread stay where the platform is.
 *
 * Why it needed extracting: Joe reports that ambiance is not smooth in dark scenes. Reading the
 * code offers several candidate causes and no way to rank them. Running the real rules over
 * synthetic scenes and measuring the *emitted light* does - see `AmbianceDarkSceneSimulation`.
 */
object AmbianceOutputRules {

    // ---------------------------------------------------------------- the deadband

    /**
     * How far the aggregate colour must move before the strip is told about it, in summed sRGB
     * bytes across the three channels. Extracted verbatim from [AmbianceProcessor].
     *
     * U-shaped in luminance: 20 at black, ~13.8 at mid grey, 15 at white. So it barely varies -
     * 1.45x from floor to darkest - while the strip is far more sensitive per byte at the bottom of
     * its range than the top. That mismatch is what makes dark scenes hold still and then lurch.
     */
    fun dynamicThreshold(luminance0to1: Double, deadbandMultiplier: Double): Double =
        (5.0 + 10.0 * luminance0to1 + 15.0 * (1.0 - luminance0to1).pow(2)) * deadbandMultiplier

    /** Summed absolute channel difference, the quantity [dynamicThreshold] is compared against. */
    fun diff(rawR: Int, rawG: Int, rawB: Int, emaR: Int, emaG: Int, emaB: Int): Double =
        (abs(rawR - emaR) + abs(rawG - emaG) + abs(rawB - emaB)).toDouble()

    /** Luminance of an sRGB triple as 0..1, matching how the threshold is indexed. */
    fun luminance(r: Int, g: Int, b: Int): Double =
        ColorConverter.luminance(r.toDouble(), g.toDouble(), b.toDouble()) / 255.0

    // ---------------------------------------------------------------- the dark floor

    /**
     * Content darker than this is left alone: genuinely black scenes should not light the strip.
     * `<= 2` rather than `== 0` allows for residual EMA and capture noise.
     */
    const val TRUE_BLACK_CUTOFF = 2

    /** Dim-but-not-black content is lifted to at least this byte so it stays visible. */
    const val FLOOR_TARGET = 14

    /**
     * Lifts dim content up to [FLOOR_TARGET], leaving true black alone.
     *
     * **This is a cliff, and the cliff is the bug.** As written, a brightest channel of 2 is left
     * at 2 and a brightest channel of 3 is multiplied up to 14. On the measured curve byte 2 emits
     * 0.06% of full light and byte 14 emits 8.45%, so a one-byte wobble in what the camera saw -
     * well inside ordinary capture noise - snaps the strip between off and clearly lit. In a dark
     * scene sitting near that boundary it does so repeatedly.
     *
     * Kept here verbatim so the simulation measures the shipped behaviour before anything changes.
     * [floorRamped] is the replacement.
     */
    fun floorStepped(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val maxC = maxOf(r, g, b)
        if (maxC !in (TRUE_BLACK_CUTOFF + 1) until FLOOR_TARGET) return Triple(r, g, b)
        val boost = FLOOR_TARGET.toFloat() / maxC
        return Triple(
            (r * boost).roundToInt().coerceIn(0, 255),
            (g * boost).roundToInt().coerceIn(0, 255),
            (b * boost).roundToInt().coerceIn(0, 255)
        )
    }

    /**
     * The same intent without the cliff: dim content still reaches [FLOOR_TARGET], but the lift
     * fades in across the bottom of the range instead of switching on between one byte and the next.
     *
     * The lift is interpolated from none at [TRUE_BLACK_CUTOFF] to full at [FLOOR_TARGET], so the
     * output rises continuously from black to the floor. Hue is preserved exactly as before - all
     * three channels take the same multiplier - and content at or above [FLOOR_TARGET] is
     * untouched, so only the region that was jumping is affected.
     */
    fun floorRamped(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val maxC = maxOf(r, g, b)
        if (maxC <= 0 || maxC >= FLOOR_TARGET) return Triple(r, g, b)
        // 0 at the cutoff, 1 at the floor. Below the cutoff it is negative and clamps to 0, which
        // is what leaves true black alone.
        val t = ((maxC - TRUE_BLACK_CUTOFF).toDouble() /
            (FLOOR_TARGET - TRUE_BLACK_CUTOFF)).coerceIn(0.0, 1.0)
        val target = maxC + t * (FLOOR_TARGET - maxC)
        val boost = target / maxC
        return Triple(
            (r * boost).roundToInt().coerceIn(0, 255),
            (g * boost).roundToInt().coerceIn(0, 255),
            (b * boost).roundToInt().coerceIn(0, 255)
        )
    }

    // ---------------------------------------------------------------- the ease

    /**
     * The fraction of the remaining distance [AmbianceOutputInterpolator] closes on one tick.
     *
     * The shipped value is a flat 0.5 *per tick*, which makes the fade duration a function of how
     * often the tick happens rather than of anything the user set. Ticking five times as often - as
     * lowering the write pacing does - would make every fade five times faster, which is a feel
     * change nobody asked for.
     *
     * `1 - exp(-dt/tau)` closes the same proportion per unit *time*, so the fade lasts as long as
     * `smoothnessMs` says regardless of tick rate. Tau is chosen so that at the shipped 100ms tick
     * and the default 150ms smoothness the alpha comes out near the old 0.5, and the current feel
     * is preserved rather than quietly retuned.
     */
    fun easeAlpha(dtMs: Long, smoothnessMs: Int): Double {
        val tau = (smoothnessMs.coerceAtLeast(1)).toDouble()
        return 1.0 - exp(-dtMs.coerceAtLeast(0L).toDouble() / tau)
    }
}
