package com.example.ambiance

import com.example.core.color.ColorConverter
import kotlin.math.abs
import kotlin.math.pow

/**
 * The "is this change worth sending" rule, lifted out of [AmbianceProcessor.processFrame] so it can
 * be simulated without a camera.
 *
 * Same split as `PacingAutoTuneEngine`: the decision is pure and testable, the capture stays where
 * the hardware is. Extracted verbatim — the numbers and the shape of the curve are exactly what was
 * running inline before, so behaviour is unchanged by the move itself.
 *
 * Why it needed extracting: the low-light flicker Joe reports has two possible causes that call for
 * *opposite* fixes, and no amount of reading the code separates them. Simulating the rule against
 * synthetic scenes does. See `AmbianceDeadbandSimulation`.
 */
object AmbianceDeadband {

    /**
     * How far the aggregate colour must move before the strip is told about it, in summed sRGB
     * bytes across the three channels.
     *
     * The curve is U-shaped in luminance: ~13.8 at mid grey, 15 at white, 20 at black. So it barely
     * changes — 1.45× from its floor to its darkest — while the strips are ~5× more sensitive per
     * byte at the bottom of the range than the top (measured, `tools/calibration/README.md`).
     */
    fun dynamicThreshold(luminance0to1: Double, deadbandMultiplier: Double): Double =
        (5.0 + 10.0 * luminance0to1 + 15.0 * (1.0 - luminance0to1).pow(2)) * deadbandMultiplier

    /** Summed absolute channel difference, the quantity [dynamicThreshold] is compared against. */
    fun diff(
        rawR: Int, rawG: Int, rawB: Int,
        emaR: Int, emaG: Int, emaB: Int
    ): Double = (abs(rawR - emaR) + abs(rawG - emaG) + abs(rawB - emaB)).toDouble()

    /** Luminance of an sRGB triple as 0..1, matching how the threshold is indexed. */
    fun luminance(r: Int, g: Int, b: Int): Double =
        ColorConverter.luminance(r.toDouble(), g.toDouble(), b.toDouble()) / 255.0

    /**
     * Light actually emitted for a commanded byte, 0..1 — measured `light ≈ (byte/255)^0.4` on both
     * devices, 2026-08-16.
     *
     * This is what makes byte-space reasoning misleading down low: byte 8 already emits 11% of full
     * light and byte 32 emits 42%, so a handful of bytes near black is a large visible step.
     */
    fun emittedLight(byteValue: Double): Double =
        (byteValue.coerceIn(0.0, 255.0) / 255.0).pow(STRIP_RESPONSE_EXPONENT)

    /** Inverse of [emittedLight]: the byte that would emit this much light. */
    fun byteForLight(light: Double): Double =
        light.coerceIn(0.0, 1.0).pow(1.0 / STRIP_RESPONSE_EXPONENT) * 255.0

    /**
     * The deadband re-derived so that one admitted change is the same size *in light* wherever it
     * happens, rather than the same size in bytes.
     *
     * [dynamicThreshold] is near-flat in bytes (13.8 to 20 across the whole range) while the strips
     * are ~5× more sensitive per byte near black. Simulation of the shipped rule
     * (`AmbianceDeadbandSimulation`) put the consequence at: a just-admitted change emits a **7.6%
     * light jump at byte 6** against **0.9% at byte 160**. That is the flicker — dark scenes hold
     * still and then lurch, because the rule waits for a change that is eight times too big before
     * it will show anything.
     *
     * **It never goes below [dynamicThreshold], and that limit is the finding, not a hedge.** Asking
     * for 1.5% steps near black demands a threshold of ~6 summed bytes, and simulating that showed
     * it admitting capture noise **7 times a second on a completely static scene** — trading a
     * lurch for a shimmer. The reason is physical: at byte 6, one byte is already a ~4% light step,
     * so a change smaller than the lurch cannot be commanded at all. **No deadband can fix dark
     * scenes; only dithering can** (the deferred item in `IMPROVEMENT_PLAN.md`, which these numbers
     * argue for promoting).
     *
     * What it does fix is the other end, where the shipped rule is too eager: at byte 160 it admits
     * changes worth 0.9% of light — invisible — and chases noise ~1×/s. Requiring a real 1.5% there
     * cuts both to nothing.
     */
    fun lightStepThreshold(
        luminance0to1: Double,
        deadbandMultiplier: Double,
        targetLightStep: Double = TARGET_LIGHT_STEP
    ): Double {
        val currentByte = byteForLight(emittedLightOfLuminance(luminance0to1))
        val steppedByte = byteForLight(emittedLightOfLuminance(luminance0to1) + targetLightStep)
        val perChannel = steppedByte - currentByte
        val byLight = perChannel * 3.0 * deadbandMultiplier
        return maxOf(byLight, dynamicThreshold(luminance0to1, deadbandMultiplier))
    }

    /**
     * Luminance arrives as 0..1 of *byte* scale, so it doubles as the byte level of a grey scene —
     * which is what the threshold is indexed on.
     */
    private fun emittedLightOfLuminance(luminance0to1: Double): Double =
        emittedLight(luminance0to1 * 255.0)

    const val STRIP_RESPONSE_EXPONENT = 0.4

    /**
     * How big one admitted change should look, as a fraction of full emitted light. 1.5% is roughly
     * what the shipped rule already delivers in mid-tones, so ordinary content behaves as before and
     * only the extremes move.
     */
    const val TARGET_LIGHT_STEP = 0.015
}
