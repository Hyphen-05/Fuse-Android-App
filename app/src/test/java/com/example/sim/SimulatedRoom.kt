package com.example.sim

import com.example.core.color.StripResponse
import kotlin.math.abs

/**
 * The device chain, modelled from what was actually measured, so a change can be judged without a
 * strip, a dark room and an evening.
 *
 * ## What this models, and where each number came from
 *
 * Everything here is out of `tools/calibration/derived/device_model.json`. Nothing is invented:
 *
 *  - **Byte to light** - [StripResponse], the measured LUT. Front-loaded: half the output by byte
 *    67, one byte is a 12% light change at byte 14.
 *  - **The write path** - [SimulatedLink] reproduces `DeviceWriteManager`: latest-wins coalescing
 *    per command type, a pacing wait, and one write in flight at a time with ~9ms of turnaround.
 *    That is what produces the measured `delivered = min(offered, 1000 / (pacing + 9))`.
 *  - **Transitions** - instant. All 18 hard jumps in `transition_probe` completed inside one frame
 *    interval, so there is no slew rate to model.
 *  - **Channels** - independent, and they share one curve. Driving one leaves the others' share
 *    constant to within 4%, and each primary swept on its own tracks the other two to within ~2%
 *    and white to within the measurement's own slop. Only white got a full 256-step ramp, so all
 *    three channels are looked up in the same LUT - justified by that check, not assumed.
 *  - **Two devices** - independent links. Each device gets its own full ceiling; a second strip
 *    costs the first nothing.
 *
 * ## What it does NOT model, and must not be asked
 *
 * **It is not a picture of the room.** There is no photometry behind the LUT (it is relative camera
 * light) and no spatial model - and none is possible, because `DuoCoProtocol` has no per-LED
 * addressing, so the whole strand shows one colour at a time. This cannot tell anyone what a scene
 * looks like on the wall. It answers *what light comes out, and when* - which is enough for
 * smoothness, stepping, flicker and throughput, and is not enough for comfort or taste. Those stay
 * Joe's eyes.
 *
 * It also does not model the strip's own limit (89-108Hz) as a loss, because nothing in the app
 * offers writes that fast once pacing is sane, and the measurement only bounded it rather than
 * locating it. Simulating a number that vague would be inventing one.
 */
class SimulatedRoom(
    val devices: List<SimulatedDevice>,
    private val startMs: Long = 0L
) {
    var nowMs: Long = startMs
        private set

    /** Advances the clock, letting every device's link and strip run to [nowMs]. */
    fun advanceTo(t: Long) {
        require(t >= nowMs) { "the clock does not go backwards" }
        // Anything already due at the current instant runs first. Without this, a write offered at
        // exactly `nowMs` is not seen until the next event, and every latency measured through the
        // sim is one step late.
        devices.forEach { it.runTo(nowMs) }
        while (nowMs < t) {
            // Step to the next thing that happens, or to t, whichever is sooner. Stepping in fixed
            // ticks instead would quantise the write path and hide exactly the timing this exists
            // to measure.
            val next = devices.mapNotNull { it.nextEventMs() }.filter { it > nowMs }.minOrNull()
            val step = if (next != null && next <= t) next else t
            nowMs = step
            devices.forEach { it.runTo(nowMs) }
        }
    }

    fun advanceBy(dt: Long) = advanceTo(nowMs + dt)

    companion object {
        /** Joe's pair: `Fireworks` in frame and `Ambiance Bars`, as they are actually saved. */
        fun twoStrips(pacingMs: Int): SimulatedRoom = SimulatedRoom(
            listOf(
                SimulatedDevice("Fireworks", pacingMs),
                SimulatedDevice("Ambiance Bars", pacingMs)
            )
        )
    }
}

/** One strip and the link that feeds it. */
class SimulatedDevice(val name: String, pacingMs: Int) {
    val link = SimulatedLink(pacingMs)
    val strip = SimulatedStrip()

    fun offer(t: Long, r: Int, g: Int, b: Int, priority: Float = Float.MAX_VALUE) =
        link.offer(t, Command(COLOUR_TYPE, intArrayOf(r, g, b), priority))

    fun nextEventMs(): Long? = listOfNotNull(link.nextEventMs(), strip.nextEventMs()).minOrNull()

    fun runTo(t: Long) {
        link.runTo(t) { delivered -> strip.command(t, delivered.channels) }
        strip.runTo(t)
    }

    companion object {
        /** The type byte a colour command carries; coalescing is per type, as on the real link. */
        const val COLOUR_TYPE = 0x05
    }
}

data class Command(val type: Int, val channels: IntArray, val priority: Float) {
    override fun equals(other: Any?) =
        other is Command && type == other.type && channels.contentEquals(other.channels)

    override fun hashCode() = 31 * type + channels.contentHashCode()
}

/**
 * `DeviceWriteManager`, with the parts that decide *which* writes survive and *when*.
 *
 * Faithful to the shipped rules: one write in flight, latest-wins per type while queued (unless a
 * held higher priority has not gone out yet), and a pacing wait measured from the last write. The
 * turnaround is the measured 9ms between a write leaving and the next being allowed.
 */
class SimulatedLink(var pacingMs: Int, private val turnaroundMs: Long = TURNAROUND_MS) {

    private val queue = ArrayDeque<Command>()
    private var inFlightUntil: Long? = null
    // Set when a write *completes*, not when it is sent - `DeviceWriteManager.onWriteCompleted`
    // assigns `lastWriteTime` as well as `tryWrite` does, so the pacing wait is measured from the
    // completion. That is why the ceiling is 1000/(pacing + turnaround) and not 1000/pacing, and
    // why the measured median ack gap at pacing 50 is 59ms rather than 50.
    private var lastCompletedMs: Long = Long.MIN_VALUE / 4

