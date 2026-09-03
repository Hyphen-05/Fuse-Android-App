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
 *  - [LATENCY_PULSE] answers `visibleLatencyMs`, but only with the driving phone's screen in
 *    frame. Aligning video to log time using the strips cannot work — they arrive already delayed
 *    by the latency being measured, so the alignment subtracts it out, and what the run measured
 *    instead was delivery *jitter* (sd 36ms, 2026-08-16). The screen flash is a second event in the
 *    same frame that BLE does not delay. See [CalibrationScreenFlash].
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
    const val ATTENTION = "attention"
    const val COLOUR_PRIMARIES = "colour_primaries"
    const val BRIGHTNESS_X_COLOUR = "brightness_x_colour"
    const val TRANSITION_PROBE = "transition_probe"
    const val CCT_SWEEP = "cct_sweep"
    const val WRITE_TYPE_PROBE = "write_type_probe"
    const val FULL_RAMP = "full_ramp"
    const val LATENCY_CAMERA = "latency_camera"
    const val HOLD_DIM = "hold_dim"
    const val PWM_PROBE = "pwm_probe"
    const val CAPTURE_ALL = "capture_all"
    const val FULL_RAMP_X3 = "full_ramp_x3"
    const val RATE_CEILING = "rate_ceiling"
    const val CCT_PROBE = "cct_probe"
    const val CHASE_PROBE = "chase_probe"
    const val FRAMING_CHECK = "framing_check"

    val ALL = listOf(
        BRIGHTNESS_RAMP, LATENCY_PULSE, RATE_RAMP, SPACING_STAIRCASE, DARK_RAMP,
        SUSTAINED_LOAD, HOLD_WHITE, ATTENTION,
        COLOUR_PRIMARIES, BRIGHTNESS_X_COLOUR, TRANSITION_PROBE, CCT_SWEEP,
        WRITE_TYPE_PROBE, FULL_RAMP, LATENCY_CAMERA, HOLD_DIM, PWM_PROBE, CAPTURE_ALL,
        FULL_RAMP_X3, RATE_CEILING, CCT_PROBE, CHASE_PROBE, FRAMING_CHECK
    )

    /**
     * The sequences that need the phone's own camera bound.
     *
     * Everything else leaves it shut, and that is not an oversight: binding costs frames, heat and
     * battery, and those costs land squarely on whatever a rate-shaped sequence is trying to
     * measure. A camera left open through [RATE_CEILING] would be measuring a hot, busy phone.
     */
    val NEEDS_PHOTOMETER = setOf(
        LATENCY_CAMERA, PWM_PROBE, FULL_RAMP_X3, CCT_PROBE, CHASE_PROBE, FRAMING_CHECK
    )

    /**
     * The three exposures [fullRampX3] cycles through at every byte, as (exposure ns, ISO).
     *
     * Their products — 0.4, 4 and 48 arbitrary units — span 120x, which is what it takes to hold
     * byte 2 and byte 255 in the same run. The 2026-09-02 session shot the ramp twice at two
     * exposures and joined them afterwards with a fitted scale factor; between bytes 48 and 203 the
     * two takes agreed to 5% and the join was sound, but below byte 48 they disagreed by up to 42%
     * and the bottom of the curve is still unsettled. Cycling *within* one run replaces the fitted
     * factor with a known ratio and removes the question.
     */
    val X3_EXPOSURES = listOf(
        2_000_000L to 200,   // least sensitive: holds the top of the range unclipped
        5_000_000L to 800,   // the middle, and the overlap with both neighbours
        15_000_000L to 3200  // most sensitive: resolves bytes 2-16, which is where the doubt is
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
        dimPercent: Int = 15,
        send: (ByteArray) -> Unit
    ): File? {
        log.setLength(0)
        log.append("elapsed_ms,label,r,g,b\n")
        val startedAt = System.currentTimeMillis()

        fun emit(label: String, r: Int, g: Int, b: Int) {
            // Stamped before the write, not after: the probe is timing how long the light takes to
            // follow the command, so the clock has to start at the last instant we still hold it.
            // Inert unless a latency run is active.
            CalibrationPhotometer.markWrite(label)
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

        // Looks for PWM flicker at a commanded duty cycle without anyone sweeping a camera.
        //
        // The smear method needs the camera moved during one exposure, and on 2026-09-02 the strip's
        // cable would not reach far enough for that. This asks the same question a different way: if
        // the driver dims by a **slow** PWM carrier, each short exposure catches a different phase of
        // it, so brightness scatters from frame to frame. If it dims by current, or by a carrier far
        // above the frame rate, every frame reads the same and the variance is sensor noise.
        //
        // The probe's 5ms exposure is the right scale on purpose. A carrier slow enough to be *seen*
        // as flicker has a period longer than 5ms and so survives into the per-frame numbers;
        // anything faster averages out inside one exposure, and is imperceptible anyway. So this
        // measures the thing that actually matters — visible flicker — rather than the carrier.
        //
        // Run it at several duty cycles and compare: the risk is a driver that switches to a slow
        // carrier only when dimmed hard.
        if (sequence == PWM_PROBE) {
            val percent = dimPercent.coerceIn(1, 100)
            send(DuoCoProtocol.createBrightnessCommand(percent))
            delay(400)
            send(DuoCoProtocol.createColorCommand(255, 255, 255))
            delay(20_000)
            return null
        }

        // Before the brightness pin, deliberately: pinning to 100 is the exact opposite of what a
        // dim hold is for. Parks the strip at white and a commanded firmware brightness so a
        // hand-swept PWM still has a known, exact duty cycle to photograph.
        if (sequence == HOLD_DIM) {
            val percent = dimPercent.coerceIn(1, 100)
            send(DuoCoProtocol.createBrightnessCommand(percent))
            delay(400)
            send(DuoCoProtocol.createColorCommand(255, 255, 255))
            delay(600_000)
            return null
        }

        // Not a measurement and not logged: it runs only when nothing is being captured, and a
        // CSV of it would look like data. It never returns on its own — see [attentionFade].
        if (sequence == ATTENTION) {
            attentionFade(send)
            return null
        }

        // Setup aid, not a measurement: parks the strips at the brightest state any run will
        // produce so the camera's exposure can be locked against the worst case. Locking against a
        // dimmer state clips the top of the ramp, which is unrecoverable after the fact.
        if (sequence == HOLD_WHITE) {
            emit("hold_white", 255, 255, 255)
            delay(180_000)
            return writeCsv(sequence, outputDir, startedAt)
        }

        // Rows marked -2 are screen events, not colours — the same trick the brightness rows use
        // with -1. Recorded from a frame callback so the compositor's share of screen latency is
        // measured rather than assumed; see [CalibrationScreenFlash] for what is left over.
        // LATENCY_PULSE only, and that scoping is the whole point. The overlay pins the phone's
        // screen to full brightness and holds it awake, which is correct for the one run that needs
        // the screen in frame — and is a lamp in a dark room for every run that does not. It fired
        // on every sequence until 2026-09-02, including a blackout dark_ramp, which is the run
        // least able to survive a constant light source pointed at the subject.
        if (sequence == LATENCY_PULSE) {
            CalibrationScreenFlash.presentedListener = { atMs ->
                record(atMs - startedAt, "screen_presented", -2, -2, -2)
            }
        }
        // Awake for every run, bright for one. Letting the screen sleep does not just darken the
        // room, it stalls the sequence — see [CalibrationScreenFlash.runActive].
        CalibrationScreenFlash.brightScreen.value = sequence == LATENCY_PULSE
        CalibrationScreenFlash.runActive.value = true

        syncMarker(::emit)

        when (sequence) {
            BRIGHTNESS_RAMP -> brightnessRamp(::emit)
            LATENCY_PULSE -> latencyPulse(::emit)
            RATE_RAMP -> rateRamp(::emit)
            SPACING_STAIRCASE -> spacingStaircase(::emit)
            DARK_RAMP -> darkRamp(::emit, ::emitBrightness)
            SUSTAINED_LOAD -> sustainedLoad(::emit, sustainedMinutes)
            COLOUR_PRIMARIES -> colourPrimaries(::emit)
            BRIGHTNESS_X_COLOUR -> brightnessXColour(::emit, ::emitBrightness)
            TRANSITION_PROBE -> transitionProbe(::emit)
            CCT_SWEEP -> cctSweep(::emit, send, ::record, startedAt)
            WRITE_TYPE_PROBE -> writeTypeProbe(send, ::record, startedAt)
            FULL_RAMP -> fullRamp(::emit, ::emitBrightness)
            FULL_RAMP_X3 -> fullRampX3(::emit, ::emitBrightness)
            RATE_CEILING -> rateCeiling(::emit)
            CCT_PROBE -> cctProbe(::emit, send, ::record, startedAt)
            CHASE_PROBE -> chaseProbe(::emit, send, ::record, startedAt)
            FRAMING_CHECK -> framingCheck(::emit)
            LATENCY_CAMERA -> latencyCamera(::emit)
            CAPTURE_ALL -> {
                // One unattended pass over everything that needs no camera repositioning and no
                // hands. Each block re-opens with a sync marker so the segments stay separable in a
                // single recording, and the whole thing lands in one CSV whose labels say which
                // block a row belongs to.
                brightnessRamp(::emit)
                syncMarker(::emit)
                darkRamp(::emit, ::emitBrightness)
                syncMarker(::emit)
                colourPrimaries(::emit)
                syncMarker(::emit)
                brightnessXColour(::emit, ::emitBrightness)
                syncMarker(::emit)
                cctSweep(::emit, send, ::record, startedAt)
                syncMarker(::emit)
                transitionProbe(::emit)
                syncMarker(::emit)
                writeTypeProbe(send, ::record, startedAt)
                syncMarker(::emit)
                spacingStaircase(::emit)
                syncMarker(::emit)
                rateRamp(::emit)
            }
            else -> return null
        }

        emit("end_black", 0, 0, 0)
        CalibrationScreenFlash.on.value = false
        CalibrationScreenFlash.runActive.value = false
        CalibrationScreenFlash.brightScreen.value = false
        CalibrationScreenFlash.presentedListener = null
        return writeCsv(sequence, outputDir, startedAt)
    }

    /**
     * The strip asking for Joe.
     *
     * The monitor is off during a capture session to keep light out of frame, so there is no way to
     * tell him on screen that a run finished, aborted, or needs him to move the camera. Joe's spec,
     * 2026-08-29: *smooth but not slow, fiery orange fades.*
     *
     * A smooth sinusoidal breath between (255, 70, 0) and (255, 150, 30), one breath in 1.4s. Red is
     * held at 255 and green never passes 150, so it never travels through yellow or white — those
     * read as a test pattern, which is the one thing this must not be mistaken for.
     *
     * It **loops until the coroutine is cancelled**, because a single pulse is missable from another
     * room and being missable defeats the point. The ViewModel cancels it when the next sequence
     * starts or when the run is acknowledged over adb.
     *
     * Writes directly rather than through `emit`: nothing here belongs in a measurement CSV.
     */
    private suspend fun attentionFade(send: (ByteArray) -> Unit) {
        // 40ms per step is 35 steps across the breath — smooth to the eye, and an order of
        // magnitude slower than the write rates the rest of this file is built to stress.
        while (true) {
            for (i in 0 until ATTENTION_STEPS) {
                val (r, g, b) = attentionColourAt(i)
                send(DuoCoProtocol.createColorCommand(r, g, b))
                delay(ATTENTION_STEP_MS)
            }
        }
    }

    /**
     * 40ms per step, 36 steps to the breath (1.44s) — smooth to the eye, and an order of magnitude
     * slower than the write rates the rest of this file is built to stress.
     *
     * Even, deliberately: with an odd step count no step lands on the turnaround, so the breath
     * peaks one short of its commanded top end and never reaches the colour it is specified as.
     */
    const val ATTENTION_STEP_MS = 40L
    const val ATTENTION_STEPS = 36

    /** Fiery orange endpoints. Green stops well short of red, which is what keeps it out of yellow. */
    private const val ATTENTION_G_LOW = 70
    private const val ATTENTION_G_HIGH = 150
    private const val ATTENTION_B_LOW = 0
    private const val ATTENTION_B_HIGH = 30

    /**
     * One step of the breath, as (r, g, b). Split out from [attentionFade] so the colour rule can be
     * tested: the whole point of the signal is that it cannot be mistaken for a measurement, and it
     * stops being that the moment it passes through white or yellow.
     */
    fun attentionColourAt(step: Int): Triple<Int, Int, Int> {
        // A raised cosine: 0 at both ends of the breath, 1 in the middle, with no corner at the
        // turnaround. A triangle wave visibly ticks at the top and bottom.
        val phase = (1.0 - kotlin.math.cos(2.0 * Math.PI * step / ATTENTION_STEPS)) / 2.0
        val g = (ATTENTION_G_LOW + (ATTENTION_G_HIGH - ATTENTION_G_LOW) * phase).toInt()
        val b = (ATTENTION_B_LOW + (ATTENTION_B_HIGH - ATTENTION_B_LOW) * phase).toInt()
        return Triple(255, g, b)
    }

    /**
     * Each channel swept alone, then the pairs, then white.
     *
     * Answers two things nothing else does. **Per-channel response**: red, green and blue are not
     * the same die and do not have the same output for the same byte, so a model built on a single
     * white curve is wrong for every colour that is not white. **Crosstalk**: whether driving one
     * channel changes what another does — shared current limiting shows up here and nowhere else.
     *
     * The pairs are the check on the single channels. If (255,255,0) is not what the red curve and
     * the green curve predict added together, the channels are not independent and no per-channel
     * model will hold.
     */
    private suspend fun colourPrimaries(emit: (String, Int, Int, Int) -> Unit) {
        val levels = listOf(0, 16, 32, 64, 96, 128, 160, 192, 224, 255)
        for (level in levels) { emit("prim_r_$level", level, 0, 0); delay(1500) }
        for (level in levels) { emit("prim_g_$level", 0, level, 0); delay(1500) }
        for (level in levels) { emit("prim_b_$level", 0, 0, level); delay(1500) }
        // Secondaries at full and half, against which the single-channel curves are checked.
        val pairs = listOf(
            Triple(255, 255, 0), Triple(0, 255, 255), Triple(255, 0, 255),
            Triple(128, 128, 0), Triple(0, 128, 128), Triple(128, 0, 128)
        )
        for ((r, g, b) in pairs) { emit("prim_pair_${r}_${g}_$b", r, g, b); delay(1500) }
        emit("prim_white_255", 255, 255, 255)
        delay(1500)
    }

    /**
     * Firmware brightness crossed with colour.
     *
     * The strip applies its own brightness on top of the RGB it is sent, and whether that is a clean
     * multiply — and whether it is the *same* multiply at every hue — is unmeasured. If it is clean,
     * a model needs one curve and one scalar. If it is not, brightness and colour cannot be modelled
     * separately at all, and every preset that dims is wrong in a way no colour tuning will fix.
     */
    private suspend fun brightnessXColour(
        emit: (String, Int, Int, Int) -> Unit,
        emitBrightness: (String, Int) -> Unit
    ) {
        val colours = listOf(
            Triple(255, 255, 255), Triple(255, 0, 0), Triple(0, 255, 0),
            Triple(0, 0, 255), Triple(255, 120, 0)
        )
        for (percent in listOf(10, 25, 50, 75, 100)) {
            emitBrightness("bxc_brightness_$percent", percent)
            delay(600)
            for ((r, g, b) in colours) {
                emit("bxc_${percent}_${r}_${g}_$b", r, g, b)
                delay(1500)
            }
        }
        emitBrightness("bxc_restore_100", 100)
        delay(400)
    }

    /**
     * Hard jumps between distant colours, with a long settle after each.
     *
     * Nobody has ever established whether the firmware steps straight to a commanded colour or
     * glides to it. It decides what "a flash" physically is: if the strip interpolates, a short
     * flash never reaches the colour it was sent, and every timing constant derived from commanded
     * values is measuring something else. Filmed at any frame rate this is visible as either an edge
     * or a ramp.
     */
    private suspend fun transitionProbe(emit: (String, Int, Int, Int) -> Unit) {
        val jumps = listOf(
            Triple(0, 0, 0) to Triple(255, 255, 255),
            Triple(255, 0, 0) to Triple(0, 0, 255),
            Triple(0, 255, 0) to Triple(255, 0, 255),
            Triple(255, 255, 255) to Triple(0, 0, 0)
        )
        repeat(3) { round ->
            for ((from, to) in jumps) {
                emit("trans_${round}_from_${from.first}_${from.second}_${from.third}", from.first, from.second, from.third)
                delay(1800)
                emit("trans_${round}_to_${to.first}_${to.second}_${to.third}", to.first, to.second, to.third)
                delay(1800)
            }
        }
    }

    /**
     * The warm/cold command across its range, against what the app currently models.
     *
     * This is a separate command from RGB, and the app converts a colour temperature into it using
     * numbers nobody has checked against the hardware. Logged with r/g = -3 to mark the rows as CCT
     * rather than colour, alongside the -1 brightness rows and the -2 screen rows.
     */
    private suspend fun cctSweep(
        emit: (String, Int, Int, Int) -> Unit,
        send: (ByteArray) -> Unit,
        record: (Long, String, Int, Int, Int) -> Unit,
        startedAt: Long
    ) {
        emit("cct_black", 0, 0, 0)
        delay(800)
        val steps = listOf(0, 25, 50, 75, 100)
        for (warm in steps) {
            for (cold in steps) {
                send(DuoCoProtocol.createCctCommand(warm, cold))
                record(System.currentTimeMillis() - startedAt, "cct_w${warm}_c$cold", -3, -3, warm * 100 + cold)
                delay(1500)
            }
        }
    }

    /**
     * The plain colour command against the music colour command, which differ by one byte.
     *
     * `createColorCommand` sends 0x10 in byte 7 and `createMusicColorCommand` sends 0x20; everything
     * else, length included, is identical. So any difference between them is the **firmware's**, not
     * the radio's — which is what makes this worth running rather than obvious. Three questions,
     * none of which has an answer:
     *
     *  1. **Does the strip treat them differently?** Blocks A and B send the same hard jumps through
     *     each. If one snaps and the other glides, every timing constant taken through one command
     *     is wrong for the other, and the visualiser has been using the music variant all along.
     *  2. **Is either faster on the wire?** Blocks C and D burst each flat out. Identical payload
     *     size says they should match exactly; if the wire log disagrees, something upstream is
     *     treating them differently and that is a finding in the app, not the strip.
     *  3. **Do they displace each other?** They share a type byte, so the write manager's supersede
     *     check sees a queued command of one kind as replaceable by the other. Block E alternates
     *     them fast enough for that to bite, which is exactly the condition a running visualiser is
     *     in.
     *
     * Logged with r/g = -4 to mark rows as write-type events. Colours are sent raw rather than
     * through `emit`, because `emit` can only send the plain command.
     */
    private suspend fun writeTypeProbe(
        send: (ByteArray) -> Unit,
        record: (Long, String, Int, Int, Int) -> Unit,
        startedAt: Long
    ) {
        fun now() = System.currentTimeMillis() - startedAt

        // A and B: identical visual content, one command type each, slow enough to film.
        for ((label, build) in listOf<Pair<String, (Int, Int, Int) -> ByteArray>>(
            "plain" to DuoCoProtocol::createColorCommand,
            "music" to DuoCoProtocol::createMusicColorCommand
        )) {
            repeat(6) { i ->
                send(build(0, 0, 0))
                record(now(), "wt_${label}_${i}_black", -4, -4, 0)
                delay(1200)
                send(build(255, 255, 255))
                record(now(), "wt_${label}_${i}_white", -4, -4, 255)
                delay(1200)
            }
        }

        // C and D: flat out, no delay. The wire log is the whole result; the strip is irrelevant.
        for ((label, build) in listOf<Pair<String, (Int, Int, Int) -> ByteArray>>(
            "plain" to DuoCoProtocol::createColorCommand,
            "music" to DuoCoProtocol::createMusicColorCommand
        )) {
            record(now(), "wt_burst_${label}_start", -4, -4, -4)
            repeat(200) { i ->
                val v = if (i % 2 == 0) 255 else 0
                send(build(v, 0, 0))
            }
            record(now(), "wt_burst_${label}_end", -4, -4, -4)
            delay(2000)
        }

        // E: alternating types at a rate where the shared type byte makes them supersede each other.
        record(now(), "wt_interleave_start", -4, -4, -4)
        repeat(100) { i ->
            val cmd = if (i % 2 == 0) {
                DuoCoProtocol.createColorCommand(255, 0, 0)
            } else {
                DuoCoProtocol.createMusicColorCommand(0, 0, 255)
            }
            send(cmd)
            delay(20)
        }
        record(now(), "wt_interleave_end", -4, -4, -4)
        delay(1500)
    }

    /**
     * Every byte from 0 to 255, then every firmware brightness percent from 1 to 100.
     *
     * Joe, 2026-09-02: we already know the steps are coarse at the bottom — what is not known is
     * **where that stops**, and no sampled grid can answer it. [BRIGHTNESS_RAMP] jumps 32 → 48 → 64;
     * if the curve stops being jumpy at 55 it is invisible. [DARK_RAMP] has every byte but stops at
     * 32, so the whole 32-255 span is interpolation.
     *
     * So this measures every byte instead of guessing which ones matter. The point is not the curve
     * itself but its **derivative**: the difference between consecutive bytes IS the step size, and
     * it can only be had from consecutive bytes. Where that difference falls below a just-noticeable
     * amount is the answer to "where does it get smooth", and it comes straight off this with no
     * fitting and no assumptions about the shape.
     *
     * The firmware-brightness half asks the same question of the other dimmer. [DARK_RAMP] covers
     * 1-20%; this covers all of it, so the two dimmers can finally be compared over their whole
     * ranges rather than at the bottom of one.
     *
     * About 7 minutes. Worth running twice at two exposures: at any single exposure the bottom of
     * the range and the top cannot both be measured, and that is the sensor's dynamic range rather
     * than anything about this sequence.
     */
    private suspend fun fullRamp(
        emit: (String, Int, Int, Int) -> Unit,
        emitBrightness: (String, Int) -> Unit
    ) {
        // 1s a step: enough frames at any video rate to average out PWM and sensor noise, and it
        // keeps all 256 inside four and a half minutes.
        for (level in 0..255) {
            emit("full_$level", level, level, level)
            delay(1000)
        }
        // Descending every fourth byte: the exposure-drift check, which needs enough points to see a
        // trend and not another four minutes to do it.
        for (level in (0..255 step 4).reversed()) {
            emit("full_down_$level", level, level, level)
            delay(600)
        }
        // The other dimmer, over its whole range rather than the bottom fifth.
        emit("full_bright_base", 255, 255, 255)
        delay(1000)
        for (percent in 1..100) {
            emitBrightness("full_bright_$percent", percent)
            delay(800)
        }
        emitBrightness("full_bright_restore_100", 100)
        delay(400)
    }

    /**
     * Is the camera pointed at the strip properly? Twenty seconds, before anything long starts.
     *
     * This is the one question in the session that a script genuinely cannot answer for itself and
     * a person genuinely can — a phone that has been nudged, or aimed at half the strand, produces
     * a run that completes, exports, and measures a wall. Everything downstream assumes the framing
     * is good, so it is worth twenty seconds to know rather than to hope.
     *
     * Black, then white, then each primary, holding long enough for several grid rows each. What
     * `analyse_framing.py` gets from that is:
     *  - **is the strip in frame at all** — cells that brighten between black and white;
     *  - **how much of the frame it fills**, which decides whether individual LEDs land in their
     *    own grid cells or smear across one;
     *  - **whether it runs off an edge**, which is the failure a preview makes easy to miss;
     *  - **which exposure to shoot at**, since white is held across all of [X3_EXPOSURES] and the
     *    right one is the brightest that does not saturate.
     *
     * The primaries are there because a camera can be pointed correctly and still be wrong: if one
     * channel clips while the others do not, the exposure suits white and not colour, and every
     * colour measurement downstream inherits it.
     */
    private suspend fun framingCheck(emit: (String, Int, Int, Int) -> Unit) {
        suspend fun holdAtEveryExposure(label: String, r: Int, g: Int, b: Int) {
            emit(label, r, g, b)
            for ((exposure, iso) in X3_EXPOSURES) {
                CalibrationPhotometer.setExposure(exposure, iso)
                // Long enough that several grid rows land on the new setting: the grid is written
                // every twelfth frame, so this is a handful of them rather than one.
                delay(1200)
            }
        }
        holdAtEveryExposure("framing_black", 0, 0, 0)
        holdAtEveryExposure("framing_white", 255, 255, 255)
        holdAtEveryExposure("framing_red", 255, 0, 0)
        holdAtEveryExposure("framing_green", 0, 255, 0)
        holdAtEveryExposure("framing_blue", 0, 0, 255)
        emit("framing_end", 0, 0, 0)
        delay(500)
    }

    /**
     * The full ramp again, but with the camera's exposure swept **inside** the run.
     *
     * The 2026-09-02 answer to "no single exposure holds byte 1 and byte 255" was to shoot the ramp
     * twice and join the takes afterwards with a fitted scale factor. That worked in the middle —
     * bytes 48-203 agreed to 5%, which is what proved the method — and failed at the bottom, where
     * the two takes disagree by up to 42% and the shape is all that survives. The bottom is exactly
     * the region the app cares about, since byte 2 already emits 5% of full light and there is
     * nothing dimmer available.
     *
     * Holding each byte while the camera cycles [X3_EXPOSURES] fixes it in one pass: three
     * measurements of *the same light*, at ratios that are known rather than fitted, so the curve
     * joins itself. It also removes the second run, the second setup and the reframing.
     *
     * Each exposure gets 500ms of its own. `setExposure` returns as soon as the request is queued,
     * not when a frame carrying it arrives, so the first frames of each window are still on the old
     * setting — which is why every row records the exposure in force and the analysis discards the
     * changeover rather than trusting a settling delay.
     *
     * About 8 minutes for the ramp, 11 with the brightness sweep.
     */
    private suspend fun fullRampX3(
        emit: (String, Int, Int, Int) -> Unit,
        emitBrightness: (String, Int) -> Unit
    ) {
        suspend fun sweepExposures(label: String) {
            for ((exposure, iso) in X3_EXPOSURES) {
                CalibrationPhotometer.setExposure(exposure, iso)
                // Long enough that several frames land on the new setting even after the queue
                // has drained the old ones.
                delay(500)
                CalibrationPhotometer.markWrite("${label}_exp_${exposure}_$iso")
            }
        }
        for (level in 0..255) {
            emit("fx3_$level", level, level, level)
            sweepExposures("fx3_$level")
        }
        // The other dimmer, at the middle exposure only: firmware brightness spans a narrower range
        // than the colour bytes do and does not need all three.
        CalibrationPhotometer.setExposure(X3_EXPOSURES[1].first, X3_EXPOSURES[1].second)
        emit("fx3_bright_base", 255, 255, 255)
        delay(800)
        for (percent in 1..100) {
            emitBrightness("fx3_bright_$percent", percent)
            delay(800)
        }
        emitBrightness("fx3_bright_restore_100", 100)
        delay(400)
    }

    /**
     * [rateRamp] again, but reaching for rates the app has never actually asked for.
     *
     * Every fast sequence run before 2026-09-02 was capped at ~15Hz delivered by
     * `DeviceWriteManager`'s pacing wait, which the calibration path was supposed to bypass and did
     * not. So "the strip drops writes above 15Hz" was never a measurement of the strip — nothing had
     * ever written to it faster. This ladder goes to 200Hz, and the run is meant to be repeated at
     * several `--ei pacing` values so the ceiling is *swept* rather than merely removed: pacing 0
     * says what the hardware can do, and 25 and 50 say what the current defaults cost.
     *
     * No camera. The wire log's send-and-ack rows carry the whole answer, and binding the camera
     * would heat the phone that is being asked how fast it can go.
     */
    private suspend fun rateCeiling(emit: (String, Int, Int, Int) -> Unit) {
        val rates = listOf(10, 20, 30, 50, 75, 100, 150, 200)
        for (rate in rates) {
            val intervalMs = (1000L / rate).coerceAtLeast(1L)
            val writes = rate * 4
            emit("rc_${rate}_marker", 0, 0, 0)
            delay(700)
            repeat(writes) { index ->
                if (index % 2 == 0) emit("rc_$rate", 255, 0, 0) else emit("rc_$rate", 0, 0, 255)
                delay(intervalMs)
            }
        }
    }

    /**
     * Why the CCT sweep emitted no light at all.
     *
     * All 25 steps of [cctSweep] measured exactly zero on 2026-09-02 — not dim, not noisy, zero,
     * over the whole frame, while the steps either side of it registered normally. Two explanations
     * fit: the command is wrong for this firmware, or `Fireworks` has no white channel to drive. The
     * difference matters, and no amount of filming a dark strip will decide it.
     *
     * So vary the command instead of the levels, one axis at a time:
     *  - the sub-mode byte at index 3, which `createCctCommand` hard-codes to 0x02;
     *  - the trailing byte at index 7, hard-coded to 0x08 where the colour command sends 0x00;
     *  - warm/cold as 0-255 rather than the 0-100 the sweep sent, in case they are raw levels;
     *  - each of those again after a mode-switch command, in case CCT needs the strip put into a
     *    white mode first.
     *
     * A known-good white is sent between every candidate as a liveness check. If those stop lighting
     * the strip too, the run has knocked the device into a state and the rest of it is worthless —
     * far better to see that in the trace than to conclude "no white channel" from a dead strip.
     */
    private suspend fun cctProbe(
        emit: (String, Int, Int, Int) -> Unit,
        send: (ByteArray) -> Unit,
        record: (Long, String, Int, Int, Int) -> Unit,
        startedAt: Long
    ) {
        val subModes = listOf(0x01, 0x02, 0x03)
        val tails = listOf(0x00, 0x08)
        val levels = listOf(255 to 0, 0 to 255, 255 to 255, 100 to 0)

        suspend fun liveness(tag: String) {
            emit("cctp_live_${tag}_white", 255, 255, 255)
            delay(900)
            emit("cctp_live_${tag}_black", 0, 0, 0)
            delay(600)
        }

        suspend fun candidate(tag: String, sub: Int, tail: Int, warm: Int, cold: Int) {
            val cmd = byteArrayOf(
                0x7e.toByte(), 0x06.toByte(), 0x05.toByte(), sub.toByte(),
                warm.toByte(), cold.toByte(), 0xff.toByte(), tail.toByte(), 0xef.toByte()
            )
            CalibrationPhotometer.markWrite(tag)
            send(cmd)
            // r/g = -5 marks a row as a raw protocol probe rather than a colour, the same way the
            // write-type probe uses -4 and the CCT sweep uses -3.
            record(System.currentTimeMillis() - startedAt, tag, -5, -5, warm * 1000 + cold)
            delay(1500)
        }

        for (sub in subModes) {
            for (tail in tails) {
                liveness("s${sub}_t$tail")
                for ((warm, cold) in levels) {
                    candidate("cctp_s${sub}_t${tail}_w${warm}_c$cold", sub, tail, warm, cold)
                }
            }
        }

        // Same matrix once more, but with the strip put into a mode first. If CCT only works from a
        // white mode, this is the block that lights and the ones above are the reason why not.
        liveness("premode")
        send(DuoCoProtocol.createModeCommand(1))
        record(System.currentTimeMillis() - startedAt, "cctp_mode_switch", -5, -5, 1)
        delay(1500)
        for (sub in subModes) {
            for ((warm, cold) in levels) {
                candidate("cctp_mode_s${sub}_w${warm}_c$cold", sub, 0x08, warm, cold)
            }
        }
        liveness("end")
    }

    /**
     * Whether the long vertical strand really is consecutive LEDs on the wire.
     *
     * `docs/positions.md` assumes it is, and every direction Mode Capture reports rests on that
     * assumption without it ever having been checked. The check is cheap: run a built-in mode with
     * an obvious chase and watch which grid cell lights when. If the lit cell walks monotonically
     * along the strand, the assumption holds; if it jumps about, every direction label from Mode
     * Capture is arbitrary and the position map needs redoing before that run is worth anything.
     *
     * Slow speed on purpose — a chase running faster than the grid is sampled tells you nothing,
     * and the grid is written every twelfth frame.
     */
    private suspend fun chaseProbe(
        emit: (String, Int, Int, Int) -> Unit,
        send: (ByteArray) -> Unit,
        record: (Long, String, Int, Int, Int) -> Unit,
        startedAt: Long
    ) {
        emit("chase_black", 0, 0, 0)
        delay(1000)
        // A single white pixel travelling is the clearest possible signal, but the built-in modes
        // are what a real chase looks like, and it is the built-in modes Mode Capture will label.
        send(DuoCoProtocol.createModeSpeedCommand(10))
        record(System.currentTimeMillis() - startedAt, "chase_speed_10", -5, -5, 10)
        delay(500)
        for (mode in listOf(1, 2, 3)) {
            send(DuoCoProtocol.createModeCommand(mode))
            record(System.currentTimeMillis() - startedAt, "chase_mode_$mode", -5, -5, mode)
            delay(15_000)
        }
        emit("chase_end", 0, 0, 0)
        delay(500)
    }

    /**
     * Hard black-to-white steps, watched by the driving phone's own camera.
     *
     * The same shape as [latencyPulse] but with the measurement inside the app rather than in a
     * video, so it needs neither a second phone nor the driver's screen in frame — see
     * [CalibrationPhotometer] for why that is the sounder instrument as well as the more convenient
     * one.
     *
     * Long gaps between pulses on purpose. Each one has to be unambiguously attributable to its own
     * write, and the strip has to be fully settled before the next edge, or the two blur into a
     * single measurement of neither.
     */
    private suspend fun latencyCamera(emit: (String, Int, Int, Int) -> Unit) {
        // Settle first: the probe's first frames arrive while the camera is still ramping exposure,
        // and a pulse landing in that window measures the camera waking up.
        emit("latcam_settle", 0, 0, 0)
        delay(3000)
        repeat(20) { index ->
            emit("latcam_${index}_on", 255, 255, 255)
            delay(900)
            emit("latcam_${index}_off", 0, 0, 0)
            delay(1400)
        }
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
            // Screen first, then the wire, in that order and with nothing between them. The screen
            // is the reference event: it must not be made late by the BLE write it is timing.
            CalibrationScreenFlash.on.value = true
            emit("pulse_${index}_on", 255, 255, 255)
            delay(400)
            CalibrationScreenFlash.on.value = false
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
