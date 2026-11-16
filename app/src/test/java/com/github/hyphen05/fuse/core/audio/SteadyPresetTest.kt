package com.github.hyphen05.fuse.core.audio

import com.github.hyphen05.fuse.AudioSettingsState
import com.github.hyphen05.fuse.RgbIntent
import com.github.hyphen05.fuse.RgbUiState
import com.github.hyphen05.fuse.core.color.StripResponse
import com.github.hyphen05.fuse.hardware.audio.AudioBackend
import com.github.hyphen05.fuse.hardware.audio.AudioCaptureFrame
import com.github.hyphen05.fuse.presentation.audioSettingsReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.max

/**
 * Plays the same synthetic song through each preset and measures the light the strip would emit.
 *
 * The number that matters is how fast the emitted light changes, because that is what reads as a
 * flash. Light is taken from the measured strip table rather than from the colour bytes, so a
 * colour washing out to white or crossing from one channel to two is counted as the brightness
 * change it really is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SteadyPresetTest {

    private val frameMs = 23L

    private fun settingsFor(preset: String): AudioSettingsState =
        audioSettingsReducer(RgbUiState(), RgbIntent.SetVisualizerPreset(preset), emptyList(), emptyMap())
            .first.audioSettings

    /** A frame with separate levels for the low, middle and high thirds of the spectrum. */
    private fun frame(bass: Float, mid: Float, high: Float): AudioCaptureFrame {
        val numBins = 512
        val magnitude = FloatArray(numBins) {
            when {
                it == 0 -> 0f
                it < 12 -> bass
                it < 110 -> mid
                else -> high
            }
        }
        return AudioCaptureFrame(
            magnitude = magnitude,
            realBins = magnitude.copyOf(),
            imagBins = FloatArray(numBins),
            numBins = numBins,
            backend = AudioBackend.AUDIO_RECORD,
            timestampMs = 0L
        )
    }

    /**
     * 70 seconds: a quiet lead-in, a long stretch with a kick every half second whose tonal
     * balance swaps every four seconds, a two-second section far louder than anything before it
     * (the case that used to white the strip out), more of the song, then silence.
     */
    private fun song(tMs: Long): AudioCaptureFrame {
        val kick = tMs % 500L < frameMs
        val midHeavy = (tMs / 4000L) % 2L == 0L
        return when {
            tMs < 2_000L -> frame(1f, 1f, 1f)
            tMs in 50_000L until 52_000L -> frame(600f, 600f, 600f)
            tMs >= 60_000L -> frame(0f, 0f, 0f)
            kick -> frame(90f, 40f, 20f)
            midHeavy -> frame(6f, 9f, 2f)
            else -> frame(6f, 2f, 9f)
        }
    }

    private class Trace(
        val light: List<Double>,
        val hue: List<Float>,
        val value: List<Float>,
        val sat: List<Float>
    )

    private fun play(preset: String): Trace {
        val settings = settingsFor(preset)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val light = ArrayList<Double>()
        val hue = ArrayList<Float>()
        val value = ArrayList<Float>()
        val sat = ArrayList<Float>()
        var t = 0L
        while (t < 70_000L) {
            val result = processor.process(song(t), settings, nowMs = 1_000L + t)
            if (result != null) {
                light += StripResponse.lightForByte(result.r) +
                    StripResponse.lightForByte(result.g) +
                    StripResponse.lightForByte(result.b)
                hue += result.hue
                value += result.value
                sat += result.sat
            }
            t += frameMs
        }
        return Trace(light, hue, value, sat)
    }

    /**
     * The largest factor by which emitted light changes across roughly a tenth of a second. The
     * small constant keeps a move between two near-black levels from counting as a huge ratio.
     */
    private fun worstLightJump(trace: Trace): Double {
        val span = 4
        var worst = 1.0
        for (i in span until trace.light.size) {
            val a = trace.light[i - span] + 0.02
            val b = trace.light[i] + 0.02
            worst = max(worst, max(a / b, b / a))
        }
        return worst
    }

    @Test
    fun `steady holds brightness and saturation still for the whole song`() {
        val trace = play("Steady")
        assertTrue(trace.value.isNotEmpty())
        trace.value.forEach { assertEquals(1.0f, it, 0f) }
        trace.sat.forEach { assertEquals(1.0f, it, 0f) }
    }

    @Test
    fun `steady never moves the hue faster than its cap`() {
        val trace = play("Steady")
        val capPerFrame = AudioDspProcessor.STEADY_MAX_HUE_DEG_PER_SEC * frameMs / 1000f
        var moved = 0f
        for (i in 1 until trace.hue.size) {
            val step = abs(((trace.hue[i] - trace.hue[i - 1] + 540f) % 360f) - 180f)
            assertTrue("hue stepped $step degrees in one frame", step <= capPerFrame + 0.01f)
            moved += step
        }
        // And it does move: a preset that sat on one colour would pass every other check here.
        assertTrue("hue only travelled $moved degrees in 70 seconds", moved > 360f)
    }

    @Test
    fun `steady light never jumps, and every beat-flash preset does`() {
        val steady = worstLightJump(play("Steady"))
        val others = listOf(
            "Default", "Punchy", "Bass Thump", "Strobe Blast", "Laser Sharp", "Beat Only",
            "Smooth Flow", "Ambient Chill", "Ebb & Flow"
        ).associateWith { worstLightJump(play(it)) }

        println("worst light change over ~0.1s: Steady=%.2fx".format(steady))
        others.forEach { (name, jump) -> println("worst light change over ~0.1s: %s=%.2fx".format(name, jump)) }

        assertTrue("steady light changed by ${steady}x within a tenth of a second", steady < 1.35)
        listOf("Default", "Punchy", "Strobe Blast").forEach { name ->
            assertTrue("$name was expected to flash but only reached ${others.getValue(name)}x", others.getValue(name) > 2.0)
        }
    }
}
