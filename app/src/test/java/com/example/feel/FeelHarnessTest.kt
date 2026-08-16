package com.example.feel

import com.example.RgbIntent
import com.example.RgbUiState
import com.example.core.audio.AudioDspProcessor
import com.example.core.protocol.DuoCoProtocol
import com.example.hardware.audio.AudioBackend
import com.example.presentation.audioSettingsReducer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runs presets end to end — synthetic track → real DSP → real protocol bytes → [VirtualStrip] —
 * and writes a picture of what each one would look like on a strip.
 *
 * Not an assertion-heavy test: its output is the PNGs under `app/build/reports/feel`, meant to be
 * looked at.
 * The few assertions here only guard the harness itself, so a broken pipeline fails loudly instead
 * of quietly producing a black image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FeelHarnessTest {

    private val outputDir = File("build/reports/feel")

    /** Drives the shipped reducer so presets are exactly what the app applies, not a copy. */
    private fun settingsFor(preset: String) =
        audioSettingsReducer(
            RgbUiState(),
            RgbIntent.SetVisualizerPreset(preset),
            emptyList(),
            emptyMap()
        ).first.audioSettings

    private fun runPreset(
        preset: String,
        pcm: ShortArray,
        connectedStripCount: Int = 1,
        pacingMs: Int = 50
    ): Pair<List<StripFrame>, StripStats> {
        val settings = settingsFor(preset)
        val processor = AudioDspProcessor(AudioBackend.AUDIO_RECORD)
        val strip = VirtualStrip(connectedStripCount = connectedStripCount)

        var lastWriteAt: Long? = null
        var durationMs = 0L
        OfflineAudio.frames(pcm).forEach { (frame, nowMs) ->
            durationMs = nowMs
            val result = processor.process(frame, settings, nowMs, effectivePacingMs = pacingMs) ?: return@forEach
            // Mirrors the app's own pacing gate: the write manager holds a frame back unless the
            // pacing interval has elapsed, or the frame carries a beat flash that bypasses it.
            val pacingElapsed = lastWriteAt?.let { nowMs - it >= pacingMs } ?: true
            if (!pacingElapsed && !result.flashFiredThisFrame) return@forEach
            lastWriteAt = nowMs
            strip.write(nowMs, DuoCoProtocol.createMusicColorCommand(result.r, result.g, result.b))
        }
        return strip.timeline(durationMs) to strip.stats()
    }

    @Test
    fun renderVisualiserPresetComparison() {
        val pcm = OfflineAudio.syntheticTrack(seconds = 20.0)
        val presets = listOf("Ebb & Flow", "Smooth Flow", "Ambient Chill", "Default", "Punchy", "Strobe Blast")

        val tracks = presets.map { preset ->
            val (frames, stats) = runPreset(preset, pcm)
            FeelTrack(
                label = preset,
                frames = frames,
                caption = "${stats.accepted} writes shown | ${stats.droppedTooFast + stats.droppedOverCapacity} dropped"
            )
        }

        val file = FeelRenderer.render(
            tracks = tracks,
            title = "Visualiser presets — 120bpm synthetic track, doubling to a busy pattern at 10s (1 strip, 50ms pacing)",
            outputFile = File(outputDir, "presets.png")
        )
        println("wrote $file")

        // Harness guards only: every preset must actually drive the strip and produce light.
        tracks.forEach { track ->
            assert(track.frames.any { it.r > 0 || it.g > 0 || it.b > 0 }) {
                "${track.label} rendered as pure black — the pipeline is broken, not the preset"
            }
        }
    }

    @Test
    fun renderPacingAndMultiStripEffect() {
        val pcm = OfflineAudio.syntheticTrack(seconds = 12.0, busyFromSeconds = 6.0)

        val tracks = listOf(
            Triple("Punchy · 50ms · 1 strip", 50, 1),
            Triple("Punchy · 20ms · 1 strip", 20, 1),
            Triple("Punchy · 20ms · 2 strips", 20, 2),
            Triple("Ebb & Flow · 50ms · 1 strip", 50, 1),
            Triple("Ebb & Flow · 20ms · 2 strips", 20, 2)
        ).map { (label, pacing, strips) ->
            val preset = if (label.startsWith("Punchy")) "Punchy" else "Ebb & Flow"
            val (frames, stats) = runPreset(preset, pcm, connectedStripCount = strips, pacingMs = pacing)
            FeelTrack(
                label = label,
                frames = frames,
                caption = "${stats.accepted} shown, ${stats.droppedTooFast + stats.droppedOverCapacity} dropped " +
                    "(${"%.0f".format(stats.dropRate * 100)}%)"
            )
        }

        val file = FeelRenderer.render(
            tracks = tracks,
            title = "Pacing and multi-strip contention — red ticks are writes the strip dropped (PROVISIONAL limits, not yet measured)",
            outputFile = File(outputDir, "pacing.png")
        )
        println("wrote $file")
    }
}
