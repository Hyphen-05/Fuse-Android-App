package com.example.debug

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Flashes the driving phone's own screen white on the same write as the strip, so a video with both
 * in frame carries its own time origin.
 *
 * ## Why absolute latency has never been measurable
 *
 * `latency_pulse` was built to answer `visibleLatencyMs` and cannot. Video time is aligned to CSV
 * time using the strips themselves, and the strips arrive already delayed by the latency being
 * measured — so the alignment subtracts out the quantity. What the run actually measured was
 * delivery *jitter* (sd 36ms, 2026-08-16).
 *
 * With the phone's screen in frame there is a second event in the video that is not delayed by BLE.
 * Screen-white to strip-lit, counted in frames, is the answer, and it needs no alignment at all.
 *
 * ## What this does not eliminate
 *
 * The screen has its own latency. [reportPresented] is called from a frame callback, so the
 * compositor's share is recorded rather than guessed, but the panel's own response — pixels actually
 * changing after the frame is handed over — stays unknown and is a systematic offset on every
 * number the run produces. It is roughly one refresh interval. At 240fps (4ms a frame) that is not
 * negligible, so quote the result with it stated rather than folded in.
 *
 * Debug tooling. Nothing in the app reaches this; only [CalibrationSequences] sets it.
 */
object CalibrationScreenFlash {

    /** True while the screen should be white. Observed by the overlay in MainActivity. */
    val on = MutableStateFlow(false)

    /**
     * True for the length of **every** run: holds the screen awake.
     *
     * Not optional, and not the same question as brightness. A run whose phone is allowed to sleep
     * does not simply go dark — the display turning off takes the app out of TOP, Android then
     * refuses `startForegroundService()` outright, the process is throttled, and `delay()` inside a
     * sequence stops advancing. On 2026-09-02 that silently stalled a brightness_ramp and a
     * spacing_staircase: no CSV, no error, and a recording of a strip that had stopped moving.
     */
    val runActive = MutableStateFlow(false)

    /**
     * True only for `latency_pulse`, which needs the screen bright and in frame. Every other run
     * pins the screen to its **minimum** instead: the phone must stay awake, but in a blackout
     * photometric run its display is a lamp pointed at the subject.
     */
    val brightScreen = MutableStateFlow(false)

    /**
     * Set by [CalibrationSequences] for the length of a latency run. Called with wall-clock
     * milliseconds at the frame callback for the white frame — near, but not identical to, the
     * moment the panel actually changes.
     */
    @Volatile
    var presentedListener: ((Long) -> Unit)? = null

    fun reportPresented(atMs: Long) {
        presentedListener?.invoke(atMs)
    }
}
