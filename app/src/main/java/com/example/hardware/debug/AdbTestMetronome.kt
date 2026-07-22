package com.example.hardware.debug

import android.os.SystemClock
import com.example.DiagnosticLogger
import com.example.hardware.audio.MetronomePlayer

/**
 * A scriptable click-track source for the self-driving test harness (see the
 * "self-driving test harness" handoff in CLAUDE.md). Distinct from the single-click
 * MetronomePlayer usage in the calibration flow (RgbControllerViewModel's StartMetronome
 * side effect) — this one owns its own MetronomePlayer instance and loop thread so it can run
 * completely independently of the calibration flow / RgbUiState.
 *
 * Plays through STREAM_MUSIC via AudioTrack, i.e. the device's normal audio output — so it is
 * picked up by the on_device (Visualizer, global mix) backend automatically, and by the
 * phone_mic (AudioRecord) backend acoustically if the phone's speaker and mic are both live
 * (e.g. device sitting on a desk, not covered).
 *
 * Every tick is logged to DiagnosticLogger under tag "AdbMetronome" (elapsedRealtime-based,
 * same clock DiagnosticLogger uses everywhere else) so a capture can be cross-referenced against
 * BeatConfidence/BeatGridPhase/AudioSync entries without needing a separate clock-offset
 * calculation.
 */
object AdbTestMetronome {
    private val player = MetronomePlayer()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // Bug investigation (2026-07-22): sustained-tone mode, added to test the hypothesis that
    // on_device's Visualizer(0) tap misses the one-shot mode's very short-lived (~120ms)
    // per-click AudioTrack sessions. Plays one continuous AudioTrack for the whole session
    // instead of constructing/destroying one per click. Independent start/stop from the
    // one-shot mode above so a script can pick either without the two interfering.
    fun startSustained(bpm: Float, frequencyHz: Double, volumeScale: Float, durationSec: Int?) {
        stop()
        player.startSustained(bpm, frequencyHz, volumeScale)
        DiagnosticLogger.log(
            "AdbMetronome",
            "started (sustained) bpm=$bpm freq=$frequencyHz volume=$volumeScale durationSec=${durationSec ?: "infinite"}"
        )
        if (durationSec != null) {
            Thread {
                try {
                    Thread.sleep(durationSec * 1000L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (player.isSustainedRunning()) {
                    player.stopSustained()
                    DiagnosticLogger.log("AdbMetronome", "auto-stopped (sustained) after ${durationSec}s")
                }
            }.also {
                it.isDaemon = true
                it.name = "AdbTestMetronomeSustainedTimer"
                it.start()
            }
        }
    }

    // Diagnostic-only (2026-07-22): continuous, gap-free tone -- see
    // MetronomePlayer.startContinuousTone's doc comment. Used to disambiguate "Visualizer never
    // sees real signal" from "the diagnostic sampling interval missed a low-duty-cycle click."
    fun startContinuousTone(frequencyHz: Double, volumeScale: Float, durationSec: Int?) {
        stop()
        player.startContinuousTone(frequencyHz, volumeScale)
        DiagnosticLogger.log(
            "AdbMetronome",
            "started (continuous tone) freq=$frequencyHz volume=$volumeScale durationSec=${durationSec ?: "infinite"}"
        )
        if (durationSec != null) {
            Thread {
                try {
                    Thread.sleep(durationSec * 1000L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                if (player.isSustainedRunning()) {
                    player.stopSustained()
                    DiagnosticLogger.log("AdbMetronome", "auto-stopped (continuous tone) after ${durationSec}s")
                }
            }.also {
                it.isDaemon = true
                it.name = "AdbTestMetronomeToneTimer"
                it.start()
            }
        }
    }

    fun start(bpm: Float, frequencyHz: Double, volumeScale: Float, durationSec: Int?) {
        stop()
        val intervalMs = (60000f / bpm.coerceIn(20f, 400f)).toLong()
        val startElapsed = SystemClock.elapsedRealtime()
        running = true
        DiagnosticLogger.log(
            "AdbMetronome",
            "started bpm=$bpm freq=$frequencyHz volume=$volumeScale intervalMs=$intervalMs durationSec=${durationSec ?: "infinite"}"
        )
        thread = Thread {
            var tick = 0
            while (running) {
                if (durationSec != null && SystemClock.elapsedRealtime() - startElapsed >= durationSec * 1000L) {
                    running = false
                    DiagnosticLogger.log("AdbMetronome", "auto-stopped after ${durationSec}s, $tick ticks")
                    break
                }
                player.playClick(frequencyHz, volumeScale, 50)
                tick++
                DiagnosticLogger.log("AdbMetronome", "tick=$tick atElapsedMs=${SystemClock.elapsedRealtime()}")
                try {
                    Thread.sleep(intervalMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "AdbTestMetronome"
            it.start()
        }
    }

    fun stop() {
        if (running) {
            running = false
            DiagnosticLogger.log("AdbMetronome", "stopped (manual)")
        }
        thread?.interrupt()
        thread = null
        if (player.isSustainedRunning()) {
            player.stopSustained()
            DiagnosticLogger.log("AdbMetronome", "stopped (sustained, manual)")
        }
    }

    fun isRunning(): Boolean = running || player.isSustainedRunning()
}
