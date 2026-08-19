package com.example.core.color

import kotlin.math.pow
import kotlin.math.roundToInt

/** An RGB triplet plus the firmware brightness that restores its original level. */
data class SplitColor(val r: Int, val g: Int, val b: Int, val brightnessPercent: Int)

/**
 * Re-encodes a colour so the RGB bytes carry only *chromaticity* and the firmware brightness command
 * carries *level*.
 *
 * ## Why
 *
 * RGB currently encodes hue and level in the same three bytes, and the strips are compressive enough
 * (light ~ (byte/255)^0.4) that almost the whole usable range sits below byte ~96. Near black there
 * is nothing left to encode hue with: at byte 6 there are six levels of ratio between channels, so a
 * slow colour sweep emits byte-identical commands for seconds. Worse, the colour axis has no fine
 * dim setting at all - byte 8, the dimmest level ever measured, already emits 11% of full light and
 * nothing reaches under it.
 *
 * The strips have a dimmer that does not have those problems. Measured 2026-08-19: a motion-smear
 * photo shows no PWM chopping at 15% brightness at a resolution that would make any carrier below
 * ~5kHz obvious, and Joe confirmed the fade is smooth at every level by eye. So level belongs on the
 * brightness command, and the colour bytes get their full range back for hue.
 *
 * ## This transform is appearance-preserving, on purpose
 *
 * It is applied to every colour the app sends that falls below [SPLIT_KNEE], so it must not change
 * how anything looks - it reproduces the same emitted light, and nothing else.
 *
 * It does *not* buy finer steps. That claim was in this file until 2026-08-19 and it was wrong:
 * above byte ~10 the colour axis resolves light more finely than the dimmer does, so splitting up
 * there costs resolution. Joe saw it immediately in ambiance, which is nothing but smooth
 * gradients. What the split actually buys is **reach** - colour byte 1 already emits 11% of full
 * light, so without the dimmer there is no way to be darker than that at all.
 *
 * Scaling all three channels by the same factor k multiplies every channel's light by k^0.4, which
 * leaves the ratios between them - the hue - untouched. So normalise the top channel to 255 for
 * maximum resolution, then hand the removed level to the brightness command:
 *
 *     k = 255 / max(r,g,b)      bytes scale up by k
 *     brightness = (max / 255) ^ 0.4     restores exactly the light that scaling added
 *
 * **Not** to be confused with normalising to constant *luminance* across hue - that would make a
 * saturated blue and a white of the same nominal value emit the same perceived brightness, which is
 * a real and desirable thing for a visualiser but changes how every existing colour looks. That is a
 * separate, opt-in, judged-by-eye change; this one is invisible by construction.
 *
 * ## The assumption, stated
 *
 * Brightness is treated as **linear in emitted light** - a duty cycle. Flicker-free smooth dimming
 * is consistent with that but does not prove it, and `dark_ramp` phase 2 is the measurement that
 * would. If it turns out non-linear, the fix is a curve here and nowhere else.
 */
object PerceptualColorSplit {

    /** Measured end-to-end response, byte to light. See CLAUDE.md. */
    private const val RESPONSE_EXPONENT = 0.4

    /**
     * Peak channel below which the split is worth doing. Above it, the colour is passed through
     * untouched.
     *
     * A byte step changes light by `0.4/b` in relative terms, so the colour axis gets *finer* as it
     * climbs; a brightness step changes it by `1/B`, and B falls as the picture darkens. They cross
     * around byte 10-16:
     *
     *     byte 128  colour 0.31%  brightness 1.32%   <- colour is 4x finer
     *     byte  64  colour 0.62%  brightness 1.74%
     *     byte  16  colour 2.50%  brightness 3.03%
     *     byte   8  colour 5.00%  brightness 3.99%   <- brightness takes over
     *
     * Splitting above the knee therefore *loses* level resolution — which is what a 2026-08-19
     * ambiance run showed: smooth gradients, which live at mid and high bytes, came out visibly
     * coarser. Below the knee the colour axis is not merely coarse, it has run out: byte 1 already
     * emits 11% of full light, so nothing dimmer than that is expressible at all, and hue near
     * black has only a handful of ratios left to describe it with.
     *
     * 14 rather than 16 so it sits at or above `AmbianceProcessor`'s floor, which lifts dim content
     * to byte 14. That is not a coincidence to be tidied away later: the floor exists because the
     * colour axis stops working around there, which is the same fact this constant encodes. It does
     * mean ambiance output is byte-identical with the split on and off, which is deliberate — that
     * mode is tuned, and this transform has no business re-tuning it.
     */
    private const val SPLIT_KNEE = 14

    /**
     * Splits [r],[g],[b] into full-range colour bytes plus the brightness that restores its level,
     * scaled by the user's own dimming setting.
     *
     * [userDimmingPercent] must be composed in here rather than applied separately: both this and
     * the Dimming slider drive the same single firmware brightness value, so whichever wrote last
     * would otherwise silently win and the slider would stop working.
     */
    fun split(r: Int, g: Int, b: Int, userDimmingPercent: Int = 100): SplitColor {
        val cr = r.coerceIn(0, 255)
        val cg = g.coerceIn(0, 255)
        val cb = b.coerceIn(0, 255)
        val dim = userDimmingPercent.coerceIn(0, 100)
        val peak = maxOf(cr, cg, cb)

        // Black has no chromaticity to preserve and no level to move. Leave brightness at the
        // user's setting so turning the colour back up does not also restore a stale dim level.
        if (peak == 0) return SplitColor(0, 0, 0, dim)

        // Dimming at zero is the user asking for the lights off, and it has to survive the floor
        // below. Without this the strip stops at 1% and never goes dark: the slider's bottom end
        // silently stops working the moment the split is on.
        if (dim == 0) return SplitColor(0, 0, 0, 0)

        // Above the knee the colour axis is the finer of the two — leave it alone. See [SPLIT_KNEE].
        if (peak >= SPLIT_KNEE) return SplitColor(cr, cg, cb, dim)

        val scale = 255.0 / peak
        val level = (peak / 255.0).pow(RESPONSE_EXPONENT)
        // Floored at 1 so a colour that is merely dim never rounds all the way to off — an explicit
        // zero is the only thing that turns the strip off.
        val percent = (level * dim).roundToInt().coerceIn(1, 100)

        return SplitColor(
            r = (cr * scale).roundToInt().coerceIn(0, 255),
            g = (cg * scale).roundToInt().coerceIn(0, 255),
            b = (cb * scale).roundToInt().coerceIn(0, 255),
            brightnessPercent = percent
        )
    }

    /** Light emitted by a commanded byte, 0..1, on the measured response curve. */
    fun emittedLight(byteValue: Int): Double =
        (byteValue.coerceIn(0, 255) / 255.0).pow(RESPONSE_EXPONENT)
}
