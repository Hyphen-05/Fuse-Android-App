package com.example.core.audio

import com.example.AudioSettingsState
import com.example.DiagnosticLogger
import com.example.core.color.ColorConverter
import com.example.hardware.audio.AudioBackend
import com.example.hardware.audio.AudioCaptureFrame

/**
 * Result of processing one [AudioCaptureFrame]. Mirrors exactly what the original
 * `startAudioEngine` per-frame block derived and used to update `_uiState`/`_telemetry` and to
 * build the `DuoCoProtocol.createMusicColorCommand(r, g, b)` call — see
 * `RgbControllerViewModel.kt`'s `dispatch`/orchestrator wiring.
 */
data class AudioDspResult(
    val r: Int,
    val g: Int,
    val b: Int,
    val hue: Float,
    val amplitudes: List<Float>,
    val isIdle: Boolean
)

/**
 * Pure, hardware-free port of the DSP pipeline that used to live inline inside
 * `RgbControllerViewModel.startAudioEngine`'s Visualizer callback (~lines 3553-3879) and
 * AudioRecord read loop (~lines 4022-4331). No Android hardware APIs, no `_uiState`/`_telemetry`
 * references — those writes now happen in the ViewModel orchestrator using this class's output.
 *
 * Holds per-run mutable DSP state (smoothing accumulators, rolling histories, the BeatDetector
 * instance, palette index, auto-gain trackers, idle timer, UI-amplitude normalization trackers)
 * that in the original code were function-local vars living for the duration of one engine run.
 * Construct a fresh instance per `startAudioEngine` call to match that reset-on-start behavior.
 *
 * The two backends (AUDIO_RECORD / VISUALIZER) share the same pipeline almost verbatim, but two
 * real divergences exist in the original source and are preserved here, branched on [backend]:
 *  1. Initial `maxObservedBass`/`maxObservedMid` seed: 20.0f (Visualizer) vs 10.0f (AudioRecord).
 *  2. The `baseSat` formula's coefficients: (0.5 + 0.5 * ratio).coerceIn(0.5, 1.0) for Visualizer
 *     vs (0.4 + 0.6 * ratio).coerceIn(0.4, 1.0) for AudioRecord.
 * These are NOT bugs to unify — CLAUDE.md documents backend bin-range/timing differences as a
 * deliberately deferred issue; the same preserve-as-is rule applies here.
 */
class AudioDspProcessor(private val backend: AudioBackend) {

    // --- DSP state (function-local vars in the original, now instance fields) ---
    private var smoothedBass = 0.0f
    private var smoothedMid = 0.0f
    private var smoothedHigh = 0.0f
    private var continuousHueOffset = 0.0f
    private var whiteHotFlashOffset = 1.0f
    private var beatHueOffset = 0.0f
    private var maxObservedBass = if (backend == AudioBackend.VISUALIZER) 20.0f else 10.0f
    private var maxObservedMid = if (backend == AudioBackend.VISUALIZER) 20.0f else 10.0f

    private val bassHistory = FloatArray(86)
    private var bassHistoryIdx = 0
    private var bassHistorySum = 0.0f
    private var bassHistoryCount = 0

    // Declared-but-unused in the original source too (`var lastBeatTime = 0L`, never read after
    // assignment) — preserved for fidelity, not removed as part of this refactor's scope.
    @Suppress("unused")
    private var lastBeatTime = 0L

    private val beatDetector = BeatDetector()
    private var beatPulsePeak = 0.0f
    private var lastBeatFlashTime = 0L
    private var currentPaletteIndex = 0
    private val palettes = floatArrayOf(0f, 60f, 120f, 180f, 240f, 300f)

    private val noiseHistory = FloatArray(86)
    private var noiseHistoryIdx = 0
    private var noiseHistoryCount = 0

    private val uiAmplitudes8 = FloatArray(8)
    private var maxUiObserved = 10.0f
    private val maxPerBandObserved = FloatArray(8) { 10.0f }
    private var silenceStartTime = 0L

