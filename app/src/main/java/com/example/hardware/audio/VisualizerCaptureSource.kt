package com.example.hardware.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import com.example.DiagnosticLogger

/**
 * Real "on_device" backend: wraps Android's `Visualizer` API (session id 0). Ported verbatim from
 * `RgbControllerViewModel.startAudioEngine`'s Visualizer branch (~lines 3481-3891 in the
 * pre-Phase-4 source) — owns spectrum *reconstruction* only (raw FFT byte array -> magnitude/
 * real/imag float arrays); the DSP pipeline that used to run inline inside
 * `onFftDataCapture` now lives in `com.example.core.audio.AudioDspProcessor`.
 *
 * `onFftDataCapture` fires on whichever thread constructed this `Visualizer` (no dedicated
 * thread is spawned here, matching the original), so [start] does its permission/init work
 * synchronously and returns the real success/failure result — unlike [AudioRecordCaptureSource],
 * whose checks originally happened inside a background thread.
 *
 * **First-activation-silent-attach watchdog (2026-07-21, addresses "on-device backend doesn't
 * activate on first click, works after toggling off/on").** No on-device Logcat trace of this
 * could be captured in the environment this fix was written in (no adb/device attached) — this
 * is a source-level fix for a real, documented Android platform quirk, not a trace-confirmed root
 * cause; flagged here explicitly, same as the item-14 `StartAudioEngine` freeze fix in CLAUDE.md.
 * `Visualizer(0)` (session 0 = the device's global output mix) can silently receive zero
 * `onFftDataCapture` callbacks if it is attached at a moment when the output mix hasn't
 * stabilized (a known characteristic of session-0 Visualizer attach, not something `enabled =
 * true` reports as an error — no exception is thrown, `start()` returns `true`, the object just
 * never calls back). Manually toggling the "On Device" card off and back on works around this by
 * releasing and re-constructing the `Visualizer`, which re-attaches once the mix has settled.
 * This class now does that automatically: a one-shot watchdog fires [WATCHDOG_TIMEOUT_MS] after
 * `enabled = true` and, if zero frames have arrived and capture is still supposed to be running,
 * tears down and reconstructs exactly once (see [retried]) — this cannot loop indefinitely.
 */
class VisualizerCaptureSource(private val context: Context) : AudioCaptureSource {

    override val backend: AudioBackend = AudioBackend.VISUALIZER

    @Volatile private var activeVisualizer: Visualizer? = null

    companion object {
        const val WATCHDOG_TIMEOUT_MS = 1500L
    }

    override fun start(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit
    ): Boolean = attemptStart(onFrame, onLog, isRunning, onError, retried = false)

    private fun attemptStart(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit,
        retried: Boolean
    ): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onLog("Record Audio Permission missing. Running simulation.")
            onError(IllegalStateException("Record Audio Permission missing. Running simulation."))
            return false
        }

        try {
            val vis = Visualizer(0)
            DiagnosticLogger.log("AudioCapture", "Active Engine: ON_DEVICE initialized (retry=$retried)")
            vis.captureSize = Visualizer.getCaptureSizeRange()[1]

            var lastFftTime = 0L
            var loggedBackendInfo = false
            val receivedAnyFrame = java.util.concurrent.atomic.AtomicBoolean(false)

            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || !isRunning()) return
                    receivedAnyFrame.set(true)

                    if (!loggedBackendInfo) {
                        loggedBackendInfo = true
                        DiagnosticLogger.log(
                            "BackendInfo",
                            "backend=on_device samplingRate=${samplingRate}Hz captureSize=${vis.captureSize} numBins=${fft.size / 2}"
                        )
                    }

                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    if (lastFftTime != 0L) {
                        val interval = nowElapsed - lastFftTime
                        DiagnosticLogger.log(
                            "AudioCapture",
                            "FFT capture interval: ${interval}ms, mode=on_device"
                        )
                    }
                    lastFftTime = nowElapsed

                    val len = fft.size
                    val numBins = len / 2
                    if (numBins < 349) return

                    val magnitude = FloatArray(numBins)
                    val realBins = FloatArray(numBins)
                    val imagBins = FloatArray(numBins)
                    magnitude[0] = Math.abs(fft[0].toInt()).toFloat()
                    realBins[0] = fft[0].toFloat()
                    imagBins[0] = 0.0f
                    for (k in 1 until numBins) {
                        val r = fft[2 * k].toFloat()
                        val i = fft[2 * k + 1].toFloat()
                        magnitude[k] = Math.sqrt((r * r + i * i).toDouble()).toFloat()
                        realBins[k] = r
                        imagBins[k] = i
                    }

                    onFrame(
                        AudioCaptureFrame(
                            magnitude = magnitude,
                            realBins = realBins,
                            imagBins = imagBins,
                            numBins = numBins,
                            backend = AudioBackend.VISUALIZER,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
            }, Visualizer.getMaxCaptureRate(), false, true)

            vis.enabled = true
            activeVisualizer = vis
            onLog("System Visualizer initialized successfully for on-device sync (direct FFT).")

            if (!retried) {
                Thread {
                    try {
                        Thread.sleep(WATCHDOG_TIMEOUT_MS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    // Only act if this watchdog's own Visualizer is still the live one (a real
                    // stop() or a subsequent start() may have already superseded it) and capture
                    // is still supposed to be active.
                    if (!receivedAnyFrame.get() && activeVisualizer === vis && isRunning()) {
                        DiagnosticLogger.log(
                            "AudioCapture",
                            "on_device: no frames received within ${WATCHDOG_TIMEOUT_MS}ms of attach, reconstructing Visualizer once"
                        )
                        try {
                            vis.enabled = false
                            vis.release()
                        } catch (e: Exception) {}
                        if (activeVisualizer === vis) activeVisualizer = null
                        attemptStart(onFrame, onLog, isRunning, onError, retried = true)
                    }
                }.apply {
                    name = "VisualizerAttachWatchdog"
                    isDaemon = true
                    start()
                }
            }

            return true
        } catch (e: Exception) {
            onLog("Failed to initialize Visualizer: ${e.message}. Running simulation.")
            onError(e)
            return false
        }
    }

    override fun stop() {
        try {
            activeVisualizer?.enabled = false
            activeVisualizer?.release()
        } catch (e: Exception) {}
        activeVisualizer = null
    }
}
