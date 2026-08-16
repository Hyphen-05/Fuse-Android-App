package com.example.feel

import kotlin.math.pow

/**
 * The physical limits of a DuoCo strip, as numbers the model can enforce.
 *
 * **Measured on hardware 2026-08-16** (see `tools/calibration/README.md`), replacing the guesses
 * this file shipped with. The headline correction: the earlier model had the *strip* dropping writes
 * that arrived too close together, and the strip does no such thing — it rendered every write that
 * reached it, at every spacing a phone can produce (110 bursts, 6ms to 66ms apart, none lost).
 *
 * What does lose colours is the app's own write queue. `DeviceWriteManager.updateCommand` removes a
 * queued command of the same type when a newer one arrives, so a colour survives only if it got out
 * before the next one was enqueued. In the staircase run only **32%** of first writes ever reached
 * the strip. That is the mechanism modelled here.
 */
data class StripLimits(
    /**
     * How long a write occupies the radio before the next one can go, per connected strip.
     * Measured: 4.6ms for one strip, 8.7ms for two — almost exactly double, so the cost is per
     * strip per write and the strips are written sequentially, not sharing a divided budget.
     */
    val writeInFlightMs: Long = 5,

    /**
     * The same window, but for the first write after the strip has been quiet — much longer, and
     * the reason bursts of colour lose their leading edge.
     *
     * Measured as the median time the first colour of a burst stayed visible before the second
     * replaced it: **68ms**, after 500ms of idle, against a commanded gap of 4-60ms. Anything sent
     * inside that window is coalesced away by the queue and never seen.
     *
     * Not scaled by strip count: the staircase was a one-strip run, so there is no evidence either
     * way, and inventing a factor would be worse than leaving it flat.
     */
    val postIdleInFlightMs: Long = 68,

    /** How long a strip must be quiet before [postIdleInFlightMs] applies rather than [writeInFlightMs]. */
    val idleThresholdMs: Long = 200,

    /**
     * Wire-to-light delay: command accepted → colour visibly changed.
     *
     * **Still a guess, and not for want of trying.** A recording aligned on the strips themselves
     * cannot yield it: video time can only be pinned to log time using something visible, the only
     * visible things are the strips, and they arrive already delayed by exactly the quantity being
     * measured — so the alignment subtracts it out and the answer comes back zero (or negative,
     * which is how the problem announced itself). Measuring it needs the phone's own screen in
     * frame, flashed on the same millisecond as the write.
     *
     * It shifts every frame equally, so no comparison between presets depends on it.
     */
    val visibleLatencyMs: Long = 35,

    /**
     * Light emitted for a commanded byte: **light ≈ (byte/255) ^ 0.4**, measured independently on
     * two devices (k = 0.39 and 0.41).
     *
     * The strips are strongly compressive — byte 32 already delivers 42% of full light, and the
     * whole top half of the range buys the last 16%. Renders apply this so what you look at is
     * emitted light rather than commanded bytes; the two are very different pictures, and judging
     * "is this preset too dim" from bytes is what made the cubic curve in `ColorConverter.hsvToRgb`
     * look like the culprit when it is closer to a correction for this.
     */
    val responseExponent: Double = 0.4,

    /**
     * The known, unavoidable firmware flash when the pixel count changes (see CLAUDE.md). Modelled
     * so a feature that changes pixel count mid-animation shows the flash in its render instead of
     * looking clean.
     */
    val pixelCountChangeFlashMs: Long = 120
)

/** One write as the harness saw it, before the queue decided what to do with it. */
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
    /** Set where a colour was swallowed by the queue, so renders can mark what was never seen. */
    val coalescedHere: Boolean = false
)

/** What the run did to the strip, beyond the pixels. */
data class StripStats(
    val accepted: Int,
    /** Writes replaced in the queue by a newer one of the same type, and so never sent. */
    val coalesced: Int,
    val malformed: Int,
    val ignored: Int
) {
    val attempted: Int get() = accepted + coalesced + malformed + ignored
    /** Fraction of colours the queue swallowed — the "computed 60 frames, showed 20" number. */
    val coalesceRate: Double get() = if (attempted == 0) 0.0 else coalesced.toDouble() / attempted
}

/**
 * A strip that accepts writes on a simulated clock and reports what it would have shown.
 *
 * The one quirk it models is the one hardware measurement found: **a write offered while another is
 * still in flight does not queue behind it — it replaces it.** A preset that computes 60 colours a
 * second does not produce 60 visible colours a second; most of them are overwritten in the queue
 * before the radio is free, and the strip never hears about them. This is why a fast preset can look
 * coarser on hardware than in the DSP trace, and it is a property of the app, not of the strip.
 *
 * Note what is deliberately *not* modelled any more: the strip refusing writes that arrive too fast.
 * Hardware showed no such behaviour anywhere in the reachable range, and simulating it was throwing
 * away a quarter of a fast preset's writes that the real strips render.
 *
 * Brightness is applied as a multiplier over colour, matching how the strip treats a brightness
 * command as a global scaler rather than as new colour data.
 */
