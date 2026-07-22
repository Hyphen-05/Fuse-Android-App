package com.example.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.AudioCaptureService
import com.example.BuildConfig
import com.example.DiagnosticLogger
import com.example.RgbControllerApplication
import com.example.hardware.debug.AdbTestMetronome

/**
 * Debug-only control surface for the self-driving audio-pipeline test harness (see CLAUDE.md's
 * "self-driving test harness" handoff). Registered dynamically (not via a manifest <receiver>)
 * from RgbControllerApplication.onCreate(), and gated on BuildConfig.DEBUG at both registration
 * and dispatch time so it is structurally inert in a release build and cannot be reached by a
 * real user even if somehow present.
 *
 * Invoke via:
 *   adb shell am broadcast -a com.example.debug.ACTION_CONTROL -p com.aistudio.expressivergb.xkzqp \
 *       --es cmd <command> [extras...]
 *
 * Dynamically-registered receivers aren't addressable by component name (-n), so every command
 * targets the receiver implicitly via -p (package) + the action string, same technique adb uses
 * for any runtime-registered receiver.
 *
 * Commands (extra "cmd"):
 *   start_diagnostics   [--ez exclude_ble true|false]
 *   stop_diagnostics    (also triggers DiagnosticLogger.exportToFile, matching the Settings-tab
 *                        Stop button's behavior — no separate export step needed)
 *   start_metronome     [--ef bpm 120] [--ef freq 1200] [--ef volume 1.0] [--ei duration_sec 60]
 *   start_metronome_sustained  (same extras as start_metronome; one continuous long-lived
 *                        AudioTrack instead of one short-lived AudioTrack per click — see
 *                        MetronomePlayer.startSustained's doc comment)
 *   start_metronome_tone [--ef freq 1200] [--ef volume 1.0] [--ei duration_sec 60]
 *                        (continuous gap-free tone, no beat structure — see
 *                        MetronomePlayer.startContinuousTone's doc comment)
 *   stop_metronome
 *   select_backend      --es mode phone_mic|on_device   (starts real music-sync audio engine)
 *   stop_backend
 *   status              (dumps current state to Logcat under tag AdbControl)
 *
 * All outcomes are logged to Logcat (tag "AdbControl") for scripted confirmation — e.g. after
 * start_diagnostics, `adb logcat -d -s AdbControl` should show the resulting excludedTags so a
 * script can fail loudly instead of silently capturing with the wrong exclusion state.
 */
class AdbControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONTROL = "com.example.debug.ACTION_CONTROL"
        private const val TAG = "AdbControl"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val cmd = intent.getStringExtra("cmd")
        if (cmd == null) {
            Log.w(TAG, "received broadcast with no 'cmd' extra")
            return
        }

        val appContainer = (context.applicationContext as RgbControllerApplication).container

        when (cmd) {
            "start_diagnostics" -> {
                val excludeBle = intent.getBooleanExtra("exclude_ble", false)
                val tags = if (excludeBle) setOf("DeviceWriteManager", "BleWriteWatchdog", "BLE") else emptySet()
                DiagnosticLogger.start(tags)
                Log.i(TAG, "start_diagnostics: excludeBle=$excludeBle appliedExcludedTags=${DiagnosticLogger.currentExcludedTags()}")
            }

            "stop_diagnostics" -> {
                DiagnosticLogger.stop()
                val uri = DiagnosticLogger.exportToFile(context)
                Log.i(TAG, "stop_diagnostics: exportedUri=$uri")
            }

            "start_metronome" -> {
                val bpm = intent.getFloatExtra("bpm", 120f)
                val freq = intent.getFloatExtra("freq", 1200f).toDouble()
                val volume = intent.getFloatExtra("volume", 1f)
                val durationSec = if (intent.hasExtra("duration_sec")) intent.getIntExtra("duration_sec", 0) else null
                AdbTestMetronome.start(bpm, freq, volume, durationSec)
                Log.i(TAG, "start_metronome: bpm=$bpm freq=$freq volume=$volume durationSec=${durationSec ?: "infinite"}")
            }

            "start_metronome_sustained" -> {
                val bpm = intent.getFloatExtra("bpm", 120f)
                val freq = intent.getFloatExtra("freq", 1200f).toDouble()
                val volume = intent.getFloatExtra("volume", 1f)
                val durationSec = if (intent.hasExtra("duration_sec")) intent.getIntExtra("duration_sec", 0) else null
                AdbTestMetronome.startSustained(bpm, freq, volume, durationSec)
                Log.i(TAG, "start_metronome_sustained: bpm=$bpm freq=$freq volume=$volume durationSec=${durationSec ?: "infinite"}")
            }

            "start_metronome_tone" -> {
                val freq = intent.getFloatExtra("freq", 1200f).toDouble()
                val volume = intent.getFloatExtra("volume", 1f)
                val durationSec = if (intent.hasExtra("duration_sec")) intent.getIntExtra("duration_sec", 0) else null
                AdbTestMetronome.startContinuousTone(freq, volume, durationSec)
                Log.i(TAG, "start_metronome_tone: freq=$freq volume=$volume durationSec=${durationSec ?: "infinite"}")
            }

            "stop_metronome" -> {
                AdbTestMetronome.stop()
                Log.i(TAG, "stop_metronome")
            }

            "select_backend" -> {
                val mode = intent.getStringExtra("mode")
                if (mode == null) {
                    Log.w(TAG, "select_backend: missing 'mode' extra (expected phone_mic or on_device)")
                    return
                }
                val listener = appContainer.adbControlSink.listener
                if (listener == null) {
                    Log.w(TAG, "select_backend: no active RgbControllerViewModel listener registered (is the app foregrounded?)")
                    return
                }
                // Bug fix (2026-07-22): MusicScreen.kt calls AudioCaptureService.start(context, mode)
                // before viewModel.startMusicSync(mode) on every real UI tap -- this harness command
                // was calling straight through to startMusicSync() alone, skipping that promotion
                // entirely. On the on_device path this left the process never actually eligible for
                // real Visualizer(0) capture content (RgbControllerViewModel's awaitForeground(500L)
                // just times out and proceeds anyway), producing FFT callbacks that fire on schedule
                // but carry no real signal -- confirmed via two full on-device diagnostic captures
                // that came back with strength=confidence=bpm=0.0 for the entire session despite
                // normal callback cadence. Mirroring MusicScreen.kt's call here closes that gap.
                if (mode == "on_device" || mode == "phone_mic") {
                    AudioCaptureService.start(context, mode)
                }
                listener.onAdbStartMusicSync(mode)
                Log.i(TAG, "select_backend: mode=$mode")
            }

            "stop_backend" -> {
                val listener = appContainer.adbControlSink.listener
                if (listener == null) {
                    Log.w(TAG, "stop_backend: no active RgbControllerViewModel listener registered")
                    return
                }
                listener.onAdbStopMusicSync()
                Log.i(TAG, "stop_backend")
            }

            "status" -> {
                Log.i(
                    TAG,
                    "status: diagnosticsRecording=${DiagnosticLogger.isRecording()} " +
                        "excludedTags=${DiagnosticLogger.currentExcludedTags()} " +
                        "metronomeRunning=${AdbTestMetronome.isRunning()} " +
                        "vmListenerRegistered=${appContainer.adbControlSink.listener != null}"
                )
            }

            else -> Log.w(TAG, "unknown cmd: $cmd")
        }
    }
}
