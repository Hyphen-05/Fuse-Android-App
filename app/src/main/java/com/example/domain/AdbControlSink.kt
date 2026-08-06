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
    }

    @Volatile
    var listener: Listener? = null
}