    private var smoothedHue = 0.0f

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

    // Diagnostic-only: throttles the periodic (non-beat) confidence sample below.
    private var lastDiagnosticSampleMs = 0L

    /**
     * Runs one frame through the DSP pipeline. Returns null if the frame should be skipped
     * outright — mirrors the Visualizer backend's original `if (numBins < 349) return` guard,
     * which discarded the frame with no state update and no downstream publish at all.
     */
    fun process(frame: AudioCaptureFrame, settings: AudioSettingsState, nowMs: Long): AudioDspResult? {
        val numBins = frame.numBins
        if (numBins < 349) return null

        val magnitude = frame.magnitude
        val realBins = frame.realBins
        val imagBins = frame.imagBins

        // Calculate frame average magnitude (excluding DC, up to minOf(349, numBins))
        val searchLimit = minOf(349, numBins)
        var magnitudeSum = 0.0f
        var magnitudeCount = 0
        for (k in 1 until searchLimit) {
            magnitudeSum += magnitude[k]
            magnitudeCount++
        }
        val frameAvgMag = if (magnitudeCount > 0) magnitudeSum / magnitudeCount else 0.0f

        // Update rolling buffer
        noiseHistory[noiseHistoryIdx] = frameAvgMag
        noiseHistoryIdx = (noiseHistoryIdx + 1) % 86
        if (noiseHistoryCount < 86) noiseHistoryCount++

        // Calculate 10th percentile of recent magnitudes
        val noiseFloor = if (noiseHistoryCount > 0) {
            val activeHistory = noiseHistory.copyOfRange(0, noiseHistoryCount)
            activeHistory.sort()
            val idx = (noiseHistoryCount * 0.1f).toInt().coerceIn(0, noiseHistoryCount - 1)
            activeHistory[idx]
        } else {
            0.0f
        }

        // Apply Noise Gate: any bin below noiseFloor * 1.5 should be zeroed out
        val threshold = noiseFloor * 1.5f
        for (k in 0 until numBins) {
            if (magnitude[k] < threshold) {
                magnitude[k] = 0.0f
                if (k < realBins.size) realBins[k] = 0.0f
                if (k < imagBins.size) imagBins[k] = 0.0f
            }
        }

        val state = settings

        var bassEnergy = 0.0f
        for (k in 1..3) bassEnergy += magnitude[k]
        val bassVal = (bassEnergy / 3.0f) * state.bassGain

        var midEnergy = 0.0f
        for (k in 4..46) midEnergy += magnitude[k]
        val midVal = (midEnergy / 43.0f) * state.midGain

        var highEnergy = 0.0f
        for (k in 47..348) highEnergy += magnitude[k]
        val highVal = (highEnergy / 302.0f) * state.highGain

        val totalEnergy = bassVal + midVal + highVal

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

        val bassAlpha = if (bassVal > smoothedBass) state.audioSmoothingAttack else state.audioSmoothingDecay
        smoothedBass = bassAlpha * bassVal + (1f - bassAlpha) * smoothedBass

        val midAlpha = if (midVal > smoothedMid) state.audioSmoothingAttack else state.audioSmoothingDecay
        smoothedMid = midAlpha * midVal + (1f - midAlpha) * smoothedMid

        val highAlpha = if (highVal > smoothedHigh) state.audioSmoothingAttack else state.audioSmoothingDecay
        smoothedHigh = highAlpha * highVal + (1f - highAlpha) * smoothedHigh

        val oldBass = bassHistory[bassHistoryIdx]
        bassHistory[bassHistoryIdx] = bassVal
        bassHistorySum = bassHistorySum - oldBass + bassVal
        bassHistoryIdx = (bassHistoryIdx + 1) % 86
        if (bassHistoryCount < 86) bassHistoryCount++
        val avgBass = if (bassHistoryCount > 0) bassHistorySum / bassHistoryCount else 0.0f

        val result = beatDetector.process(
            magnitude = magnitude,
            realBins = realBins,
            imagBins = imagBins,
            bassRange = 1..8,
            midRange = 9..46,
            midWeight = state.midFluxWeight,
            thresholdMultiplier = state.beatThresholdMultiplier,
            minCooldownMs = 180,
            maxCooldownMs = state.beatCooldownMs,
            now = nowMs
        )
        val isBeat = result.isBeat && totalEnergy >= state.noiseGateThreshold
        if (isBeat || nowMs - lastDiagnosticSampleMs >= 1500L) {
            if (!isBeat) lastDiagnosticSampleMs = nowMs
            DiagnosticLogger.log(
                "BeatConfidence",
                "backend=${backend.name} isBeat=$isBeat strength=${result.strength} confidence=${result.confidence} " +
                    "bpm=${result.bpm} bpmConfidence=${result.bpmConfidence}"
            )
        }
        if (isBeat) {
            if (state.isPaletteCyclingEnabled) {
                currentPaletteIndex = (currentPaletteIndex + 1) % palettes.size
            }
            beatPulsePeak = 0.6f + 0.4f * result.strength
            lastBeatFlashTime = nowMs

            val useWhiteFlash = state.visualizerPreset == "Strobe Blast" || state.visualizerPreset == "Beat Only" || state.visualizerPreset == "Laser Sharp"
            if (useWhiteFlash) {
                whiteHotFlashOffset = 0.0f
                continuousHueOffset = (continuousHueOffset + 15f) % 360f
            } else {
                beatHueOffset = 180.0f
                continuousHueOffset = (continuousHueOffset + 30f) % 360f
            }
        }

        // Calculate Energy Ratios (Hue)
        whiteHotFlashOffset = Math.min(1.0f, whiteHotFlashOffset + 0.05f)
        beatHueOffset *= 0.85f

        val activeTotal = smoothedBass + smoothedMid + smoothedHigh
        val midRatio = if (activeTotal > 0) smoothedMid / activeTotal else 0.0f
        val highRatio = if (activeTotal > 0) smoothedHigh / activeTotal else 0.0f

        continuousHueOffset = (continuousHueOffset + (activeTotal * 0.01f)) % 360f

        var hue = (continuousHueOffset + (midRatio * 60f) - (highRatio * 60f) + 360f + beatHueOffset) % 360f

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
            // Aggressive dual-rate auto-gain: rapid decay in low-amplitude states to boost quiet sections instantly
            val decayFactorBass = if (bassVal < avgBass) 0.97f else 0.992f
            maxObservedBass = maxOf(maxObservedBass * decayFactorBass, bassVal, 10.0f)

            val decayFactorMid = if (smoothedMid < maxObservedMid * 0.5f) 0.97f else 0.992f
            maxObservedMid = maxOf(maxObservedMid * decayFactorMid, smoothedMid, 10.0f)
        } else {
            maxObservedBass = 100.0f
            maxObservedMid = 100.0f
        }

