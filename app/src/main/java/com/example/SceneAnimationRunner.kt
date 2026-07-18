package com.example

import com.example.core.animation.ProceduralSceneParams

import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SceneAnimationRunner(
    private val macAddresses: List<String>,
    private val sequence: ProceduralSceneParams,
    private val sendCommand: suspend (String, ByteArray) -> Unit,
    private val saveState: suspend (String, Boolean, Int, Int, Int, Int) -> Unit
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val updateIntervalMs = 50L

    // Tuning constants pending on-device feel testing
    private val MAX_SMOOTH_TRANSITION_MS = 90000L // placeholder pending on-device feel testing

    private val CALM_MOOD_MULTIPLIER = 0.5f
    private val ENERGETIC_MOOD_MULTIPLIER = 1.5f
    private val DEFAULT_MOOD_MULTIPLIER = 1.0f

    private val CALM_ACCENT_BIAS = 2.0f
    private val CALM_SNAP_BIAS = 0.5f
    private val CALM_PULSE_BIAS = 0.5f

    private val ENERGETIC_ACCENT_BIAS = 0.5f
    private val ENERGETIC_SNAP_BIAS = 1.5f
    private val ENERGETIC_PULSE_BIAS = 1.5f

    private fun isCycleMode(): Boolean {
        return when (sequence.sequenceMode) {
            "cycle" -> true
            "ping-pong" -> false
            else -> sequence.randomness < 0.3f
        }
    }

    fun start() {
        stop()
        activeEvent = null
        activeEventTicksRemaining = 0
        if (sequence.palette.isEmpty()) return

        com.example.DiagnosticLogger.log(
            "SceneAnimationRunner",
            "Starting animation runner. Mode=${if (isCycleMode()) "loop" else "ping-pong"}. Interval=${updateIntervalMs}ms. MacAddresses=$macAddresses"
        )

        job = scope.launch {
            try {
                var currentRgb = sequence.palette.first()
                var nextRgb = pickNextColor(currentRgb)
                
                macAddresses.forEach { mac -> sendCommand(mac, DuoCoProtocol.createMusicColorCommand(currentRgb.first, currentRgb.second, currentRgb.third)) }
                macAddresses.forEach { mac -> saveState(mac, true, currentRgb.first, currentRgb.second, currentRgb.third, 50) }

                var timeElapsedMs = 0L
                val modeStr = if (isCycleMode()) "loop" else "ping-pong"

                while (isActive) {
                    var cycleElapsedMs = 0L
                    val transitionTimeMs = determineTransitionTime()
                    val holdTimeMs = determineHoldTime()
                    val cycleTotalMs = transitionTimeMs + holdTimeMs

                    if (transitionTimeMs <= 0) {
                        currentRgb = nextRgb
                        macAddresses.forEach { mac -> sendCommand(mac, DuoCoProtocol.createMusicColorCommand(currentRgb.first, currentRgb.second, currentRgb.third)) }
                    } else {
                        val stepsCount = (transitionTimeMs / updateIntervalMs).coerceAtLeast(1)
                        val startRgb = currentRgb
                        
                        for (i in 1..stepsCount) {
                            if (!isActive) break
                            
                            val rawProgress = i.toFloat() / stepsCount
                            val progress = applyTransitionEasing(rawProgress)
                            
                            val (rBase, gBase, bBase) = interpolateHsv(startRgb, nextRgb, progress)
                            
                            val rawBrightFactor = calculateBrightness(cycleElapsedMs, cycleTotalMs)
                            val brightFactor = applyColorSyncAndHarmonics(rawBrightFactor, rBase, gBase, bBase, timeElapsedMs)
                            
                            // Handle rare event overlay
                            triggerRareEventIfNeeded()
                            val (r, g, b) = applyRareEvent(rBase, gBase, bBase, brightFactor)

                            macAddresses.forEach { mac -> sendCommand(mac, DuoCoProtocol.createMusicColorCommand(r, g, b)) }
                            
                            val beforeDelay = android.os.SystemClock.elapsedRealtime()
                            delay(updateIntervalMs)
                            val afterDelay = android.os.SystemClock.elapsedRealtime()
                            val observed = afterDelay - beforeDelay
                            if (abs(observed - updateIntervalMs) > 20) {
                                com.example.DiagnosticLogger.log(
                                    "SceneAnimationRunner",
                                    "Tick interval observed: ${observed}ms (expected: ${updateIntervalMs}ms). Mode=$modeStr"
                                )
                            }
                            
                            timeElapsedMs += updateIntervalMs
                            cycleElapsedMs += updateIntervalMs
                        }
                        currentRgb = nextRgb
                    }

                    macAddresses.forEach { mac -> saveState(mac, true, currentRgb.first, currentRgb.second, currentRgb.third, 50) }
                    
                    val holdStepsCount = (holdTimeMs / updateIntervalMs).coerceAtLeast(1)
                    for (i in 1..holdStepsCount) {
                        if (!isActive) break
                        
                        val rawBrightFactor = calculateBrightness(cycleElapsedMs, cycleTotalMs)
                        val brightFactor = applyColorSyncAndHarmonics(rawBrightFactor, currentRgb.first, currentRgb.second, currentRgb.third, timeElapsedMs)
                        triggerRareEventIfNeeded()
                        val (r, g, b) = applyRareEvent(currentRgb.first, currentRgb.second, currentRgb.third, brightFactor)
                        
                        if (sequence.brightnessBehaviour != "stable" || sequence.harmonicLayering != "none" || hasActiveRareEvent()) {
                            macAddresses.forEach { mac -> sendCommand(mac, DuoCoProtocol.createMusicColorCommand(r, g, b)) }
                        }
                        
                        val beforeDelay = android.os.SystemClock.elapsedRealtime()
                        delay(updateIntervalMs)
                        val afterDelay = android.os.SystemClock.elapsedRealtime()
                        val observed = afterDelay - beforeDelay
                        if (abs(observed - updateIntervalMs) > 20) {
                            com.example.DiagnosticLogger.log(
                                "SceneAnimationRunner",
                                "Tick interval observed: ${observed}ms (expected: ${updateIntervalMs}ms). Mode=$modeStr"
                            )
                        }
                        
                        timeElapsedMs += updateIntervalMs
                        cycleElapsedMs += updateIntervalMs
                    }
                    
                    nextRgb = pickNextColor(currentRgb)
                }
            } catch (e: Exception) {
                com.example.DiagnosticLogger.log(
                    "SceneAnimationRunner",
                    "Exception inside SceneAnimationRunner loop: ${android.util.Log.getStackTraceString(e)}"
                )
                stop()
            }
        }
    }

    private fun interpolateHsv(startRgb: Triple<Int, Int, Int>, nextRgb: Triple<Int, Int, Int>, progress: Float): Triple<Int, Int, Int> {
        val hsvStart = FloatArray(3)
        val hsvNext = FloatArray(3)
        android.graphics.Color.RGBToHSV(startRgb.first, startRgb.second, startRgb.third, hsvStart)
        android.graphics.Color.RGBToHSV(nextRgb.first, nextRgb.second, nextRgb.third, hsvNext)
        
        var hStart = hsvStart[0]
        var hNext = hsvNext[0]
        
        // Shortest path for Hue
        if (abs(hNext - hStart) > 180f) {
            if (hNext > hStart) {
                hStart += 360f
            } else {
                hNext += 360f
            }
        }
        
        val hBase = ((hStart + (hNext - hStart) * progress) % 360f).let { if (it < 0) it + 360f else it }
        val sBase = hsvStart[1] + (hsvNext[1] - hsvStart[1]) * progress
        val vBase = hsvStart[2] + (hsvNext[2] - hsvStart[2]) * progress
        
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hBase, sBase, vBase))
        return Triple(
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }

    private fun pickNextColor(currentRgb: Triple<Int, Int, Int>): Triple<Int, Int, Int> {
        val palette = sequence.palette
        if (palette.size == 1) return palette.first()
        
        // If low randomness, just cycle through the palette sequentially
        if (isCycleMode()) {
            val currentIndex = palette.indexOf(currentRgb)
            return if (currentIndex != -1) {
                palette[(currentIndex + 1) % palette.size]
            } else {
                palette.first()
            }
        }
        
        // Use randomness to either pick the next dominant or a wild accent
        val isAccent = Random.nextFloat() < (sequence.randomness * 0.3f) // max 30% chance for accent
        
        return if (isAccent && palette.size > 2) {
            // Pick a random color from the latter half of the palette (accents)
            palette.subList(palette.size / 2, palette.size).random()
        } else {
            // Pick a dominant color
            val dominantCount = if (palette.size <= 3) palette.size else (palette.size / 2).coerceAtLeast(1)
            val dominants = palette.subList(0, dominantCount)
            // Try to not repeat the exact same color if possible
            val options = dominants.filter { it != currentRgb }
            if (options.isNotEmpty()) options.random() else dominants.random()
        }
    }

    private fun speedScale(energy: Float): Double {
        val t = 1.0 - energy.toDouble().coerceIn(0.0, 1.0)
        // 0.06x (very slow) at energy=0 up to 1.0x (normal) at energy=1
        return Math.pow(0.06, t)
    }

    private fun expInterp(minMs: Long, maxMs: Long, energy: Float): Long {
        val scale = speedScale(energy)
        val curve = (1.0 - scale) / (1.0 - 0.06)
        return (minMs + (maxMs - minMs) * curve).toLong()
    }

    private fun determineTransitionTime(): Long {
        val behaviour = sequence.colorInterpMode.lowercase()
        if (behaviour == "stepped" || behaviour == "snap" || behaviour == "instant") {
            return 0L
        }
        val (minMs, maxMs) = when (behaviour) {
            "cosine", "smooth", "variable fade" -> Pair(1500L, MAX_SMOOTH_TRANSITION_MS)
            "linear", "fast flash" -> Pair(1000L, 15000L)
            else -> Pair(1000L, 15000L)
        }
        var finalTime = expInterp(minMs, maxMs, sequence.energy)

        // Apply always-on baseline jitter
        if (finalTime > 0) {
            val baselineJitterFactor = 0.9f + Random.nextFloat() * 0.2f  // ±10%
            finalTime = (finalTime * baselineJitterFactor).toLong()
        }

        return if (sequence.randomness > 0.5f && finalTime > 0) {
            val variance = (finalTime * 0.5f * sequence.randomness).toLong().coerceAtLeast(1L)
            (finalTime - variance/2 + Random.nextLong(variance)).coerceAtLeast(50L)
        } else {
            finalTime
        }
    }

    private fun determineHoldTime(): Long {
        val (minMs, maxMs) = when(sequence.movementType.lowercase()) {
            "drift", "wander", "calm" -> Pair(2000L, 45000L)
            "pulse", "breathe", "wave" -> Pair(400L, 20000L)
            "flicker", "spark", "chaotic", "storm" -> Pair(80L, 5000L)
            "glitch" -> Pair(50L, 2000L)
            "strobe" -> Pair(50L, 500L)
            "beacon" -> Pair(3000L, 30000L)
            "heartbeat" -> Pair(600L, 4000L)
            else -> {
                com.example.DiagnosticLogger.log("SceneAnimationRunner", "Unrecognized movementType value: '${sequence.movementType}', falling back to default hold range")
                Pair(1000L, 20000L)
            }
        }
        
        var finalTime = expInterp(minMs, maxMs, sequence.energy)

        // Apply always-on baseline jitter
        if (finalTime > 0) {
            val baselineJitterFactor = 0.9f + Random.nextFloat() * 0.2f  // ±10%
            finalTime = (finalTime * baselineJitterFactor).toLong()
        }
        
        return if (sequence.randomness > 0.2f && finalTime > 0) {
            val variance = (finalTime * sequence.randomness).toLong().coerceAtLeast(1L)
            (finalTime - variance/2 + Random.nextLong(variance)).coerceAtLeast(0L)
        } else {
            finalTime
        }
    }

    private fun applyTransitionEasing(progress: Float): Float {
        return when (sequence.colorInterpMode.lowercase()) {
            "cosine", "eased", "smooth" -> progress * progress * (3 - 2 * progress) // smoothstep
            "linear" -> progress
            else -> progress
        }
    }

    private fun applyColorSyncAndHarmonics(baseBrightness: Float, r: Int, g: Int, b: Int, timeMs: Long): Float {
        var result = baseBrightness
        if (sequence.syncBrightnessToColor) {
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            result *= (0.4f + luminance * 0.6f)
        }
        if (sequence.harmonicLayering != "none") {
            val noise = when (sequence.harmonicLayering.lowercase()) {
                "subtle-noise" -> Random.nextFloat() * 0.1f - 0.05f
                "syncopated" -> if (timeMs % 400 < 100) 0.2f else -0.1f
                else -> 0f
            }
            result = (result + noise).coerceIn(0f, 1f)
        }
        return result
    }

    private fun calculateBrightness(cycleElapsedMs: Long, cycleTotalMs: Long): Float {
        val baseBrightness = 1.0f
        
        if (sequence.brightnessBehaviour == "stable") return baseBrightness
        
        val progress = if (cycleTotalMs > 0) 
            (cycleElapsedMs.toDouble() / cycleTotalMs.toDouble()).coerceIn(0.0, 1.0) 
        else 0.0
        
        return when(sequence.brightnessBehaviour.lowercase()) {
            "breathe", "pulse" -> {
                val cycle = sin(progress * Math.PI)
                0.4f + (cycle * cycle * 0.6f).toFloat() // 0.4 to 1.0
            }
            "flicker", "storm" -> {
                if (Random.nextFloat() < (sequence.energy * 0.5f + 0.1f)) {
                    Random.nextFloat() * 0.5f + 0.5f
                } else {
                    1.0f
                }
            }
            "drift" -> {
                val cycle = sin(progress * Math.PI)
                0.7f + (cycle * 0.3f).toFloat() // 0.4 to 1.0
            }
            "ember" -> {
                0.2f + Random.nextFloat() * 0.3f + (sin(progress * Math.PI * 4.0) * 0.1f).toFloat()
            }
            "clouds" -> {
                val primarySin = sin(progress * Math.PI * 2.0)
                val secondarySin = sin(progress * Math.PI * 5.0)
                val noise = Random.nextFloat() * 0.1f - 0.05f
                val layered = 0.75f + (primarySin * 0.15f).toFloat() + (secondarySin * 0.05f).toFloat() + noise
                layered.coerceIn(0.5f, 1.0f)
            }
            "generator" -> {
                val triangle = if (progress < 0.5) progress * 2.0 else (1.0 - progress) * 2.0
                0.4f + (triangle * 0.6f).toFloat()
            }
            else -> {
                com.example.DiagnosticLogger.log("SceneAnimationRunner", "Unrecognized brightnessBehaviour value: '${sequence.brightnessBehaviour}', falling back to stable brightness")
                baseBrightness
            }
        }
    }

    // --- Rare Events State ---
    private sealed class RareEvent {
        abstract val totalTicks: Int
        
        class AccentFlash(override val totalTicks: Int, val accentColor: Triple<Int, Int, Int>?, val blendStrength: Float) : RareEvent()
        class SnapBlackReturn(override val totalTicks: Int, val dipDepth: Float) : RareEvent()
        class DoublePulseBrighten(override val totalTicks: Int, val pulseStrength: Float) : RareEvent()
    }

    private var activeEvent: RareEvent? = null
    private var activeEventTicksRemaining = 0
    
    private fun applyRareEvent(r: Int, g: Int, b: Int, brightness: Float): Triple<Int, Int, Int> {
        val event = activeEvent
        if (activeEventTicksRemaining > 0 && event != null) {
            val tickIndex = event.totalTicks - activeEventTicksRemaining
            activeEventTicksRemaining--
            if (activeEventTicksRemaining <= 0) {
                activeEvent = null
            }
            
            return when (event) {
                is RareEvent.AccentFlash -> {
                    val accent = event.accentColor
                    if (accent != null) {
                        val progress = if (event.totalTicks > 1) {
                            tickIndex.toFloat() / (event.totalTicks - 1)
                        } else {
                            0.5f
                        }
                        val currentStrength = event.blendStrength * (1.0f - abs(progress - 0.5f) * 2.0f)
                        val invStrength = 1.0f - currentStrength
                        val blendedR = (accent.first * currentStrength + r * invStrength)
                        val blendedG = (accent.second * currentStrength + g * invStrength)
                        val blendedB = (accent.third * currentStrength + b * invStrength)
                        Triple(
                            (blendedR * brightness).toInt().coerceIn(0, 255),
                            (blendedG * brightness).toInt().coerceIn(0, 255),
                            (blendedB * brightness).toInt().coerceIn(0, 255)
                        )
                    } else {
                        Triple(
                            (r * brightness).toInt().coerceIn(0, 255),
                            (g * brightness).toInt().coerceIn(0, 255),
                            (b * brightness).toInt().coerceIn(0, 255)
                        )
                    }
                }
                is RareEvent.SnapBlackReturn -> {
                    val progress = if (event.totalTicks > 1) {
                        tickIndex.toFloat() / (event.totalTicks - 1)
                    } else {
                        0.5f
                    }
                    val multiplier = 1.0f - event.dipDepth * (1.0f - abs(progress - 0.5f) * 2.0f)
                    val finalBrightness = brightness * multiplier
                    Triple(
                        (r * finalBrightness).toInt().coerceIn(0, 255),
                        (g * finalBrightness).toInt().coerceIn(0, 255),
                        (b * finalBrightness).toInt().coerceIn(0, 255)
                    )
                }
                is RareEvent.DoublePulseBrighten -> {
                    val progress = if (event.totalTicks > 1) {
                        tickIndex.toFloat() / (event.totalTicks - 1)
                    } else {
                        0.5f
                    }
                    val multiplier = 1.0f + event.pulseStrength * abs(sin(progress * 2.0 * Math.PI)).toFloat()
                    val finalBrightness = brightness * multiplier
                    Triple(
                        (r * finalBrightness).toInt().coerceIn(0, 255),
                        (g * finalBrightness).toInt().coerceIn(0, 255),
                        (b * finalBrightness).toInt().coerceIn(0, 255)
                    )
                }
            }
        }
        
        return Triple(
            (r * brightness).toInt().coerceIn(0, 255),
            (g * brightness).toInt().coerceIn(0, 255),
            (b * brightness).toInt().coerceIn(0, 255)
        )
    }
    
    private fun hasActiveRareEvent() = activeEventTicksRemaining > 0

    private fun triggerRareEventIfNeeded() {
        if (hasActiveRareEvent()) return
        
        val brightnessBehaviour = sequence.brightnessBehaviour.lowercase()
        val isCalm = brightnessBehaviour in listOf("ember", "drift", "breathe", "clouds", "stable")
        val isEnergetic = brightnessBehaviour in listOf("storm", "flicker", "pulse", "generator")
        
        val moodMultiplier = when {
            isCalm -> CALM_MOOD_MULTIPLIER
            isEnergetic -> ENERGETIC_MOOD_MULTIPLIER
            else -> DEFAULT_MOOD_MULTIPLIER
        }
        
        val prob = sequence.randomness * 0.05f * sequence.energy * moodMultiplier
        
        if (Random.nextFloat() < prob) {
            val randomness = sequence.randomness.coerceIn(0f, 1f)
            
            val accentBias = when {
                isCalm -> CALM_ACCENT_BIAS
                isEnergetic -> ENERGETIC_ACCENT_BIAS
                else -> 1.0f
            }
            val snapBias = when {
                isCalm -> CALM_SNAP_BIAS
                isEnergetic -> ENERGETIC_SNAP_BIAS
                else -> 1.0f
            }
            val pulseBias = when {
                isCalm -> CALM_PULSE_BIAS
                isEnergetic -> ENERGETIC_PULSE_BIAS
                else -> 1.0f
            }
            
            val accentWeight = (1.0f - randomness) * accentBias
            val snapWeight = (randomness * 0.5f) * snapBias
            val pulseWeight = (randomness * 0.5f) * pulseBias
            
            val totalWeight = accentWeight + snapWeight + pulseWeight
            val r = Random.nextFloat() * totalWeight
            
            val isSmooth = sequence.colorInterpMode.lowercase() in listOf("cosine", "smooth", "variable fade", "eased") || sequence.movementType.lowercase() in listOf("drift", "wander", "calm")
            
            val durationMultiplier = if (isSmooth) 10.0f else 1.0f
            val blendStrength = if (isSmooth) 0.3f else 0.8f
            val dipDepth = if (isSmooth) 0.3f else 0.95f
            val pulseStrength = if (isSmooth) 0.15f else 0.4f
            
            val multiplier = (1.0f / speedScale(sequence.energy).toFloat()).coerceIn(1f, 8f) * durationMultiplier
            
            val event = when {
                r < accentWeight -> {
                    val duration = ((2..6).random() * multiplier).toInt()
                    val palette = sequence.palette
                    val accent = if (palette.size >= 2) {
                        palette.subList(palette.size / 2, palette.size).random()
                    } else {
                        null
                    }
                    RareEvent.AccentFlash(duration, accent, blendStrength)
                }
                r < accentWeight + snapWeight -> {
                    RareEvent.SnapBlackReturn(((3..8).random() * multiplier).toInt(), dipDepth)
                }
                else -> {
                    RareEvent.DoublePulseBrighten(((4..10).random() * multiplier).toInt(), pulseStrength)
                }
            }
            activeEvent = event
            activeEventTicksRemaining = event.totalTicks
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}

