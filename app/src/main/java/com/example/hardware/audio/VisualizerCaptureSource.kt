package com.example.hardware.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import com.example.DiagnosticLogger

/**
 * Real "on_device" backend: wraps Android's `Visualizer` API (session id 0). Ported verbatim from
 * `RgbControllerViewModel.startAudioEngine`'s Visualizer branch (~lines 3481-3891 in the
 * pre-Phase-4 source) — owns spectrum *reconstruction* only (raw FFT byte array -> magnitude/
 * real/imag float arrays); the DSP pipeline that used to run inline inside
 * `onFftDataCapture` now lives in `com.example.core.audio.AudioDspProcessor`.
 *
 * `onFftDataCapture` fires on whichever thread constructed this `Visualizer` (no dedicated
 * thread is spawned here, matching the original), so [start] does its permission/init work
 * synchronously and returns the real success/failure result — unlike [AudioRecordCaptureSource],
 * whose checks originally happened inside a background thread.
 *
 * **First-activation-silent-attach watchdog (2026-07-21, addresses "on-device backend doesn't
 * activate on first click, works after toggling off/on").** No on-device Logcat trace of this
 * could be captured in the environment this fix was written in (no adb/device attached) — this
 * is a source-level fix for a real, documented Android platform quirk, not a trace-confirmed root
 * cause; flagged here explicitly, same as the item-14 `StartAudioEngine` freeze fix in CLAUDE.md.
 * `Visualizer(0)` (session 0 = the device's global output mix) can silently receive zero
 * `onFftDataCapture` callbacks if it is attached at a moment when the output mix hasn't
 * stabilized (a known characteristic of session-0 Visualizer attach, not something `enabled =
 * true` reports as an error — no exception is thrown, `start()` returns `true`, the object just
 * never calls back). Manually toggling the "On Device" card off and back on works around this by
 * releasing and re-constructing the `Visualizer`, which re-attaches once the mix has settled.
 * This class now does that automatically: a one-shot watchdog fires [WATCHDOG_TIMEOUT_MS] after
 * `enabled = true` and, if zero frames have arrived and capture is still supposed to be running,
 * tears down and reconstructs exactly once (see [retried]) — this cannot loop indefinitely.
 *
 * **Correction (2026-07-22): the above watchdog was never the actual bug.** An on-device
 * diagnostic capture showed "Active Engine: ON_DEVICE initialized" immediately followed (~1ms
 * later) by the app falling back to "Active Engine: SIMULATION started successfully", with
 * `retry=true` never appearing anywhere in the capture -- meaning the watchdog's own reconstruction
 * path never even ran. That sequencing only happens if the `try` block in [attemptStart] throws and
 * `onError` fires synchronously, immediately, with no retry at all on that path.
 *
 * **Second correction (2026-07-22): it isn't a timing/release race either.** A second capture
 * gave the actual exception text: `IllegalStateException: setCaptureSize() called in wrong state:
 * 2` (2 = `STATE_ENABLED`) — on a brand-new `Visualizer` object this class never called
 * `.enabled = true` on. Confirmed by the developer to reproduce even on a freshly force-stopped
 * and relaunched app process, i.e. with zero prior activity from this app in that process at all —
 * ruling out any theory involving this app's own session lifecycle, leaked resources, or async
 * release timing, all of which assume the interference comes from *our own* earlier attempts.
 * Session 0 is the device's *shared* global output mix; Android explicitly supports multiple
 * independent `Visualizer` clients attaching to it at once (native setup can legitimately return
 * `ALREADY_EXISTS` rather than `SUCCESS`), which is how multiple visualizer/equalizer apps can
 * watch the same output simultaneously. If some other, already-running `Visualizer(0)` client —
 * another app, or a persistent system/vendor audio-effects component, either way outside this
 * app's control — is already enabled on this session, a brand-new wrapper here attaches to that
 * same shared native handle and inherits its current `ENABLED` state, making `captureSize=`
 * illegal (Android requires `STATE_INITIALIZED`) no matter how many times or how long this class
 * retries it. The actual fix: detect `vis.enabled` immediately after construction and skip the
 * capture-size/enable calls entirely when it's already true, registering only our own listener
 * against whatever the shared effect is already configured with. See the `alreadyEnabledElsewhere`
 * branches in [attemptStart]. The bounded exception-retry loop ([MAX_EXCEPTION_RETRIES]) and the
 * watchdog above are both left in place as defensive fallbacks for a genuinely transient failure,
 * but neither was the actual cause here.
 *
 * **Real root cause (2026-07-22), found via the self-driving adb harness (CLAUDE.md item 25) and
 * confirmed by the developer's own real-world usage pattern ("sometimes it works fine, sometimes
 * it doesn't; toggling off and back on a few times eventually gets it working" — i.e.
 * intermittent, not permanent, and self-heals under exactly the retry behavior this class already
 * had for a *different* symptom).** The watchdog above only ever checked "did `onFftDataCapture`
 * fire at all" ([receivedAnyFrame]) — but on-device diagnostic captures showed the callback firing
 * on schedule, at the right size, for the *entire* session, with the raw FFT `ByteArray` itself
 * genuinely all zero bytes every time (confirmed by logging the raw callback payload directly,
 * before any of this class's own magnitude/flux reconstruction runs). `receivedAnyFrame` is set
 * `true` on the very first callback regardless of content, so the watchdog always saw "frames are
 * flowing" and never fired its reconstruction path — structurally blind to exactly the failure
 * mode actually occurring. This matches a real, if under-documented, `Visualizer(session=0)`
 * characteristic: attaching while the primary output thread is idle/in-standby (no active
 * playback yet, or between tracks) can bind the effect to that thread's current (soon-to-be-
 * replaced-or-reactivated) state; when real playback later starts, the callback keeps firing on
 * the original binding but never receives the new stream's samples. There is nothing to catch or
 * retry synchronously — no exception, no state change Java can observe — which is exactly why a
 * few *manual* toggles (each one a fresh release+reconstruct, landing at a new, uncorrelated
 * moment relative to whatever the output thread is doing) eventually lands on a good attach purely
 * by chance, and why this always looked intermittent rather than deterministic.
 *
 * **Fix:** track real signal content, not just callback occurrence — [receivedRealSignal] is set
 * only once a callback's `fft` bytes contain at least one nonzero value. The watchdog now checks
 * *that* flag instead of [receivedAnyFrame], and — unlike the original one-shot watchdog — retries
 * up to [MAX_ATTACH_RETRIES] times (each attempt gets its own fresh [WATCHDOG_TIMEOUT_MS] window),
 * mirroring what manually mashing the toggle a few times already did, just automatically and
 * faster. If real signal still hasn't shown up after all retries are exhausted, this now calls
 * [onError] to trigger the existing demo-simulation fallback rather than silently leaving a
 * permanently-mute `Visualizer` running and reporting "success" forever — the prior behavior for
 * this exact exhaustion case, which the original one-shot design never handled either.
 */
