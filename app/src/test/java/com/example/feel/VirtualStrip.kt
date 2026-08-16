package com.example.feel

/**
 * The physical limits of a DuoCo strip, as numbers the model can enforce.
 *
 * **These are provisional.** Everything here is a plausible starting value inferred from what the
 * app already does (a 50ms default pacing, the documented need to pace at all, the known
 * pixel-count flash) — none of it has been measured against real hardware yet. Until a
 * characterisation run replaces them, this model is useful for comparing *relative* behaviour
 * between presets and for catching regressions, and must not be quoted as "the hardware does X".
 *
 * Each field names what would measure it, so the calibration run has a checklist.
 */
data class StripLimits(
    /**
     * Writes arriving closer together than this are dropped by the strip rather than queued.
     * Measured by: ramping write rate on real hardware and finding where commanded colours stop
     * appearing (camera) or where achieved fps stops tracking commanded fps (wire log).
     */
    val minWriteSpacingMs: Long = 20,

    /**
     * Sustained writes per second one strip can absorb over a long run, independent of bursts.
     * Measured by: a 60s constant-rate run at several rates, comparing sent vs rendered.
     */
    val sustainedWritesPerSecond: Int = 40,

    /**
     * Each additional strip on the same radio costs throughput. 1.0 = no cost; 0.6 = each strip
     * gets 60% of what it would get alone when two are connected.
     * Measured by: repeating the sustained-rate run with one strip, then two.
     */
    val multiDeviceThroughputFactor: Double = 0.6,

    /**
     * Wire-to-light delay: command accepted → colour visibly changed.
     * Measured by: camera capture of a step change against the write timestamp in the log.
     */
    val visibleLatencyMs: Long = 35,

    /**
     * The known, unavoidable firmware flash when the pixel count changes (see CLAUDE.md). Modelled
     * so a feature that changes pixel count mid-animation shows the flash in its render instead of
     * looking clean.
     */
    val pixelCountChangeFlashMs: Long = 120
)

