package com.example.core.audio

import com.example.feel.OfflineAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pins both halves of [MusicPresence]: it has to call room tone room tone, and it must never once
 * call music room tone.
 *
 * The second half is the one that matters. This gates every flash path in [AudioDspProcessor], so a
 * false "no music" verdict does not degrade the show, it *stops* it — mid-song, on exactly the
 * broadband moments (risers, cymbal washes, distorted choruses) that a listener is most invested in.
 * Hence the real-master frame count below, which is the only test here allowed to be strict.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MusicPresenceTest {

    private val frameMs = OfflineAudio.FRAME_INTERVAL_MS

    /** Runs a whole PCM stream through and returns the fraction of frames judged to be music. */
    private fun musicFraction(pcm: ShortArray, presence: MusicPresence = MusicPresence()): Double {
        var music = 0
        var total = 0
        for ((frame, atMs) in OfflineAudio.frames(pcm)) {
            if (presence.update(frame.magnitude, frame.numBins, atMs)) music++
            total++
        }
        return if (total == 0) 0.0 else music.toDouble() / total
    }

    private fun whiteNoise(seconds: Double, amplitude: Double = 0.01): ShortArray {
        val out = ShortArray((seconds * OfflineAudio.SAMPLE_RATE).toInt())
        var state = 0x5D3F19B
        for (n in out.indices) {
            state = state * 1103515245 + 12345
            val white = ((state ushr 16) and 0x7FFF) / 16384.0 - 1.0
            out[n] = (white * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun tone(seconds: Double, hz: Double = 440.0): ShortArray {
        val out = ShortArray((seconds * OfflineAudio.SAMPLE_RATE).toInt())
        for (n in out.indices) {
            out[n] = (0.3 * sin(2 * PI * hz * n / OfflineAudio.SAMPLE_RATE) * Short.MAX_VALUE)
                .toInt().toShort()
        }
        return out
    }

    @Test
    fun `room tone is eventually judged to be no music`() {
        val fraction = musicFraction(whiteNoise(seconds = 10.0))
        // The first ~1.2s is the sustain window, and it is deliberately not counted against it.
        assertTrue("still called $fraction of pure noise music", fraction < 0.2)
    }

    @Test
    fun `a pure tone is never mistaken for noise`() {
        assertEquals(1.0, musicFraction(tone(seconds = 10.0)), 0.0)
    }

    @Test
    fun `the synthetic track is music in every frame`() {
        assertEquals(1.0, musicFraction(OfflineAudio.syntheticTrack(seconds = 20.0)), 0.0)
    }

    /**
     * The one that guards the show. Joe's own master, every frame: a single false verdict here is a
     * strip that goes dark in the middle of a song.
     */
    @Test
    fun `a real master is music in every frame`() {
        val wav = File("../tools/feel-audio/almost-there.wav")
        assumeTrue("no real-music file present", wav.exists())
        assertEquals(1.0, musicFraction(OfflineAudio.readWav(wav)), 0.0)
    }

    @Test
    fun `a broadband moment inside a song does not stop the show`() {
        // A one-second noise burst is a riser or a cymbal wash — shorter than the sustain window,
        // so the show must ride through it.
        val pcm = OfflineAudio.syntheticTrack(seconds = 10.0) +
            whiteNoise(seconds = 1.0, amplitude = 0.3) +
            OfflineAudio.syntheticTrack(seconds = 10.0)
        assertEquals(1.0, musicFraction(pcm), 0.0)
    }

    @Test
    fun `music returns on the first structured frame`() {
        val presence = MusicPresence()
        val noise = whiteNoise(seconds = 5.0)
        var atMs = 0L
        for ((frame, t) in OfflineAudio.frames(noise)) {
            presence.update(frame.magnitude, frame.numBins, t)
            atMs = t
        }
        assertFalse("should have settled into noise", presence.update(
            OfflineAudio.frames(whiteNoise(seconds = 0.1)).first().first.magnitude,
            OfflineAudio.frames(whiteNoise(seconds = 0.1)).first().first.numBins,
            atMs + frameMs.toLong()
        ))
        val firstMusicFrame = OfflineAudio.frames(tone(seconds = 0.5)).first().first
        assertTrue(
            "one structured frame must be enough to start the show again",
            presence.update(firstMusicFrame.magnitude, firstMusicFrame.numBins, atMs + 200)
        )
    }

    @Test
    fun `reset forgets the room`() {
        val presence = MusicPresence()
        for ((frame, t) in OfflineAudio.frames(whiteNoise(seconds = 5.0))) {
            presence.update(frame.magnitude, frame.numBins, t)
        }
        presence.reset()
        val (frame, _) = OfflineAudio.frames(whiteNoise(seconds = 0.1)).first()
        assertTrue("a fresh session starts trusting again", presence.update(frame.magnitude, frame.numBins, 0L))
    }
}
