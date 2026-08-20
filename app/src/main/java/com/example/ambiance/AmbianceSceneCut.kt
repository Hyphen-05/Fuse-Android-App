package com.example.ambiance

import kotlin.math.abs

/**
 * The "did the scene just cut" rule, lifted out of [AmbianceProcessor.processFrame] so it can be
 * simulated without a camera — same split as [AmbianceDeadband] and `PacingAutoTuneEngine`.
 *
 * A cut means "snap the EMA to the new colour instead of easing toward it", so a false positive
 * looks like the strip lurching for no reason and a missed one looks like a slow fade into the new
 * scene.
 *
 * Extracted verbatim: [delta] is the mean per-channel sRGB distance between the raw frame and the
 * EMA, and [isCut] is the shipped `aggDelta > sceneCutSensitivity`. Nothing about the behaviour
 * changed with the move.
 *
 * ## Why there is no time normalisation here
 *
 * The rule *looks* rate-dependent: the threshold is fixed, but the delta it is compared against
 * accumulates for however long the frame took to arrive. That predicts two problems — jank invents
 * cuts, and the same slider position means different things at different capture rates, which is
 * why adaptive capture rate was considered blocked on this.
 *
 * `AmbianceSceneCutSimulation` measured both, and **neither survives measurement**:
 *
 *  - Halving the capture rate to 10fps barely moves the cut count (at sensitivity 90: 29/min
 *    against 32/min at 20fps; under a panning scene 6 against 8). The reason is that the EMA is the
 *    thing the delta is measured *from*, and its own alpha is `1 - exp(-dt/tau)` — a longer gap
 *    lets the EMA catch up further in the same step, which cancels the extra drift. The rule is
 *    already time-normalised, just not anywhere it is written down.
 *  - Jank does not invent cuts either: 5% of frames arriving 600ms late produced *fewer* false cuts
 *    per minute than steady capture, not more.
 *
 * Scaling the threshold by frame length was tried at two exponents and both over-corrected badly —
 * at 10fps they drove cuts to 0–3/min against the 32 that 20fps produces — and in the jank case
 * they gave up real cuts at the same rate they gave up false ones (sensitivity 90: false 19 → 10,
 * real 8 → 5). That is not separating signal from noise, it is just being less sensitive, which is
 * the same failure mode as the rejected intensity scalar in `IMPROVEMENT_PLAN.md`.
 *
 * So the shipped rule stays exactly as it is. The simulation keeps the rejected candidates so the
 * next person to notice the same thing can see the numbers instead of re-deriving the theory.
 */
object AmbianceSceneCut {

    /** Mean absolute per-channel sRGB distance between the raw frame and the smoothed colour. */
    fun delta(
        rawR: Int, rawG: Int, rawB: Int,
        emaR: Int, emaG: Int, emaB: Int
    ): Double = (abs(rawR - emaR) + abs(rawG - emaG) + abs(rawB - emaB)) / 3.0

    /** Whether this frame's [delta] is a big enough jump to snap to rather than ease toward. */
    fun isCut(delta: Double, sensitivity: Double): Boolean = delta > sensitivity
}
