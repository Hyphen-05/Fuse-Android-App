package com.example.core.audio

import com.example.AudioSettingsState
import com.example.core.color.ColorConverter

/**
 * Phase 5, part B: pure, hardware-free port of the DSP pipeline that used to live inline inside
 * `RgbControllerViewModel.runAudioSimulationEngine()`'s `while` loop — a second, hand-duplicated
 * DSP pipeline (randomness-driven, not real captured audio) used as the fallback when the real
 * `AudioRecord`/Visualizer capture backends fail to start. No Android hardware APIs, no
 * `_uiState`/`_telemetry`/`queueCommand` references — those writes still happen in the ViewModel
 * orchestrator, via the same `publishAudioDspResult(AudioDspResult)` helper the real capture
 * pipeline (`AudioDspProcessor`) already uses. This class only produces the [AudioDspResult].
 *
 * Holds per-run mutable DSP state (smoothing accumulators, rolling histories, palette index,
 * auto-gain trackers, idle timer, UI-amplitude normalization trackers) that in the original code
 * were function-local vars living for the duration of one simulation-engine run. Construct a
 * fresh instance per `runAudioSimulationEngine` call to match that reset-on-start behavior.
 *
 * Deliberately NOT unified with [AudioDspProcessor]/[BeatDetector] — this simulates plausible
 * audio energy with `java.util.Random` rather than processing real FFT bins, so it doesn't run
 * real beat detection at all (see the inline `isBeat`/`avgBass` heuristic below, unrelated to
 * [BeatDetector]'s autocorrelation pipeline). Preserved verbatim, not "fixed" or reconciled.
 */
class DemoAudioDspSimulator {
    private val random = java.util.Random()

    private var smoothedBass = 0.0f
    private var smoothedMid = 0.0f
    private var smoothedHigh = 0.0f
    private var maxObservedBass = 10.0f
    private var maxObservedMid = 10.0f

    private val bassHistory = FloatArray(86)
    private var bassHistoryIdx = 0
    private var bassHistorySum = 0.0f
    private var bassHistoryCount = 0
    private var lastBeatTime = 0L
    private var beatPulsePeak = 0.0f
    private var lastBeatFlashTime = 0L
    private var currentPaletteIndex = 0
    private val palettes = arrayOf(0f, 60f, 120f, 180f, 240f, 300f)

    private var smoothedHue = 0.0f

    private val uiAmplitudes8 = FloatArray(8)
    private var silenceStartTime = 0L

    // Sustained energy section-change variables
    private val energyWindowSize = 250 // ~6 seconds
    private val energyHistory = FloatArray(energyWindowSize)
    private var energyHistoryIdx = 0
    private var energyHistorySum = 0.0f
    private var energyHistorySqSum = 0.0f
    private var energyHistoryCount = 0

    private val shortWindowSize = 43 // ~1 second
    private val shortHistory = FloatArray(shortWindowSize)
    private var shortHistoryIdx = 0
    private var shortHistorySum = 0.0f
    private var shortHistoryCount = 0

