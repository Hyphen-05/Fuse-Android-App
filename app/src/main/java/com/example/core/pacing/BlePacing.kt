package com.example.core.pacing

/**
 * The one place the default write pacing is written down.
 *
 * ## Why this exists
 *
 * The same unset preference used to have three different defaults: the write path read 50, the
 * Settings screen read 100, the Ambiance slowest-device sync read 100, and `resetAllPacing` used
 * 100 as well. They only diverged before a device registered - registration publishes the real
 * value into state - but the UI could still show 100ms for a link running at 50, which is exactly
 * the kind of thing that wastes an evening when a number stops matching what is on the wall.
 *
 * ## Why 11ms
 *
 * Pacing is a minimum gap between writes to one device, measured from the previous write
 * *completing*. With ~9ms of write turnaround the delivered ceiling is `1000 / (pacing + 9)`, which
 * the 2026-09-03 sweep confirmed at four settings:
 *
 * | pacing | delivered ceiling | median ack gap |
 * |---|---|---|
 * | 50ms (the old default) | 14.9Hz | 59ms |
 * | 25ms | 27.5Hz | 32ms |
 * | 0ms | no ceiling below 86Hz | 10ms |
 *
 * **Below that ceiling nothing is lost at any pacing**, and the strip itself survived everything up
 * to 89Hz - roughly six times what the old default allowed. So the 50ms was the only reason any
 * frame was ever dropped, and it was a stored guess about a link that is measured now.
 *
 * 11ms rather than 0 because over-driving the link is measurably worse than asking politely:
 * offering 502Hz for fifteen minutes delivered 67Hz *in lumps*, with 441 stalls over a second each
 * and three quarters of the run stalled, where asking for 89Hz delivered a clean 89Hz. A pacing
 * number is the cheapest guard against a runaway producer, so it is kept and set to something the
 * hardware is comfortable with: a 50Hz ceiling, which matches
 * `AmbianceOutputInterpolator.MIN_TICK_MS` so the two ends of the pipe agree.
 */
object BlePacing {

    /** Default minimum gap between writes to one device, in ms, when nothing is stored. */
    const val DEFAULT_MS = 11

    /** The delivered rate this default allows, per device, from the measured ceiling model. */
    const val DEFAULT_CEILING_HZ = 50

    /** Write turnaround measured on the link: the gap a write costs over and above pacing. */
    const val TURNAROUND_MS = 9

    /** Delivered writes per second for a given pacing, per the measured ceiling model. */
    fun ceilingHz(pacingMs: Int): Double = 1000.0 / (pacingMs.coerceAtLeast(0) + TURNAROUND_MS)
}