class VisualizerCaptureSource(private val context: Context) : AudioCaptureSource {

    override val backend: AudioBackend = AudioBackend.VISUALIZER

    @Volatile private var activeVisualizer: Visualizer? = null

    companion object {
        const val WATCHDOG_TIMEOUT_MS = 1500L
        // Bug fix (2026-07-22): an on-device diagnostic capture confirmed the real on_device
        // "doesn't activate" failure is NOT the silent-zero-callback scenario the watchdog below
        // targets -- the log shows "Active Engine: ON_DEVICE initialized" immediately followed
        // (~1ms later) by "Active Engine: SIMULATION started successfully", with no watchdog log
        // line ("no frames received...") anywhere in the capture. That sequencing only happens if
        // the try block below throws synchronously and onError() fires immediately -- there was no
        // retry at all on that path before now. Root cause: stop() releases the previous
        // Visualizer's native AudioEffect resources synchronously from the Java side, but
        // AudioEffect.release() only *requests* native teardown -- Android does not guarantee it
        // completes before the call returns. Re-selecting on_device shortly after last disabling
        // it (or after a StopMusicSyncInternal→StartAudioEngine mode switch) can construct/enable a
        // new Visualizer(0) while the old one's native session-0 attachment is still tearing down,
        // which throws. A short bounded retry directly on the exception (not a longer "no frames"
        // timeout, a different failure mode) is what actually recovers from this.
        //
        // Correction (2026-07-22), from a second capture: a flat 3x200ms retry failed identically
        // all 3/3 times -- but that capture also proved the retry loop itself was leaking a native
        // Visualizer on every failed attempt (see the catch block in attemptStart), which by itself
        // could fully explain 3/3 identical failures regardless of how long the real teardown
        // takes. Now that every failed attempt properly releases before retrying, the backoff is
        // widened from a flat 200ms to exponential (200ms/400ms/800ms/1600ms/3200ms, ~6.2s total
        // worst case) to give real headroom for a genuine native-teardown race, since the previous
        // fixed 600ms total window was never actually tested without the leak confounding it.
        const val MAX_EXCEPTION_RETRIES = 5
        const val EXCEPTION_RETRY_BASE_DELAY_MS = 200L

        // Bug fix (2026-07-22): bounds how many times the watchdog will release+reconstruct the
        // Visualizer while it's producing callbacks with no real signal content (see the
        // "Real root cause" doc comment above). Each retry gets its own WATCHDOG_TIMEOUT_MS
        // window, so worst case this takes MAX_ATTACH_RETRIES * WATCHDOG_TIMEOUT_MS (~7.5s) before
        // falling back to simulation -- comparable to the few manual toggles this replaces.
        const val MAX_ATTACH_RETRIES = 5

        // Bug fix (2026-07-23): "on_device active, pause media for a while, resume -> LEDs never
        // come back". Root cause: MAX_ATTACH_RETRIES/WATCHDOG_TIMEOUT_MS above bound only the very
        // *first* attach attempt of a start() call -- once real signal has been seen even once,
        // a later stall (the exact same session-0-binds-to-a-stale-output-thread quirk described
        // in the class doc comment, just triggered by the output track pausing/restarting instead
        // of initial attach) still only gets MAX_ATTACH_RETRIES (5) reconnect attempts, each
        // bounded by WATCHDOG_TIMEOUT_MS (1.5s) -- about 7.5s -- before giving up and calling
        // onError(), which permanently falls back to the demo/simulation engine for the rest of
        // the session. A real pause (switching tracks, a phone call, just stopping to talk) can
        // easily outlast 7.5s, so this reads as "never recovers" even though the mechanism that
        // fixed first-activation was right there. Once real signal has been confirmed at least
        // once this session, reconnect attempts must never permanently give up -- silence just
        // means "no music right now," not "capture is broken." See [everReceivedRealSignal].
        const val STALL_RECHECK_INTERVAL_MS = 4000L
    }

