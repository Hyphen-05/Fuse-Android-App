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
 * ## Why it is still 50ms, after being dropped to 11 and put back
 *
 * It was lowered to 11ms on 2026-09-04 and Joe's verdict on the hardware was **"everything is
 * steppy now"** - worse than before, and worse everywhere rather than only in the dark scenes the
 * change was aimed at. It is back at 50ms until something better is demonstrated on the strip
 * rather than in a model.
 *
 * The mistake is worth keeping written down, because the measurements did not make it and the
 * reasoning did. 11ms gives a delivered ceiling of `1000/(11+9)` = 50Hz, and
 * `AmbianceOutputInterpolator` ticks at 20ms = 50Hz. **That is exactly 100% utilisation with no
 * slack**, chosen because the two numbers matching looked tidy. The endurance run had already
 * measured what happens when a link is offered more than it can comfortably take: 502Hz offered
 * came back as 67Hz *in lumps*, with 441 stalls over a second. The calibration runs that reached
 * 89Hz cleanly were a dedicated sequence with nothing else on the phone; ambiance is screen
 * capture plus video decode plus two strips.
 *
 * So a lower number is not obviously wrong, but it needs headroom under the tick rate and it needs
 * checking on the wall. Do not lower it again without both.
 */
object BlePacing {

    /** Default minimum gap between writes to one device, in ms, when nothing is stored. */
    const val DEFAULT_MS = 50

    /** The delivered rate this default allows, per device, from the measured ceiling model. */
    const val DEFAULT_CEILING_HZ = 17

    /** Write turnaround measured on the link: the gap a write costs over and above pacing. */
    const val TURNAROUND_MS = 9

    /** Delivered writes per second for a given pacing, per the measured ceiling model. */
    fun ceilingHz(pacingMs: Int): Double = 1000.0 / (pacingMs.coerceAtLeast(0) + TURNAROUND_MS)
}
