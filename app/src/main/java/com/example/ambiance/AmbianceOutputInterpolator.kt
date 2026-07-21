package com.example.ambiance

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.example.core.color.ColorConverter
import com.example.domain.AmbianceCommandSink

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
        val pacingMs = com.example.data.repository.AppPreferencesRepositoryImpl(context)
            .getPacingPrefInt("slowest_connected_pacing", 100)
            .coerceAtLeast(20)

        handler?.postDelayed({
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastTickTime != 0L) {
                val delta = now - lastTickTime
                val expected = pacingMs
                val drift = delta - expected
                val isAlive = handlerThread?.isAlive ?: false
                val looperState = handlerThread?.looper?.thread?.state?.toString() ?: "null"
                com.example.DiagnosticLogger.log(
                    "AmbianceOutputInterpolator",
                    "Tick: actual=${delta}ms, expected=${expected}ms, drift=${drift}ms. Thread alive=$isAlive, Looper state=$looperState"
                )
            }
            lastTickTime = now

            easeStep()
            scheduleTick()
        }, pacingMs.toLong())
    }

    private fun easeStep() {
        if (!hasTarget) return

        currentLinR += 0.5 * (targetLinR - currentLinR)
        currentLinG += 0.5 * (targetLinG - currentLinG)
        currentLinB += 0.5 * (targetLinB - currentLinB)

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
}