        // Backend-specific baseSat coefficients — real divergence in the original source, preserved.
        val baseSat = if (backend == AudioBackend.VISUALIZER) {
            if (state.isAutoGainEnabled) {
                (0.5f + 0.5f * (smoothedMid / maxObservedMid)).coerceIn(0.5f, 1.0f)
            } else {
                (smoothedMid / 100.0f).coerceIn(0.0f, 1.0f)
            }
        } else {
            if (state.isAutoGainEnabled) {
                (0.4f + 0.6f * (smoothedMid / maxObservedMid)).coerceIn(0.4f, 1.0f)
            } else {
                (smoothedMid / 100.0f).coerceIn(0.0f, 1.0f)
            }
        }
        val sat = (baseSat * whiteHotFlashOffset).coerceIn(0.0f, 1.0f)

        val sensitivityMultiplier = (state.musicSensitivity / 50.0f)

        val bassFloor = 0.8f * avgBass
        val rangeBass = maxOf(maxObservedBass - bassFloor, 5.0f)
        val bassContribution = if (state.isAutoGainEnabled) {
            ((smoothedBass - bassFloor) / rangeBass).coerceIn(0.0f, 1.0f)
        } else {
            (smoothedBass / 100.0f).coerceIn(0.0f, 1.0f)
        }

