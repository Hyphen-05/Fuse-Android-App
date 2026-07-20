package com.example.domain

/**
 * Bridge between the ambiance screen-capture path (a Service and a HandlerThread-driven
 * interpolator, neither of which has a DI path to the ViewModel) and the single live
 * RgbControllerViewModel instance. Replaces the getActiveInstance() static-singleton lookup
 * for these two call sites (see CLAUDE.md Phase 6 notes).
 *
 * Deliberately a single-listener slot, not an event bus: there is only ever one
 * RgbControllerViewModel instance alive that should receive these calls.
 */
class AmbianceCommandSink {
    interface Listener {
        fun writeAmbianceColor(r: Int, g: Int, b: Int)
        fun setAmbianceCaptureActive(active: Boolean)
    }

    @Volatile
    var listener: Listener? = null
}
