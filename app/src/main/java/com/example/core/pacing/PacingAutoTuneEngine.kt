package com.example.core.pacing

/**
 * One row of the tuning log shown in `PacingAutoTuneDialog` — an interval that was tried, whether
 * it passed, and why. Moved verbatim (name/fields unchanged) from
 * `com.example.ui.components.PacingAutoTuneDialog` so the pure engine below can produce and store
 * it without a UI-layer dependency.
 */
data class TuningResult(val interval: Int, val isSuccess: Boolean, val reason: String)

/** Coarse phase label the dialog maps to its on-screen phase text. */
enum class TuningPhase {
    IDLE, BURST_PHASE, FINE_TUNING, STRESS_TEST, STRESS_TEST_ONLY, COMPLETE, FAILED, CANCELLED
}

/** What the caller (the dialog's coroutine) should do next. */
sealed class TuningAction {
    data class RunBurst(val interval: Int) : TuningAction()
    data class RunStress(val interval: Int) : TuningAction()
    data class Finished(val pacingMs: Int, val succeeded: Boolean) : TuningAction()
}

/**
 * Pure, hardware-free port of the pacing auto-tune state machine that used to live inline inside
 * `PacingAutoTuneDialog.kt`'s `LaunchedEffect(isTuning)` block (the nested `runBurst`/`runStress`
 * suspend functions plus the burst/fine-tune/stress control flow around them). No coroutines, no
 * `delay`, no BLE/telemetry reads — those stay in the dialog, which drives this engine by feeding
 * back real-world measurements (connection state, achieved fps) and executing the [TuningAction]
 * it returns.
 *
 * Two responsibilities are kept separate, same as `AudioDspProcessor` splits "pure math" from
 * "hardware IO":
 *  1. Success **evaluation** ([evaluateBurst]/[evaluateStress]) — given a measurement, is this
 *     interval good enough? Pure functions, independently testable.
 *  2. Sequencing / **state machine** ([start]/[onBurstResult]/[onStressResult]) — given the last
 *     result, what should be tried next, and when is the whole run done? This is the part most
 *     prone to off-by-one/ordering bugs in the original inline code (nested `while`/`for` loops
 *     with early `break`s), so it's the highest-value part to have under test.
 *
 * The mid-run "Cancelled" (coroutine cancellation checked inside the send loop) and "Disconnected"
 * (checked every 20 iterations mid-stress) outcomes are inherently tied to real timing/IO and stay
 * in the dialog — it constructs a [TuningResult] for those cases directly and still feeds it
 * through [onBurstResult]/[onStressResult] so the sequencing logic has exactly one path.
 */
class PacingAutoTuneEngine {

    companion object {
        /** Same fixed probe list as the original `intervalsToTest`. */
        val BURST_INTERVALS_MS = listOf(100, 80, 50, 30, 20, 10, 5, 0)
        const val MAX_CANDIDATE_MS = 500
        const val BURST_SIZE = 30
        const val STRESS_DURATION_MS = 120_000L
        const val STRESS_PROGRESS_CHECK_EVERY = 20
    }

    var phase: TuningPhase = TuningPhase.IDLE
        private set

    private val mutableResults = mutableListOf<TuningResult>()
    val results: List<TuningResult> get() = mutableResults.toList()

    private var bestPacing = -1
    private var originalPacing = 100
    private var burstIndex = 0
    private var failedInterval = -1
    private var fineTuneCandidate = -1

    /**
     * Burst-phase progress fraction (0f..1f), matching the original
     * `progress = index.toFloat() / intervalsToTest.size` computed just before running the burst
     * at that index. 0f outside [TuningPhase.BURST_PHASE] (fine-tuning/stress reset progress
     * themselves in the original code too).
     */
    val burstProgress: Float
        get() = if (phase == TuningPhase.BURST_PHASE) burstIndex.toFloat() / BURST_INTERVALS_MS.size else 0f

    /** Resets all state and returns the first [TuningAction] to execute. */
    fun start(originalPacingMs: Int, stressTestOnly: Boolean): TuningAction {
        phase = TuningPhase.IDLE
        mutableResults.clear()
        bestPacing = -1
        burstIndex = 0
        failedInterval = -1
        fineTuneCandidate = -1
        originalPacing = originalPacingMs

        return if (stressTestOnly) {
            phase = TuningPhase.STRESS_TEST_ONLY
            TuningAction.RunStress(originalPacingMs)
        } else {
            phase = TuningPhase.BURST_PHASE
            TuningAction.RunBurst(BURST_INTERVALS_MS[0])
        }
    }