class VirtualStrip(
    private val limits: StripLimits = StripLimits(),
    private val connectedStripCount: Int = 1
) {
    private var busyUntilMs: Long? = null
    private var lastIssuedAtMs: Long? = null
    private var powered = true
    private var brightnessPercent = 100
    private var r = 0
    private var g = 0
    private var b = 0

    private var accepted = 0
    private var coalesced = 0
    private var malformed = 0
    private var ignored = 0

    /** At most one pending write per command type, exactly as `updateCommand` keeps it. */
    private val pending = LinkedHashMap<Byte, StripEvent>()

    private val visibleChanges = mutableListOf<StripFrame>()

    /** Feeds one write at simulated time [atMs]. Returns whether it will ever be seen. */
    fun write(atMs: Long, bytes: ByteArray): Boolean {
        when (val event = DuoCoDecoder.decode(bytes)) {
            is StripEvent.Malformed -> {
                malformed++
                return false
            }
            is StripEvent.Color, is StripEvent.Brightness, is StripEvent.Power, is StripEvent.Cct -> {
                drainUntil(atMs)
                val busyUntil = busyUntilMs
                if (busyUntil != null && busyUntil > atMs) {
                    val type = bytes.getOrNull(2) ?: 0
                    // Latest wins: a same-type write still waiting is gone, never sent, never seen.
                    if (pending.put(type, event) != null) {
                        coalesced++
                        markCoalesced(atMs)
                    }
                    return true
                }
                issue(event, atMs)
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

    /**
     * Advances the radio to [untilMs], letting anything queued go out as it frees up.
     *
     * Each drained write starts its own in-flight window, so a backlog clears at the wire's pace
     * rather than all at once — which is what makes a burst of computed colour arrive as a couple of
     * visible steps instead of as a burst.
     */
    private fun drainUntil(untilMs: Long) {
        while (true) {
            val busyUntil = busyUntilMs ?: return
            if (busyUntil > untilMs) return
            val next = pending.entries.firstOrNull()
            if (next == null) {
                busyUntilMs = null
                return
            }
            pending.remove(next.key)
            issue(next.value, busyUntil)
        }
    }

    private fun inFlightMsAt(atMs: Long): Long {
        val since = lastIssuedAtMs?.let { atMs - it }
        return if (since == null || since >= limits.idleThresholdMs) {
            limits.postIdleInFlightMs
        } else {
            limits.writeInFlightMs * connectedStripCount
        }
    }

    private fun issue(event: StripEvent, atMs: Long) {
        busyUntilMs = atMs + inFlightMsAt(atMs)
        lastIssuedAtMs = atMs
        accepted++
        apply(event, atMs)
    }

    private fun markCoalesced(atMs: Long) {
        visibleChanges.add(
            StripFrame(atMs + limits.visibleLatencyMs, r, g, b, powered, coalescedHere = true)
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

    fun stats() = StripStats(accepted, coalesced, malformed, ignored)

    /** Commanded byte → light actually emitted, on the measured response curve. */
    private fun emitted(value: Double): Int =
        ((value / 255.0).coerceIn(0.0, 1.0).pow(limits.responseExponent) * 255).toInt()

    /**
     * Samples what the strip was showing every [stepMs] from 0 to [untilMs] — the strip holds its
     * last state between writes, which is exactly why coalesced colours read as a coarser animation
     * rather than as darkness.
     *
     * Values come back as **emitted light**, not commanded bytes, unless [inEmittedLight] is false.
     * The two differ enormously at the bottom of the range (byte 32 emits 42% of full light), and
     * every judgement about whether a preset looks dim or washed out has to be made on the former.
     */
    fun timeline(untilMs: Long, stepMs: Long = 10, inEmittedLight: Boolean = true): List<StripFrame> {
        // Let anything still queued go out, or a run's last colours would vanish from the render.
        drainUntil(untilMs)

        val out = mutableListOf<StripFrame>()
        var index = 0
        var current = StripFrame(0, 0, 0, 0, powered = true)
        var coalescedSinceLastSample = false
        var t = 0L
        while (t <= untilMs) {
            while (index < visibleChanges.size && visibleChanges[index].atMs <= t) {
                val change = visibleChanges[index]
                if (change.coalescedHere) coalescedSinceLastSample = true else current = change
                index++
            }
            val scale = if (current.powered) brightnessPercent / 100.0 else 0.0
            fun channel(value: Int): Int {
                val scaled = value * scale
                return if (inEmittedLight) emitted(scaled) else scaled.toInt()
            }
            out.add(
                StripFrame(
                    atMs = t,
                    r = channel(current.r),
                    g = channel(current.g),
                    b = channel(current.b),
                    powered = current.powered,
                    coalescedHere = coalescedSinceLastSample
                )
            )
            coalescedSinceLastSample = false
            t += stepMs
        }
        return out
    }
}
