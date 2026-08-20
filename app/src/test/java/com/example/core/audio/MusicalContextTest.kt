package com.example.core.audio

import com.example.feel.OfflineAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The section classifier and loudness reference, against material whose structure is known exactly
 * because it was generated — [OfflineAudio.structuredTrack] is intro/build/chorus/breakdown/chorus
 * at known second boundaries, which is ground truth no downloaded song can offer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MusicalContextTest {

    /** Feeds the real spectrum frames in, so the numbers are the ones the DSP would see. */
    private fun run(pcm: ShortArray): List<Pair<Long, MusicalDynamics>> {
        val context = MusicalContext()
        val out = ArrayList<Pair<Long, MusicalDynamics>>()
        var last = 0L
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            var energy = 0f
            for (i in 0 until frame.numBins) energy += frame.magnitude[i]
            out.add(atMs to context.update(energy, if (last == 0L) 0L else atMs - last, atMs))
            last = atMs
        }
        return out
    }

    private fun dominantSection(
        run: List<Pair<Long, MusicalDynamics>>,
        section: OfflineAudio.Section
    ): MusicSection {
        val from = (section.fromSec * 1000).toLong()
        val to = (section.toSec * 1000).toLong()
        return run.filter { it.first in from until to }
            .groupingBy { it.second.section }.eachCount()
            .maxByOrNull { it.value }!!.key
    }

    private fun meanPercentile(
        run: List<Pair<Long, MusicalDynamics>>,
        section: OfflineAudio.Section
    ): Float {
        val from = (section.fromSec * 1000).toLong()
        val to = (section.toSec * 1000).toLong()
        return run.filter { it.first in from until to }
            .map { it.second.loudnessPercentile }.average().toFloat()
    }

    @Test
    fun `section timeline, printed`() {
        val run = run(OfflineAudio.structuredTrack())
        var nextSampleMs = 0L
        for ((atMs, dyn) in run) {
            if (atMs >= nextSampleMs) {
                println("%5.1fs %-10s percentile=%.2f slope=%+.2f intensity=%.2f".format(
                    atMs / 1000.0, dyn.section, dyn.loudnessPercentile, dyn.slope, dyn.intensity))
                nextSampleMs = atMs + 2000
            }
        }
    }

    @Test
    fun `it reads the structure it is given`() {
        val run = run(OfflineAudio.structuredTrack())
        for (section in OfflineAudio.Section.entries) {
            println(
                "%-10s dominant=%-10s meanPercentile=%.2f meanIntensity=%.2f".format(
                    section, dominantSection(run, section), meanPercentile(run, section),
                    run.filter { it.first in (section.fromSec * 1000).toLong() until (section.toSec * 1000).toLong() }
                        .map { it.second.intensity }.average()
                )
            )
        }

        assertEquals(MusicSection.BREAKDOWN, dominantSection(run, OfflineAudio.Section.BREAKDOWN))
        assertEquals(MusicSection.BUILD, dominantSection(run, OfflineAudio.Section.BUILD))
        assertEquals(MusicSection.STEADY, dominantSection(run, OfflineAudio.Section.INTRO))
        assertTrue(
            "a chorus must not read as quiet",
            meanPercentile(run, OfflineAudio.Section.CHORUS) > meanPercentile(run, OfflineAudio.Section.INTRO)
        )
        // A drop is allowed to land anywhere in the loud half — pinning the second would be pinning
        // this synthetic track's exact shape, not testing the idea. It must not land in the quiet.
        val dropTimes = run.filter { it.second.section == MusicSection.DROP }.map { it.first / 1000.0 }
        assertTrue("a drop should occur somewhere", dropTimes.isNotEmpty())
        // The 2s grace is the detector's own latency, not slack: the passage level is smoothed over
        // 2s, so a drop already holding when the music collapses cannot know for about that long.
        // Asserting tighter would be asserting the smoothing constants, which are free to change.
        val breakdownSettled = OfflineAudio.Section.BREAKDOWN.fromSec + 2.0
        assertTrue(
            "drops must not fire during the intro, or once a breakdown has settled, got $dropTimes",
            dropTimes.none { it < OfflineAudio.Section.BUILD.fromSec } &&
                dropTimes.none { it in breakdownSettled..OfflineAudio.Section.BREAKDOWN.toSec }
        )
    }

    @Test
    fun `the same music quieter reads the same`() {
        // The D.2 claim in one assertion: level is judged relative to the material, so a track
        // mastered 12dB down must produce the same percentiles, not a dimmer show.
        val loud = run(OfflineAudio.structuredTrack())
        val quiet = run(OfflineAudio.atGain(OfflineAudio.structuredTrack(), 0.25))
        for (section in OfflineAudio.Section.entries) {
            assertEquals(
                "$section",
                meanPercentile(loud, section).toDouble(),
                meanPercentile(quiet, section).toDouble(),
                0.15
            )
        }
    }

    @Test
    fun `silence never teaches it that silence is normal`() {
        val context = MusicalContext()
        var now = 0L
        repeat(2000) { context.update(400f, 23L, now); now += 23 }
        val duringMusic = context.update(400f, 23L, now)
        repeat(4000) { context.update(0f, 23L, now); now += 23 }
        val afterSilence = context.update(0f, 23L, now)

        // The learned floor must not sink toward silence, or an adaptive gate stops detecting idle.
        assertTrue(afterSilence.musicFloor >= 2f)
        assertTrue(
            "silence must still sit below the gate",
            afterSilence.effectiveNoiseGate(8f) > 0f && 0f < afterSilence.musicFloor
        )
        assertTrue(duringMusic.loudnessPercentile > 0f)
    }

    @Test
    fun `a fresh context does not swing wildly before it has learned anything`() {
        val context = MusicalContext()
        val first = context.update(300f, 0L, 0L)
        assertTrue(first.intensity in 0.3f..1.2f)
        assertEquals(MusicSection.STEADY, first.section)
    }
}