    /**
     * Pure success rule from the original `runBurst()`: measured fps against the interval's
     * target fps, plus the "fast enough at low latency anyway" carve-out.
     */
    fun evaluateBurst(interval: Int, isConnected: Boolean, currentFps: Int): TuningResult {
        if (!isConnected) return TuningResult(interval, false, "Disconnected")

        val targetFps = if (interval == 0) 100 else 1000 / interval
        val minAcceptableFps = (targetFps * 0.6).toInt().coerceAtMost(30)
        val success = currentFps >= minAcceptableFps || (currentFps >= 20 && interval <= 20)
        val reason = if (success) "Stable (~$currentFps fps)" else "Bottlenecked (~$currentFps fps)"
        return TuningResult(interval, success, reason)
    }

    /** Pure success rule from the original `runStress()`'s end-of-run connection check. */
    fun evaluateStress(interval: Int, isConnected: Boolean): TuningResult {
        return if (isConnected) {
            TuningResult(interval, true, "Stress Passed")
        } else {
            TuningResult(interval, false, "Disconnected at end")
        }
    }

    /**
     * Feed a burst result (from [evaluateBurst] or a dialog-constructed "Cancelled"/mid-loop
     * result) back into the state machine. Returns what to do next.
     */
    fun onBurstResult(result: TuningResult): TuningAction {
        mutableResults.add(TuningResult(result.interval, result.isSuccess, "Burst: ${result.reason}"))

        return when (phase) {
            TuningPhase.BURST_PHASE -> {
                if (!result.isSuccess) {
                    // First failure in the fixed probe list: switch to incrementing from here.
                    failedInterval = result.interval
                    fineTuneCandidate = failedInterval + 1
                    phase = TuningPhase.FINE_TUNING
                    TuningAction.RunBurst(fineTuneCandidate)
                } else {
                    burstIndex++
                    if (burstIndex < BURST_INTERVALS_MS.size) {
                        TuningAction.RunBurst(BURST_INTERVALS_MS[burstIndex])
                    } else {
                        // Every probed interval passed, including 0ms - stress test starting at 0.
                        failedInterval = -1
                        fineTuneCandidate = 0
                        phase = TuningPhase.STRESS_TEST
                        TuningAction.RunStress(0)
                    }
                }
            }
            TuningPhase.FINE_TUNING -> {
                if (result.isSuccess) {
                    phase = TuningPhase.STRESS_TEST
                    TuningAction.RunStress(fineTuneCandidate)
                } else {
                    fineTuneCandidate++
                    if (fineTuneCandidate > MAX_CANDIDATE_MS) finish() else TuningAction.RunBurst(fineTuneCandidate)
                }
            }
            else -> finish()
        }
    }

    /**
     * Feed a stress result (from [evaluateStress] or a dialog-constructed mid-run
     * "Cancelled"/"Disconnected" result) back into the state machine. Returns what to do next.
     */
    fun onStressResult(result: TuningResult): TuningAction {
        mutableResults.add(TuningResult(result.interval, result.isSuccess, "Stress: ${result.reason}"))

        return when (phase) {
            TuningPhase.STRESS_TEST_ONLY -> {
                if (result.isSuccess) bestPacing = result.interval
                finish()
            }
            TuningPhase.STRESS_TEST -> {
                if (result.isSuccess) {
                    bestPacing = result.interval
                    finish()
                } else if (failedInterval != -1) {
                    // Reached via the fine-tuning path: resume incrementing burst candidates.
                    phase = TuningPhase.FINE_TUNING
                    fineTuneCandidate++
                    if (fineTuneCandidate > MAX_CANDIDATE_MS) finish() else TuningAction.RunBurst(fineTuneCandidate)
                } else {
                    // Reached via the "all bursts passed" path: keep incrementing stress candidates directly.
                    fineTuneCandidate++
                    if (fineTuneCandidate > MAX_CANDIDATE_MS) finish() else TuningAction.RunStress(fineTuneCandidate)
                }
            }
            else -> finish()
        }
    }

    private fun finish(): TuningAction {
        return if (bestPacing != -1) {
            phase = TuningPhase.COMPLETE
            TuningAction.Finished(bestPacing, true)
        } else {
            phase = TuningPhase.FAILED
            TuningAction.Finished(originalPacing, false)
        }
    }
}
