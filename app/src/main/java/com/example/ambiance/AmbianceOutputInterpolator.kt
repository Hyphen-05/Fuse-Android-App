package com.example.ambiance

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.example.core.color.ColorConverter
import com.example.domain.AmbianceCommandSink

/**
 * Fades the strip toward whatever colour ambiance last computed, between captures.
 *
 * Two numbers govern how that looks: how often it ticks (the slowest connected device's write
 * pacing, floored at [MIN_TICK_MS]) and how much of the remaining distance each tick closes. Those
 * used to be coupled - see [easeStep].
 */
class AmbianceOutputInterpolator(
    private val context: Context,
    private val ambianceCommandSink: AmbianceCommandSink
) {

    private var currentLinR: Double = 0.0
    private var currentLinG: Double = 0.0
    private var currentLinB: Double = 0.0

    private var targetLinR: Double = 0.0
    private var targetLinG: Double = 0.0
    private var targetLinB: Double = 0.0

    private var lastWrittenSrgb: Triple<Int, Int, Int>? = null
    private var hasTarget: Boolean = false

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var lastTickTime: Long = 0

    // One repository for the object's lifetime. This used to be constructed inside scheduleTick(),
    // i.e. every 20–100ms for as long as ambiance ran, and each construction opens six
    // SharedPreferences handles.
    private val preferencesRepository = com.example.data.repository.AppPreferencesRepositoryImpl(context)

    fun start() {
        com.example.DiagnosticLogger.log(
            "AmbianceOutputInterpolator",
            "Starting AmbianceOutputInterpolator. Thread initialized."
        )
        lastTickTime = 0L
        handlerThread = HandlerThread("AmbianceOutputInterpolatorThread").apply { start() }
        handler = Handler(handlerThread!!.looper)
        scheduleTick()
    }

    fun stop() {
        com.example.DiagnosticLogger.log(
            "AmbianceOutputInterpolator",
            "Stopping AmbianceOutputInterpolator."
        )
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    fun updateTargetColor(color: Triple<Int, Int, Int>, isSceneCut: Boolean) {
        handler?.post {
            val linR = ColorConverter.srgbToLinear(color.first)
            val linG = ColorConverter.srgbToLinear(color.second)
            val linB = ColorConverter.srgbToLinear(color.third)
            
            targetLinR = linR
            targetLinG = linG
            targetLinB = linB

            if (!hasTarget || isSceneCut) {
                currentLinR = linR
                currentLinG = linG
                currentLinB = linB
                hasTarget = true
                writeIfChanged()
            }
        }
    }

    private fun scheduleTick() {
        val pacingMs = preferencesRepository
            .getPacingPrefInt(SLOWEST_PACING_PREF_KEY, DEFAULT_SLOWEST_PACING_MS)
            .coerceAtLeast(MIN_TICK_MS)

        handler?.postDelayed({
            val now = android.os.SystemClock.elapsedRealtime()
            // The real elapsed time, not the interval we asked for: the handler thread is shared
            // and drifts, and the ease below is only rate-independent if it is told the truth.
            val dtMs = if (lastTickTime == 0L) pacingMs.toLong() else (now - lastTickTime)
            // This used to log every tick. At the old 100ms that was 10 lines a second; at 20ms it
            // would be 50, which drowns the diagnostics file the log exists to make readable. The
            // thread-death it was chasing shows up as a *long* gap, so only long gaps are worth a
            // line.
            if (lastTickTime != 0L && dtMs > pacingMs * 4L) {
                com.example.DiagnosticLogger.log(
                    "AmbianceOutputInterpolator",
                    "Tick stalled: actual=${dtMs}ms, expected=${pacingMs}ms. " +
                        "Thread alive=${handlerThread?.isAlive ?: false}, " +
                        "Looper state=${handlerThread?.looper?.thread?.state?.toString() ?: "null"}"
                )
            }
            lastTickTime = now

            easeStep(dtMs)
            scheduleTick()
        }, pacingMs.toLong())
    }

    /**
     * Closes part of the distance to the target colour.
     *
     * This used to take a flat half of the remaining distance **per tick**, which made the fade
     * duration a function of the tick rate rather than of anything the user chose - so dropping the
     * write pacing, which ticks this five times as often, would silently have made every fade five
     * times faster. `AmbianceOutputRules.easeAlpha` closes the same proportion per unit *time*
     * instead, and at the old 100ms tick with the default 150ms smoothness it comes out at 0.487,
     * so the shipped feel is preserved rather than retuned.
     *
     * Simulation (`AmbianceDarkSceneSimulation`) puts the effect of the pair - this and a lower
     * pacing - at zero visible steps against eleven, with the largest single jump in emitted light
     * falling from 2.9% of full output to 1.1%.
     */
    private fun easeStep(dtMs: Long) {
        if (!hasTarget) return

        val smoothnessMs = preferencesRepository
            .getAmbiancePrefInt(SMOOTHNESS_PREF_KEY, DEFAULT_SMOOTHNESS_MS)
            .coerceAtLeast(10)
        val alpha = AmbianceOutputRules.easeAlpha(dtMs, smoothnessMs)
        currentLinR += alpha * (targetLinR - currentLinR)
        currentLinG += alpha * (targetLinG - currentLinG)
        currentLinB += alpha * (targetLinB - currentLinB)

        writeIfChanged()
    }

    private fun writeIfChanged() {
        val r = ColorConverter.linearToSrgb(currentLinR)
        val g = ColorConverter.linearToSrgb(currentLinG)
        val b = ColorConverter.linearToSrgb(currentLinB)
        val currentSrgb = Triple(r, g, b)

        if (currentSrgb != lastWrittenSrgb) {
            ambianceCommandSink.listener?.writeAmbianceColor(r, g, b)
            lastWrittenSrgb = currentSrgb
        }
    }

    companion object {
        /**
         * The fastest this will tick regardless of pacing. 20ms is 50 ticks a second, which the
         * measured write path delivers comfortably at any pacing below 11ms, and going faster only
         * spends battery on writes that carry no new information.
         */
        const val MIN_TICK_MS = 20
    }
}
