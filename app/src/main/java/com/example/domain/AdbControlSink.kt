package com.example.domain

/**
 * Bridge between the debug-only AdbControlReceiver (no DI path to the ViewModel, same
 * constraint as AmbianceCaptureService) and the single live RgbControllerViewModel instance.
 * Same single-listener-slot pattern as AmbianceCommandSink (see that class's doc comment) —
 * deliberately not reused directly since it's a distinct, debug-tooling-only capability set
 * (starting/stopping the real music-sync audio engine for a scripted backend comparison), not
 * part of the ambiance screen-capture path.
 */
class AdbControlSink {
    interface Listener {
        fun onAdbStartMusicSync(mode: String)
        fun onAdbStopMusicSync()

        /**
         * Starts the synthetic-audio DSP simulator. Debug tooling only, and deliberately the sole
         * entry point to it — see RgbControllerViewModel.runAudioSimulationEngine's doc comment for
         * why it must never be reachable from the app itself.
         */
        fun onAdbStartAudioSimulation()

        /**
         * Runs a scripted calibration sequence on every connected strip and writes a CSV of what
         * was sent when. Debug tooling only: it exists so measurements come from the hardware
         * instead of guessed constants.
         *
         * [pacingMs] decides what the run is measuring. The default of -1 **bypasses pacing**,
         * which is what this doc claimed for months while the code silently did the opposite: the
         * call site let `bypassPacing` default to false, so every calibration write went through
         * `DeviceWriteManager`'s wait. With the 50ms default that is a hard 15Hz ceiling, and it is
         * why the 2026-09-02 session concluded "roughly half of all writes never arrive" — the
         * writes above 15Hz were being coalesced by the app before the radio ever saw them, and no
         * sequence has ever asked the strip for more. A value >= 0 pins that pacing for the run
         * instead, which is how the ceiling gets swept rather than merely removed.
         */
        fun onAdbRunCalibration(
            sequence: String,
            minutes: Int = 0,
            attention: Boolean = true,
            dimPercent: Int = 15,
            pacingMs: Int = -1
        )

        /** Cancels a running sequence, and acknowledges the attention signal, which never ends on its own. */
        fun onAdbStopCalibration()
    }

    @Volatile
    var listener: Listener? = null
}
