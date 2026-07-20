package com.example.hardware.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.example.DiagnosticLogger

/**
 * Real "phone_mic" backend: raw `AudioRecord` + a dedicated capture thread + manual Hamming-
 * windowed FFT (via [Fft], moved out of `RgbControllerViewModel` unchanged). Ported verbatim from
 * `RgbControllerViewModel.startAudioEngine`'s AudioRecord branch (~lines 3893-4349 in the
 * pre-Phase-4 source) — this class owns spectrum *reconstruction* only; the DSP pipeline that
 * used to follow in the same loop body now lives in `com.example.core.audio.AudioDspProcessor`.
 *
 * Deviation from the literal interface doc comment: because the original permission/buffer/init
 * checks all happened *inside* the background thread (not before spawning it), [start] here
 * mirrors that — it always spawns and starts the thread and returns `true` (thread launched
 * successfully), with failures surfacing asynchronously via [onError] on the capture thread,
 * exactly matching original control flow/timing. The onError callback receives an
 * IllegalStateException whose message is exactly the addLog(...) text the original code logged
 * at each failure branch, so the caller can forward it verbatim.
 */
class AudioRecordCaptureSource(private val context: Context) : AudioCaptureSource {

    override val backend: AudioBackend = AudioBackend.AUDIO_RECORD

    @Volatile private var activeRecord: AudioRecord? = null
    private var thread: Thread? = null

    override fun start(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit
    ): Boolean {
        thread = Thread {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                onLog("Record Audio Permission missing or denied. Running simulation.")
                onError(IllegalStateException("Record Audio Permission missing or denied. Running simulation."))
                return@Thread
            }

            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                onLog("AudioRecord buffer size error, starting simulation.")
                onError(IllegalStateException("AudioRecord buffer size error, starting simulation."))
                return@Thread
            }

            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    maxOf(minBufferSize, 1024 * 2)
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    onLog("Failed to initialize AudioRecord. Starting simulation.")
                    onError(IllegalStateException("Failed to initialize AudioRecord. Starting simulation."))
                    return@Thread
                }

                activeRecord = record
                record.startRecording()
                onLog("AudioRecord recording started at 44.1 kHz (buffer: 1024).")
                DiagnosticLogger.log("AudioCapture", "Active Engine: PHONE_MIC started successfully")

                val buffer = ShortArray(1024)
                val real = FloatArray(1024)
                val imag = FloatArray(1024)

                // Precompute Hamming Window
                val hammingWindow = FloatArray(1024) { i ->
                    0.54f - 0.46f * Math.cos(2.0 * Math.PI * i / 1023.0).toFloat()
                }

                var lastReadTime = 0L

                while (!Thread.currentThread().isInterrupted && activeRecord != null && isRunning()) {
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    if (lastReadTime != 0L) {
                        val interval = nowElapsed - lastReadTime
                        DiagnosticLogger.log(
                            "AudioCapture",
                            "MIC read callback interval: ${interval}ms, mode=phone_mic"
                        )
                    }
                    lastReadTime = nowElapsed

                    val readResult = record.read(buffer, 0, 1024)
                    if (readResult <= 0) {
                        if (readResult == AudioRecord.ERROR_INVALID_OPERATION || readResult == AudioRecord.ERROR_BAD_VALUE) {
                            break
                        }
                        continue
                    }

                    // Copy raw PCM data to float array and apply Hamming Window
                    for (i in 0 until 1024) {
                        val sample = if (i < readResult) buffer[i].toFloat() else 0.0f
                        real[i] = sample * hammingWindow[i]
                        imag[i] = 0.0f
                    }

                    // Compute FFT
                    Fft.fft(real, imag)

                    // Compute spectral magnitudes
                    val magnitude = FloatArray(512)
                    for (k in 0 until 512) {
                        magnitude[k] = Math.sqrt((real[k] * real[k] + imag[k] * imag[k]).toDouble()).toFloat()
                    }

                    onFrame(
                        AudioCaptureFrame(
                            magnitude = magnitude,
                            realBins = real,
                            imagBins = imag,
                            numBins = 512,
                            backend = AudioBackend.AUDIO_RECORD,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                onLog("Audio capture thread error: ${e.message}")
                onError(e)
            } finally {
                try {
                    record?.stop()
                } catch (e: Exception) {}
                try {
                    record?.release()
                } catch (e: Exception) {}
            }
        }.apply {
            name = "AudioCaptureThread"
            priority = Thread.MAX_PRIORITY - 1
            start()
        }

        return true
    }

    override fun stop() {
        thread?.interrupt()
        thread = null

        val record = activeRecord
        activeRecord = null

        try {
            record?.stop()
        } catch (e: Exception) {}
        try {
            record?.release()
        } catch (e: Exception) {}
    }
}
