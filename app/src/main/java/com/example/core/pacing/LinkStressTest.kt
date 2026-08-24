package com.example.core.pacing

/**
 * What a 120-second sustained-write run concluded about one device's link.
 *
 * [inFlightMs] and [achievedFps] are measurements, not criteria — [passed] is still only "the link
 * was still connected at the end", which is the same rule `PacingAutoTuneEngine.evaluateStress`
 * applied and the same one IMPROVEMENT_PLAN.md Tier E Phase 3 flags as the weakest of the three
 * failure modes: it catches disconnection, not queue backup and not strip-side drops. They are
 * reported because they are the numbers worth reading afterwards, not because they gate the result.
 */
data class StressResult(
    val passed: Boolean,
    val reason: String,
    val achievedFps: Int,
    val inFlightMs: Double
)

/**
 * The 120s sustained-write diagnostic, kept from `PacingAutoTuneEngine` when Tier E Phase 3 step 4
 * removed everything else about it.
 *
 * The rest of that engine — the 100/80/50/30/20/10/5/0ms burst probe, the fine-tune-upward loop and
 * the sequencing between them — existed to arrive at a pacing value to store in a pref. There is no
 * pacing pref any more (the radio is the pacer; see `DeviceWriteManager.tryWrite`), so there is
 * nothing for a tuning run to produce. What survives is the part that answers a question still
 * worth asking: does this link survive two minutes of writes at full rate?
 *
 * Pure and hardware-free for the same reason its predecessor was: the coroutine, the `delay`s and
 * the BLE/telemetry reads stay in the dialog, which feeds real measurements in here.
 */
object LinkStressTest {

    const val DURATION_MS = 120_000L

    /** How often the send loop checks progress and liveness, in iterations. */
    const val PROGRESS_CHECK_EVERY = 20

    /**
     * Delay between queued commands during the run. 1ms is "as fast as the loop can offer them",
     * which is the point: with the artificial pacing wait gone the queue coalesces whatever the
     * radio cannot take, so the stress worth applying is full rate. This is what the old engine's
     * `0ms` probe did.
     */
    const val SEND_DELAY_MS = 1L

    /** Verdict for a run that reached the end. */
    fun evaluate(isConnected: Boolean, achievedFps: Int, inFlightMs: Double): StressResult =
        if (isConnected) {
            StressResult(true, "Survived 120s at full rate", achievedFps, inFlightMs)
        } else {
            StressResult(false, "Disconnected at end", achievedFps, inFlightMs)
        }

    /** Verdict for a run that ended early — cancelled by the user, or dropped mid-run. */
    fun aborted(reason: String, achievedFps: Int, inFlightMs: Double): StressResult =
        StressResult(false, reason, achievedFps, inFlightMs)
}
