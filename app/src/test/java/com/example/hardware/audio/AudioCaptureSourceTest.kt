package com.example.hardware.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Construction/contract-level coverage for the real capture sources, plus a wiring test using
 * [FakeAudioCaptureSource].
 *
 * Deliberately NOT covered here (noted per the Phase 4 task's own guidance that deep on-device
 * behavior verification is out of scope for unit tests): actually starting AudioRecord/Visualizer
 * capture and receiving real hardware callbacks. Robolectric's AudioRecord/Visualizer shadows
 * don't model real microphone input, so a "does it really capture audio" test would be
 * exercising the shadow, not the hardware contract — that's a manual on-device smoke test,
 * consistent with CLAUDE.md's existing note that the Phase 3b reducer wiring also still needs a
 * manual on-device pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioCaptureSourceTest {

    @Test
    fun `AudioRecordCaptureSource reports the AUDIO_RECORD backend`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = AudioRecordCaptureSource(context)
        assertEquals(AudioBackend.AUDIO_RECORD, source.backend)
    }

    @Test
    fun `VisualizerCaptureSource reports the VISUALIZER backend`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = VisualizerCaptureSource(context)
        assertEquals(AudioBackend.VISUALIZER, source.backend)
    }

    @Test
    fun `stop before start is a no-op and does not throw`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AudioRecordCaptureSource(context).stop()
        VisualizerCaptureSource(context).stop()
    }

    @Test
    fun `VisualizerCaptureSource watchdog timeout is a small, bounded positive window`() {
        // Regression guard for the 2026-07-21 "on-device backend doesn't activate on first
        // click" fix: the silent-attach watchdog (see VisualizerCaptureSource's start/attemptStart
        // doc comment) must fire within a short, human-perceptible window — not so short it
        // reconstructs a Visualizer that just hasn't received its first frame yet, not so long
        // the toggle-and-wait workaround it replaces still feels faster than just waiting.
        assertTrue(VisualizerCaptureSource.WATCHDOG_TIMEOUT_MS in 500L..5000L)
    }

    @Test
    fun `fake source records start and forwards emitted frames`() {
        val fake = FakeAudioCaptureSource(backend = AudioBackend.AUDIO_RECORD)
        val received = mutableListOf<AudioCaptureFrame>()

        val started = fake.start(
            onFrame = { received.add(it) },
            onLog = {},
            isRunning = { true },
            onError = { throw it }
        )

        assertTrue(started)
        assertTrue(fake.isStarted)

        val frame = AudioCaptureFrame(
            magnitude = FloatArray(512),
            realBins = FloatArray(512),
            imagBins = FloatArray(512),
            numBins = 512,
            backend = AudioBackend.AUDIO_RECORD,
            timestampMs = 42L
        )
        fake.emitFrame(frame)

        assertEquals(1, received.size)
        assertEquals(42L, received[0].timestampMs)

        fake.stop()
        assertEquals(1, fake.stopCallCount)
    }

    @Test
    fun `fake source forwards errors and start-failure signals`() {
        val fake = FakeAudioCaptureSource(startResult = false)
        var caught: Throwable? = null

        val started = fake.start(
            onFrame = {},
            onLog = {},
            isRunning = { true },
            onError = { caught = it }
        )

        assertEquals(false, started)

        val boom = IllegalStateException("Record Audio Permission missing or denied. Running simulation.")
        fake.emitError(boom)
        assertEquals(boom, caught)
    }
}
