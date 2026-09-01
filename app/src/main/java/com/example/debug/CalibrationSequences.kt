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
 *  - [LATENCY_PULSE] was meant to answer `visibleLatencyMs`. It cannot: video time can only be
 *    aligned to log time using the strips themselves, which arrive already delayed by the latency
 *    being measured, so the alignment subtracts it out. What it does measure is delivery *jitter*
 *    (sd 36ms, 2026-08-16). The constant needs the phone's own screen in frame — see the README.
 *  - [RATE_RAMP] answers `sustainedWritesPerSecond`, from its CSV alone with no video at all.
 *  - [SPACING_STAIRCASE] answers `minWriteSpacingMs`, which [RATE_RAMP] on video could not.
 *  - [DARK_RAMP] covers the bottom of the range that [BRIGHTNESS_RAMP] skipped, where the fitted
 *    curve disagrees with its own lowest measured point by 2×, and asks whether the brightness
 *    command is a finer dimmer than the colour bytes.
 *  - [SUSTAINED_LOAD] answers whether the link survives an hour flat out, which is the last thing
 *    standing between the pacing measurements and removing the manual pacing setting. Needs no
 *    camera and nobody watching.
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
    const val SPACING_STAIRCASE = "spacing_staircase"
    const val DARK_RAMP = "dark_ramp"
    const val SUSTAINED_LOAD = "sustained_load"
    const val HOLD_WHITE = "hold_white"

    val ALL = listOf(
        BRIGHTNESS_RAMP, LATENCY_PULSE, RATE_RAMP, SPACING_STAIRCASE, DARK_RAMP,
        SUSTAINED_LOAD, HOLD_WHITE
    )

    /**
     * How long [sustainedLoad] runs when the caller does not say. Joe's call on 2026-08-18: the
     * plan asks for an hour, an hour is also an hour of strobing his room and of the shared test
     * phone, and 15 minutes catches a gross disconnect, a sag or a stall pattern. Pass
     * `--ei minutes 60` for the run the plan actually asks for.
     */
    private const val DEFAULT_SUSTAINED_MINUTES = 15

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
        sustainedMinutes: Int = 0,
        send: (ByteArray) -> Unit
    ): File? {
        log.setLength(0)
        log.append("elapsed_ms,label,r,g,b\n")
        val startedAt = System.currentTimeMillis()

        fun emit(label: String, r: Int, g: Int, b: Int) {
            send(DuoCoProtocol.createColorCommand(r, g, b))
            record(System.currentTimeMillis() - startedAt, label, r, g, b)
        }

        // Brightness rows are logged with r = g = -1 and the percentage in the b column, matching
        // how the pin-to-100 row below already marks itself as "not a colour".
        fun emitBrightness(label: String, percent: Int) {
            send(DuoCoProtocol.createBrightnessCommand(percent))
            record(System.currentTimeMillis() - startedAt, label, -1, -1, percent)
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
            SPACING_STAIRCASE -> spacingStaircase(::emit)
            DARK_RAMP -> darkRamp(::emit, ::emitBrightness)
            SUSTAINED_LOAD -> sustainedLoad(::emit, sustainedMinutes)
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

    /**
     * Asks the drop question in a form a 60fps camera can answer, which [rateRamp] could not.
     *
     * [rateRamp] asks "did each of these forty fast events happen?" — a question about fast events,
     * needing a camera faster than the events. The 2026-08-16 run showed that failing: with ±50ms of
     * delivery jitter against a 17ms frame, "dropped" and "arrived late" are the same observation
     * above about 5Hz, and three different detectors gave three different answers at 10Hz.
     *
     * So ask it as a *steady state* instead. From black, send red, then blue [spacing] ms later,
     * then hold for most of a second. What the wall is showing when it settles says what happened,
     * and it says it for long enough that frame rate stops mattering:
     *  - **blue** — both writes rendered
     *  - **red** — the second was dropped: the strip would not take it that soon after the first
     *  - **black** — both were dropped, or the strip was still off
     *
     * Ten bursts at each spacing turn the jitter from noise into a probability: the answer is a drop
     * *rate* per spacing, and `minWriteSpacingMs` is where that rate leaves zero. The commanded
     * spacing is only a request — coroutine `delay` is not exact at 2ms — so the analysis bins on
     * the achieved gap between the two logged timestamps, never on the label.
     *
     * What this measures is the whole pipeline, not the strip alone: a write coalesced by Android's
     * BLE stack and one refused by the strip's firmware look identical from here. That is the right
     * scope for the feel harness, which models what the user sees, but it is why the constant this
     * feeds is named for write *spacing* rather than for the strip.
     */
    private suspend fun spacingStaircase(emit: (String, Int, Int, Int) -> Unit) {
        val spacings = listOf(2, 4, 6, 8, 12, 16, 20, 25, 30, 40, 60)
        val repeats = 10
        for (spacing in spacings) {
            emit("stair_${spacing}_marker", 0, 0, 0)
            delay(700)
            repeat(repeats) { index ->
                emit("stair_${spacing}_${index}_reset", 0, 0, 0)
                delay(500)
                emit("stair_${spacing}_${index}_first", 255, 0, 0)
                delay(spacing.toLong())
                emit("stair_${spacing}_${index}_second", 0, 0, 255)
                // Long enough that the settled colour spans tens of frames, so reading it needs no
                // alignment better than "somewhere in this window".
                delay(700)
            }
        }
        controlBursts(emit)
    }

    /**
     * Bursts that send the first colour and **no second write at all**, so the analysis can be
     * checked end to end rather than trusted.
     *
     * The 2026-08-16 run nearly buried its most important finding for want of these. Reading only
     * the settled colour, a dropped *second* write leaves red — which is what the run was built to
     * detect — but a lost *first* write leaves blue, identical to a clean success. That scored
     * 110/110 and called it a sweep, while 68% of first writes were in fact never arriving.
     *
     * These bursts must settle on **red**. Any that read blue or black mean the classifier is
     * reading the wrong window, and every number from that run is suspect — which is exactly what
     * `find_sync` latching onto the wrong flash did to the first analysis.
     */
    private suspend fun controlBursts(emit: (String, Int, Int, Int) -> Unit) {
        emit("control_marker", 0, 0, 0)
        delay(700)
        repeat(10) { index ->
            emit("control_${index}_reset", 0, 0, 0)
            delay(500)
            emit("control_${index}_first", 255, 0, 0)
            delay(700)
        }
    }

    /**
     * Measures the bottom of the range, which no run has ever covered, and settles whether the
     * brightness command is a finer dimmer than the colour bytes are.
     *
     * Two questions, one recording, because both are about the same few percent of light:
     *
     * **Phase 1 — the dark end of the response curve.** `light = (byte/255)^0.4` was fitted across
     * the whole ramp, and it misses its own lowest measured point by better than 2× (byte 8 read 11%
     * of full light; the curve says 25%). Everything the ambiance work cares about lives at bytes
     * 4-24, inside that gap, and nothing below byte 8 has ever been measured at all. Either the
     * strip has a real toe down there — a minimum usable duty cycle, PWM resolution running out — or
     * the dimmest wall patch was the one the camera measured worst. Stepping every byte from 0 to 32
     * separates those: a toe is a smooth bend, a measurement artefact is not.
     *
     * **Phase 2 — is brightness finer than a byte?** The same dim colour can be commanded as small
     * bytes at full brightness, or as large bytes scaled down by the brightness command. They differ
     * in resolution enormously: at byte 6 there are six steps between the colour and black, at byte
     * 96 there are ninety-six. If brightness is a PWM duty cycle held at more than 8-bit precision —
     * which is the usual way to build one — the second route is strictly better, and it is free: one
     * extra command, no ongoing wire cost, and it is the hardware's own dimmer rather than a
     * reimplementation of it over the radio. Holding the colour fixed at 96 and stepping brightness
     * 1-20% shows it directly: smooth steps mean the fine dimmer exists, a staircase that lands on
     * the same handful of levels as phase 1 means it does not.
     *
     * Phase 2 restores brightness to 100% at the end, because every other sequence assumes it.
     */
    private suspend fun darkRamp(
        emit: (String, Int, Int, Int) -> Unit,
        emitBrightness: (String, Int) -> Unit
    ) {
        for (level in 0..32) {
            emit("dark_$level", level, level, level)
            delay(1500)
        }
        // Descending, as in brightnessRamp: the same level reading differently on the way down means
        // the camera's exposure drifted and the run is not usable.
        for (level in 32 downTo 0 step 4) {
            emit("dark_down_$level", level, level, level)
            delay(1000)
        }

        emit("dark_phase2_marker", 0, 0, 0)
        delay(1500)
        for (percent in 1..20) {
            emitBrightness("bright_$percent", percent)
            emit("bright_${percent}_colour", 96, 96, 96)
            delay(1500)
        }
        emitBrightness("bright_restore_100", 100)
    }

    /**
     * Writes flat out for an hour, so that "does the link survive sustained load" stops being a
     * guess. **No camera, no dark room, nobody watching** — the CSV is the entire result.
     *
     * This is the one measurement standing between the pacing work and shipping. Removing the
     * artificial pacing wait leaves writes completion-gated, which the measurements say is right;
     * but every ramp so far ran flat out for about fifteen seconds, so nothing tested whether a
     * long run of it disconnects, backs the queue up, or degrades.
     *
     * **Duration is a parameter, and the default is a compromise Joe chose on 2026-08-18.** The
     * plan asks "does an hour hold"; an hour is also an hour of strobing in his room and an hour
     * of the shared test phone. 15 minutes catches a gross disconnect, a sag or a stall pattern,
     * and that is what is being run. It does *not* answer the hour question — anything that only
     * shows up after 20 minutes of thermal load is still unmeasured, so a clean 15 is permission
     * to proceed, not proof. Pass `--ei minutes 60` when the full run is wanted. Three failure modes, and the auto-tune
     * engine currently only notices the first.
     *
     * What makes the CSV readable afterwards: the timestamps alone give achieved rate over time, so
     * a link that degrades shows as a rate that sags. Colour alternates so a camera *could* be
     * pointed at it if anyone wants a light-side check, but nothing here depends on that. A marker
     * row every 30s segments the file without any alignment work.
     *
     * Deliberately not a "stress test" in the auto-tune sense: it makes no judgement and asserts
     * nothing. It produces a trace, and the analysis decides.
     */
    private suspend fun sustainedLoad(emit: (String, Int, Int, Int) -> Unit, minutes: Int) {
        val totalMs = (if (minutes > 0) minutes else DEFAULT_SUSTAINED_MINUTES) * 60 * 1000L
        val startedAt = System.currentTimeMillis()
        var index = 0
        var nextMarkerAt = 30_000L
        while (System.currentTimeMillis() - startedAt < totalMs) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= nextMarkerAt) {
                emit("sustained_marker_${nextMarkerAt / 1000}s", 0, 0, 0)
                nextMarkerAt += 30_000L
            }
            if (index % 2 == 0) emit("sustained", 255, 0, 0) else emit("sustained", 0, 0, 255)
            index++
            // No delay at all: the radio is the pacer, which is exactly the configuration the
            // pacing removal would ship. Testing anything slower would not test the thing.
            delay(1)
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
