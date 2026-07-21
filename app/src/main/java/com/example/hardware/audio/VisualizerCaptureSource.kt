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
 */
class VisualizerCaptureSource(private val context: Context) : AudioCaptureSource {

    override val backend: AudioBackend = AudioBackend.VISUALIZER

    @Volatile private var activeVisualizer: Visualizer? = null

    override fun start(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit
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
            DiagnosticLogger.log("AudioCapture", "Active Engine: ON_DEVICE initialized")
            vis.captureSize = Visualizer.getCaptureSizeRange()[1]

            var lastFftTime = 0L
            var loggedBackendInfo = false

            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || !isRunning()) return

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
