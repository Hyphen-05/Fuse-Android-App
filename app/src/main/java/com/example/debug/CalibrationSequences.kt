package com.example.debug

import android.os.Environment
import com.example.core.protocol.DuoCoProtocol
import kotlinx.coroutines.delay
import java.io.File

/**
 * Scripted light sequences whose only purpose is to be *measured*, so the strip model in the feel
 * harness can stop running on guessed constants.
 *
 * Each sequence writes a CSV of exactly what was sent and when. Pair that with a video of the strip
 * and every constant in `StripLimits` becomes a measurement:
 *  - [BRIGHTNESS_RAMP] answers whether the cubic curve in `ColorConverter.hsvToRgb` matches what the
 *    LEDs actually do — the open question behind every "too dim" report.
 *  - [LATENCY_PULSE] answers `visibleLatencyMs`.
 *  - [RATE_RAMP] answers `minWriteSpacingMs` and `sustainedWritesPerSecond`.
 *  - Running [RATE_RAMP] with two strips connected answers `multiDeviceThroughputFactor`.
 *
 * Every sequence opens with [syncMarker]: three fast full-white flashes, which is the alignment
 * point between video time and the timestamps in the CSV. Without it there is no way to line a
 * phone recording up with a log to millisecond accuracy.
 *
 * Writes bypass pacing deliberately — the point is to control the wire timing exactly, not to be
 * well-behaved. That is also why this is debug-only tooling and not reachable from the app.
 */
object CalibrationSequences {

    const val BRIGHTNESS_RAMP = "brightness_ramp"
    const val LATENCY_PULSE = "latency_pulse"
    const val RATE_RAMP = "rate_ramp"
    const val HOLD_WHITE = "hold_white"

    val ALL = listOf(BRIGHTNESS_RAMP, LATENCY_PULSE, RATE_RAMP, HOLD_WHITE)

    /** One line per write: when it was sent, and what was in it. */
    private val log = StringBuilder()

    private fun record(atMs: Long, label: String, r: Int, g: Int, b: Int) {
        log.append("$atMs,$label,$r,$g,$b\n")
    }

    /**
     * Runs [sequence], sending every command through [send], and returns the CSV file written.
     *
     * [send] is supplied by the ViewModel so this stays free of BLE plumbing; it is expected to
     * write to every connected strip with pacing bypassed.
     */
    suspend fun run(
        sequence: String,
        outputDir: File?,
        send: (ByteArray) -> Unit
    ): File? {
        log.setLength(0)
        log.append("elapsed_ms,label,r,g,b\n")
        val startedAt = System.currentTimeMillis()

        fun emit(label: String, r: Int, g: Int, b: Int) {
            send(DuoCoProtocol.createColorCommand(r, g, b))
            record(System.currentTimeMillis() - startedAt, label, r, g, b)
        }

        // The strip applies its own brightness setting on top of whatever RGB it is sent, so a run
        // taken at the user's current dimming level measures RGB × that level and nothing can be
        // untangled afterwards. Pin it to 100% first; the app's slider is left showing whatever it
        // showed before, so this has to be reset by hand (or by moving the slider) after a session.
        send(DuoCoProtocol.createBrightnessCommand(100))
        record(0, "brightness_pinned_100", -1, -1, -1)
        delay(400)

        // Setup aid, not a measurement: parks the strips at the brightest state any run will
        // produce so the camera's exposure can be locked against the worst case. Locking against a
        // dimmer state clips the top of the ramp, which is unrecoverable after the fact.
        if (sequence == HOLD_WHITE) {
            emit("hold_white", 255, 255, 255)
            delay(180_000)
            return writeCsv(sequence, outputDir, startedAt)
        }

        syncMarker(::emit)

        when (sequence) {
            BRIGHTNESS_RAMP -> brightnessRamp(::emit)
            LATENCY_PULSE -> latencyPulse(::emit)
            RATE_RAMP -> rateRamp(::emit)
            else -> return null
        }

        emit("end_black", 0, 0, 0)
        return writeCsv(sequence, outputDir, startedAt)
    }

    private suspend fun syncMarker(emit: (String, Int, Int, Int) -> Unit) {
        emit("sync_black", 0, 0, 0)
        delay(1000)
        repeat(3) {
            emit("sync_flash", 255, 255, 255)
            delay(120)
            emit("sync_gap", 0, 0, 0)
            delay(280)
        }
        delay(1500)
    }

    /**
     * Holds each commanded level long enough for a camera to settle, stepping white from off to
     * full. Measured against video, the resulting curve *is* the strip's real response — if it
     * comes back roughly linear in the commanded byte, the cubic correction is wrong and is what
     * has been eating the brightness range.
     */
    private suspend fun brightnessRamp(emit: (String, Int, Int, Int) -> Unit) {
        val levels = listOf(0, 8, 16, 24, 32, 48, 64, 80, 96, 112, 128, 160, 192, 224, 255)
        for (level in levels) {
            emit("ramp_$level", level, level, level)
            delay(2000)
        }
        // Repeated descending so a camera with drifting auto-exposure can be caught out: if the
        // same commanded level reads differently on the way down, the recording is not usable.
        for (level in levels.reversed()) {
            emit("ramp_down_$level", level, level, level)
            delay(1200)
        }
    }

    /**
     * Hard black-to-white steps at known times. The gap between the CSV timestamp and the frame
     * where the strip visibly changes is the wire-to-light latency, to within one video frame.
     */
    private suspend fun latencyPulse(emit: (String, Int, Int, Int) -> Unit) {
        repeat(12) { index ->
            emit("pulse_${index}_on", 255, 255, 255)
            delay(400)
            emit("pulse_${index}_off", 0, 0, 0)
            delay(1600)
        }
    }

    /**
     * Alternates two easily-told-apart colours at rising rates. Where the strip stops alternating
     * cleanly on video is the point writes are being dropped — the number the whole pacing model
     * currently guesses at.
     */
    private suspend fun rateRamp(emit: (String, Int, Int, Int) -> Unit) {
        val rates = listOf(2, 5, 10, 15, 20, 30, 40, 50, 65, 80, 100)
        for (rate in rates) {
            val intervalMs = (1000L / rate).coerceAtLeast(1L)
            val writes = rate * 4 // four seconds at each rate
            // A long black gap announces each new rate, so the video can be segmented without
            // relying on the timestamps alone.
            emit("rate_${rate}_marker", 0, 0, 0)
            delay(700)
            repeat(writes) { index ->
                if (index % 2 == 0) emit("rate_$rate", 255, 0, 0) else emit("rate_$rate", 0, 0, 255)
                delay(intervalMs)
            }
        }
    }

    private fun writeCsv(sequence: String, outputDir: File?, startedAt: Long): File? {
        val dir = outputDir
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: return null
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "fuse_calibration_${sequence}_$startedAt.csv")
        file.writeText(log.toString())
        return file
    }
}