/** One write as the harness saw it, before the strip decided what to do with it. */
data class WriteAttempt(val atMs: Long, val bytes: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** What the strip is showing at a moment in simulated time. */
data class StripFrame(
    val atMs: Long,
    val r: Int,
    val g: Int,
    val b: Int,
    val powered: Boolean,
    /** Set on frames where a write was dropped, so renders can mark the stutter. */
    val droppedWriteHere: Boolean = false
)

/** What the run did to the strip, beyond the pixels. */
data class StripStats(
    val accepted: Int,
    val droppedTooFast: Int,
    val droppedOverCapacity: Int,
    val malformed: Int,
    val ignored: Int
) {
    val attempted: Int get() = accepted + droppedTooFast + droppedOverCapacity + malformed + ignored
    val dropRate: Double get() = if (attempted == 0) 0.0 else (droppedTooFast + droppedOverCapacity).toDouble() / attempted
}

/**
 * A strip that accepts writes on a simulated clock and reports what it would have shown.
 *
 * Two quirks are modelled, both of which change *feel* rather than correctness:
 *  - **Too-fast writes are dropped, not queued.** A preset that computes 60 changes a second does
 *    not produce 60 visible changes a second; the strip simply misses some. This is why a
 *    fast-flashing preset can look coarser on hardware than in the DSP trace.
 *  - **Sustained throughput is capped**, and the cap is shared across connected strips, so the same
 *    preset degrades further with two strips than with one.
 *
 * Brightness is applied as a multiplier over colour, matching how the strip treats a brightness
 * command as a global scaler rather than as new colour data.
 */
class VirtualStrip(
    private val limits: StripLimits = StripLimits(),
    private val connectedStripCount: Int = 1
) {
    // Nullable rather than a sentinel: `atMs - Long.MIN_VALUE` overflows to a negative number, which
    // read as "too soon" and silently dropped every write ever offered.
    private var lastAcceptedAtMs: Long? = null
    private var powered = true
    private var brightnessPercent = 100
    private var r = 0
    private var g = 0
    private var b = 0

    private var accepted = 0
    private var droppedTooFast = 0
    private var droppedOverCapacity = 0
    private var malformed = 0
    private var ignored = 0

    // Rolling one-second window of accept times, for the sustained-rate cap.
    private val recentAccepts = ArrayDeque<Long>()

    private val effectiveCapacity: Int
        get() = if (connectedStripCount <= 1) {
            limits.sustainedWritesPerSecond
        } else {
            (limits.sustainedWritesPerSecond * limits.multiDeviceThroughputFactor).toInt()
                .coerceAtLeast(1)
        }

    private val visibleChanges = mutableListOf<StripFrame>()

    /** Feeds one write at simulated time [atMs]. Returns whether the strip acted on it. */
    fun write(atMs: Long, bytes: ByteArray): Boolean {
        when (val event = DuoCoDecoder.decode(bytes)) {
            is StripEvent.Malformed -> {
                malformed++
                return false
            }
            is StripEvent.Color, is StripEvent.Brightness, is StripEvent.Power, is StripEvent.Cct -> {
                val since = lastAcceptedAtMs?.let { atMs - it }
                if (since != null && since < limits.minWriteSpacingMs) {
                    droppedTooFast++
                    markDrop(atMs)
                    return false
                }
                while (recentAccepts.isNotEmpty() && atMs - recentAccepts.first() > 1000) {
                    recentAccepts.removeFirst()
                }
                if (recentAccepts.size >= effectiveCapacity) {
                    droppedOverCapacity++
                    markDrop(atMs)
                    return false
                }
                apply(event, atMs)
                lastAcceptedAtMs = atMs
                recentAccepts.addLast(atMs)
                accepted++
                return true
            }
            else -> {
                // Modes, scenes, mic toggles: accepted by the strip, but this model has no firmware
                // animation engine to render them. Counted so a render is never silently empty.
                ignored++
                return true
            }
        }
    }

    private fun markDrop(atMs: Long) {
        visibleChanges.add(
            StripFrame(atMs + limits.visibleLatencyMs, r, g, b, powered, droppedWriteHere = true)
        )
    }

    private fun apply(event: StripEvent, atMs: Long) {
        when (event) {
            is StripEvent.Power -> powered = event.on
            is StripEvent.Brightness -> brightnessPercent = event.percent
            is StripEvent.Color -> {
                r = event.r; g = event.g; b = event.b
            }
            is StripEvent.Cct -> {
                // Warm/cold rendered as an approximate RGB so CCT runs are visible in a render at
                // all. Not a colorimetric claim — the real mapping is a calibration-run question.
                val warm = event.warm / 255.0
                val cold = event.cold / 255.0
                r = ((warm * 1.0 + cold * 0.75) * 255).toInt().coerceIn(0, 255)
                g = ((warm * 0.78 + cold * 0.85) * 255).toInt().coerceIn(0, 255)
                b = ((warm * 0.43 + cold * 1.0) * 255).toInt().coerceIn(0, 255)
            }
            else -> Unit
        }
        visibleChanges.add(StripFrame(atMs + limits.visibleLatencyMs, r, g, b, powered))
    }

    fun stats() = StripStats(accepted, droppedTooFast, droppedOverCapacity, malformed, ignored)

    /**
     * Samples what the strip was showing every [stepMs] from 0 to [untilMs] — the strip holds its
     * last state between writes, which is exactly why dropped writes read as a stutter rather than
     * as darkness.
     */
    fun timeline(untilMs: Long, stepMs: Long = 10): List<StripFrame> {
        val out = mutableListOf<StripFrame>()
        var index = 0
        var current = StripFrame(0, 0, 0, 0, powered = true)
        var droppedSinceLastSample = false
        var t = 0L
        while (t <= untilMs) {
            while (index < visibleChanges.size && visibleChanges[index].atMs <= t) {
                val change = visibleChanges[index]
                if (change.droppedWriteHere) droppedSinceLastSample = true else current = change
                index++
            }
            val scale = if (current.powered) brightnessPercent / 100.0 else 0.0
            out.add(
                StripFrame(
                    atMs = t,
                    r = (current.r * scale).toInt(),
                    g = (current.g * scale).toInt(),
                    b = (current.b * scale).toInt(),
                    powered = current.powered,
                    droppedWriteHere = droppedSinceLastSample
                )
            )
            droppedSinceLastSample = false
            t += stepMs
        }
        return out
    }
}
