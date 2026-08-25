package com.example.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The properties that make [ContinuousDrive] a replacement for beat-triggered flashing rather than
 * just a smoother version of it.
 *
 * Each test pins a claim the design rests on, so that tuning the constants later cannot quietly
 * break the shape. Frames are 16ms, roughly the real capture rate.
 */
class ContinuousDriveTest {

    private val dt = 16L

    /** Runs [frames] frames at a constant band level and returns the last Drive. */
    private fun steady(
        drive: ContinuousDrive,
        bass: Float,
        mid: Float = 0f,
        high: Float = 0f,
        frames: Int
    ): ContinuousDrive.Drive {
        var last = ContinuousDrive.Drive(0f, 0f, 0f)
        repeat(frames) { last = drive.process(bass, mid, high, dt) }
        return last
    }

    @Test
    fun `auto-gain makes a quiet song and a loud song reach the same level`() {
        val quiet = ContinuousDrive()
        val loud = ContinuousDrive()

        // Two orders of magnitude apart, held long enough for the running average to settle.
        val q = steady(quiet, bass = 0.5f, frames = 600)
        val l = steady(loud, bass = 50f, frames = 600)

        // This is the property that stops the strip being dim on quiet tracks and pinned on loud
        // ones, and it is why no per-track sensitivity knob is needed.
        assertEquals(q.level, l.level, 0.02f)
    }

    @Test
    fun `a sharp rise produces punch, a sustained level does not`() {
        val drive = ContinuousDrive()
        steady(drive, bass = 1f, frames = 400)

        // Sustained: the two envelopes have converged, so there is nothing to report.
        val sustained = drive.process(1f, 0f, 0f, dt)
        assertTrue("sustained punch should be ~0, was ${sustained.punch}", sustained.punch < 0.02f)

        // A kick: four times the level, arriving in one frame.
        var kick = drive.process(4f, 0f, 0f, dt)
        repeat(3) { kick = drive.process(4f, 0f, 0f, dt) }
        assertTrue("a kick should produce punch, was ${kick.punch}", kick.punch > 0.1f)
    }

    @Test
    fun `punch decays back to zero after a transient`() {
        val drive = ContinuousDrive()
        steady(drive, bass = 1f, frames = 400)
        repeat(4) { drive.process(4f, 0f, 0f, dt) }

        // Back to the quiet level; within about a second the gap should have closed.
        val after = steady(drive, bass = 1f, frames = 60)
        assertTrue("punch should decay, was ${after.punch}", after.punch < 0.05f)
    }

    @Test
    fun `silence produces no light`() {
        val drive = ContinuousDrive()
        steady(drive, bass = 1f, frames = 400)

        val silent = steady(drive, bass = 0f, frames = 300)
        assertEquals(0f, silent.level, 0.001f)
        assertEquals(0f, silent.punch, 0.001f)
    }

    @Test
    fun `tilt is relative to the song, not absolute spectral balance`() {
        // Per-band auto-gain normalises every band to the same average, so a *steady* mix has no
        // tilt however bass-heavy it is in absolute terms. That is deliberate: absolute balance is
        // near-constant for a given track, so hue driven by it would sit still. What moves the
        // colour is the balance departing from what this song has been doing — a hi-hat section, a
        // bass drop. This pins that, because it is easy to "fix" back into absolute balance and
        // lose the behaviour.
        val drive = ContinuousDrive()
        var settled = ContinuousDrive.Drive(0f, 0f, 0f)
        repeat(600) { settled = drive.process(4f, 1f, 0.5f, dt) }
        assertEquals("a steady mix should sit near zero tilt", 0f, settled.tilt, 0.15f)

        // Now the treble lifts well above what this song has established.
        var brighter = settled
        repeat(120) { brighter = drive.process(4f, 1f, 4f, dt) }
        assertTrue("more treble than usual should tilt positive, was ${brighter.tilt}", brighter.tilt > 0.1f)

        // And a bass drop, from the same baseline, goes the other way.
        val other = ContinuousDrive()
        var settled2 = ContinuousDrive.Drive(0f, 0f, 0f)
        repeat(600) { settled2 = other.process(4f, 1f, 0.5f, dt) }
        var heavier = settled2
        repeat(120) { heavier = other.process(16f, 1f, 0.5f, dt) }
        assertTrue("a bass surge should tilt negative, was ${heavier.tilt}", heavier.tilt < -0.1f)
    }

    @Test
    fun `output is frame-rate independent`() {
        // The same music at two capture rates must land in the same place, or tuning done at one
        // rate would be wrong at the other — the app's rate varies with what else is running.
        val at16 = ContinuousDrive()
        val at8 = ContinuousDrive()

        repeat(600) { at16.process(1f, 0f, 0f, 16L) }
        repeat(1200) { at8.process(1f, 0f, 0f, 8L) }

        val a = at16.process(1f, 0f, 0f, 16L)
        val b = at8.process(1f, 0f, 0f, 8L)
        assertEquals(a.level, b.level, 0.02f)
    }

    @Test
    fun `reset clears the auto-gain so a new song is not judged by the last one`() {
        val drive = ContinuousDrive()
        steady(drive, bass = 50f, frames = 600)
        drive.reset()

        val fresh = steady(drive, bass = 0.5f, frames = 600)
        val virgin = steady(ContinuousDrive(), bass = 0.5f, frames = 600)
        assertEquals(virgin.level, fresh.level, 0.02f)
    }
}
