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
 *   adb shell am broadcast -a com.example.debug.ACTION_CONTROL -p com.github.hyphen05.fuse \
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
 *   start_simulation    (runs the synthetic DemoAudioDspSimulator through the real DSP→BLE
 *                        delivery path — the only way to reach it, it is never a fallback for
 *                        failed real capture; stop with stop_backend)
 *   stop_backend
 *   run_calibration     --es sequence <name> [--ei minutes N] [--ei percent N]
 *                       [--ez attention true|false] [--ei pacing N]
 *                       (pacing omitted bypasses pacing, which is what a calibration run is for;
 *                        a value pins it, which is how the write ceiling gets swept)
 *   stop_calibration
 *   start_recording     [--es name <label>] [--el exposure_ns N] [--ei iso N] [--ef focus N]
 *                       [--ez uhd true]
 *                       (aimed at the *camera* phone, not the driver — see CalibrationRecorder)
 *   stop_recording
 *   run_mode_capture    [--ef x0 N --ef y0 N --ef x1 N --ef y1 N] [--ei positions N]
 *                       (opens the Mode Capture screen and drives it to completion; endpoints
 *                        default to a vertical line down the frame, and are better taken from a
 *                        chase_probe grid — see ModeCaptureAutoRun)
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

            "start_simulation" -> {
                val listener = appContainer.adbControlSink.listener
                if (listener == null) {
                    Log.w(TAG, "start_simulation: no active RgbControllerViewModel listener registered")
                    return
                }
                // No AudioCaptureService.start() here, unlike select_backend: the simulator never
                // touches the mic or the Visualizer, so there is nothing to promote the process for.
                listener.onAdbStartAudioSimulation()
                Log.i(TAG, "start_simulation")
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

            "run_calibration" -> {
                val sequence = intent.getStringExtra("sequence")
                if (sequence == null || sequence !in CalibrationSequences.ALL) {
                    Log.w(TAG, "run_calibration: 'sequence' must be one of ${CalibrationSequences.ALL}")
                    return
                }
                val listener = appContainer.adbControlSink.listener
                if (listener == null) {
                    Log.w(TAG, "run_calibration: no active RgbControllerViewModel listener registered")
                    return
                }
                // Optional --ei minutes N, currently only sustained_load reads it. Lets the
                // duration change without a rebuild, which matters for the one sequence whose
                // whole point is how long it runs.
                val minutes = intent.getIntExtra("minutes", 0)
                // --ei percent N, read only by hold_dim. Default 15 matches the duty cycle the
                // 2026-08-19 PWM result was taken at, so a repeat is a repeat.
                val percent = intent.getIntExtra("percent", 15)
                // --ez attention false when someone is watching the phone anyway. Default on: the
                // monitor is off during a capture session, so otherwise a finished run says nothing.
                val attention = intent.getBooleanExtra("attention", true)
                // --ei pacing N pins the per-device write pacing for the run. Omitted means
                // bypass it entirely, which is what a calibration run is supposed to do — see
                // AdbControlSink.onAdbRunCalibration. Pass a value to sweep the ceiling instead
                // of removing it.
                val pacing = intent.getIntExtra("pacing", -1)
                listener.onAdbRunCalibration(sequence, minutes, attention, percent, pacing)
                Log.i(TAG, "run_calibration: sequence=$sequence minutes=$minutes attention=$attention percent=$percent pacing=$pacing started")
            }

            // The second phone, driven over adb instead of by taps on a camera app. Runs on
            // whichever device receives the broadcast, so it is aimed at the *camera* phone while
            // run_calibration is aimed at the driver. See CalibrationRecorder.
            "start_recording" -> {
                val name = intent.getStringExtra("name") ?: "session"
                val exposureNs = intent.getLongExtra("exposure_ns", CalibrationRecorder.DEFAULT_EXPOSURE_NS)
                val iso = intent.getIntExtra("iso", CalibrationRecorder.DEFAULT_SENSITIVITY)
                val focus = intent.getFloatExtra("focus", CalibrationRecorder.DEFAULT_FOCUS_DIOPTRES)
                val fhd = !intent.getBooleanExtra("uhd", false)
                val ok = CalibrationRecorder.start(context, name, exposureNs, iso, focus, fhd) {
                    Log.i(TAG, it)
                }
                Log.i(TAG, "start_recording: ok=$ok ${CalibrationRecorder.settingsLine}")
            }

            // Mode Capture, without a human at the screen. The receiver cannot reach a PreviewView,
            // so it leaves a request and MainActivity opens the screen — see ModeCaptureAutoRun.
            "run_mode_capture" -> {
                val x0 = intent.getFloatExtra("x0", 0.5f)
                val y0 = intent.getFloatExtra("y0", 0.05f)
                val x1 = intent.getFloatExtra("x1", 0.5f)
                val y1 = intent.getFloatExtra("y1", 0.95f)
                val positions = intent.getIntExtra("positions", 0)
                ModeCaptureAutoRun.request(x0, y0, x1, y1, positions)
                // Bringing the app forward is the receiver's job: Mode Capture needs a live camera
                // preview, and a backgrounded app has neither that nor a foreground it can keep.
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                }
                Log.i(TAG, "run_mode_capture: endpoints=($x0,$y0)-($x1,$y1) positions=$positions requested")
            }

            "stop_recording" -> {
                val file = CalibrationRecorder.stop { Log.i(TAG, it) }
                Log.i(TAG, "stop_recording: file=${file?.absolutePath ?: "none"}")
            }

            "stop_calibration" -> {
                val listener = appContainer.adbControlSink.listener
                if (listener == null) {
                    Log.w(TAG, "stop_calibration: no active RgbControllerViewModel listener registered")
                    return
                }
                listener.onAdbStopCalibration()
                Log.i(TAG, "stop_calibration")
            }

            "status" -> {
                Log.i(
                    TAG,
                    "status: diagnosticsRecording=${DiagnosticLogger.isRecording()} " +
                        "excludedTags=${DiagnosticLogger.currentExcludedTags()} " +
                        "metronomeRunning=${AdbTestMetronome.isRunning()} " +
                        "recording=${CalibrationRecorder.isRecording()} " +
                        "recorderSettings=${CalibrationRecorder.settingsLine} " +
                        "modeCaptureAuto=${ModeCaptureAutoRun.status.value} " +
                        "modeCaptureExport=${ModeCaptureAutoRun.lastExportPath} " +
                        "vmListenerRegistered=${appContainer.adbControlSink.listener != null}"
                )
            }

            else -> Log.w(TAG, "unknown cmd: $cmd")
        }
    }
}