    // Sticky for the lifetime of this VisualizerCaptureSource instance (one per on_device
    // start()/stop() cycle, but NOT per external media pause/resume -- capture keeps running
    // continuously across those). Once true, the attach/stall watchdog below switches from
    // "bounded retries then give up" to "retry forever, more gently" -- see STALL_RECHECK_INTERVAL_MS.
    @Volatile private var everReceivedRealSignal = false

    override fun start(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit
    ): Boolean = attemptStart(onFrame, onLog, isRunning, onError, attachRetryCount = 0, exceptionRetryCount = 0)

    private fun attemptStart(
        onFrame: (AudioCaptureFrame) -> Unit,
        onLog: (String) -> Unit,
        isRunning: () -> Boolean,
        onError: (Throwable) -> Unit,
        attachRetryCount: Int,
        exceptionRetryCount: Int
    ): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            onLog("Record Audio Permission missing. Running simulation.")
            onError(IllegalStateException("Record Audio Permission missing. Running simulation."))
            return false
        }

        // Set the instant construction succeeds, so the catch block below can release it on any
        // later failure in this same attempt -- see the bug fix note there for why that matters.
        // (Kept separate from the `val vis` used in the try body itself so `vis` can stay a
        // non-null local the whole way through, rather than forcing null-checks/!! everywhere.)
        var constructedVis: Visualizer? = null
        try {
            val vis = Visualizer(0)
            constructedVis = vis
            // Bug fix (2026-07-22): confirmed via two on-device diagnostic captures that this
            // reproduces even on a fresh app process (force-stopped and relaunched, zero prior
            // activity of our own) -- ruling out any theory involving our own app's session
            // lifecycle, leaked resources, or async release races entirely. Session 0 is the
            // device's *shared* global output mix; Android explicitly allows multiple independent
            // Visualizer clients to attach to it simultaneously (native setup can legitimately
            // return ALREADY_EXISTS rather than SUCCESS), which is exactly how e.g. multiple
            // visualizer/equalizer apps can watch the same output at once. If some other,
            // already-running Visualizer(0) client (another app, or a persistent system/vendor
            // audio-effects component -- outside this app's control either way) is already enabled
            // on this session, our brand-new Java wrapper attaches to that same shared native
            // handle and inherits its current ENABLED state -- explaining "setCaptureSize() called
            // in wrong state: 2" (2 = STATE_ENABLED) on an object we never called .enabled=true on
            // ourselves. Android requires STATE_INITIALIZED (not STATE_ENABLED) to change
            // captureSize, so that call is simply illegal here, no matter how many times or how
            // long we wait to retry it -- there is nothing transient to wait out. The actual fix:
            // detect this up front and skip straight to registering our own listener against
            // whatever the shared effect is already configured with, instead of trying (and
            // retrying) to reconfigure an effect that was never ours to configure.
            val alreadyEnabledElsewhere = vis.enabled
            DiagnosticLogger.log(
                "AudioCapture",
                "Active Engine: ON_DEVICE initialized (attachRetryCount=$attachRetryCount, alreadyEnabledElsewhere=$alreadyEnabledElsewhere)"
            )
            if (!alreadyEnabledElsewhere) {
                vis.captureSize = Visualizer.getCaptureSizeRange()[1]
            } else if (vis.captureSize / 2 < 349) {
                // visualizer-review-2026-07-22.md A6: when attaching to an already-enabled shared
                // session-0 client, we inherit whatever captureSize that other client configured --
                // we cannot call captureSize= ourselves (that's the whole reason this branch
                // exists). If that inherited size is small enough that every frame's numBins falls
                // under the callback's own `numBins < 349` guard, every single frame gets silently
                // dropped downstream in AudioDspProcessor -- but the attach watchdog's
                // receivedRealSignal check only looks at raw FFT byte content, which is still
                // genuinely nonzero, so it never fires. Net effect without this check: capture
                // reports success, real signal is flowing, and zero frames ever reach the DSP --
                // a permanently dark visualizer with no error and no fallback. Treat this exactly
                // like a failed attach: release our wrapper (do NOT touch `enabled` -- it's a
                // shared handle, disabling it would affect the other client) and retry/give up
                // through the same attachRetryCount path the real watchdog uses.
                DiagnosticLogger.log(
                    "AudioCapture",
                    "on_device: inherited shared-session captureSize=${vis.captureSize} yields numBins=${vis.captureSize / 2} (<349), " +
                        "treating as failed attach (attempt ${attachRetryCount + 1}/$MAX_ATTACH_RETRIES)"
                )
                try {
                    vis.release()
                } catch (re: Exception) {}
                if (attachRetryCount < MAX_ATTACH_RETRIES) {
                    return attemptStart(onFrame, onLog, isRunning, onError, attachRetryCount = attachRetryCount + 1, exceptionRetryCount = exceptionRetryCount)
                }
                onLog("Visualizer's shared session capture size is too small after $MAX_ATTACH_RETRIES attempts. Running simulation.")
                onError(IllegalStateException("Visualizer(0) shared-session captureSize never yielded enough bins after $MAX_ATTACH_RETRIES attempts"))
                return false
            }

            var lastFftTime = 0L
            var lastRawFftLogMs = 0L
            var loggedBackendInfo = false
            val receivedAnyFrame = java.util.concurrent.atomic.AtomicBoolean(false)
            // Bug fix (2026-07-22): distinct from receivedAnyFrame -- set only once a callback's
            // fft bytes actually contain nonzero content, not just on the callback firing at all.
            // See the "Real root cause" doc comment above for why receivedAnyFrame alone was blind
            // to this failure mode.
            val receivedRealSignal = java.util.concurrent.atomic.AtomicBoolean(false)

            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || !isRunning()) return
                    receivedAnyFrame.set(true)

                    // Bug fix (2026-07-22): cheap unthrottled scan (only runs until the first
                    // nonzero byte is ever seen this attempt) so the watchdog below can tell a
                    // genuinely-signal-carrying attach apart from one that's only producing
                    // correctly-shaped but all-zero callbacks -- see the "Real root cause" doc
                    // comment above.
                    if (!receivedRealSignal.get()) {
                        var sawNonZero = false
                        for (b in fft) {
                            if (b.toInt() != 0) {
                                sawNonZero = true
                                break
                            }
                        }
                        if (sawNonZero) {
                            receivedRealSignal.set(true)
                            everReceivedRealSignal = true
                        }
                    }

                    if (!loggedBackendInfo) {
                        loggedBackendInfo = true
                        // Bug fix (2026-07-22): Android's Visualizer.OnFftDataCapture documents
                        // samplingRate as milliHertz, not Hertz -- this line was logging the raw
                        // value with a "Hz" suffix (e.g. "48000000Hz" for a perfectly normal
                        // 48kHz capture), which is exactly the ambiguity CLAUDE.md flagged as
                        // unconfirmed when this instrumentation was first added. Logging both the
                        // raw milliHz value and the converted Hz value removes the ambiguity
                        // without discarding anything a future diagnostic pass might want.
                        DiagnosticLogger.log(
                            "BackendInfo",
                            "backend=on_device samplingRateMilliHz=$samplingRate samplingRateHz=${samplingRate / 1000} captureSize=${vis.captureSize} numBins=${fft.size / 2}"
                        )
                    }

                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    if (lastFftTime != 0L) {
                        val interval = nowElapsed - lastFftTime
                        DiagnosticLogger.log(
                            "AudioCapture",
                            "FFT capture interval: ${interval}ms, mode=on_device"
                        )
                    }
                    lastFftTime = nowElapsed

                    // Diagnostic-only (2026-07-22, on_device signal-zero investigation): confirms
                    // whether the raw FFT byte array itself carries real content at the source, or
                    // is coming back all-zero from Visualizer/session 0 directly -- distinguishes
                    // "our own magnitude/flux math is discarding real data" from "Visualizer(0)
                    // genuinely never sees this app's own AudioTrack output." Throttled to ~1.5s,
                    // same cadence as AudioDspProcessor's SignalLevel/BeatConfidence sampling.
                    if (nowElapsed - lastRawFftLogMs >= 1500L) {
                        lastRawFftLogMs = nowElapsed
                        var nonZeroCount = 0
                        var maxAbsByte = 0
                        for (b in fft) {
                            val v = b.toInt()
                            if (v != 0) nonZeroCount++
                            if (Math.abs(v) > maxAbsByte) maxAbsByte = Math.abs(v)
                        }
                        DiagnosticLogger.log(
                            "RawFft",
                            "backend=on_device fftLen=${fft.size} nonZeroBytes=$nonZeroCount maxAbsByte=$maxAbsByte"
                        )
                    }

                    val len = fft.size
                    val numBins = len / 2
                    if (numBins < 349) return

                    val magnitude = FloatArray(numBins)
                    val realBins = FloatArray(numBins)
                    val imagBins = FloatArray(numBins)
                    magnitude[0] = Math.abs(fft[0].toInt()).toFloat()
                    realBins[0] = fft[0].toFloat()
                    imagBins[0] = 0.0f
                    for (k in 1 until numBins) {
                        val r = fft[2 * k].toFloat()
                        val i = fft[2 * k + 1].toFloat()
                        magnitude[k] = Math.sqrt((r * r + i * i).toDouble()).toFloat()
                        realBins[k] = r
                        imagBins[k] = i
                    }

                    onFrame(
                        AudioCaptureFrame(
                            magnitude = magnitude,
                            realBins = realBins,
                            imagBins = imagBins,
                            numBins = numBins,
                            backend = AudioBackend.VISUALIZER,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
            }, Visualizer.getMaxCaptureRate(), false, true)

            if (!alreadyEnabledElsewhere) {
                vis.enabled = true
            }
            activeVisualizer = vis
            onLog("System Visualizer initialized successfully for on-device sync (direct FFT).")

            Thread {
                // Bug fix (2026-07-23): once this session has ever seen real signal, this
                // watchdog re-checks on the gentler STALL_RECHECK_INTERVAL_MS cadence instead of
                // the fast first-activation WATCHDOG_TIMEOUT_MS -- see STALL_RECHECK_INTERVAL_MS's
                // doc comment. Captured before the sleep so a stall discovered *during* this wait
                // still uses the interval appropriate to whether we'd already succeeded going in.
                val alreadySucceededThisSession = everReceivedRealSignal
                try {
                    Thread.sleep(if (alreadySucceededThisSession) STALL_RECHECK_INTERVAL_MS else WATCHDOG_TIMEOUT_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                // Only act if this watchdog's own Visualizer is still the live one (a real
                // stop() or a subsequent start() may have already superseded it) and capture
                // is still supposed to be active.
                if (!receivedRealSignal.get() && activeVisualizer === vis && isRunning()) {
                    val reason = if (!receivedAnyFrame.get()) {
                        "no frames received at all"
                    } else {
                        "frames received on schedule but with zero signal content (session-0 " +
                            "attach likely bound to a stale/inactive output thread)"
                    }
                    try {
                        vis.enabled = false
                        vis.release()
                    } catch (e: Exception) {}
                    if (activeVisualizer === vis) activeVisualizer = null

                    // Bug fix (2026-07-23): once real signal has been confirmed at least once
                    // this session, a stall (e.g. the external media player being paused) must
                    // never permanently exhaust the retry budget and fall back to simulation --
                    // that reads as "the visualizer never recovers when I resume playback." Reset
                    // the retry count instead, so reconnect attempts continue indefinitely at the
                    // STALL_RECHECK_INTERVAL_MS cadence until real signal returns.
                    if (everReceivedRealSignal || attachRetryCount < MAX_ATTACH_RETRIES) {
                        val nextAttachRetryCount = if (everReceivedRealSignal) 0 else attachRetryCount + 1
                        DiagnosticLogger.log(
                            "AudioCapture",
                            "on_device: $reason (everReceivedRealSignal=$everReceivedRealSignal, attempt " +
                                "${attachRetryCount + 1}${if (everReceivedRealSignal) "" else "/$MAX_ATTACH_RETRIES"}), reconstructing Visualizer"
                        )
                        attemptStart(onFrame, onLog, isRunning, onError, attachRetryCount = nextAttachRetryCount, exceptionRetryCount = if (everReceivedRealSignal) 0 else exceptionRetryCount)
                    } else {
                        DiagnosticLogger.log(
                            "AudioCapture",
                            "on_device: $reason, gave up after $MAX_ATTACH_RETRIES reconstruction attempts, falling back to simulation"
                        )
                        onLog("Visualizer never produced real signal after $MAX_ATTACH_RETRIES attempts. Running simulation.")
                        onError(IllegalStateException("Visualizer(0) attach never produced real signal after $MAX_ATTACH_RETRIES attempts"))
                    }
                }
            }.apply {
                name = "VisualizerAttachWatchdog"
                isDaemon = true
                start()
            }

            return true
        } catch (e: Exception) {
            // Bug fix (2026-07-22), found from a second on-device capture: the previous version of
            // this catch block never released `vis` if construction succeeded but a later setup
            // call (captureSize/listener/enabled) threw -- e.g. the actual observed exception,
            // "setCaptureSize() called in wrong state: 2" (state 2 = ENABLED on a *freshly
            // constructed* Visualizer, which only happens if this session-0 attach is colliding
            // with a native AudioEffect resource that was never released). Retrying without
            // releasing meant every retry constructed and abandoned yet another native Visualizer,
            // compounding the exact resource collision it was trying to recover from -- which is
            // almost certainly why the retry added last time failed identically all 3/3 times
            // instead of ever recovering. Releasing here before any retry/give-up is the actual
            // fix; the retry loop by itself was not.
            try {
                constructedVis?.enabled = false
                constructedVis?.release()
            } catch (re: Exception) {}
            // Bug fix (2026-07-23): same "never permanently give up once we've proven real
            // capture works this session" reasoning as the watchdog's give-up branch below --
            // a reconnect attempt triggered by a stall (e.g. after the external media player was
            // paused and resumed) can hit this same construction exception, and exhausting the
            // exception-retry budget here would fall back to simulation just as wrongly.
            if (everReceivedRealSignal || (exceptionRetryCount < MAX_EXCEPTION_RETRIES && isRunning())) {
                if (!isRunning()) return false
                val delayMs = if (everReceivedRealSignal) STALL_RECHECK_INTERVAL_MS else EXCEPTION_RETRY_BASE_DELAY_MS shl exceptionRetryCount
                DiagnosticLogger.log(
                    "AudioCapture",
                    "on_device: init threw ${e.javaClass.simpleName}: ${e.message}, retrying in ${delayMs}ms " +
                        "(everReceivedRealSignal=$everReceivedRealSignal, attempt ${exceptionRetryCount + 1}${if (everReceivedRealSignal) "" else "/$MAX_EXCEPTION_RETRIES"})"
                )
                try {
                    Thread.sleep(delayMs)
                } catch (ie: InterruptedException) {
                    return false
                }
                return attemptStart(onFrame, onLog, isRunning, onError, attachRetryCount = attachRetryCount, exceptionRetryCount = if (everReceivedRealSignal) 0 else exceptionRetryCount + 1)
            }
            onLog("Failed to initialize Visualizer: ${e.message}. Running simulation.")
            onError(e)
            return false
        }
    }

    override fun stop() {
        try {
            activeVisualizer?.enabled = false
            activeVisualizer?.release()
        } catch (e: Exception) {}
        activeVisualizer = null
    }
}