        val midFloor = 0.5f * (maxObservedMid * 0.1f)
        val rangeMid = maxOf(maxObservedMid - midFloor, 5.0f)
        val midContribution = ((smoothedMid - midFloor) / rangeMid).coerceIn(0.0f, 1.0f)

        var bc = bassContribution
        bc = if (smoothedBass >= state.noiseGateThreshold) {
            Math.pow(bc.toDouble(), 1.5).toFloat()
        } else {
            0.0f
        }
        val valBase = maxOf(bc, midContribution * 0.3f)

        val value: Float
        if (state.visualizerPreset == "Beat Only") {
            val elapsedMs = nowMs - lastBeatFlashTime
            val t = elapsedMs.toFloat() / state.beatFlashDecayMs
            val beatEnvelope = maxOf(1f - t * t, 0f) * beatPulsePeak
            value = maxOf(state.visualizerMinBrightness, (beatEnvelope * maxOf(0.5f, state.audioFlashStrength)).coerceIn(0f, 1f))
        } else {
            var baseValVal = (valBase * sensitivityMultiplier).coerceIn(0.0f, 1.0f)
            baseValVal = Math.pow(baseValVal.toDouble(), state.audioGammaExponent.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
            val ambientLevel = baseValVal * state.ambientCapFraction
            val elapsedMs = nowMs - lastBeatFlashTime
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
                silenceStartTime = nowMs
            }
            isIdle = (nowMs - silenceStartTime) > state.idleTriggerDelayMs
        } else {
            silenceStartTime = 0L
            isIdle = false
        }

        val normalizedAmplitudesList: List<Float> = if (isIdle) {
            val timeSec = nowMs / 1000.0
            val pulseVal = 0.15f + 0.10f * Math.sin(2.0 * Math.PI * timeSec / 4.0).toFloat()
            List(8) { pulseVal.coerceIn(0.05f, 1.0f) }
        } else if (isBelow) {
            List(8) { 0.05f }
        } else {
            val edges = intArrayOf(1, 2, 5, 10, 23, 49, 108, 234, 512)
            for (band in 0 until 8) {
                val startBin = edges[band]
                val endBin = minOf(edges[band + 1], numBins - 1)
                var peak = 0.0f
                for (bin in startBin until endBin) {
                    if (magnitude[bin] > peak) {
                        peak = magnitude[bin]
                    }
                }
                // Equal-loudness inspired weighting (boost higher bands)
                val weight = 1.0f + (band * 0.4f)
                uiAmplitudes8[band] = (if (endBin > startBin) peak else magnitude[startBin]) * weight
            }

            maxUiObserved *= 0.98f
            var currentGlobalMax = 0.0f
            for (band in 0 until 8) {
                maxPerBandObserved[band] *= 0.98f
                maxPerBandObserved[band] = maxOf(maxPerBandObserved[band], uiAmplitudes8[band], 2.0f)
                currentGlobalMax = maxOf(currentGlobalMax, uiAmplitudes8[band])
            }
            maxUiObserved = maxOf(maxUiObserved, currentGlobalMax, 5.0f)

            uiAmplitudes8.mapIndexed { index, amp ->
                val bandNorm = amp / (maxPerBandObserved[index] + 0.001f)
                val globalNorm = amp / (maxUiObserved + 0.001f)
                // Blend between per-band normalization (70%) and global normalization (30%)
                val blended = (bandNorm * 0.7f) + (globalNorm * 0.3f)
                blended.coerceIn(0.05f, 1.0f)
            }
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