    var offered = 0
        private set
    var delivered = 0
        private set
    /** Writes discarded because a newer one of the same type arrived before they went out. */
    var coalesced = 0
        private set

    fun offer(t: Long, command: Command) {
        offered++
        val heldHigher = queue.any { it.type == command.type && it.priority > command.priority }
        if (heldHigher) return
        val removed = queue.count { it.type == command.type }
        queue.removeAll { it.type == command.type }
        coalesced += removed
        queue.addLast(command)
    }

    fun nextEventMs(): Long? {
        inFlightUntil?.let { return it }
        if (queue.isEmpty()) return null
        return maxOf(lastCompletedMs + pacingMs, 0L)
    }

    fun runTo(t: Long, onDelivered: (Command) -> Unit) {
        while (true) {
            val flight = inFlightUntil
            if (flight != null) {
                if (flight > t) return
                inFlightUntil = null
            }
            val next = queue.firstOrNull() ?: return
            val ready = if (pacingMs > 0) lastCompletedMs + pacingMs else Long.MIN_VALUE / 4
            if (ready > t) return
            queue.removeFirst()
            inFlightUntil = t + turnaroundMs
            lastCompletedMs = t + turnaroundMs
            delivered++
            onDelivered(next)
        }
    }

    companion object {
        /**
         * Write turnaround: the gap between one write going out and the next being allowed, over
         * and above pacing. Measured as ~9ms - `pacing 0` and `bypassPacing` both ceilinged at
         * 86-89Hz with a 10ms median ack gap.
         */
        const val TURNAROUND_MS = 9L
    }
}

/**
 * The strip: it steps to whatever it was last told, after the measured wire-to-light delay.
 *
 * Latency is the measured rise/fall median of ~40ms/36ms. It is modelled as a fixed delay rather
 * than a distribution: the measurement saw excursions to ~170ms, but a single run cannot say what
 * shape they follow, and inventing one would put made-up jitter into every result.
 */
class SimulatedStrip(private val latencyMs: Long = LATENCY_MS) {

    private val pending = ArrayDeque<Pair<Long, IntArray>>()
    private var committed = intArrayOf(0, 0, 0)

    /** Every light level this strip actually emitted, and when. One entry per visible change. */
    val trace = mutableListOf<LightSample>()

    fun command(t: Long, channels: IntArray) {
        pending.addLast((t + latencyMs) to channels.copyOf())
    }

    fun nextEventMs(): Long? = pending.firstOrNull()?.first

    fun runTo(t: Long) {
        while (pending.isNotEmpty() && pending.first().first <= t) {
            val (at, ch) = pending.removeFirst()
            if (!ch.contentEquals(committed)) {
                committed = ch
                trace.add(LightSample(at, ch.copyOf(), lightOf(ch)))
            }
        }
    }

    /** What the strip is emitting right now, per channel, 0.0-1.0 of full output. */
    fun light(): DoubleArray = lightOf(committed)

    private fun lightOf(ch: IntArray) =
        doubleArrayOf(
            StripResponse.lightForByte(ch[0]),
            StripResponse.lightForByte(ch[1]),
            StripResponse.lightForByte(ch[2])
        )

    companion object {
        /** Measured rise median, `latency_camera` on the driving phone's own clock. */
        const val LATENCY_MS = 40L
    }
}

data class LightSample(val atMs: Long, val bytes: IntArray, val light: DoubleArray) {
    /**
     * Overall emitted light, Rec.709 weighted over the three channels.
     *
     * The weights are for human perception of a colour, and the strip's channels are not
     * photometrically calibrated against each other - so this is a stand-in for "how bright does
     * this look", good for comparing two runs of the same content and not for anything absolute.
     */
    val luma: Double get() = 0.2126 * light[0] + 0.7152 * light[1] + 0.0722 * light[2]

    override fun equals(other: Any?) = other is LightSample && atMs == other.atMs &&
        bytes.contentEquals(other.bytes)

    override fun hashCode() = 31 * atMs.hashCode() + bytes.contentHashCode()
}

/**
 * What a light trace looks like as a viewer, reduced to the few numbers that separate "smooth" from
 * "steppy" - which is the whole question ambiance keeps raising.
 */
object TraceMetrics {

    /**
     * A jump big enough to read as a step rather than a fade.
     *
     * 2% of full output. Chosen to sit just above what the deadband work found invisible at the
     * bright end (0.9% admitted changes were chasing noise nobody could see) and well below the
     * 7.6% lurch it found at the dark end. It is a threshold for *counting* steps, not a claim
     * about a perceptual limit, which nothing here measured.
     */
    const val VISIBLE_JUMP = 0.02

    fun jumps(trace: List<LightSample>): List<Double> =
        trace.zipWithNext { a, b -> abs(b.luma - a.luma) }

    fun visibleJumps(trace: List<LightSample>): Int = jumps(trace).count { it >= VISIBLE_JUMP }

    fun largestJump(trace: List<LightSample>): Double = jumps(trace).maxOrNull() ?: 0.0

    /** Changes per second: how often the light moved at all. */
    fun changeRateHz(trace: List<LightSample>, spanMs: Long): Double =
        if (spanMs <= 0) 0.0 else trace.size * 1000.0 / spanMs

    fun summary(trace: List<LightSample>, spanMs: Long): String {
        val j = jumps(trace)
        val mean = if (j.isEmpty()) 0.0 else j.average()
        return "%5.1f changes/s  %3d visible steps  largest %5.1f%%  mean %4.2f%%".format(
            changeRateHz(trace, spanMs), visibleJumps(trace), largestJump(trace) * 100, mean * 100
        )
    }
}
