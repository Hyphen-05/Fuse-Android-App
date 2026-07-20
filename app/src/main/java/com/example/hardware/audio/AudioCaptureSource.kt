package com.example.hardware.audio

/**
 * Which hardware backend produced an [AudioCaptureFrame]. Downstream DSP (see
 * `com.example.core.audio.AudioDspProcessor`) branches on this for backend-specific bin-range
 * constants that are deliberately NOT unified between backends (see CLAUDE.md "Deferred /
 * known-not-urgent" — the AudioRecord (~43Hz) vs Visualizer (~20Hz) sample-rate mismatch).
 */
enum class AudioBackend { AUDIO_RECORD, VISUALIZER }

/**
 * One frame of raw spectrum data, already FFT'd but with no DSP (noise gate, band energy,
 * smoothing, beat detection, etc.) applied yet — that happens in AudioDspProcessor.process().
 *
 * Deviation from the original spec shape: the spec's sketch only had `magnitudes: FloatArray`.
 * The real pipeline (see BeatDetector.process in com.example.core.audio.BeatDetector) needs the real/imag
 * FFT bins too (for phase-flux beat detection), so those are carried here rather than only the
 * derived magnitude array.
 */
data class AudioCaptureFrame(
    val magnitude: FloatArray,
    val realBins: FloatArray,
    val imagBins: FloatArray,
    val numBins: Int,
    val backend: AudioBackend,
    val timestampMs: Long
)

/**
 * Hardware-facing audio capture source. Implementations own the platform-specific capture
 * mechanism (Visualizer API session, raw AudioRecord + manual FFT thread) and hand back
 * reconstructed spectrum frames via [start]'s onFrame callback. No DSP happens here — see
 * `com.example.core.audio.AudioDspProcessor` for that, which is hardware-free and unit-testable.
 */
interface AudioCaptureSource {
    val backend: AudioBackend

    /**
     * Starts capture. Returns true if capture started successfully (e.g. permission granted,
     * device/session initialized) — false means the caller should fall back (e.g. to
     * RgbControllerViewModel.runAudioSimulationEngine(), unchanged/out of scope here).
     * onFrame may be invoked from a background thread (AudioRecord backend) or from whatever
     * thread constructed the underlying Visualizer (Visualizer backend) — callers must not
     * assume a particular thread.
     *
     * Deviation from the spec's original 2-lambda sketch: two extra parameters were added
     * because the original per-backend code needs them and neither can be satisfied by this
     * hardware-layer class reaching into the ViewModel directly:
     *  - [onLog] forwards the exact addLog(...) message text the original code logged at each
     *    success/failure branch (addLog is a private ViewModel instance method tied to
     *    `_telemetry`, which this class must not touch).
     *  - [isRunning] reproduces the original loop guard
     *    `_uiState.value.audioSettings.isAudioSyncRunning` without a direct `_uiState` reference.
     */
    fun start(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit
    ): Boolean

    /**
     * Stops capture. Mirrors the original teardown semantics exactly, including the known,
     * deliberately-preserved latent issue that interrupting the AudioRecord backend's read loop
     * does not reliably unblock a blocking `AudioRecord.read()` call — actual thread exit happens
     * on the next loop check after read() returns. Do not "fix" this here.
     */
    fun stop()
}
