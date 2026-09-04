package com.example.sim

import com.example.core.color.StripResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the simulator against the hardware it claims to model, before anything is judged with it.
 *
 * A simulator that has not been held against a measurement is a second opinion from the same person
 * who wrote the code. The numbers on the right-hand side of these assertions all come from
 * `tools/calibration/derived/wire_rate_curve.csv` - the 2026-09-03 pacing sweep on Joe's
 * `Fireworks` strip - and from `device_model.json`.
 *
 * ## The one place it is knowingly optimistic
 *
 * The sim reproduces the measured **median ack gap** at every pacing value, because that gap is
 * exactly `pacing + turnaround` and both are modelled. It runs about **10% fast on mean delivered
 * rate** (16.9Hz against a measured 14.9Hz at pacing 50), because real links take occasional long
 * gaps that a fixed turnaround cannot produce, and one run is not enough to say what shape those
 * follow. So: trust it for *comparisons between two settings*, which is what it is for, and do not
 * quote its absolute throughput as a prediction.
 */
class SimulatedRoomTest {

    /** Offers writes at a steady rate for a while and reports what got through. */
    private fun run(pacingMs: Int, offerHz: Double, seconds: Int = 10): SimulatedDevice {
        val room = SimulatedRoom(listOf(SimulatedDevice("Fireworks", pacingMs)))
        val device = room.devices[0]
        val intervalMs = (1000.0 / offerHz).toLong().coerceAtLeast(1L)
        var t = 0L
        var i = 0
        while (t < seconds * 1000L) {
            room.advanceTo(t)
            // A slowly moving colour, so successive commands differ and nothing is skipped for
            // being identical - the question here is the link, not the content.
            device.offer(t, (i % 200) + 40, 80, 120)
            i++
            t += intervalMs
        }
        room.advanceTo(seconds * 1000L + 500L)
        return device
    }

    @Test
    fun `pacing sets the delivery ceiling, as measured`() {
        // Measured ceilings: pacing 50 -> 14.9Hz, pacing 25 -> 27.5Hz, pacing 0 -> no ceiling
        // below 86Hz. The sim's ceiling is 1000 / (pacing + 9), which is the median-ack-gap model.
        val cases = listOf(50 to 16.9, 25 to 29.4, 11 to 50.0, 0 to 111.0)
        for ((pacing, expectedCeiling) in cases) {
            val device = run(pacing, offerHz = 200.0)
            val delivered = device.link.delivered / 10.0
            assertEquals(
                "pacing ${pacing}ms should ceiling near ${expectedCeiling}Hz",
                expectedCeiling, delivered, expectedCeiling * 0.1
            )
        }
    }

    @Test
    fun `below the ceiling nothing is lost`() {
        // The measured survival column is 1.0 at every requested rate below the ceiling, at every
        // pacing value. That is the finding the app's write path has to keep honouring.
        for ((pacing, safeHz) in listOf(50 to 10.0, 25 to 20.0, 0 to 60.0)) {
            val device = run(pacing, offerHz = safeHz)
            assertEquals(
                "pacing ${pacing}ms at ${safeHz}Hz should lose nothing",
                0, device.link.coalesced
            )
            assertTrue(
                "pacing ${pacing}ms at ${safeHz}Hz should deliver what it was offered",
                device.link.delivered >= device.link.offered - 2
            )
        }
    }

    @Test
    fun `above the ceiling the loss is coalescing, not the strip`() {
        // 32% of first writes reached the strip in the 2026-08-16 measurement, and the cause was
        // DeviceWriteManager dropping a queued command when a newer one arrived - never the strip
        // refusing writes. The sim must lose writes the same way, or it will attribute a dropped
        // colour to the wrong place.
        val device = run(pacingMs = 50, offerHz = 100.0)
        assertTrue("most writes should be coalesced away", device.link.coalesced > device.link.delivered)
        assertEquals(
            "every offered write is either delivered or coalesced",
            device.link.offered, device.link.delivered + device.link.coalesced
        )
    }

    @Test
    fun `a second strip costs the first nothing`() {
        // Measured: each device gets its own full ceiling, and multiDeviceThroughputFactor
        // describes nothing. Two strips at once must deliver the same as one.
        val one = run(pacingMs = 25, offerHz = 200.0).link.delivered
        val room = SimulatedRoom.twoStrips(pacingMs = 25)
        var t = 0L
        var i = 0
        while (t < 10_000L) {
            room.advanceTo(t)
            room.devices.forEach { it.offer(t, (i % 200) + 40, 80, 120) }
            i++
            t += 5L
        }
        room.advanceTo(10_500L)
        room.devices.forEach {
            assertEquals(
                "${it.name} should deliver as much as a lone strip",
                one.toDouble(), it.link.delivered.toDouble(), one * 0.05
            )
        }
    }

    @Test
    fun `the strip steps to a new colour rather than gliding`() {
        // transition_probe: all 18 hard jumps completed inside one 33ms frame interval, so the
        // trace should contain the two endpoints and nothing between them.
        val room = SimulatedRoom(listOf(SimulatedDevice("Fireworks", 0)))
        val device = room.devices[0]
        device.offer(0, 255, 255, 255)
        room.advanceTo(500)
        device.offer(500, 0, 0, 0)
        room.advanceTo(1000)
        assertEquals("one step up, one step down", 2, device.strip.trace.size)
        assertEquals(1.0, device.strip.trace[0].luma, 1e-9)
        assertEquals(0.0, device.strip.trace[1].luma, 1e-9)
    }

    @Test
    fun `light arrives after the measured wire-to-light delay`() {
        val room = SimulatedRoom(listOf(SimulatedDevice("Fireworks", 0)))
        val device = room.devices[0]
        device.offer(0, 255, 255, 255)
        room.advanceTo(39)
        assertEquals("still dark just before the measured 40ms", 0.0, device.strip.light()[0], 1e-9)
        room.advanceTo(40)
        assertEquals("lit at the measured 40ms", 1.0, device.strip.light()[0], 1e-9)
    }

    @Test
    fun `the response table is the measured one`() {
        // Guards against the generated LUT being regenerated from a different model, or hand-edited.
        assertEquals("byte 0 emits nothing", 0.0, StripResponse.lightForByte(0), 1e-9)
        assertEquals("byte 1 emits nothing", 0.0, StripResponse.lightForByte(1), 1e-9)
        assertEquals("byte 255 is full output", 1.0, StripResponse.lightForByte(255), 1e-9)
        assertEquals("half the output by byte 67", 67, (0..255).first { StripResponse.lightForByte(it) >= 0.5 })
        // The number that makes dark scenes hard: one byte is a large fraction of the light there.
        assertTrue(
            "one byte at 14 should be a ~12% relative light step",
            StripResponse.lightStepAtByte(14) / StripResponse.lightForByte(14) > 0.10
        )
    }
}