    fun process(state: AudioSettingsState): AudioDspResult {
        val startTime = System.currentTimeMillis()
        val timeSec = startTime / 1000.0

        // Simulate realistic rhythmic energy (120 BPM tempo = 500ms intervals)
        val isBeatInstant = (startTime % 500) < 60
        val rawBass = if (isBeatInstant) {
            (40.0f + random.nextFloat() * 20.0f) * state.bassGain
        } else {
            (5.0f + random.nextFloat() * 5.0f) * state.bassGain
        }

        val rawMid = (15.0f + Math.sin(timeSec * 3.0).toFloat() * 10.0f + random.nextFloat() * 5.0f) * state.midGain
        val rawHigh = (5.0f + Math.cos(timeSec * 5.0).toFloat() * 4.0f + random.nextFloat() * 3.0f) * state.highGain

        val totalEnergy = rawBass + rawMid + rawHigh

        // 1. Add totalEnergy to 6-second window for mean and variance
        val oldEnergy = energyHistory[energyHistoryIdx]
        energyHistory[energyHistoryIdx] = totalEnergy
        energyHistorySum = energyHistorySum - oldEnergy + totalEnergy
        energyHistorySqSum = energyHistorySqSum - (oldEnergy * oldEnergy) + (totalEnergy * totalEnergy)
        energyHistoryIdx = (energyHistoryIdx + 1) % energyWindowSize
        if (energyHistoryCount < energyWindowSize) energyHistoryCount++

        val rollingMean = if (energyHistoryCount > 0) energyHistorySum / energyHistoryCount else 0.0f
        val rollingMeanSq = if (energyHistoryCount > 0) energyHistorySqSum / energyHistoryCount else 0.0f
        val rollingVariance = maxOf(0.0f, rollingMeanSq - (rollingMean * rollingMean))

        // 2. Add totalEnergy to 1-second window for detecting sustain
        val oldShort = shortHistory[shortHistoryIdx]
        shortHistory[shortHistoryIdx] = totalEnergy
        shortHistorySum = shortHistorySum - oldShort + totalEnergy
        shortHistoryIdx = (shortHistoryIdx + 1) % shortWindowSize
        if (shortHistoryCount < shortWindowSize) shortHistoryCount++

        val shortMean = if (shortHistoryCount > 0) shortHistorySum / shortHistoryCount else 0.0f

        val sustainThreshold = maxOf(35.0f, state.noiseGateThreshold * 5.0f)
        val isSustainedSection = (shortMean > sustainThreshold) && (rollingVariance > 5.0f)

        val bassAlpha = if (rawBass > smoothedBass) state.audioSmoothingAttack else state.audioSmoothingDecay
        smoothedBass = bassAlpha * rawBass + (1f - bassAlpha) * smoothedBass

        val midAlpha = if (rawMid > smoothedMid) state.audioSmoothingAttack else state.audioSmoothingDecay
        smoothedMid = midAlpha * rawMid + (1f - midAlpha) * smoothedMid

        val highAlpha = if (rawHigh > smoothedHigh) state.audioSmoothingAttack else state.audioSmoothingDecay
        smoothedHigh = highAlpha * rawHigh + (1f - highAlpha) * smoothedHigh

        val oldBass = bassHistory[bassHistoryIdx]
        bassHistory[bassHistoryIdx] = rawBass
        bassHistorySum = bassHistorySum - oldBass + rawBass
        bassHistoryIdx = (bassHistoryIdx + 1) % 86
        if (bassHistoryCount < 86) bassHistoryCount++
        val avgBass = if (bassHistoryCount > 0) bassHistorySum / bassHistoryCount else 0.0f

        val now = System.currentTimeMillis()
        val isBeat = rawBass > avgBass * state.beatThresholdMultiplier && rawBass > state.noiseGateThreshold && (now - lastBeatTime > state.beatCooldownMs)
        if (isBeat) {
            lastBeatTime = now
            if (state.isPaletteCyclingEnabled) {
                currentPaletteIndex = (currentPaletteIndex + 1) % palettes.size
            }
            beatPulsePeak = 1.0f
            lastBeatFlashTime = now
        }

        // Simulate vocal/melody sweeping dominant frequency
        val dominantFreq = 50.0f + Math.abs(Math.sin(timeSec * 0.5) * 2000.0).toFloat() + random.nextFloat() * 100.0f

        // Map dominant frequency to HSV Hue using band-range constraints
        val bandMinF: Float
        val bandMaxF: Float
        val minHue: Float
        val maxHue: Float
        if (dominantFreq < 150.0f) {
            bandMinF = 20.0f
            bandMaxF = 150.0f
            minHue = 0.0f
            maxHue = 40.0f
        } else if (dominantFreq < 2000.0f) {
            bandMinF = 150.0f
            bandMaxF = 2000.0f
            minHue = 80.0f
            maxHue = 160.0f
        } else {
            bandMinF = 2000.0f
            bandMaxF = maxOf(2000.0f, dominantFreq, 10000.0f)
            minHue = 200.0f
            maxHue = 280.0f
        }

        var hue = if (state.isLogarithmicScalingEnabled) {
            val logMin = Math.log10(bandMinF.toDouble())
            val logMax = Math.log10(bandMaxF.toDouble())
            val freq = dominantFreq.coerceIn(bandMinF, bandMaxF)
            val logFreq = Math.log10(freq.toDouble())
            (minHue + ((logFreq - logMin) / (logMax - logMin)) * (maxHue - minHue)).toFloat().coerceIn(minHue, maxHue)
        } else {
            val freq = dominantFreq.coerceIn(bandMinF, bandMaxF)
            (minHue + ((freq - bandMinF) / (bandMaxF - bandMinF)) * (maxHue - minHue)).toFloat().coerceIn(minHue, maxHue)
        }

        // Shift band-hue-range boundaries by 120 degrees if in sustained section
        if (isSustainedSection) {
            hue = (hue + 120.0f) % 360f
        }

        if (state.isPaletteCyclingEnabled) {
            hue = (hue + palettes[currentPaletteIndex]) % 360f
        }

        // Smoothly interpolate hue only when not experiencing a beat pulse spike using circular smoothing
        val targetRad = Math.toRadians(hue.toDouble())
        val targetX = Math.cos(targetRad).toFloat()
        val targetY = Math.sin(targetRad).toFloat()

        val smoothedRad = Math.toRadians(smoothedHue.toDouble())
        val curSmoothedX = Math.cos(smoothedRad).toFloat()
        val curSmoothedY = Math.sin(smoothedRad).toFloat()

        val nextX: Float
        val nextY: Float
        if (isBeat) {
            nextX = targetX
            nextY = targetY
        } else {
            val alpha = (0.10f * state.visualizerColorSpeed).coerceIn(0.01f, 1.0f)
            nextX = alpha * targetX + (1f - alpha) * curSmoothedX
            nextY = alpha * targetY + (1f - alpha) * curSmoothedY
        }

        val recoveredHueRad = Math.atan2(nextY.toDouble(), nextX.toDouble())
        smoothedHue = ((Math.toDegrees(recoveredHueRad).toFloat() + 360f) % 360f)

        if (state.isAutoGainEnabled) {
            // Aggressive dual-rate auto-gain using rawBass instead of smoothedBass to maintain snappiness
            val decayFactorBass = if (rawBass < avgBass) 0.97f else 0.992f
            maxObservedBass = maxOf(maxObservedBass * decayFactorBass, rawBass, 10.0f)

            val decayFactorMid = if (smoothedMid < maxObservedMid * 0.5f) 0.97f else 0.992f
            maxObservedMid = maxOf(maxObservedMid * decayFactorMid, smoothedMid, 10.0f)
        } else {
            maxObservedBass = 100.0f
            maxObservedMid = 100.0f
        }

        val sat = if (state.isAutoGainEnabled) {
            (0.4f + 0.6f * (smoothedMid / maxObservedMid)).coerceIn(0.4f, 1.0f)
        } else {
            (smoothedMid / 100.0f).coerceIn(0.0f, 1.0f)
        }

        val sensitivityMultiplier = (state.musicSensitivity / 50.0f)

        var valBase: Float
        val bassFloor = 0.8f * avgBass
        val rangeBass = maxOf(maxObservedBass - bassFloor, 5.0f)
        valBase = if (state.isAutoGainEnabled) {
            ((smoothedBass - bassFloor) / rangeBass).coerceIn(0.0f, 1.0f)
        } else {
            (smoothedBass / 100.0f).coerceIn(0.0f, 1.0f)
        }
        if (smoothedBass < state.noiseGateThreshold) {
            valBase = 0.0f
        } else {
            valBase = Math.pow(valBase.toDouble(), 2.0).toFloat()
        }

        var value: Float
        if (state.visualizerPreset == "Beat Only") {
            val elapsedMs = now - lastBeatFlashTime
            val t = elapsedMs.toFloat() / state.beatFlashDecayMs
            val beatEnvelope = maxOf(1f - t * t, 0f) * beatPulsePeak
            value = maxOf(state.visualizerMinBrightness, (beatEnvelope * maxOf(0.5f, state.audioFlashStrength)).coerceIn(0f, 1f))
        } else {
            var baseValVal = (valBase * sensitivityMultiplier).coerceIn(0.0f, 1.0f)
            baseValVal = Math.pow(baseValVal.toDouble(), state.audioGammaExponent.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
            val ambientLevel = baseValVal * state.ambientCapFraction
            val elapsedMs = now - lastBeatFlashTime
            val t = elapsedMs.toFloat() / state.beatFlashDecayMs
            val beatEnvelope = maxOf(1f - t * t, 0f) * beatPulsePeak
            val beatFlash = beatEnvelope * state.audioFlashStrength
            value = maxOf(state.visualizerMinBrightness, (ambientLevel + beatFlash).coerceIn(0f, 1f))
        }

        val (r, g, b) = ColorConverter.hsvToRgb(smoothedHue, sat, value)

        val isBelow = totalEnergy < state.noiseGateThreshold
        val isIdle: Boolean
        if (isBelow) {
            if (silenceStartTime == 0L) {
                silenceStartTime = startTime
            }
            isIdle = (startTime - silenceStartTime) > state.idleTriggerDelayMs
        } else {
            silenceStartTime = 0L
            isIdle = false
        }

        val normalizedAmplitudesList = if (isIdle) {
            val pulseVal = 0.15f + 0.10f * Math.sin(2.0 * Math.PI * timeSec / 4.0).toFloat()
            List(8) { pulseVal.coerceIn(0.05f, 1.0f) }
        } else if (isBelow) {
            List(8) { 0.05f }
        } else {
            for (band in 0 until 8) {
                val baseVal = if (band in 0..1) {
                    if (isBeatInstant) 0.8f + random.nextFloat() * 0.2f else 0.1f + random.nextFloat() * 0.1f
                } else if (band in 2..5) {
                    0.2f + Math.sin(timeSec * 2.0 + band).toFloat() * 0.15f + random.nextFloat() * 0.1f
                } else {
                    0.1f + Math.cos(timeSec * 4.0 + band).toFloat() * 0.1f + random.nextFloat() * 0.05f
                }
                uiAmplitudes8[band] = baseVal.coerceIn(0.05f, 1.0f)
            }
            uiAmplitudes8.toList()
        }

        return AudioDspResult(
            r = r,
            g = g,
            b = b,
            hue = smoothedHue,
            amplitudes = normalizedAmplitudesList,
            isIdle = isIdle
        )
    }
}
