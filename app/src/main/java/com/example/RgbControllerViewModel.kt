package com.example

import com.example.core.protocol.DuoCoProtocol
import com.example.core.color.ColorConverter
import com.example.core.calibration.CalibrationMatrixSolver
import com.example.core.animation.ProceduralSceneParams
import com.example.domain.repository.AppPreferencesRepository
import com.example.domain.repository.RgbDatabaseRepository

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.RgbDeviceAlias
import com.example.db.RgbPreset
import com.example.db.SavedDevice
import com.example.db.CustomMode
import com.example.db.ColorCalibration
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.MediaRecorder
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjectionManager
import android.media.AudioTrack
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.content.Intent
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import com.example.domain.model.AppScene
import com.example.domain.model.DeviceSceneState
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.util.UUID

private const val TAG = "BleRgbController"

// ============================================================================

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

data class ScannedRgbDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isDuoCoSuspected: Boolean,
    val alias: String? = null
)

data class CctCorrectionProfile(
    val macAddress: String,
    val timestamp: Long,
    val scaleR: Float,
    val offsetR: Float,
    val scaleG: Float,
    val offsetG: Float,
    val scaleB: Float,
    val offsetB: Float,
    val iso: Int,
    val exposureNs: Long
) {
    fun toJson(): String {
        val obj = org.json.JSONObject()
        obj.put("macAddress", macAddress)
        obj.put("timestamp", timestamp)
        obj.put("scaleR", scaleR.toDouble())
        obj.put("offsetR", offsetR.toDouble())
        obj.put("scaleG", scaleG.toDouble())
        obj.put("offsetG", offsetG.toDouble())
        obj.put("scaleB", scaleB.toDouble())
        obj.put("offsetB", offsetB.toDouble())
        obj.put("iso", iso)
        obj.put("exposureNs", exposureNs)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): CctCorrectionProfile? {
            return try {
                val obj = org.json.JSONObject(jsonStr)
                CctCorrectionProfile(
                    macAddress = obj.getString("macAddress"),
                    timestamp = obj.getLong("timestamp"),
                    scaleR = obj.getDouble("scaleR").toFloat(),
                    offsetR = obj.getDouble("offsetR").toFloat(),
                    scaleG = obj.getDouble("scaleG").toFloat(),
                    offsetG = obj.getDouble("offsetG").toFloat(),
                    scaleB = obj.getDouble("scaleB").toFloat(),
                    offsetB = obj.getDouble("offsetB").toFloat(),
                    iso = obj.getInt("iso"),
                    exposureNs = obj.getLong("exposureNs")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class ActiveDeviceState(
    val activeFeatureName: String = "Colour",
    val red: Int = 255,
    val green: Int = 0,
    val blue: Int = 128,
    val warmth: Int = 50,
    val modeIndex: Int = 0,
    val brightness: Int = 80,
    val isPowerOn: Boolean = true
)

data class ControllerUiState(
    val isDbLoaded: Boolean = false,
    val activeFeatureName: String = "Colour",
    val showFpsTracker: Boolean = false,
    val connectionState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectedDeviceAddress: String? = null,
    val connectedDeviceName: String? = null,
    val isScanning: Boolean = false,
    val scannedDevices: List<ScannedRgbDevice> = emptyList(),
    val isPowerOn: Boolean = true,
    val red: Int = 255,
    val green: Int = 0,
    val blue: Int = 128,
    val brightness: Int = 80,
    val modeIndex: Int = 0,
    val modeSpeed: Int = 50,
    val warmth: Int = 50,
    val isDemoMode: Boolean = true, // default to true since there's no physical BLE in the emulator
    val errorMessage: String? = null,
    val logMessages: List<String> = emptyList(),
    val deviceConnectionStates: Map<String, BleConnectionState> = emptyMap(),
    val musicMode: String? = null,
    val musicSensitivity: Int = 50,
    val musicAmplitudes: List<Float> = emptyList(),
    val visualizerHue: Float = 0.0f,
    val isVisualizerIdle: Boolean = false,
    val isAudioSyncRunning: Boolean = false,
    val audioSmoothingAttack: Float = 0.85f,
    val audioSmoothingDecay: Float = 0.12f,
    val beatFlashDecayMs: Float = 200f,
    val ambientCapFraction: Float = 0.40f,
    val midFluxWeight: Float = 0.25f,
    val transmissionDelayMs: Int = 16,
    val noiseGateThreshold: Float = 5.0f,
    val beatThresholdMultiplier: Float = 1.3f,
    val beatCooldownMs: Int = 250,
    val bassGain: Float = 1.0f,
    val midGain: Float = 1.0f,
    val highGain: Float = 1.0f,
    val isAutoGainEnabled: Boolean = true,
    val isPaletteCyclingEnabled: Boolean = true,
    val isLogarithmicScalingEnabled: Boolean = true,
    val bluetoothDelayMs: Int = 0,
    val totalVisualDelayMs: Int = 180,
    val visualizerPreset: String = "Default",
    val audioGammaExponent: Float = 0.45f,
    val audioFlashStrength: Float = 0.3f,
    val visualizerMinBrightness: Float = 0.15f,
    val visualizerColorSpeed: Float = 1.0f,
    val idleTriggerDelayMs: Long = 2500L,
    val detectedAudioDeviceName: String? = null,
    val activeAudioDeviceIdentifier: String? = null,
    val showCalibrationPrompt: Boolean = false,
    val isCalibrationModeActive: Boolean = false,
    val calibrationDelayOffsetMs: Int = 0,
    val deviceAchievedFps: Map<String, Int> = emptyMap(),
    val devicePacingMs: Map<String, Int> = emptyMap(),
    val isTestPatternRunning: Map<String, Boolean> = emptyMap(),
    val colorCalibrations: Map<String, ColorCalibration> = emptyMap(),
    val cctCalibrations: Map<String, CctCorrectionProfile> = emptyMap(),
    val ambianceResponseSpeed: Float = 0.5f,
    val ambianceSmoothnessMs: Int = 150,
    val ambianceSaturationBoost: Float = 1.4f,
    val ambianceBrightnessCompensation: Float = 1.0f,
    val ambianceUpdateRateCapFps: Int = 20,
    val ambianceSceneCutSensitivity: Float = 50.0f,
    val ambianceNoiseDeadband: Float = 0.10f,
    val ambiancePreset: String = "Balanced",
    val deviceStatesMap: Map<String, ActiveDeviceState> = emptyMap()
)


private class BeatDetector(
    private val fluxHistorySize: Int = 64,
    private val medianWindowMs: Long = 1000L,
    val lookaheadMs: Long = 180L,
    private val peakWindowMs: Long = 60L,
    private val tempoHistoryMs: Long = 8000L,
    private val tempoUpdateIntervalMs: Long = 1500L,
    private val minBpm: Float = 60f,
    private val maxBpm: Float = 200f,
    private val preferredBpm: Float = 120f,
    private val octaveToleranceRatio: Float = 0.85f,
    private val transitionPenalty: Float = 100f
) {
    data class FluxSample(val timestampMs: Long, val flux: Float)

    // (a) Why the buffer is time-based rather than count-based:
    // Different frame cadences between callers (e.g. on_device runs at ~33-50ms intervals, whereas phone_mic
    // runs at ~23ms intervals). A count-based ring buffer would represent vastly different durations of physical
    // audio depending on the source. A time-based buffer guarantees a uniform physical time-span of retention.
    private val fluxBuffer = kotlin.collections.ArrayDeque<FluxSample>()
    private val tempoBuffer = kotlin.collections.ArrayDeque<FluxSample>()

    private var prevBassMag: FloatArray? = null
    private var prevBassReal: FloatArray? = null
    private var prevBassImag: FloatArray? = null
    private var prevPrevBassReal: FloatArray? = null
    private var prevPrevBassImag: FloatArray? = null

    private var prevMidMag: FloatArray? = null
    private var prevMidReal: FloatArray? = null
    private var prevMidImag: FloatArray? = null
    private var prevPrevMidReal: FloatArray? = null
    private var prevPrevMidImag: FloatArray? = null

    private var prevHighMidMag: FloatArray? = null
    private var prevHighMidReal: FloatArray? = null
    private var prevHighMidImag: FloatArray? = null
    private var prevPrevHighMidReal: FloatArray? = null
    private var prevPrevHighMidImag: FloatArray? = null

    private var prevHighMag: FloatArray? = null
    private var prevHighReal: FloatArray? = null
    private var prevHighImag: FloatArray? = null
    private var prevPrevHighReal: FloatArray? = null
    private var prevPrevHighImag: FloatArray? = null

    private val ibiHistory = FloatArray(4) { 500f }
    private var ibiIdx = 0
    private var lastBeatTime = 0L

    private var lastTempoUpdateTime = 0L
    private var lockedBpm = 0f
    private var candidateStreakCount = 0
    private var previousCandidateBpm = 0f
    private var lockedBpmConfidence = 0f
    private var gridBeats = listOf<Long>()

    data class BeatResult(
        val isBeat: Boolean, 
        val strength: Float,
        val confidence: Float,
        val bpm: Float,
        val bpmConfidence: Float
    )

    private class BandResult(
        val totalFlux: Float,
        val curMagArr: FloatArray,
        val curRealArr: FloatArray,
        val curImagArr: FloatArray
    )

    private fun computeBandFlux(
        magnitude: FloatArray,
        realBins: FloatArray,
        imagBins: FloatArray,
        range: IntRange,
        prevMagArray: FloatArray?,
        prevRealArray: FloatArray?,
        prevImagArray: FloatArray?,
        prevPrevRealArray: FloatArray?,
        prevPrevImagArray: FloatArray?,
        phaseWeight: Float
    ): BandResult {
        var magFlux = 0f
        var phaseFlux = 0f

        val curMagArr = FloatArray(range.last - range.first + 1)
        val curRealArr = FloatArray(curMagArr.size)
        val curImagArr = FloatArray(curMagArr.size)

        var i = 0
        for (k in range) {
            val curMag = magnitude[k]
            val curReal = realBins[k]
            val curImag = imagBins[k]

            curMagArr[i] = curMag
            curRealArr[i] = curReal
            curImagArr[i] = curImag

            // Magnitude Flux (log-compressed)
            val curLogMag = kotlin.math.ln(1f + curMag)
            val prevMag = prevMagArray?.getOrNull(i) ?: curMag
            val prevLogMag = kotlin.math.ln(1f + prevMag)
            val diff = curLogMag - prevLogMag
            if (diff > 0f) magFlux += diff

            // Phase Flux (complex-domain onset detection, Duxbury/Bello method)
            if (prevRealArray != null && prevImagArray != null && prevPrevRealArray != null && prevPrevImagArray != null) {
                val pReal = prevRealArray[i]
                val pImag = prevImagArray[i]
                val ppReal = prevPrevRealArray[i]
                val ppImag = prevPrevImagArray[i]

                val pPhase = kotlin.math.atan2(pImag, pReal)
                val ppPhase = kotlin.math.atan2(ppImag, ppReal)

                // Predicted phase for frame n: phi_hat = 2 * phase(n-1) - phase(n-2)
                val phi_hat = 2f * pPhase - ppPhase
                // Predicted magnitude for frame n: R_hat = magnitude(n-1) [constant magnitude assumption]
                val r_hat = prevMag

                // Only count deviation if actual magnitude >= predicted magnitude
                // (bias toward energy increases)
                if (curMag >= r_hat) {
                    // Predicted complex value: X_hat = R_hat * (cos(phi_hat), sin(phi_hat))
                    val x_hat_real = r_hat * kotlin.math.cos(phi_hat)
                    val x_hat_imag = r_hat * kotlin.math.sin(phi_hat)

                    // Euclidean distance |X - X_hat|
                    val distReal = curReal - x_hat_real
                    val distImag = curImag - x_hat_imag
                    val distance = kotlin.math.sqrt(distReal * distReal + distImag * distImag)

                    phaseFlux += distance
                }
            }
            i++
        }

        val totalFlux = magFlux + phaseWeight * phaseFlux
        return BandResult(totalFlux, curMagArr, curRealArr, curImagArr)
    }

    private fun updateTempoAndGrid(now: Long) {
        if (tempoBuffer.size < 2) return

        val startTime = tempoBuffer.first().timestampMs
        val endTime = tempoBuffer.last().timestampMs
        val durationMs = endTime - startTime
        if (durationMs < 2000L) return

        // a. Resample onto a uniform time grid (100Hz / 10ms steps)
        val stepMs = 10L
        val numSteps = (durationMs / stepMs).toInt() + 1
        val resampled = FloatArray(numSteps)
        
        var bufferIdx = 0
        for (i in 0 until numSteps) {
            val t = startTime + i * stepMs
            while (bufferIdx < tempoBuffer.size - 1 && tempoBuffer[bufferIdx + 1].timestampMs < t) {
                bufferIdx++
            }
            if (bufferIdx >= tempoBuffer.size - 1) {
                resampled[i] = tempoBuffer.last().flux
            } else {
                val s1 = tempoBuffer[bufferIdx]
                val s2 = tempoBuffer[bufferIdx + 1]
                if (s2.timestampMs == s1.timestampMs) {
                    resampled[i] = s1.flux
                } else {
                    val fraction = (t - s1.timestampMs).toFloat() / (s2.timestampMs - s1.timestampMs)
                    resampled[i] = s1.flux + fraction * (s2.flux - s1.flux)
                }
            }
        }

        // b. Autocorrelation over lag values
        val minLag = (60000f / maxBpm / stepMs).toInt().coerceAtLeast(1)
        val maxLag = (60000f / minBpm / stepMs).toInt().coerceAtMost(numSteps - 1)
        
        var zeroLagAc = 0f
        for (i in 0 until numSteps) {
            zeroLagAc += resampled[i] * resampled[i]
        }
        if (zeroLagAc < 1e-6f) return

        val autocorrelations = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in 0 until numSteps - lag) {
                sum += resampled[i] * resampled[i + lag]
            }
            autocorrelations[lag] = sum
        }

        // c. Apply musical tempo prior & d. Find highest prior-weighted lag
        var bestLag = minLag
        var maxWeightedAc = -1f
        val stdDevBpm = 30f
        
        for (lag in minLag..maxLag) {
            val bpmAtLag = 60000f / (lag * stepMs)
            val rawAc = autocorrelations[lag]
            val diff = bpmAtLag - preferredBpm
            val weight = kotlin.math.exp(-(diff * diff) / (2f * stdDevBpm * stdDevBpm).toDouble()).toFloat()
            val weightedAc = rawAc * weight
            if (weightedAc > maxWeightedAc) {
                maxWeightedAc = weightedAc
                bestLag = lag
            }
        }

        val candidateRawAc = autocorrelations[bestLag]

        // e. Octave Error Resolution
        // Octave error resolution checks half and double the candidate lag. 
        // Since autocorrelation naturally peaks at multiples of the true beat period, we might falsely lock 
        // onto double-time or half-time. By checking if the alternative's raw strength is comparable (within octaveToleranceRatio),
        // we can prefer the tempo that maintains continuity with the previous lockedBpm, preventing flip-flopping.
        var resolvedLag = bestLag
        val lagOptions = mutableListOf(bestLag)
        
        val halfLag = bestLag / 2
        if (halfLag in minLag..maxLag && autocorrelations[halfLag] >= candidateRawAc * octaveToleranceRatio) {
            lagOptions.add(halfLag)
        }
        val doubleLag = bestLag * 2
        if (doubleLag in minLag..maxLag && autocorrelations[doubleLag] >= candidateRawAc * octaveToleranceRatio) {
            lagOptions.add(doubleLag)
        }

        if (lagOptions.size > 1) {
            val targetBpm = if (lockedBpm > 0f) lockedBpm else preferredBpm
            resolvedLag = lagOptions.minByOrNull { lag ->
                val bpm = 60000f / (lag * stepMs)
                kotlin.math.abs(bpm - targetBpm)
            } ?: bestLag
        }

        val resolvedCandidateBpm = 60000f / (resolvedLag * stepMs)

        // f. Smooth the lock
        // Why 2-cycle streak: Autocorrelation on rolling windows can be noisy. A single frame might spike at an incorrect lag.
        // Requiring the same candidate (within 3 BPM tolerance) for 2 consecutive updates acts as a debounce, ensuring 
        // the tempo estimate is stable before we shift our locked BPM.
        if (lockedBpm == 0f) {
            lockedBpm = resolvedCandidateBpm
            lockedBpmConfidence = autocorrelations[resolvedLag] / zeroLagAc
        } else {
            if (kotlin.math.abs(resolvedCandidateBpm - previousCandidateBpm) < 3f) {
                candidateStreakCount++
                if (candidateStreakCount >= 2) {
                    lockedBpm = lockedBpm * 0.7f + resolvedCandidateBpm * 0.3f
                    lockedBpmConfidence = (autocorrelations[resolvedLag] / zeroLagAc) * 0.3f + lockedBpmConfidence * 0.7f
                }
            } else {
                candidateStreakCount = 1
            }
        }
        previousCandidateBpm = resolvedCandidateBpm

        // 3. DP BEAT GRID
        // DP Scoring tradeoff: We want beats to align with high ODF salience (peaks in flux), but we also want 
        // rhythmic regularity. The DP score adds the ODF value (rewarding peaks) but subtracts a transition penalty 
        // squared by the deviation from the expected period. A high penalty forces rigid tempo; a lower penalty 
        // allows the grid to flex to capture syncopated or slightly off-beat peaks.
        if (lockedBpm > 0f) {
            val periodMs = 60000f / lockedBpm
            val periodSteps = kotlin.math.round(periodMs / stepMs).toInt().coerceAtLeast(1)
            
            val scores = FloatArray(numSteps)
            val backtrack = IntArray(numSteps) { -1 }

            val minSearch = (periodSteps * 0.5f).toInt()
            val maxSearch = (periodSteps * 1.5f).toInt()

            for (i in 0 until numSteps) {
                val odfVal = resampled[i]
                var bestPrevScore = 0f
                var bestPrevIdx = -1

                for (p in minSearch..maxSearch) {
                    val prevIdx = i - p
                    if (prevIdx >= 0) {
                        val deltaMs = p * stepMs
                        val deviationSec = (deltaMs - periodMs) / 1000f
                        val penalty = transitionPenalty * (deviationSec * deviationSec)
                        val score = scores[prevIdx] - penalty
                        if (bestPrevIdx == -1 || score > bestPrevScore) {
                            bestPrevScore = score
                            bestPrevIdx = prevIdx
                        }
                    }
                }
                
                scores[i] = odfVal + bestPrevScore
                backtrack[i] = bestPrevIdx
            }

            var bestEndIdx = numSteps - 1
            var bestEndScore = -Float.MAX_VALUE
            val searchStart = (numSteps - periodSteps).coerceAtLeast(0)
            for (i in searchStart until numSteps) {
                if (scores[i] > bestEndScore) {
                    bestEndScore = scores[i]
                    bestEndIdx = i
                }
            }

            val beats = mutableListOf<Long>()
            var currIdx = bestEndIdx
            while (currIdx >= 0) {
                beats.add(startTime + currIdx * stepMs)
                currIdx = backtrack[currIdx]
            }
            gridBeats = beats.reversed()
        }
    }

    fun process(
        magnitude: FloatArray,
        realBins: FloatArray,
        imagBins: FloatArray,
        bassRange: IntRange,
        midRange: IntRange,
        midWeight: Float,
        thresholdMultiplier: Float,
        minCooldownMs: Int,
        maxCooldownMs: Int,
        now: Long,
        highMidRange: IntRange = 47..116,
        highRange: IntRange = 117..280,
        highMidWeight: Float = 0.5f,
        highWeight: Float = 0.3f,
        bassPhaseWeight: Float = 0.1f,
        midPhaseWeight: Float = 0.5f,
        highMidPhaseWeight: Float = 1.0f,
        highPhaseWeight: Float = 1.5f
    ): BeatResult {
        val bassRes = computeBandFlux(magnitude, realBins, imagBins, bassRange, prevBassMag, prevBassReal, prevBassImag, prevPrevBassReal, prevPrevBassImag, bassPhaseWeight)
        prevPrevBassReal = prevBassReal
        prevPrevBassImag = prevBassImag
        prevBassMag = bassRes.curMagArr
        prevBassReal = bassRes.curRealArr
        prevBassImag = bassRes.curImagArr

        val midRes = computeBandFlux(magnitude, realBins, imagBins, midRange, prevMidMag, prevMidReal, prevMidImag, prevPrevMidReal, prevPrevMidImag, midPhaseWeight)
        prevPrevMidReal = prevMidReal
        prevPrevMidImag = prevMidImag
        prevMidMag = midRes.curMagArr
        prevMidReal = midRes.curRealArr
        prevMidImag = midRes.curImagArr

        val highMidRes = computeBandFlux(magnitude, realBins, imagBins, highMidRange, prevHighMidMag, prevHighMidReal, prevHighMidImag, prevPrevHighMidReal, prevPrevHighMidImag, highMidPhaseWeight)
        prevPrevHighMidReal = prevHighMidReal
        prevPrevHighMidImag = prevHighMidImag
        prevHighMidMag = highMidRes.curMagArr
        prevHighMidReal = highMidRes.curRealArr
        prevHighMidImag = highMidRes.curImagArr

        val highRes = computeBandFlux(magnitude, realBins, imagBins, highRange, prevHighMag, prevHighReal, prevHighImag, prevPrevHighReal, prevPrevHighImag, highPhaseWeight)
        prevPrevHighReal = prevHighReal
        prevPrevHighImag = prevHighImag
        prevHighMag = highRes.curMagArr
        prevHighReal = highRes.curRealArr
        prevHighImag = highRes.curImagArr

        val flux = bassRes.totalFlux + midRes.totalFlux * midWeight + highMidRes.totalFlux * highMidWeight + highRes.totalFlux * highWeight

        // 1. Append the new sample to the time-based buffer
        fluxBuffer.addLast(FluxSample(now, flux))
        tempoBuffer.addLast(FluxSample(now, flux))

        // Trim samples older than the retention window relative to 'now' (the latest sample timestamp)
        val retentionMs = medianWindowMs + lookaheadMs + 300L
        val cutoffTime = now - retentionMs
        while (fluxBuffer.isNotEmpty() && fluxBuffer.first().timestampMs < cutoffTime) {
            fluxBuffer.removeFirst()
        }

        val tempoCutoffTime = now - tempoHistoryMs
        while (tempoBuffer.isNotEmpty() && tempoBuffer.first().timestampMs < tempoCutoffTime) {
            tempoBuffer.removeFirst()
        }

        // Throttled execution: We run tempo estimation on a throttled cadence (e.g. 1.5s) because it's 
        // computationally expensive (resampling + autocorrelation over 8 seconds of data) and tempo inherently 
        // doesn't change on a frame-to-frame basis.
        if (now - lastTempoUpdateTime >= tempoUpdateIntervalMs && tempoBuffer.size >= 10) {
            lastTempoUpdateTime = now
            updateTempoAndGrid(now)
        }

        // 2. Introduce the evaluation point (results now describe evalTimestamp in the past)
        // (c) Results describe evalTimestamp, a point ~lookaheadMs in the past:
        // This intentionally introduces a fixed detection latency (~150-200ms) to allow looking ahead
        // into "future" flux values relative to evalTimestamp for robust peak-picking and centered threshold evaluation.
        val evalTimestamp = now - lookaheadMs

        val earliestLimit = evalTimestamp - (medianWindowMs / 2)
        val latestLimit = evalTimestamp + lookaheadMs // i.e. now

        // Check if the buffer has enough samples reaching back and forward to safely evaluate
        val hasEarliest = fluxBuffer.any { it.timestampMs <= earliestLimit }
        val hasLatest = fluxBuffer.any { it.timestampMs >= latestLimit }

        if (!hasEarliest || !hasLatest) {
            return BeatResult(isBeat = false, strength = 0f, confidence = 0f, bpm = lockedBpm, bpmConfidence = lockedBpmConfidence)
        }

        // 3. Centered median/MAD threshold evaluation
        // (b) Why the threshold window is centered rather than causal:
        // Causal thresholding is prone to latency-skewed detection and high false-positive rates on rising edges
        // because the adaptive threshold is calculated solely from past frames. A centered window places the
        // evaluation frame precisely in the middle of the statistics window, resulting in a stable reference
        // threshold that reflects local signal conditions symmetrically.
        val subset = fluxBuffer.filter { it.timestampMs in earliestLimit..(evalTimestamp + (medianWindowMs / 2)) }
        if (subset.isEmpty()) {
            return BeatResult(isBeat = false, strength = 0f, confidence = 0f, bpm = lockedBpm, bpmConfidence = lockedBpmConfidence)
        }

        val subsetFluxes = subset.map { it.flux }
        val medianFlux = getMedian(subsetFluxes)

        val absoluteDeviations = subsetFluxes.map { kotlin.math.abs(it - medianFlux) }
        val mad = getMedian(absoluteDeviations)

        // 1.4826 standard scale factor for normal distribution matching, with noise protection
        val scaledMad = maxOf(mad * 1.4826f, 0.1f)
        val threshold = maxOf(medianFlux + thresholdMultiplier * scaledMad, 0.3f)

        // 4. Local peak-picking: find the sample closest to evalTimestamp
        val evalSample = fluxBuffer.minByOrNull { kotlin.math.abs(it.timestampMs - evalTimestamp) }
            ?: return BeatResult(isBeat = false, strength = 0f, confidence = 0f, bpm = lockedBpm, bpmConfidence = lockedBpmConfidence)

        // Filter samples inside the small peak window around evalTimestamp
        val peakWindowStart = evalTimestamp - peakWindowMs
        val peakWindowEnd = evalTimestamp + peakWindowMs
        val peakSamples = fluxBuffer.filter { it.timestampMs in peakWindowStart..peakWindowEnd }

        // Local maximum check: sample flux must be >= all others in its peak window, and strictly above threshold (with low-signal guard)
        val isLocalMax = peakSamples.all { evalSample.flux >= it.flux }
        val isAboveThreshold = evalSample.flux > threshold
        val isCandidateBeat = isLocalMax && isAboveThreshold

        // 5. Cooldown and IBI tracking in evalTimestamp's timeline with tempo-adaptation & collapse-prevention
        val expectedPeriod = if (lockedBpm > 0f) 60000f / lockedBpm else ibiHistory.average().toFloat()
        val safeMinCooldown = maxOf(minCooldownMs.toFloat(), 180f)
        val dynamicCooldown = (expectedPeriod * 0.55f).coerceIn(safeMinCooldown, maxOf(safeMinCooldown, maxCooldownMs.toFloat()))
        val cooldownOk = (evalTimestamp - lastBeatTime) > dynamicCooldown

        var isBeat = false
        if (isCandidateBeat && cooldownOk) {
            isBeat = true
            if (lastBeatTime != 0L) {
                val ibi = (evalTimestamp - lastBeatTime).coerceIn(180L, 2000L)
                ibiHistory[ibiIdx] = ibi.toFloat()
                ibiIdx = (ibiIdx + 1) % ibiHistory.size
            }
            lastBeatTime = evalTimestamp
        }

        // 6. Strength calculation using MAD and median
        val strength = if (scaledMad > 0.001f) {
            ((evalSample.flux - medianFlux) / scaledMad).coerceIn(0f, 4f) / 4f
        } else {
            0f
        }

        var phaseAgreement = 0f
        if (gridBeats.isNotEmpty() && lockedBpm > 0f) {
            val periodMs = 60000f / lockedBpm
            val nearestBeat = gridBeats.minByOrNull { kotlin.math.abs(it - evalTimestamp) }
            if (nearestBeat != null) {
                val deviation = kotlin.math.abs(nearestBeat - evalTimestamp).toFloat()
                val maxDeviation = periodMs / 4f
                phaseAgreement = if (deviation >= maxDeviation) 0f else 1f - (deviation / maxDeviation)
            }
        }

        // CONFIDENCE SCORE
        // Weights rationale: 
        // peakSalience (0.5) is the primary driver because local energy bursts are the actual physical beats.
        // bpmConfidence (0.2) scales our trust in the current rhythmic context.
        // phaseAgreement (0.3) nudges the confidence up if the peak aligns well with our expected DP grid.
        val confidence = if (lockedBpm == 0f) {
            strength // peakSalience-only before lock
        } else {
            (strength * 0.5f) + (lockedBpmConfidence * 0.2f) + (phaseAgreement * 0.3f)
        }

        return BeatResult(isBeat, strength, confidence, lockedBpm, lockedBpmConfidence)
    }

    private fun getMedian(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        val sorted = list.sorted()
        val size = sorted.size
        return if (size % 2 == 0) {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2f
        } else {
            sorted[size / 2]
        }
    }
}

@SuppressLint("MissingPermission")

class RgbControllerViewModel(
    private val application: android.content.Context,
    private val prefsRepo: com.example.domain.repository.AppPreferencesRepository,
    private val repository: com.example.domain.repository.RgbDatabaseRepository,
    private val connectionManager: com.example.domain.ConnectionManager
) : androidx.lifecycle.ViewModel() {

    private fun getApplication(): android.app.Application = application as android.app.Application

    companion object {
        @Volatile
        private var instance: RgbControllerViewModel? = null

        fun getActiveInstance(): RgbControllerViewModel? = instance

        // Static maps to survive Activity/ViewModel recreation and prevent GC disconnects
        val activeConnections = ConcurrentHashMap<String, BluetoothGatt>()
        val writeCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
        val retryAttempts = ConcurrentHashMap<String, Int>()
        val activeExcludedMacs = ConcurrentHashMap.newKeySet<String>()
        val deviceWriteManagers = ConcurrentHashMap<String, RgbControllerViewModel.DeviceWriteManager>()
        private val connectionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    }

        private val deviceStateStore = DeviceStateStore(application)
    private val deviceAutomationMode = java.util.concurrent.ConcurrentHashMap<String, AutomationType>()
    private val deviceRestoredFeatureName = java.util.concurrent.ConcurrentHashMap<String, String>()
    enum class AutomationType { AUDIO, AMBIANCE }
    
        
    private val _uiState = MutableStateFlow(
        ControllerUiState(
            isPowerOn = prefsRepo.getAppStatePrefBoolean("power_on", true),
            activeFeatureName = prefsRepo.getAppStatePrefString("active_feature_name", "Colour") ?: "Colour",
            showFpsTracker = prefsRepo.getAppStatePrefBoolean("show_fps_tracker", false),
            red = prefsRepo.getAppStatePrefInt("red", 255),
            green = prefsRepo.getAppStatePrefInt("green", 0),
            blue = prefsRepo.getAppStatePrefInt("blue", 128),
            brightness = prefsRepo.getAppStatePrefInt("brightness", 80),
            modeIndex = prefsRepo.getAppStatePrefInt("mode_index", 0),
            modeSpeed = prefsRepo.getAppStatePrefInt("mode_speed", 50),
            warmth = prefsRepo.getAppStatePrefInt("warmth", 50),
            audioSmoothingAttack = prefsRepo.getAppStatePrefFloat("audio_smoothing_attack", 0.85f),
            audioSmoothingDecay = prefsRepo.getAppStatePrefFloat("audio_smoothing_decay", 0.12f),
            beatFlashDecayMs = prefsRepo.getAppStatePrefFloat("beat_flash_decay_ms", 200f),
            ambientCapFraction = prefsRepo.getAppStatePrefFloat("ambient_cap_fraction", 0.40f),
            midFluxWeight = prefsRepo.getAppStatePrefFloat("mid_flux_weight", 0.25f),
            transmissionDelayMs = prefsRepo.getAppStatePrefInt("transmission_delay_ms", 16),
            noiseGateThreshold = prefsRepo.getAppStatePrefFloat("noise_gate_threshold", 5.0f),
            beatThresholdMultiplier = prefsRepo.getAppStatePrefFloat("beat_threshold_multiplier", 1.3f),
            beatCooldownMs = prefsRepo.getAppStatePrefInt("beat_cooldown_ms", 250),
            bassGain = prefsRepo.getAppStatePrefFloat("bass_gain", 1.0f),
            midGain = prefsRepo.getAppStatePrefFloat("mid_gain", 1.0f),
            highGain = prefsRepo.getAppStatePrefFloat("high_gain", 1.0f),
            isAutoGainEnabled = prefsRepo.getAppStatePrefBoolean("is_auto_gain_enabled", true),
            isPaletteCyclingEnabled = prefsRepo.getAppStatePrefBoolean("is_palette_cycling_enabled", true),
            isLogarithmicScalingEnabled = prefsRepo.getAppStatePrefBoolean("is_logarithmic_scaling_enabled", true),
            bluetoothDelayMs = prefsRepo.getAppStatePrefInt("bluetooth_delay_ms", 0),
            totalVisualDelayMs = prefsRepo.getAppStatePrefInt("bluetooth_delay_ms", 0) + BeatDetector().lookaheadMs.toInt(),
            visualizerPreset = prefsRepo.getAppStatePrefString("visualizer_preset", "Default") ?: "Default",
            audioGammaExponent = prefsRepo.getAppStatePrefFloat("audio_gamma_exponent", 0.45f),
            audioFlashStrength = prefsRepo.getAppStatePrefFloat("audio_flash_strength", 0.3f),
            visualizerMinBrightness = prefsRepo.getAppStatePrefFloat("visualizer_min_brightness", 0.15f),
            visualizerColorSpeed = prefsRepo.getAppStatePrefFloat("visualizer_color_speed", 1.0f),
            idleTriggerDelayMs = prefsRepo.getAppStatePrefLong("idle_trigger_delay_ms", 2500L),
            ambianceResponseSpeed = prefsRepo.getAmbiancePrefFloat("response_speed", 0.5f),
            ambianceSmoothnessMs = prefsRepo.getAmbiancePrefInt("smoothness_ms", 150),
            ambianceSaturationBoost = prefsRepo.getAmbiancePrefFloat("saturation_boost", 1.4f),
            ambianceBrightnessCompensation = prefsRepo.getAmbiancePrefFloat("brightness_compensation", 1.0f),
            ambianceUpdateRateCapFps = prefsRepo.getAmbiancePrefInt("update_rate_cap_fps", 20),
            ambianceSceneCutSensitivity = prefsRepo.getAmbiancePrefFloat("scene_cut_sensitivity", 110.0f), // Balanced default is 110
            ambianceNoiseDeadband = prefsRepo.getAmbiancePrefFloat("noise_deadband", 0.10f),
            ambiancePreset = prefsRepo.getAmbiancePrefString("ambiance_preset", "Balanced") ?: "Balanced"
        )
    )
    val uiState: StateFlow<ControllerUiState> = _uiState.asStateFlow()

    private val _protocolBytes = MutableStateFlow(
        byteArrayOf(
            0x7E.toByte(), // Header
            0x00.toByte(), // Mutable 1
            0x00.toByte(), // Mutable 2
            0x00.toByte(), // Mutable 3
            0x00.toByte(), // Mutable 4
            0x00.toByte(), // Mutable 5
            0x00.toByte(), // Mutable 6
            0x00.toByte(), // Mutable 7
            0xEF.toByte()  // Footer
        )
    )
    val protocolBytes: StateFlow<ByteArray> = _protocolBytes.asStateFlow()

        
    @Volatile var bypassColorCalibration = false
    @Volatile var currentCalibrationBrightness: Float? = null

    fun getCalibrationMatrices(calibration: ColorCalibration): Map<Float, FloatArray> {
        val map = mutableMapOf<Float, FloatArray>()
        try {
            val json = org.json.JSONObject(calibration.samplesJson)
            if (json.optBoolean("is_multi_brightness", false)) {
                val matricesObj = json.getJSONObject("matrices")
                val keys = matricesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val keyFloat = key.toFloatOrNull() ?: continue
                    val arr = matricesObj.getJSONArray(key)
                    val matrix = FloatArray(9)
                    for (i in 0..8) {
                        matrix[i] = arr.getDouble(i).toFloat()
                    }
                    map[keyFloat] = matrix
                }
            }
        } catch (e: Exception) {
            // Error parsing multi-brightness matrices
        }
        
        // If empty, populate with the single matrix from ColorCalibration fields
        if (map.isEmpty()) {
            map[100f] = floatArrayOf(
                calibration.m11, calibration.m12, calibration.m13,
                calibration.m21, calibration.m22, calibration.m23,
                calibration.m31, calibration.m32, calibration.m33
            )
        }
        return map
    }

    fun interpolateMatrices(brightnessPercent: Float, matrices: Map<Float, FloatArray>): FloatArray {
        if (matrices.isEmpty()) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }
        if (matrices.size == 1) {
            return matrices.values.first()
        }
        
        val sortedLevels = matrices.keys.sorted()
        val minLevel = sortedLevels.first()
        val maxLevel = sortedLevels.last()
        
        if (brightnessPercent <= minLevel) {
            return matrices[minLevel] ?: matrices.values.first()
        }
        if (brightnessPercent >= maxLevel) {
            return matrices[maxLevel] ?: matrices.values.first()
        }
        
        var lower = minLevel
        var upper = maxLevel
        for (i in 0 until sortedLevels.size - 1) {
            val l1 = sortedLevels[i]
            val l2 = sortedLevels[i+1]
            if (brightnessPercent >= l1 && brightnessPercent <= l2) {
                lower = l1
                upper = l2
                break
            }
        }
        
        val m1 = matrices[lower] ?: return matrices.values.first()
        val m2 = matrices[upper] ?: return m1
        
        val t = (brightnessPercent - lower) / (upper - lower)
        val result = FloatArray(9)
        for (i in 0..8) {
            result[i] = m1[i] * (1f - t) + m2[i] * t
        }
        return result
    }

    fun applyCalibrationIfRequired(address: String, cmd: ByteArray): ByteArray {
        // Old full-spectrum RGB calibration is entirely disabled per scope change
        return cmd
    }

    fun processCommandWithCalibration(address: String, command: ByteArray): ByteArray {
        if (command.size % 9 != 0) return command
        val result = ByteArray(command.size)
        for (i in 0 until command.size step 9) {
            val chunk = command.copyOfRange(i, i + 9)
            val processed = applyCalibrationIfRequired(address, chunk)
            System.arraycopy(processed, 0, result, i, 9)
        }
        return result
    }

    inner class DeviceWriteManager(
        val address: String,
        val gatt: BluetoothGatt,
        val charac: BluetoothGattCharacteristic
    ) {
        private val commandQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        @Volatile var isWriting = false
        @Volatile var lastWriteTime = 0L
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        var currentPacingMs = prefsRepo.getPacingPrefInt(address, 50)
        
        private var framesSent = 0
        private var lastFpsTime = System.currentTimeMillis()
        @Volatile private var pendingJob: kotlinx.coroutines.Job? = null
        
        private var consecutiveWatchdogTriggers = 0
        private var lastQueueLogTime = 0L

        fun updateCommand(command: ByteArray) {
            val processed = processCommandWithCalibration(address, command)
            
            val type = if (processed.size >= 3) processed[2] else null
            if (type != null) {
                val iterator = commandQueue.iterator()
                while (iterator.hasNext()) {
                    val existing = iterator.next()
                    if (existing.size >= 3 && existing[2] == type) {
                        iterator.remove()
                    }
                }
            }
            
            val qSizeBefore = commandQueue.size
            // Fallback limit just in case
            if (commandQueue.size > 20) {
                commandQueue.poll()
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "Backpressure triggered (Queue size > 20)! Polled/dropped command. address=$address. (${getDiagAttribution(address)})"
                )
            }
            commandQueue.offer(processed)
            val qSizeAfter = commandQueue.size
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "Write enqueued: address=$address, cmdHex=${processed.joinToString("") { String.format("%02X", it) }}, queueSizeBefore=$qSizeBefore, queueSizeAfter=$qSizeAfter. (${getDiagAttribution(address)})"
            )
            
            val now = System.currentTimeMillis()
            if (now - lastQueueLogTime >= 1000L) {
                Log.d("BleWriteQueue", "Queue size for $address: ${commandQueue.size}")
                lastQueueLogTime = now
            }

            tryWrite()
        }

        fun onWriteCompleted() {
            com.example.DiagnosticLogger.log(
                "DeviceWriteManager",
                "onWriteCompleted callback received for $address. (${getDiagAttribution(address)})"
            )
            consecutiveWatchdogTriggers = 0
            lastWriteTime = System.currentTimeMillis()
            isWriting = false
            framesSent++
            val now = System.currentTimeMillis()
            if (now - lastFpsTime >= 1000L) {
                val fps = framesSent
                framesSent = 0
                lastFpsTime = now
                _uiState.update { s -> 
                    s.copy(deviceAchievedFps = s.deviceAchievedFps + (address to fps)) 
                }
            }
            tryWrite()
        }

        @Synchronized
        private fun tryWrite() {
            if (isWriting) return
            val cmd = commandQueue.peek() ?: return
            
            val now = System.currentTimeMillis()
            val elapsed = now - lastWriteTime
            
            if (currentPacingMs > 0) {
                if (elapsed < currentPacingMs) {
                    if (pendingJob == null || pendingJob?.isActive != true) {
                        pendingJob = connectionScope.launch(Dispatchers.IO) {
                            delay(currentPacingMs - elapsed)
                            pendingJob = null
                            tryWrite()
                        }
                    }
                    return
                }
            }
            
            isWriting = true
            val cmdToWrite = commandQueue.poll()
            if (cmdToWrite == null) {
                isWriting = false
                return
            }
            val currentWriteTime = System.currentTimeMillis()
            lastWriteTime = currentWriteTime
            
            charac.writeType = writeType
            try {
                val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(charac, cmdToWrite, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    charac.value = cmdToWrite
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(charac)
                }
                
                if (!success) {
                    Log.w("BleWriteQueue", "writeCharacteristic() returned false for $address")
                    com.example.DiagnosticLogger.log(
                        "DeviceWriteManager",
                        "writeCharacteristic() returned false (write failure) for $address. (${getDiagAttribution(address)})"
                    )
                } else {
                    com.example.DiagnosticLogger.log(
                        "DeviceWriteManager",
                        "writeCharacteristic() initiated (write success) for $address. (${getDiagAttribution(address)})"
                    )
                }
            } catch (e: Exception) {
                isWriting = false
                com.example.DiagnosticLogger.log(
                    "DeviceWriteManager",
                    "writeCharacteristic() Exception for $address: ${android.util.Log.getStackTraceString(e)}. (${getDiagAttribution(address)})"
                )
                return
            }
            
            connectionScope.launch(Dispatchers.IO) {
                com.example.DiagnosticLogger.log(
                    "BleWriteWatchdog",
                    "Watchdog check tick scheduled for device $address (currentWriteTime=$currentWriteTime). (${getDiagAttribution(address)})"
                )
                delay(2000)
                com.example.DiagnosticLogger.log(
                    "BleWriteWatchdog",
                    "Watchdog check tick running for device $address: isWriting=$isWriting, lastWriteTime=$lastWriteTime, expectedWriteTime=$currentWriteTime, consecutiveWatchdogTriggers=$consecutiveWatchdogTriggers. (${getDiagAttribution(address)})"
                )
                if (isWriting && lastWriteTime == currentWriteTime) {
                    Log.w("BleWriteWatchdog", "Watchdog fired for device $address at timestamp $currentWriteTime — forcing reset")
                    com.example.DiagnosticLogger.log(
                        "BleWriteWatchdog",
                        "Watchdog FIRED for device $address at timestamp $currentWriteTime — forcing reset. (${getDiagAttribution(address)})"
                    )
                    isWriting = false
                    consecutiveWatchdogTriggers++
                    
                    if (consecutiveWatchdogTriggers >= 3) {
                        Log.e("BleWriteWatchdog", "device $address appears frozen — forcing reconnect")
                        com.example.DiagnosticLogger.log(
                            "BleWriteWatchdog",
                            "device $address appears frozen (consecutiveTriggers=$consecutiveWatchdogTriggers) — forcing reconnect. (${getDiagAttribution(address)})"
                        )
                        consecutiveWatchdogTriggers = 0
                        try {
                            gatt.disconnect()
                        } catch (e: Exception) {
                            Log.e("BleWriteWatchdog", "Exception forcing disconnect on frozen device", e)
                            com.example.DiagnosticLogger.log(
                                "BleWriteWatchdog",
                                "Exception forcing disconnect on frozen device $address: ${android.util.Log.getStackTraceString(e)}"
                            )
                        }
                    } else {
                        tryWrite()
                    }
                }
            }
        }
    }

    private val sceneRunners = ConcurrentHashMap<String, SceneAnimationRunner>()

    private val testPatternJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun toggleTestPattern(address: String) {
        val isRunning = _uiState.value.isTestPatternRunning[address] == true
        if (isRunning) {
            testPatternJobs[address]?.cancel()
            testPatternJobs.remove(address)
            _uiState.update { s -> s.copy(isTestPatternRunning = s.isTestPatternRunning + (address to false)) }
        } else {
            _uiState.update { s -> s.copy(isTestPatternRunning = s.isTestPatternRunning + (address to true)) }
            testPatternJobs[address] = viewModelScope.launch {
                val colors = listOf(
                    android.graphics.Color.RED,
                    android.graphics.Color.GREEN,
                    android.graphics.Color.BLUE,
                    android.graphics.Color.WHITE
                )
                var idx = 0
                while (isActive) {
                    val c = colors[idx % colors.size]
                    val cmd = DuoCoProtocol.createColorCommand(
                        android.graphics.Color.red(c),
                        android.graphics.Color.green(c),
                        android.graphics.Color.blue(c)
                    )
                    deviceWriteManagers[address]?.updateCommand(cmd)
                    idx++
                    // Try to send at maximum speed to see achievable rate, but maybe we should pace it to see distinct colors?
                    // We can just sleep for a small amount, or let the DeviceWriteManager throttle it.
                    // If we want the user to see the colors, we should pace it at maybe 50ms interval, or whatever current pacing is.
                    // Wait, the test pattern is to visually judge. The user needs to see it cycles fast.
                    // Actually let's just cycle it every 50ms, the pacing will throttle it if needed.
                    delay(30)
                }
            }
        }
    }

    fun setDevicePacing(address: String, ms: Int) {
        val manager = deviceWriteManagers[address]
        if (manager != null) {
            manager.currentPacingMs = ms
            prefsRepo.putPacingPrefInt(address, ms)
            _uiState.update { s -> 
                s.copy(devicePacingMs = s.devicePacingMs + (address to ms)) 
            }
        }
    }

    private val _byteOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val byteOverrides: StateFlow<Map<String, String>> = _byteOverrides.asStateFlow()

    private fun loadOverridesFromPrefs() {
        val allPrefs = prefsRepo.getProtocolOverrideAll()
        val overridesMap = mutableMapOf<String, String>()
        for ((key, value) in allPrefs) {
            if (value is String) {
                val bytes = DuoCoProtocol.parseHex(value)
                if (bytes != null) {
                    DuoCoProtocol.overrides[key] = bytes
                    overridesMap[key] = value
                }
            }
        }
        _byteOverrides.value = overridesMap
    }

    fun updateOverride(key: String, hexString: String) {
        val cleanHex = hexString.trim().uppercase()
        if (cleanHex.isEmpty()) {
            prefsRepo.removeProtocolOverride(key)
            DuoCoProtocol.overrides.remove(key)
            _byteOverrides.update { it - key }
        } else {
            val bytes = DuoCoProtocol.parseHex(cleanHex)
            if (bytes != null) {
                val formatted = DuoCoProtocol.formatHex(bytes)
                prefsRepo.putProtocolOverrideString(key, formatted)
                DuoCoProtocol.overrides[key] = bytes
                _byteOverrides.update { it + (key to formatted) }
            }
        }
    }

    private var lastWriteTime = 0L
    private var pendingWriteJob: kotlinx.coroutines.Job? = null

    var mediaProjectionResultCode: Int = 0
    var mediaProjectionIntent: Intent? = null



    fun updateProtocolByte(index: Int, value: Int) {
        if (index in 1..7) {
            val current = _protocolBytes.value.clone()
            current[index] = value.toByte()
            _protocolBytes.value = current

            pendingWriteJob?.cancel()
            pendingWriteJob = viewModelScope.launch {
                val now = System.currentTimeMillis()
                val elapsed = now - lastWriteTime
                if (elapsed < 40) {
                    delay(40 - elapsed)
                }
                sendCommand(current, "Protocol Explorer")
                lastWriteTime = System.currentTimeMillis()
            }
        }
    }

    private var bluetoothAdapter: BluetoothAdapter? = null

            private val metronomePlayer = MetronomePlayer()
    private var metronomeJob: kotlinx.coroutines.Job? = null
    @Volatile private var activeBluetoothAudioDevice: BluetoothDevice? = null

    private val routingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val action = intent?.action
            if (action == "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED") {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as? BluetoothDevice
                }
                activeBluetoothAudioDevice = device
                addLog("RoutingReceiver: active device changed to ${device?.name ?: "none"} (${device?.address ?: "none"})")
                checkActiveAudioRoute()
            }
        }
    }

    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            checkActiveAudioRoute()
        }
        
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            checkActiveAudioRoute()
        }
    }

    // Handler to run scan timeouts
    
    private val _scenes = MutableStateFlow<List<AppScene>>(emptyList())
    val scenes: StateFlow<List<AppScene>> = _scenes.asStateFlow()

    private var sceneChainJob: kotlinx.coroutines.Job? = null

    private fun cancelSceneChain() {
        sceneChainJob?.cancel()
        sceneChainJob = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopScanning() }

    private fun saveDeviceState(macAddress: String, mode: AutomationType) {
        if (deviceAutomationMode[macAddress] == null) {
            val s = _uiState.value
            val devState = s.deviceStatesMap[macAddress]
            val red = devState?.red ?: s.red
            val green = devState?.green ?: s.green
            val blue = devState?.blue ?: s.blue
            val warmth = devState?.warmth ?: s.warmth
            val brightness = devState?.brightness ?: s.brightness
            val power = devState?.isPowerOn ?: s.isPowerOn
            
            val featureName = devState?.activeFeatureName ?: s.activeFeatureName
            deviceRestoredFeatureName[macAddress] = if (featureName == "Colour" || featureName == "CCT") featureName else "Colour"
            
            deviceAutomationMode[macAddress] = mode
            
            viewModelScope.launch(Dispatchers.IO) {
                deviceStateStore.saveState(
                    macAddress = macAddress,
                    power = power,
                    red = red,
                    green = green,
                    blue = blue,
                    warmth = warmth,
                    brightness = brightness
                )
            }
        }
    }

    private fun restoreDeviceState(macAddress: String, mode: AutomationType) {
        if (deviceAutomationMode[macAddress] == mode) {
            deviceAutomationMode.remove(macAddress)
            viewModelScope.launch(Dispatchers.IO) {
                val state = deviceStateStore.getState(macAddress)
                if (state != null) {
                    val powerCmd = DuoCoProtocol.createPowerCommand(state.power)
                    val brightnessCmd = DuoCoProtocol.createBrightnessCommand(state.brightness)
                    val colorCmd = DuoCoProtocol.createColorCommand(state.red, state.green, state.blue)
                    
                    sendCommandToDeviceDirect(macAddress, powerCmd)
                    sendCommandToDeviceDirect(macAddress, brightnessCmd)
                    sendCommandToDeviceDirect(macAddress, colorCmd)
                    
                    val memoryFeatureName = deviceRestoredFeatureName.remove(macAddress)
                    val restoredFeatureName = if (memoryFeatureName != null) {
                        memoryFeatureName
                    } else {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(state.warmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        val isCct = rgb[0] == state.red && rgb[1] == state.green && rgb[2] == state.blue
                        if (isCct) "CCT" else "Colour"
                    }
                    
                    _uiState.update { current ->
                        val newMap = current.deviceStatesMap.toMutableMap()
                        val existing = newMap[macAddress] ?: ActiveDeviceState()
                        newMap[macAddress] = existing.copy(
                            activeFeatureName = restoredFeatureName,
                            red = state.red,
                            green = state.green,
                            blue = state.blue,
                            warmth = state.warmth,
                            brightness = state.brightness,
                            isPowerOn = state.power
                        )
                        current.copy(deviceStatesMap = newMap)
                    }
                }
            }
        }
    }

    // Preset list, Device alias list, and Saved devices list from Room
    val savedPresets: StateFlow<List<RgbPreset>>
    val savedAliases: StateFlow<List<RgbDeviceAlias>>
    val savedDevices: StateFlow<List<SavedDevice>>
    val customModes: StateFlow<List<CustomMode>>

    init {
        instance = this
        loadOverridesFromPrefs()
        _scenes.value = prefsRepo.loadScenes()
        
        savedPresets = repository.allPresets.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        savedAliases = repository.allDeviceAliases.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        savedDevices = repository.allSavedDevices.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        customModes = repository.allCustomModes.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Initialize custom modes with default values if empty
        viewModelScope.launch {
            customModes.collect { list ->
                val currentVersion = prefsRepo.getAppStatePrefInt("custom_modes_version", 1)
                val isOldDb = list.isNotEmpty() && (list.size < 200 || currentVersion < 3)
                if (list.isEmpty() || isOldDb) {
                    if (isOldDb) {
                        repository.deleteAllCustomModes()
                    }
                    prefsRepo.putAppStatePrefInt("custom_modes_version", 3)
                    val defaultModes = mutableListOf<CustomMode>()
                    defaultModes.add(CustomMode(0, "Auto Play", "Classic Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(1, "Magic Forward", "Classic Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(2, "Magic Back", "Classic Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(212, "7-Color Energy", "Classic Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(193, "7-Color Jump", "Jump Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(194, "R-G-B Jump", "Jump Effects", "none", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(195, "Y-C-P Jump", "Jump Effects", "none", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(196, "7-Color Strobe", "Strobe Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(197, "R-G-B Strobe", "Strobe Effects", "none", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(198, "Y-C-P Strobe", "Strobe Effects", "none", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(199, "7-Color Gradual", "Gradual Effects", "none", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(200, "R-Y Gradual", "Gradual Effects", "none", "Red,Yellow"))
                    defaultModes.add(CustomMode(201, "R-P Gradual", "Gradual Effects", "none", "Red,Purple"))
                    defaultModes.add(CustomMode(202, "G-C Gradual", "Gradual Effects", "none", "Green,Cyan"))
                    defaultModes.add(CustomMode(203, "G-Y Gradual", "Gradual Effects", "none", "Green,Yellow"))
                    defaultModes.add(CustomMode(204, "B-P Gradual", "Gradual Effects", "none", "Blue,Purple"))
                    defaultModes.add(CustomMode(205, "Red Marquee", "Marquee Effects", "up", "Red"))
                    defaultModes.add(CustomMode(206, "Green Marquee", "Marquee Effects", "up", "Green"))
                    defaultModes.add(CustomMode(207, "Blue Marquee", "Marquee Effects", "up", "Blue"))
                    defaultModes.add(CustomMode(208, "Yellow Marquee", "Marquee Effects", "up", "Yellow"))
                    defaultModes.add(CustomMode(209, "Cyan Marquee", "Marquee Effects", "up", "Cyan"))
                    defaultModes.add(CustomMode(210, "Purple Marquee", "Marquee Effects", "up", "Purple"))
                    defaultModes.add(CustomMode(211, "White Marquee", "Marquee Effects", "up", "White"))
                    defaultModes.add(CustomMode(77, "7-Color Race", "Race Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(78, "7-Color Race Back", "Race Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(79, "R-G-B Race", "Race Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(80, "R-G-B Race Back", "Race Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(81, "Y-C-P Race", "Race Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(82, "Y-C-P Race Back", "Race Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(83, "7-Color Wave", "Wave Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(84, "7-Color Wave Back", "Wave Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(85, "R-G-B Wave", "Wave Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(86, "R-G-B Wave Back", "Wave Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(87, "Y-C-P Wave", "Wave Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(88, "Y-C-P Wave Back", "Wave Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(181, "7-Color Flush", "Flush Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(182, "7-Color Flush Back", "Flush Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(183, "R-G-B Flush", "Flush Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(184, "R-G-B Flush Back", "Flush Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(185, "Y-C-P Flush", "Flush Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(186, "Y-C-P Flush Back", "Flush Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(187, "7-Color Flush Close", "Flush Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(188, "7-Color Flush Open", "Flush Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(189, "R-G-B Flush Close", "Flush Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(190, "R-G-B Flush Open", "Flush Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(191, "Y-C-P Flush Close", "Flush Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(192, "Y-C-P Flush Open", "Flush Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(57, "7-Color Close", "Curtain Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(58, "7-Color Open", "Curtain Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(59, "R-G-B Close", "Curtain Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(60, "R-G-B Open", "Curtain Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(61, "Y-C-P Close", "Curtain Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(62, "Y-C-P Open", "Curtain Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(63, "Red Close", "Curtain Effects", "down", "Red"))
                    defaultModes.add(CustomMode(64, "Red Open", "Curtain Effects", "up", "Red"))
                    defaultModes.add(CustomMode(65, "Green Close", "Curtain Effects", "down", "Green"))
                    defaultModes.add(CustomMode(66, "Green Open", "Curtain Effects", "up", "Green"))
                    defaultModes.add(CustomMode(67, "Blue Close", "Curtain Effects", "down", "Blue"))
                    defaultModes.add(CustomMode(68, "Blue Open", "Curtain Effects", "up", "Blue"))
                    defaultModes.add(CustomMode(69, "Yellow Close", "Curtain Effects", "down", "Yellow"))
                    defaultModes.add(CustomMode(70, "Yellow Open", "Curtain Effects", "up", "Yellow"))
                    defaultModes.add(CustomMode(71, "Cyan Close", "Curtain Effects", "down", "Cyan"))
                    defaultModes.add(CustomMode(72, "Cyan Open", "Curtain Effects", "up", "Cyan"))
                    defaultModes.add(CustomMode(73, "Purple Close", "Curtain Effects", "down", "Purple"))
                    defaultModes.add(CustomMode(74, "Purple Open", "Curtain Effects", "up", "Purple"))
                    defaultModes.add(CustomMode(75, "White Close", "Curtain Effects", "down", "White"))
                    defaultModes.add(CustomMode(76, "White Open", "Curtain Effects", "up", "White"))
                    defaultModes.add(CustomMode(3, "7-Color Trans", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(4, "7-Color Trans Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(5, "R-G-B Trans", "Transition Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(6, "R-G-B Trans Back", "Transition Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(7, "Y-C-P Trans", "Transition Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(8, "Y-C-P Trans Back", "Transition Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(9, "6-Color to Red", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(10, "6-Color to Red Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(11, "6-Color to Green", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(12, "6-Color to Green Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(13, "6-Color to Blue", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(14, "6-Color to Blue Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(15, "6-Color to Cyan", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(16, "6-Color to Cyan Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(17, "6-Color to Yellow", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(18, "6-Color to Yellow Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(19, "6-Color to Purple", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(20, "6-Color to Purple Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(21, "6-Color to White", "Transition Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(22, "6-Color to White Back", "Transition Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(39, "7-Color Water", "Water Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(40, "7-Color Water Back", "Water Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(41, "R-G-B Water", "Water Effects", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(42, "R-G-B Water Back", "Water Effects", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(43, "Y-C-P Water", "Water Effects", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(44, "Y-C-P Water Back", "Water Effects", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(45, "R-G Water", "Water Effects", "up", "Red,Green"))
                    defaultModes.add(CustomMode(46, "R-G Water Back", "Water Effects", "down", "Red,Green"))
                    defaultModes.add(CustomMode(47, "G-B Water", "Water Effects", "up", "Green,Blue"))
                    defaultModes.add(CustomMode(48, "G-B Water Back", "Water Effects", "down", "Green,Blue"))
                    defaultModes.add(CustomMode(49, "Y-B Water", "Water Effects", "up", "Yellow,Blue"))
                    defaultModes.add(CustomMode(50, "Y-B Water Back", "Water Effects", "down", "Yellow,Blue"))
                    defaultModes.add(CustomMode(51, "Y-C Water", "Water Effects", "up", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(52, "Y-C Water Back", "Water Effects", "down", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(53, "C-P Water", "Water Effects", "up", "Cyan,Purple"))
                    defaultModes.add(CustomMode(54, "C-P Water Back", "Water Effects", "down", "Cyan,Purple"))
                    defaultModes.add(CustomMode(55, "White Water", "Water Effects", "up", "White"))
                    defaultModes.add(CustomMode(56, "White Water Back", "Water Effects", "down", "White"))
                    defaultModes.add(CustomMode(143, "W-R-W Flow", "Flow Effects", "up", "Red,White"))
                    defaultModes.add(CustomMode(144, "W-R-W Flow Back", "Flow Effects", "down", "Red,White"))
                    defaultModes.add(CustomMode(145, "W-G-W Flow", "Flow Effects", "up", "Green,White"))
                    defaultModes.add(CustomMode(146, "W-G-W Flow Back", "Flow Effects", "down", "Green,White"))
                    defaultModes.add(CustomMode(147, "W-B-W Flow", "Flow Effects", "up", "Blue,White"))
                    defaultModes.add(CustomMode(148, "W-B-W Flow Back", "Flow Effects", "down", "Blue,White"))
                    defaultModes.add(CustomMode(149, "W-Y-W Flow", "Flow Effects", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(150, "W-Y-W Flow Back", "Flow Effects", "down", "Yellow,White"))
                    defaultModes.add(CustomMode(151, "W-C-W Flow", "Flow Effects", "up", "Cyan,White"))
                    defaultModes.add(CustomMode(152, "W-C-W Flow Back", "Flow Effects", "down", "Cyan,White"))
                    defaultModes.add(CustomMode(153, "W-P-W Flow", "Flow Effects", "up", "Purple,White"))
                    defaultModes.add(CustomMode(154, "W-P-W Flow Back", "Flow Effects", "down", "Purple,White"))
                    defaultModes.add(CustomMode(155, "R-W-R Flow", "Flow Effects", "up", "Red,White"))
                    defaultModes.add(CustomMode(156, "R-W-R Flow Back", "Flow Effects", "down", "Red,White"))
                    defaultModes.add(CustomMode(157, "G-W-G Flow", "Flow Effects", "up", "Green,White"))
                    defaultModes.add(CustomMode(158, "G-W-G Flow Back", "Flow Effects", "down", "Green,White"))
                    defaultModes.add(CustomMode(159, "B-W-B Flow", "Flow Effects", "up", "Blue,White"))
                    defaultModes.add(CustomMode(160, "B-W-B Flow Back", "Flow Effects", "down", "Blue,White"))
                    defaultModes.add(CustomMode(161, "Y-W-Y Flow", "Flow Effects", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(162, "Y-W-Y Flow Back", "Flow Effects", "down", "Yellow,White"))
                    defaultModes.add(CustomMode(163, "C-W-C Flow", "Flow Effects", "up", "Cyan,White"))
                    defaultModes.add(CustomMode(164, "C-W-C Flow Back", "Flow Effects", "down", "Cyan,White"))
                    defaultModes.add(CustomMode(165, "P-W-P Flow", "Flow Effects", "up", "Purple,White"))
                    defaultModes.add(CustomMode(166, "P-W-P Flow Back", "Flow Effects", "down", "Purple,White"))
                    defaultModes.add(CustomMode(23, "7-Color Tail", "Tail Effects", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(24, "7-Color Tail Back", "Tail Effects", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(25, "Red Tail", "Tail Effects", "up", "Red"))
                    defaultModes.add(CustomMode(26, "Red Tail Back", "Tail Effects", "down", "Red"))
                    defaultModes.add(CustomMode(27, "Green Tail", "Tail Effects", "up", "Green"))
                    defaultModes.add(CustomMode(28, "Green Tail Back", "Tail Effects", "down", "Green"))
                    defaultModes.add(CustomMode(29, "Blue Tail", "Tail Effects", "up", "Blue"))
                    defaultModes.add(CustomMode(30, "Blue Tail Back", "Tail Effects", "down", "Blue"))
                    defaultModes.add(CustomMode(31, "Yellow Tail", "Tail Effects", "up", "Yellow"))
                    defaultModes.add(CustomMode(32, "Yellow Tail Back", "Tail Effects", "down", "Yellow"))
                    defaultModes.add(CustomMode(33, "Cyan Tail", "Tail Effects", "up", "Cyan"))
                    defaultModes.add(CustomMode(34, "Cyan Tail Back", "Tail Effects", "down", "Cyan"))
                    defaultModes.add(CustomMode(35, "Purple Tail", "Tail Effects", "up", "Purple"))
                    defaultModes.add(CustomMode(36, "Purple Tail Back", "Tail Effects", "down", "Purple"))
                    defaultModes.add(CustomMode(37, "White Tail", "Tail Effects", "up", "White"))
                    defaultModes.add(CustomMode(38, "White Tail Back", "Tail Effects", "down", "White"))
                    defaultModes.add(CustomMode(89, "Red Running", "Forward Chase", "up", "Red"))
                    defaultModes.add(CustomMode(91, "Green Running", "Forward Chase", "up", "Green"))
                    defaultModes.add(CustomMode(93, "Blue Running", "Forward Chase", "up", "Blue"))
                    defaultModes.add(CustomMode(95, "Yellow Running", "Forward Chase", "up", "Yellow"))
                    defaultModes.add(CustomMode(97, "Cyan Running", "Forward Chase", "up", "Cyan"))
                    defaultModes.add(CustomMode(99, "Purple Running", "Forward Chase", "up", "Purple"))
                    defaultModes.add(CustomMode(101, "White Running", "Forward Chase", "up", "White"))
                    defaultModes.add(CustomMode(103, "7-Color Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(105, "R-G-B Running", "Forward Chase", "up", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(107, "Y-C-P Running", "Forward Chase", "up", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(109, "B-P-C-Y Running", "Forward Chase", "up", "Blue,Purple,Cyan,Yellow"))
                    defaultModes.add(CustomMode(111, "B-G-C-Y Running", "Forward Chase", "up", "Blue,Green,Cyan,Yellow"))
                    defaultModes.add(CustomMode(113, "Red-Dot in White Running", "Forward Chase", "up", "Red,White"))
                    defaultModes.add(CustomMode(115, "Green-Dot in Red Running", "Forward Chase", "up", "Red,Green"))
                    defaultModes.add(CustomMode(117, "Blue-Dot in Green Running", "Forward Chase", "up", "Green,Blue"))
                    defaultModes.add(CustomMode(119, "Yellow-Dot in Blue Running", "Forward Chase", "up", "Blue,Yellow,White"))
                    defaultModes.add(CustomMode(121, "Cyan-Dot in Yellow Running", "Forward Chase", "up", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(123, "Purple-Dot in Cyan Running", "Forward Chase", "up", "Cyan,Purple"))
                    defaultModes.add(CustomMode(125, "White-Dot in Purple Running", "Forward Chase", "up", "Purple,White"))
                    defaultModes.add(CustomMode(127, "White-Dot in Red Running", "Forward Chase", "up", "Red,White"))
                    defaultModes.add(CustomMode(129, "7-Color in Red Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(131, "7-Color in Green Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(133, "7-Color in Blue Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(135, "7-Color in Yellow Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(137, "7-Color in Cyan Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(139, "7-Color in Purple Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(141, "7-Color in White Running", "Forward Chase", "up", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(167, "Green-Dot in Blue Running", "Forward Chase", "up", "Green,Blue"))
                    defaultModes.add(CustomMode(169, "Green-Dot in Red Running", "Forward Chase", "up", "Red,Green"))
                    defaultModes.add(CustomMode(171, "Red-Dot in Blue Running", "Forward Chase", "up", "Red,Blue"))
                    defaultModes.add(CustomMode(173, "Cyan-Dot in Yellow Running", "Forward Chase", "up", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(175, "Yellow-Dot in Purple Running", "Forward Chase", "up", "Yellow,Purple,White"))
                    defaultModes.add(CustomMode(177, "White-Dot in Yellow Running", "Forward Chase", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(179, "Yellow-Dot in White Running", "Forward Chase", "up", "Yellow,White"))
                    defaultModes.add(CustomMode(90, "Red Run Back", "Reverse Chase", "down", "Red"))
                    defaultModes.add(CustomMode(92, "Green Run Back", "Reverse Chase", "down", "Green"))
                    defaultModes.add(CustomMode(94, "Blue Run Back", "Reverse Chase", "down", "Blue"))
                    defaultModes.add(CustomMode(96, "Yellow Run Back", "Reverse Chase", "down", "Yellow"))
                    defaultModes.add(CustomMode(98, "Cyan Run Back", "Reverse Chase", "down", "Cyan"))
                    defaultModes.add(CustomMode(100, "Purple Run Back", "Reverse Chase", "down", "Purple"))
                    defaultModes.add(CustomMode(102, "White Run Back", "Reverse Chase", "down", "White"))
                    defaultModes.add(CustomMode(104, "7-Color Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(106, "R-G-B Run Back", "Reverse Chase", "down", "Red,Green,Blue"))
                    defaultModes.add(CustomMode(108, "Y-C-P Run Back", "Reverse Chase", "down", "Yellow,Cyan,Purple"))
                    defaultModes.add(CustomMode(110, "B-P-C-Y Run Back", "Reverse Chase", "down", "Blue,Purple,Cyan,Yellow"))
                    defaultModes.add(CustomMode(112, "B-G-C-Y Run Back", "Reverse Chase", "down", "Blue,Green,Cyan,Yellow"))
                    defaultModes.add(CustomMode(114, "Red-Dot in White Run Back", "Reverse Chase", "down", "Red,White"))
                    defaultModes.add(CustomMode(116, "Green-Dot in Red Run Back", "Reverse Chase", "down", "Red,Green"))
                    defaultModes.add(CustomMode(118, "Blue-Dot in Green Run Back", "Reverse Chase", "down", "Green,Blue"))
                    defaultModes.add(CustomMode(120, "Yellow-Dot in Blue Run Back", "Reverse Chase", "down", "Blue,Yellow,White"))
                    defaultModes.add(CustomMode(122, "Cyan-Dot in Yellow Run Back", "Reverse Chase", "down", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(124, "Purple-Dot in Cyan Run Back", "Reverse Chase", "down", "Cyan,Purple"))
                    defaultModes.add(CustomMode(126, "White-Dot in Purple Run Back", "Reverse Chase", "down", "Purple,White"))
                    defaultModes.add(CustomMode(128, "White-Dot in Red Run Back", "Reverse Chase", "down", "Red,White"))
                    defaultModes.add(CustomMode(130, "7-Color in Red Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(132, "7-Color in Green Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(134, "7-Color in Blue Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(136, "7-Color in Yellow Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(138, "7-Color in Cyan Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(140, "7-Color in Purple Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(142, "7-Color in White Run Back", "Reverse Chase", "down", "Red,Green,Blue,Yellow,Cyan,Pink,Purple,White"))
                    defaultModes.add(CustomMode(168, "Green-Dot in Blue Run Back", "Reverse Chase", "down", "Green,Blue"))
                    defaultModes.add(CustomMode(170, "Green-Dot in Red Run Back", "Reverse Chase", "down", "Red,Green"))
                    defaultModes.add(CustomMode(172, "Red-Dot in Blue Run Back", "Reverse Chase", "down", "Red,Blue"))
                    defaultModes.add(CustomMode(174, "Cyan-Dot in Yellow Run Back", "Reverse Chase", "down", "Yellow,Cyan"))
                    defaultModes.add(CustomMode(176, "Yellow-Dot in Purple Run Back", "Reverse Chase", "down", "Yellow,Purple,White"))
                    defaultModes.add(CustomMode(178, "White-Dot in Yellow Run Back", "Reverse Chase", "down", "Yellow,White"))
                    defaultModes.add(CustomMode(180, "Yellow-Dot in White Run Back", "Reverse Chase", "down", "Yellow,White"))

                    repository.insertCustomModes(defaultModes)
                }
            }
        }

        // Initialize BluetoothAdapter
        val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // If no hardware Bluetooth available, enforce Demo Mode automatically
        if (bluetoothAdapter == null) {
            _uiState.update { it.copy(isDemoMode = true) }
            addLog("No BLE hardware found. Auto-fallback to Demo/Simulation Mode.")
        } else {
            _uiState.update { it.copy(isDemoMode = false) }
            addLog("BLE adapter loaded. Ready to scan!")
        }

        // Combine DB aliases with scanned devices
        viewModelScope.launch {
            combine(savedAliases, _uiState) { aliases, state ->
                // Map aliases to devices
                state.scannedDevices.map { dev ->
                    val foundAlias = aliases.find { it.macAddress == dev.address }
                    dev.copy(alias = foundAlias?.aliasName)
                }
            }.collect { updatedDevices ->
                _uiState.update { it.copy(scannedDevices = updatedDevices) }
            }
        }

        // Collect and sync color calibrations
        viewModelScope.launch {
            repository.allColorCalibrations.collect { calibrations ->
                val calMap = calibrations.associateBy { it.macAddress }
                _uiState.update { it.copy(colorCalibrations = calMap) }
            }
        }

        // Initialize with default color preset in database if empty
        viewModelScope.launch {
            savedPresets.collect { list ->
                if (list.isEmpty()) {
                    repository.insertPreset(RgbPreset(name = "Chill Sunset", red = 255, green = 110, blue = 40, brightness = 90))
                    repository.insertPreset(RgbPreset(name = "Cyberpunk Neon", red = 255, green = 0, blue = 255, brightness = 100))
                    repository.insertPreset(RgbPreset(name = "Forest Green", red = 0, green = 255, blue = 80, brightness = 75))
                    repository.insertPreset(RgbPreset(name = "Ocean Breeze", red = 0, green = 150, blue = 255, brightness = 80))
                }
            }
        }

        // Background auto-connection scanner loop
        viewModelScope.launch {
            while (true) {
                delay(10000) // check every 10 seconds
                val hasDisconnectedAutoConnectDevices = savedDevices.value.any { saved ->
                    saved.isAutoConnectEnabled && 
                    !connectionManager.isManuallyDisconnected(saved.macAddress) &&
                    _uiState.value.deviceConnectionStates[saved.macAddress] != BleConnectionState.CONNECTED &&
                    _uiState.value.deviceConnectionStates[saved.macAddress] != BleConnectionState.CONNECTING
                }
                
                if (hasDisconnectedAutoConnectDevices && !_uiState.value.isScanning) {
                    addLog("Background manager: locating disconnected auto-connect devices...")
                    startScanning()
                }
            }
        }

        // Register routing receiver and audio device callback
        val filter = android.content.IntentFilter().apply {
            addAction("android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED")
        }
        application.registerReceiver(routingReceiver, filter)
        val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
        // Initial run of active audio route check
        checkActiveAudioRoute()
        loadCctCalibrations()

        // Restore/re-register DeviceWriteManagers for existing active connections to survive Activity/ViewModel recreation
        activeConnections.forEach { (address, gatt) ->
            val charac = writeCharacteristics[address]
            if (charac != null) {
                val manager = DeviceWriteManager(address, gatt, charac)
                deviceWriteManagers[address] = manager
                addLog("Restored DeviceWriteManager for existing active connection: $address")
            }
        }

        // Sync initial UI state with existing active BLE connections
        if (activeConnections.isNotEmpty()) {
            val initialStates = activeConnections.keys.associateWith { BleConnectionState.CONNECTED }
            val firstConnectedAddress = activeConnections.keys.first()
            _uiState.update { s ->
                s.copy(
                    deviceConnectionStates = s.deviceConnectionStates + initialStates,
                    connectionState = BleConnectionState.CONNECTED,
                    connectedDeviceAddress = firstConnectedAddress,
                    connectedDeviceName = "DuoCo Light"
                )
            }
            addLog("Synchronized UI state with ${activeConnections.size} active connection(s).")
        }

        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.allSavedDevices,
                repository.allCustomModes,
                repository.allPresets
            ) { _, _, _ -> true }
                .collect {
                    _uiState.update { s -> s.copy(isDbLoaded = true) }
                }
        }

        // Reactively sync slowest connected device's pacing to shared preferences for Ambiance capture service
        viewModelScope.launch {
            _uiState.collect { state ->
                val connected = state.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }
                val slowest = if (connected.isEmpty()) {
                    0
                } else {
                    connected.keys.mapNotNull { addr ->
                        state.devicePacingMs[addr] ?: prefsRepo.getPacingPrefInt(addr, 100)
                    }.maxOrNull() ?: 0
                }
                prefsRepo.putPacingPrefInt("slowest_connected_pacing", slowest)
            }
        }

        viewModelScope.launch {
            var lastActive = false
            com.example.ambiance.AmbianceCaptureState.isActive.collect { active ->
                if (active && !lastActive) {
                    val targetAddresses = getCurrentlyControlledDeviceAddresses()
                    targetAddresses.forEach { address ->
                        saveDeviceState(address, AutomationType.AMBIANCE)
                    }
                    val presetName = _uiState.value.ambiancePreset ?: "Balanced"
                    val label = "Ambiance - $presetName"
                    _uiState.update { current ->
                        val newMap = current.deviceStatesMap.toMutableMap()
                        targetAddresses.forEach { address ->
                            val existing = newMap[address] ?: ActiveDeviceState()
                            newMap[address] = existing.copy(activeFeatureName = label)
                        }
                        current.copy(deviceStatesMap = newMap)
                    }
                } else if (!active && lastActive) {
                    deviceAutomationMode.forEach { (address, mode) ->
                        if (mode == AutomationType.AMBIANCE) {
                            restoreDeviceState(address, AutomationType.AMBIANCE)
                        }
                    }
                }
                 lastActive = active
            }
        }
    }


    private fun loadCctCalibrations() {
        val calMap = mutableMapOf<String, CctCorrectionProfile>()
        try {
            val allPrefs = prefsRepo.getCctCalibrationAll()
            for ((address, jsonStr) in allPrefs) {
                if (jsonStr is String) {
                    val profile = CctCorrectionProfile.fromJson(jsonStr)
                    if (profile != null) {
                        calMap[address] = profile
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ColorCalibration", "Failed to load CCT calibrations", e)
        }
        _uiState.update { it.copy(cctCalibrations = calMap) }
    }

    fun saveCctCorrectionProfile(profile: CctCorrectionProfile) {
        prefsRepo.putCctCalibrationString(profile.macAddress, profile.toJson())
        loadCctCalibrations()
    }

    fun deleteCctCorrectionProfile(address: String) {
        prefsRepo.removeCctCalibration(address)
        loadCctCalibrations()
    }

    fun addLog(message: String) {
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _uiState.update { state ->
            val list = state.logMessages.toMutableList()
            list.add(0, "[$timeStr] $message")
            if (list.size > 50) list.removeAt(list.size - 1)
            state.copy(logMessages = list)
        }
        Log.d(TAG, message)
    }

    fun getAudioDeviceIdentifier(device: BluetoothDevice): String {
        val address = device.address
        if (address != null && address.isNotEmpty() && address != "02:00:00:00:00:00") {
            return address
        }
        val typeStr = when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "LE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
            else -> "Unknown"
        }
        return "${device.name ?: "Unknown"}_$typeStr"
    }

    fun checkActiveAudioRoute() {
        val audioManager = getApplication().getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetoothAudio = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (hasBluetoothAudio) {
            val device = activeBluetoothAudioDevice
            if (device != null) {
                val identifier = getAudioDeviceIdentifier(device)
                val name = device.name ?: "Bluetooth Audio Device"
                onBluetoothAudioRouteActive(name, identifier)
            } else {
                val btOutput = devices.find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                val name = btOutput?.productName?.toString() ?: "Bluetooth Audio Device"
                val identifier = "${name}_Classic"
                onBluetoothAudioRouteActive(name, identifier)
            }
        } else {
            onBluetoothAudioRouteInactive()
        }
    }

    private fun onBluetoothAudioRouteActive(name: String, identifier: String) {
        val savedDelay = prefsRepo.getCalibrationDelayPrefInt(identifier, -1)
        _uiState.update { state ->
            if (savedDelay >= 0) {
                if (state.bluetoothDelayMs != savedDelay) {
                    addLog("Detected active Bluetooth audio: $name. Automatically applied saved delay profile of $savedDelay ms.")
                }
                state.copy(
                    detectedAudioDeviceName = name,
                    activeAudioDeviceIdentifier = identifier,
                    bluetoothDelayMs = savedDelay,
                    totalVisualDelayMs = savedDelay + BeatDetector().lookaheadMs.toInt(),
                    showCalibrationPrompt = false
                )
            } else {
                if (state.detectedAudioDeviceName != name) {
                    addLog("Detected active Bluetooth audio: $name. No delay profile found. Setting 0ms (inviting calibration).")
                }
                state.copy(
                    detectedAudioDeviceName = name,
                    activeAudioDeviceIdentifier = identifier,
                    bluetoothDelayMs = 0,
                    totalVisualDelayMs = BeatDetector().lookaheadMs.toInt(),
                    showCalibrationPrompt = true
                )
            }
        }
    }

    private fun onBluetoothAudioRouteInactive() {
        _uiState.update { state ->
            if (state.detectedAudioDeviceName != null) {
                addLog("Bluetooth audio inactive. Reverting delay compensation to 0ms.")
            }
            state.copy(
                detectedAudioDeviceName = null,
                activeAudioDeviceIdentifier = null,
                bluetoothDelayMs = 0,
                totalVisualDelayMs = BeatDetector().lookaheadMs.toInt(),
                showCalibrationPrompt = false
            )
        }
    }

    fun dismissCalibrationPrompt() {
        _uiState.update { it.copy(showCalibrationPrompt = false) }
    }

    fun startCalibrationMode() {
        stopCalibrationMode()
        val identifier = _uiState.value.activeAudioDeviceIdentifier
        val savedDelay = if (identifier != null) {
            prefsRepo.getCalibrationDelayPrefInt(identifier, 0)
        } else {
            prefsRepo.getCalibrationDelayPrefInt("default_delay", 0)
        }
        
        _uiState.update { 
            it.copy(
                isCalibrationModeActive = true,
                calibrationDelayOffsetMs = savedDelay
            )
        }
        
        addLog("Calibration Mode started. Click plays every 1000ms. Pre-populated delay: $savedDelay ms")
        
        metronomeJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                metronomePlayer.playClick()
                val currentDelay = _uiState.value.calibrationDelayOffsetMs
                launch {
                    delay(currentDelay.toLong())
                    sendCalibrationFlash()
                }
                delay(1000L)
            }
        }
    }

    fun updateCalibrationSliderValue(value: Int) {
        _uiState.update { it.copy(calibrationDelayOffsetMs = value) }
    }

    fun saveCalibrationAndExit() {
        val currentDelay = _uiState.value.calibrationDelayOffsetMs
        val identifier = _uiState.value.activeAudioDeviceIdentifier
        
        if (identifier != null) {
            prefsRepo.putCalibrationDelayPrefInt(identifier, currentDelay)
            addLog("Saved calibrated delay of $currentDelay ms for device: ${_uiState.value.detectedAudioDeviceName}")
        } else {
            prefsRepo.putCalibrationDelayPrefInt("default_delay", currentDelay)
            addLog("Saved default calibrated delay of $currentDelay ms")
        }
        
        prefsRepo.putAppStatePrefInt("bluetooth_delay_ms", currentDelay)
        
        _uiState.update { 
            it.copy(
                bluetoothDelayMs = currentDelay,
                totalVisualDelayMs = currentDelay + BeatDetector().lookaheadMs.toInt(),
                showCalibrationPrompt = false
            )
        }
        
        stopCalibrationMode()
    }

    fun stopCalibrationMode() {
        metronomeJob?.cancel()
        metronomeJob = null
        _uiState.update { 
            it.copy(isCalibrationModeActive = false) 
        }
        addLog("Calibration Mode stopped.")
    }

    fun sendCalibrationFlash() {
        viewModelScope.launch(Dispatchers.IO) {
            val whiteColor = DuoCoProtocol.createColorCommand(255, 255, 255)
            val fullBrightness = DuoCoProtocol.createBrightnessCommand(100)
            val pulseOn = fullBrightness + whiteColor
            broadcastCommandDirect(pulseOn)
            
            delay(120) // pulse duration
            
            val blackColor = DuoCoProtocol.createColorCommand(0, 0, 0)
            broadcastCommandDirect(blackColor)
        }
    }

    fun setDemoMode(isDemo: Boolean) {
        _uiState.update { it.copy(isDemoMode = isDemo) }
        addLog(if (isDemo) "Switched to Demo Mode" else "Switched to Real Hardware BLE Mode")
        if (isDemo) {
            disconnect()
        }
    }

    // --- SCANNING ---

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("Scan failed with error code: $errorCode")
            _uiState.update { it.copy(isScanning = false, errorMessage = "Scan failed: error $errorCode") }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName
            ?: result.device.name
            ?: return

        val supported = listOf(
            "MELK-",
            "ELK-",
            "ELK-BLEDOM",
            "BLEDOM",
            "DuoCo"
        )

        if (!supported.any { name.startsWith(it) }) {
            return
        }

        val address = result.device.address
        val rssi = result.rssi

        // Since it matched our prefix, it is a suspected compatible controller
        val isDuoCo = true

        _uiState.update { state ->
            val existing = state.scannedDevices.find { it.address == address }
            val updatedList = if (existing != null) {
                state.scannedDevices.map { 
                    if (it.address == address) it.copy(rssi = rssi, name = name) else it 
                }
            } else {
                val dbAlias = savedAliases.value.find { it.macAddress == address }?.aliasName
                state.scannedDevices + ScannedRgbDevice(address, name, rssi, isDuoCo, dbAlias)
            }
            state.copy(scannedDevices = updatedList.sortedByDescending { it.rssi })
        }

        // Auto connect if saved and enabled
        val saved = savedDevices.value.find { it.macAddress == address }
        if (saved != null && saved.isAutoConnectEnabled && !connectionManager.isManuallyDisconnected(address)) {
            val isConnected = activeConnections.containsKey(address) || 
                              _uiState.value.deviceConnectionStates[address] == BleConnectionState.CONNECTED
            val isConnecting = _uiState.value.deviceConnectionStates[address] == BleConnectionState.CONNECTING
            if (!isConnected && !isConnecting) {
                addLog("Auto-connecting in background to $address (${saved.customName})")
                connectDevice(address)
            }
        }
    }

    fun startScanning() {
        val state = _uiState.value
        if (state.isScanning) return

        _uiState.update { it.copy(isScanning = true, errorMessage = null) }
        addLog("Scan started...")

        if (state.isDemoMode) {
            // Simulate scanned devices
            viewModelScope.launch {
                delay(400)
                addSimulatedDevice("00:1A:7D:DA:71:11", "ELK-BLEDOM", -52, true)
                delay(300)
                addSimulatedDevice("AA:BB:CC:11:22:33", "Smart LED Bulb", -65, true)
                delay(500)
                addSimulatedDevice("FC:45:96:22:88:99", "Generic Light Strips", -80, false)
                delay(300)
                addSimulatedDevice("44:23:AA:55:12:90", "LED DMX Controller", -45, true)
                
                delay(10000) // scan for 10 seconds in demo
                if (_uiState.value.isScanning) {
                    stopScanning()
                }
            }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _uiState.update { it.copy(isScanning = false, errorMessage = "Bluetooth is disabled or unavailable") }
            addLog("Bluetooth must be enabled to scan.")
            return
        }

        try {
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                _uiState.update { it.copy(isScanning = false, errorMessage = "BLE Scanner unavailable") }
                addLog("BLE scanner failed to initialize.")
                return
            }
            scanner.startScan(scanCallback)
            handler.postDelayed(scanTimeoutRunnable, 12000) // Scan for 12s
        } catch (e: SecurityException) {
            _uiState.update { it.copy(isScanning = false, errorMessage = "Missing Bluetooth Permissions") }
            addLog("SecurityException: Permissions not granted.")
        }
    }

    fun stopScanning() {
        if (!_uiState.value.isScanning) return
        _uiState.update { it.copy(isScanning = false) }
        handler.removeCallbacks(scanTimeoutRunnable)
        addLog("Scan stopped.")

        if (!_uiState.value.isDemoMode) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                addLog("SecurityException during stopScan.")
            }
        }
    }

    private fun addSimulatedDevice(address: String, name: String, rssi: Int, isDuoCo: Boolean) {
        _uiState.update { state ->
            if (state.scannedDevices.any { it.address == address }) return@update state
            val dbAlias = savedAliases.value.find { it.macAddress == address }?.aliasName
            val list = state.scannedDevices + ScannedRgbDevice(address, name, rssi, isDuoCo, dbAlias)
            state.copy(scannedDevices = list.sortedByDescending { it.rssi })
        }

        // Auto connect if saved and enabled (Demo mode)
        val saved = savedDevices.value.find { it.macAddress == address }
        if (saved != null && saved.isAutoConnectEnabled && !connectionManager.isManuallyDisconnected(address)) {
            val isConnected = activeConnections.containsKey(address) || 
                              _uiState.value.deviceConnectionStates[address] == BleConnectionState.CONNECTED
            val isConnecting = _uiState.value.deviceConnectionStates[address] == BleConnectionState.CONNECTING
            if (!isConnected && !isConnecting) {
                addLog("Auto-connecting (Simulated) to $address (${saved.customName})")
                connectDevice(address)
            }
        }
    }

    private fun getDiagAttribution(address: String): String {
        val automation = deviceAutomationMode[address]?.name ?: "NONE"
        return "MAC=$address, Automation=$automation"
    }

    // --- CONNECTION ---

    fun connectDevice(address: String) {
        connectionManager.connect(address)
        val state = _uiState.value
        val deviceToConnect = state.scannedDevices.find { it.address == address }
        val displayName = deviceToConnect?.alias ?: deviceToConnect?.name ?: "Unknown Device"

        com.example.DiagnosticLogger.log(
            "BLE",
            "connectDevice attempt: address=$address (${getDiagAttribution(address)})"
        )

        _uiState.update { s ->
            s.copy(
                deviceConnectionStates = s.deviceConnectionStates + (address to BleConnectionState.CONNECTING),
                connectedDeviceAddress = address,
                connectedDeviceName = displayName
            )
        }
        addLog("Connecting to $displayName ($address)...")

        // Auto-save device to SavedDevices in Room when connecting
        viewModelScope.launch(Dispatchers.IO) {
            val existing = savedDevices.value.find { it.macAddress == address }
            if (existing == null) {
                repository.insertSavedDevice(
                    SavedDevice(
                        macAddress = address,
                        customName = displayName,
                        isAutoConnectEnabled = true,
                        isActiveControlEnabled = true
                    )
                )
                addLog("Saved connected device $displayName to database.")
            }
        }

        if (state.isDemoMode) {
            viewModelScope.launch {
                delay(1200) // Simulating connection lag
                _uiState.update { s ->
                    s.copy(
                        deviceConnectionStates = s.deviceConnectionStates + (address to BleConnectionState.CONNECTED),
                        connectionState = BleConnectionState.CONNECTED
                    )
                }
                addLog("Connected (Simulated) to $displayName!")
            }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            _uiState.update { s ->
                s.copy(deviceConnectionStates = s.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = adapter.getRemoteDevice(address)
                connectionManager.connect(address)
                
                val gatt = device.connectGatt(getApplication(), false, gattCallback)
                activeConnections[address] = gatt
            } catch (e: SecurityException) {
                _uiState.update { s ->
                    s.copy(
                        deviceConnectionStates = s.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED),
                        errorMessage = "Permission Denied"
                    )
                }
                addLog("Gatt connect permission failed for $address.")
                com.example.DiagnosticLogger.log(
                    "BLE",
                    "connectDevice SecurityException: address=$address. ${android.util.Log.getStackTraceString(e)}"
                )
            } catch (e: Exception) {
                _uiState.update { s ->
                    s.copy(
                        deviceConnectionStates = s.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED),
                        errorMessage = e.message
                    )
                }
                addLog("Connect error for $address: ${e.message}")
                com.example.DiagnosticLogger.log(
                    "BLE",
                    "connectDevice Exception: address=$address. ${android.util.Log.getStackTraceString(e)}"
                )
            }
        }
    }

    fun disconnectDevice(address: String) {
        com.example.DiagnosticLogger.log(
            "BLE",
            "disconnectDevice called: address=$address (${getDiagAttribution(address)})"
        )
        connectionManager.disconnect(address, manual = true)
        val gatt = activeConnections.remove(address)
        writeCharacteristics.remove(address)
        deviceWriteManagers.remove(address)
        
        _uiState.update { state ->
            state.copy(
                deviceConnectionStates = state.deviceConnectionStates + (address to BleConnectionState.DISCONNECTING)
            )
        }
        
        if (_uiState.value.isDemoMode) {
            viewModelScope.launch {
                delay(300)
                _uiState.update { state ->
                    val updatedStates = state.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED)
                    val stillConnected = updatedStates.filter { it.value == BleConnectionState.CONNECTED }
                    val activeAddr = stillConnected.keys.firstOrNull()
                    val activeName = activeAddr?.let { addr -> state.scannedDevices.find { it.address == addr }?.name }
                    state.copy(
                        deviceConnectionStates = updatedStates,
                        connectionState = if (stillConnected.isNotEmpty()) BleConnectionState.CONNECTED else BleConnectionState.DISCONNECTED,
                        connectedDeviceAddress = activeAddr,
                        connectedDeviceName = activeName
                    )
                }
                addLog("Disconnected (Simulated) from $address.")
            }
            return
        }

        try {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    gatt?.disconnect()
                    gatt?.close()
                } catch (e: SecurityException) {
                    addLog("SecurityException inside IO disconnect coroutine of $address.")
                }
            }
            _uiState.update { state ->
                val updatedStates = state.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED)
                val stillConnected = updatedStates.filter { it.value == BleConnectionState.CONNECTED }
                val activeAddr = stillConnected.keys.firstOrNull()
                val activeName = activeAddr?.let { addr -> state.scannedDevices.find { it.address == addr }?.name }
                state.copy(
                    deviceConnectionStates = updatedStates,
                    connectionState = if (stillConnected.isNotEmpty()) BleConnectionState.CONNECTED else BleConnectionState.DISCONNECTED,
                    connectedDeviceAddress = activeAddr,
                    connectedDeviceName = activeName
                )
            }
            addLog("Disconnected $address.")
        } catch (e: SecurityException) {
            addLog("SecurityException during disconnect of $address.")
        }
    }

    fun disconnect() {
        val keys = activeConnections.keys.toList()
        if (keys.isEmpty()) {
            _uiState.update {
                it.copy(
                    connectionState = BleConnectionState.DISCONNECTED,
                    connectedDeviceAddress = null,
                    connectedDeviceName = null,
                    deviceConnectionStates = emptyMap()
                )
            }
            return
        }
        keys.forEach { disconnectDevice(it) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleConnectionStateChange(gatt, status, newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleServicesDiscovered(gatt, status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleCharacteristicRead(gatt, characteristic, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val activeVm = getActiveInstance() ?: this@RgbControllerViewModel
            activeVm.handleCharacteristicChanged(gatt, characteristic)
        }
    }

    fun handleConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        val address = gatt?.device?.address ?: return
        
        com.example.DiagnosticLogger.log(
            "BLE",
            "handleConnectionStateChange: address=$address, status=$status, newState=$newState (${getDiagAttribution(address)})"
        )
        
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            addLog("Connected to $address! Discovering services...")
            _uiState.update { state ->
                state.copy(
                    deviceConnectionStates = state.deviceConnectionStates + (address to BleConnectionState.CONNECTING)
                )
            }
            activeConnections[address] = gatt
            retryAttempts.remove(address)
            connectionManager.setConnected(address)

            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(512)
            } catch (e: SecurityException) {
                addLog("SecurityException requesting priority or MTU for $address: ${e.message}")
            } catch (e: Exception) {
                addLog("Error requesting priority or MTU for $address: ${e.message}")
            }

            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                addLog("SecurityException: Service discovery denied for $address.")
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            addLog("Disconnected from GATT ($address).")
            val wasActive = activeConnections.containsKey(address)
            activeConnections.remove(address)
            writeCharacteristics.remove(address)
            deviceWriteManagers.remove(address)
            
            _uiState.update { state ->
                val updatedStates = state.deviceConnectionStates + (address to BleConnectionState.DISCONNECTED)
                val stillConnected = updatedStates.filter { it.value == BleConnectionState.CONNECTED }
                val activeAddr = stillConnected.keys.firstOrNull()
                val activeName = activeAddr?.let { addr -> state.scannedDevices.find { it.address == addr }?.name }
                state.copy(
                    deviceConnectionStates = updatedStates,
                    connectionState = if (stillConnected.isNotEmpty()) BleConnectionState.CONNECTED else BleConnectionState.DISCONNECTED,
                    connectedDeviceAddress = activeAddr,
                    connectedDeviceName = activeName
                )
            }

            // Exponential backoff retry logic if unexpected drop
            if (wasActive && !connectionManager.isManuallyDisconnected(address)) {
                connectionManager.disconnect(address, manual = false)
                val saved = savedDevices.value.find { it.macAddress == address }
                if (saved != null && saved.isAutoConnectEnabled) {
                    val attempt = retryAttempts.getOrDefault(address, 0)
                    if (attempt < 5) {
                        retryAttempts[address] = attempt + 1
                        val delayMs = (1000L * Math.pow(2.0, attempt.toDouble())).toLong()
                        addLog("Unexpected drop for $address. Retrying in ${delayMs / 1000}s (Attempt ${attempt + 1})...")
                        com.example.DiagnosticLogger.log(
                            "BLE",
                            "Initiating reconnect backoff retry for $address: attempt=${attempt + 1}, delayMs=$delayMs (${getDiagAttribution(address)})"
                        )
                        viewModelScope.launch {
                            delay(delayMs)
                            if (!activeConnections.containsKey(address) && savedDevices.value.find { it.macAddress == address }?.isAutoConnectEnabled == true) {
                                connectDevice(address)
                            }
                        }
                    } else {
                        addLog("Max retries reached for $address. Stopping auto-retry.")
                        com.example.DiagnosticLogger.log(
                            "BLE",
                            "Max retries (5) reached for $address. Stopping auto-retry. (${getDiagAttribution(address)})"
                        )
                    }
                }
            }
        }
    }

    fun handleServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        val address = gatt?.device?.address ?: return
        com.example.DiagnosticLogger.log(
            "BLE",
            "handleServicesDiscovered: address=$address, status=$status. (${getDiagAttribution(address)})"
        )
        if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
            addLog("Services discovered for $address.")
            findDuoCoCharacteristicForGatt(gatt)
        } else {
            addLog("Service discovery failed for $address with status $status")
        }
    }

    fun handleCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
        val address = gatt?.device?.address ?: return
        com.example.DiagnosticLogger.log(
            "BLE",
            "handleCharacteristicWrite callback: address=$address, status=$status. (${getDiagAttribution(address)})"
        )
        deviceWriteManagers[address]?.onWriteCompleted()
    }

    fun handleCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
        // Handled or ignored
    }

    fun handleCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
        // Handled or ignored
    }

    private fun registerCharacteristic(gatt: BluetoothGatt, charac: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        writeCharacteristics[address] = charac
        val manager = DeviceWriteManager(address, gatt, charac)
        deviceWriteManagers[address] = manager
        
        addLog("Found write characteristic for $address: ${charac.uuid} (Ack Supported: ${manager.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT})")
        
        _uiState.update { state ->
            state.copy(
                deviceConnectionStates = state.deviceConnectionStates + (address to BleConnectionState.CONNECTED),
                connectionState = BleConnectionState.CONNECTED,
                connectedDeviceAddress = address,
                connectedDeviceName = state.scannedDevices.find { it.address == address }?.alias ?: state.scannedDevices.find { it.address == address }?.name ?: "Unknown Device",
                devicePacingMs = state.devicePacingMs + (address to manager.currentPacingMs)
            )
        }
    }

    private fun findDuoCoCharacteristicForGatt(gatt: BluetoothGatt) {
        val address = gatt.device.address
        var found = false
        for (service in gatt.services) {
            val charac = service.getCharacteristic(DuoCoProtocol.CHARACTERISTIC_UUID)
            if (charac != null) {
                registerCharacteristic(gatt, charac)
                found = true
                break
            }
        }
        if (!found) {
            for (service in gatt.services) {
                for (charac in service.characteristics) {
                    if (charac.uuid == DuoCoProtocol.CHARACTERISTIC_UUID) {
                        registerCharacteristic(gatt, charac)
                        found = true
                        break
                    }
                }
                if (found) break
            }
        }

        if (!found) {
            addLog("Warning: DuoCo characteristic not found for $address. Commands may not work.")
            _uiState.update { state ->
                state.copy(
                    deviceConnectionStates = state.deviceConnectionStates + (address to BleConnectionState.CONNECTED),
                    connectionState = BleConnectionState.CONNECTED,
                    connectedDeviceAddress = address
                )
            }
        }
    }

    private fun syncPhysicalBulbOfDevice(gatt: BluetoothGatt) {
        val state = _uiState.value
        val powerCmd = DuoCoProtocol.createPowerCommand(state.isPowerOn)
        val colorCmd = DuoCoProtocol.createColorCommand(state.red, state.green, state.blue)
        val brightnessCmd = DuoCoProtocol.createBrightnessCommand(state.brightness)
        val batched = powerCmd + colorCmd + brightnessCmd
        deviceWriteManagers[gatt.device.address]?.updateCommand(batched)
    }

    // --- COMMAND TRANSMISSION & OUTBOUND QUEUE ---

    fun queueCommand(command: ByteArray) {
        if (!_uiState.value.isAudioSyncRunning) return
        
        val delayMs = _uiState.value.totalVisualDelayMs
        if (delayMs > 0) {
            viewModelScope.launch(Dispatchers.Default) {
                delay(delayMs.toLong())
                if (!_uiState.value.isAudioSyncRunning) return@launch
                broadcastCommandDirect(command)
            }
        } else {
            broadcastCommandDirect(command)
        }
    }

    fun sendCommandToDeviceDirect(address: String, command: ByteArray) {
        if (_uiState.value.isDemoMode) {
            val finalCmd = processCommandWithCalibration(address, command)
            val hexStr = finalCmd.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
            Log.d("ColorCalibration", "Simulated transmit to $address: $hexStr")
        } else {
            deviceWriteManagers[address]?.updateCommand(command)
        }
    }

    private fun broadcastCommandDirect(command: ByteArray) {
        val targetAddresses = getCurrentlyControlledDeviceAddresses()
        targetAddresses.forEach { address ->
            sceneRunners[address]?.release()
            sceneRunners.remove(address)
            
            if (activeExcludedMacs.contains(address)) return@forEach
            if (_uiState.value.isDemoMode) {
                if (_uiState.value.deviceConnectionStates[address] == BleConnectionState.CONNECTED) {
                    val finalCmd = processCommandWithCalibration(address, command)
                    val hexStr = finalCmd.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
                    addLog("[Simulated Broadcast] Sent to $address: $hexStr")
                }
            } else {
                deviceWriteManagers[address]?.updateCommand(command)
            }
        }
    }

    fun getCurrentlyControlledDeviceAddresses(): List<String> {
        return savedDevices.value
            .filter { it.isActiveControlEnabled }
            .map { it.macAddress }
    }

    private var isApplyingScene = false
    private fun clearExclusionsIfNotApplyingScene() {
        if (!isApplyingScene) {
            activeExcludedMacs.clear()
        }
    }


    fun broadcastCommand(command: ByteArray, cancelRunningScenes: Boolean = true) {
        val targetAddresses = getCurrentlyControlledDeviceAddresses()
        
        targetAddresses.forEach { address ->
            if (cancelRunningScenes) {
                sceneRunners[address]?.release()
                sceneRunners.remove(address)
            }

            if (_uiState.value.isDemoMode) {
                if (_uiState.value.deviceConnectionStates[address] == BleConnectionState.CONNECTED) {
                    val finalCmd = processCommandWithCalibration(address, command)
                    val hexStr = finalCmd.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
                    addLog("[Simulated Broadcast] Sent to $address: $hexStr")
                }
            } else {
                deviceWriteManagers[address]?.updateCommand(command)
            }
        }
    }

    fun setActiveFeatureName(name: String) {
        cancelSceneChain()
        _uiState.update { it.copy(activeFeatureName = name) }
        prefsRepo.putAppStatePrefString("active_feature_name", name)
        clearExclusionsIfNotApplyingScene()
    }

    fun setShowFpsTracker(enabled: Boolean) {
        prefsRepo.putAppStatePrefBoolean("show_fps_tracker", enabled)
        _uiState.update { it.copy(showFpsTracker = enabled) }
    }

    fun writeAmbianceColor(r: Int, g: Int, b: Int) {
        if (!com.example.ambiance.AmbianceCaptureState.isActive.value) {
            return
        }
        val command = DuoCoProtocol.createColorCommand(r, g, b)
        broadcastCommandDirect(command)
    }

    private fun sendCommand(command: ByteArray, debugName: String, cancelRunningScenes: Boolean = true) {
        val hexStr = command.joinToString(" ") { String.format("0x%02X", it.toInt() and 0xFF) }
        addLog("Send Command ($debugName): $hexStr")
        broadcastCommand(command, cancelRunningScenes)
    }

    private fun syncPhysicalBulb() {
        val state = _uiState.value
        val powerCmd = DuoCoProtocol.createPowerCommand(state.isPowerOn)
        val colorCmd = DuoCoProtocol.createColorCommand(state.red, state.green, state.blue)
        val brightnessCmd = DuoCoProtocol.createBrightnessCommand(state.brightness)
        val batched = powerCmd + colorCmd + brightnessCmd
        broadcastCommand(batched)
    }

    private fun delayMs(ms: Long) {
        viewModelScope.launch {
            delay(ms)
        }
    }


    private fun getLedPresetName(id: String): String {
        return when(id) {
            "energetic_1" -> "Energetic 1"
            "energetic_2" -> "Energetic 2"
            "rhythm_1" -> "Rhythm 1"
            "rhythm_2" -> "Rhythm 2"
            "spectrum_1" -> "Spectrum 1"
            "spectrum_2" -> "Spectrum 2"
            "rolling_1" -> "Rolling 1"
            "rolling_2" -> "Rolling 2"
            else -> "Unknown"
        }
    }

    private fun getVisualizerPresetName(id: String): String {
        return when(id) {
            "Default" -> "Balanced"
            "Punchy" -> "Punchy"
            "Smooth Flow" -> "Smooth Flow"
            "Strobe Blast" -> "Strobe Blast"
            "Ambient Chill" -> "Ambient Chill"
            "Bass Thump" -> "Bass Thump"
            "Laser Sharp" -> "Laser Sharp"
            else -> id
        }
    }

    // --- CONTROL INTERFACES ---

    data class VisualizerConfig(
        val attack: Float, val decay: Float, val flash: Float, val gamma: Float, val idleDelay: Long,
        val noiseGate: Float, val bassGain: Float,
        val midGain: Float, val highGain: Float, val paletteCycling: Boolean, val beatMult: Float,
        val minBrightness: Float, val colorSpeed: Float,
        val beatFlashDecayMs: Float, val ambientCapFraction: Float, val midFluxWeight: Float
    )

    
    
    fun setVisualizerPreset(preset: String) {
        val currentMusicMode = _uiState.value.musicMode
        val featureName = if (currentMusicMode == "phone_mic" || currentMusicMode == "on_device") {
            "Audio Visualiser (${getVisualizerPresetName(preset)})"
        } else {
            _uiState.value.activeFeatureName
        }

        val config = when (preset) {
            "Punchy" -> VisualizerConfig(0.95f, 0.35f, 0.6f, 0.35f, 2000L, 8.0f, 1.2f, 1.0f, 1.0f, true, 1.3f, 0.15f, 1.5f, 140f, 0.40f, 0.20f)
            "Smooth Flow" -> VisualizerConfig(0.25f, 0.08f, 0.00f, 0.45f, 2800L, 4.0f, 1.0f, 1.1f, 1.0f, true, 1.8f, 0.18f, 1.2f, 300f, 0.45f, 0.30f)
            "Strobe Blast" -> VisualizerConfig(1.0f, 0.85f, 1.0f, 0.2f, 1500L, 10.0f, 1.0f, 1.0f, 1.5f, true, 1.2f, 0.10f, 2.0f, 90f, 0.25f, 0.15f)
            "Ambient Chill" -> VisualizerConfig(0.10f, 0.02f, 0.00f, 0.65f, 5000L, 3.0f, 1.0f, 0.7f, 0.4f, true, 2.5f, 0.35f, 0.15f, 600f, 0.75f, 0.20f)
            "Bass Thump" -> VisualizerConfig(0.90f, 0.20f, 0.5f, 0.4f, 2500L, 6.0f, 2.0f, 0.6f, 0.4f, true, 1.4f, 0.15f, 1.0f, 170f, 0.45f, 0.05f)
            "Laser Sharp" -> VisualizerConfig(1.0f, 0.95f, 0.8f, 0.3f, 1500L, 12.0f, 1.0f, 1.0f, 1.0f, true, 1.1f, 0.10f, 3.0f, 70f, 0.20f, 0.10f)
            "Beat Only" -> VisualizerConfig(1.0f, 0.9f, 1.0f, 0.3f, 1500L, 10.0f, 1.0f, 1.0f, 1.0f, false, 1.2f, 0.05f, 2.0f, 90f, 0.0f, 0.15f)
            else -> VisualizerConfig(0.85f, 0.12f, 0.3f, 0.45f, 2500L, 5.0f, 1.0f, 1.0f, 1.0f, true, 1.6f, 0.15f, 1.0f, 200f, 0.40f, 0.25f)
        }

        _uiState.update {
            it.copy(
                visualizerPreset = preset,
                activeFeatureName = featureName,
                audioSmoothingAttack = config.attack,
                audioSmoothingDecay = config.decay,
                audioFlashStrength = config.flash,
                audioGammaExponent = config.gamma,
                idleTriggerDelayMs = config.idleDelay,
                noiseGateThreshold = config.noiseGate,
                bassGain = config.bassGain,
                midGain = config.midGain,
                highGain = config.highGain,
                isPaletteCyclingEnabled = config.paletteCycling,
                beatThresholdMultiplier = config.beatMult,
                visualizerMinBrightness = config.minBrightness,
                visualizerColorSpeed = config.colorSpeed,
                beatFlashDecayMs = config.beatFlashDecayMs,
                ambientCapFraction = config.ambientCapFraction,
                midFluxWeight = config.midFluxWeight
            )
        }
        
                    prefsRepo.putAppStatePrefString("visualizer_preset", preset)
            prefsRepo.putAppStatePrefString("active_feature_name", featureName)
            prefsRepo.putAppStatePrefFloat("audio_smoothing_attack", config.attack)
            prefsRepo.putAppStatePrefFloat("audio_smoothing_decay", config.decay)
            prefsRepo.putAppStatePrefFloat("audio_flash_strength", config.flash)
            prefsRepo.putAppStatePrefFloat("audio_gamma_exponent", config.gamma)
            prefsRepo.putAppStatePrefLong("idle_trigger_delay_ms", config.idleDelay)
            prefsRepo.putAppStatePrefFloat("noise_gate_threshold", config.noiseGate)
            prefsRepo.putAppStatePrefFloat("bass_gain", config.bassGain)
            prefsRepo.putAppStatePrefFloat("mid_gain", config.midGain)
            prefsRepo.putAppStatePrefFloat("high_gain", config.highGain)
            prefsRepo.putAppStatePrefBoolean("is_palette_cycling_enabled", config.paletteCycling)
            prefsRepo.putAppStatePrefFloat("beat_threshold_multiplier", config.beatMult)
            prefsRepo.putAppStatePrefFloat("visualizer_min_brightness", config.minBrightness)
            prefsRepo.putAppStatePrefFloat("visualizer_color_speed", config.colorSpeed)
            prefsRepo.putAppStatePrefFloat("beat_flash_decay_ms", config.beatFlashDecayMs)
            prefsRepo.putAppStatePrefFloat("ambient_cap_fraction", config.ambientCapFraction)
            prefsRepo.putAppStatePrefFloat("mid_flux_weight", config.midFluxWeight)
    }

    fun setAudioSmoothingAttack(value: Float) {
        _uiState.update { it.copy(audioSmoothingAttack = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("audio_smoothing_attack", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setAudioSmoothingDecay(value: Float) {
        _uiState.update { it.copy(audioSmoothingDecay = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("audio_smoothing_decay", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setAudioGammaExponent(value: Float) {
        _uiState.update { it.copy(audioGammaExponent = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("audio_gamma_exponent", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setAudioFlashStrength(value: Float) {
        _uiState.update { it.copy(audioFlashStrength = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("audio_flash_strength", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setVisualizerMinBrightness(value: Float) {
        _uiState.update { it.copy(visualizerMinBrightness = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("visualizer_min_brightness", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setVisualizerColorSpeed(value: Float) {
        _uiState.update { it.copy(visualizerColorSpeed = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("visualizer_color_speed", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setAmbianceResponseSpeed(value: Float) {
        _uiState.update { it.copy(ambianceResponseSpeed = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefFloat("response_speed", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun setAmbianceSmoothnessMs(value: Int) {
        _uiState.update { it.copy(ambianceSmoothnessMs = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefInt("smoothness_ms", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun setAmbianceSaturationBoost(value: Float) {
        _uiState.update { it.copy(ambianceSaturationBoost = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefFloat("saturation_boost", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun setAmbianceBrightnessCompensation(value: Float) {
        _uiState.update { it.copy(ambianceBrightnessCompensation = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefFloat("brightness_compensation", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun setAmbianceUpdateRateCapFps(value: Int) {
        _uiState.update { it.copy(ambianceUpdateRateCapFps = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefInt("update_rate_cap_fps", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun setAmbianceSceneCutSensitivity(value: Float) {
        _uiState.update { it.copy(ambianceSceneCutSensitivity = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefFloat("scene_cut_sensitivity", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun setAmbianceNoiseDeadband(value: Float) {
        _uiState.update { it.copy(ambianceNoiseDeadband = value, ambiancePreset = "Custom") }
        prefsRepo.putAmbiancePrefFloat("noise_deadband", value)
        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")
    }

    fun applyAmbiancePreset(
        presetId: String,
        responseSpeed: Float,
        smoothnessMs: Int,
        saturationBoost: Float,
        brightnessCompensation: Float,
        sceneCutSensitivity: Float,
        noiseDeadband: Float
    ) {
        cancelSceneChain()
        _uiState.update {
            it.copy(
                ambianceResponseSpeed = responseSpeed,
                ambianceSmoothnessMs = smoothnessMs,
                ambianceSaturationBoost = saturationBoost,
                ambianceBrightnessCompensation = brightnessCompensation,
                ambianceSceneCutSensitivity = sceneCutSensitivity,
                ambianceNoiseDeadband = noiseDeadband,
                ambiancePreset = presetId
            )
        }
                    prefsRepo.putAmbiancePrefFloat("response_speed", responseSpeed)
            prefsRepo.putAmbiancePrefInt("smoothness_ms", smoothnessMs)
            prefsRepo.putAmbiancePrefFloat("saturation_boost", saturationBoost)
            prefsRepo.putAmbiancePrefFloat("brightness_compensation", brightnessCompensation)
            prefsRepo.putAmbiancePrefFloat("scene_cut_sensitivity", sceneCutSensitivity)
            prefsRepo.putAmbiancePrefFloat("noise_deadband", noiseDeadband)
            prefsRepo.putAmbiancePrefString("ambiance_preset", presetId)
    }

    fun setIdleTriggerDelayMs(value: Long) {
        _uiState.update { it.copy(idleTriggerDelayMs = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefLong("idle_trigger_delay_ms", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setTransmissionDelayMs(value: Int) {
        _uiState.update { it.copy(transmissionDelayMs = value) }
        prefsRepo.putAppStatePrefInt("transmission_delay_ms", value)
    }

    fun setNoiseGateThreshold(value: Float) {
        _uiState.update { it.copy(noiseGateThreshold = value) }
        prefsRepo.putAppStatePrefFloat("noise_gate_threshold", value)
    }

    fun setBeatThresholdMultiplier(value: Float) {
        _uiState.update { it.copy(beatThresholdMultiplier = value, visualizerPreset = "Custom") }
                    prefsRepo.putAppStatePrefFloat("beat_threshold_multiplier", value)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Custom")
    }

    fun setBeatCooldownMs(value: Int) {
        _uiState.update { it.copy(beatCooldownMs = value) }
        prefsRepo.putAppStatePrefInt("beat_cooldown_ms", value)
    }

    fun setBassGain(value: Float) {
        _uiState.update { it.copy(bassGain = value) }
        prefsRepo.putAppStatePrefFloat("bass_gain", value)
    }

    fun setMidGain(value: Float) {
        _uiState.update { it.copy(midGain = value) }
        prefsRepo.putAppStatePrefFloat("mid_gain", value)
    }

    fun setHighGain(value: Float) {
        _uiState.update { it.copy(highGain = value) }
        prefsRepo.putAppStatePrefFloat("high_gain", value)
    }

    fun setAutoGainEnabled(value: Boolean) {
        _uiState.update { it.copy(isAutoGainEnabled = value) }
        prefsRepo.putAppStatePrefBoolean("is_auto_gain_enabled", value)
    }

    fun setPaletteCyclingEnabled(value: Boolean) {
        _uiState.update { it.copy(isPaletteCyclingEnabled = value) }
        prefsRepo.putAppStatePrefBoolean("is_palette_cycling_enabled", value)
    }

    fun setLogarithmicScalingEnabled(value: Boolean) {
        _uiState.update { it.copy(isLogarithmicScalingEnabled = value) }
        prefsRepo.putAppStatePrefBoolean("is_logarithmic_scaling_enabled", value)
    }

    fun setBluetoothDelayMs(value: Int) {
        _uiState.update { it.copy(
            bluetoothDelayMs = value,
            totalVisualDelayMs = value + BeatDetector().lookaheadMs.toInt()
        ) }
        prefsRepo.putAppStatePrefInt("bluetooth_delay_ms", value)
    }
    
    fun resetAudioPipelineSettings() {
        _uiState.update {
            it.copy(
                audioSmoothingAttack = 0.85f,
                audioSmoothingDecay = 0.12f,
                transmissionDelayMs = 16,
                noiseGateThreshold = 5.0f,
                beatThresholdMultiplier = 1.3f,
                beatCooldownMs = 250,
                bassGain = 1.0f,
                midGain = 1.0f,
                highGain = 1.0f,
                isAutoGainEnabled = true,
                isPaletteCyclingEnabled = true,
                isLogarithmicScalingEnabled = true,
                bluetoothDelayMs = 0,
                totalVisualDelayMs = BeatDetector().lookaheadMs.toInt(),
                visualizerPreset = "Default",
                audioGammaExponent = 0.45f,
                audioFlashStrength = 0.3f,
                visualizerMinBrightness = 0.15f,
                visualizerColorSpeed = 1.0f,
                beatFlashDecayMs = 200f,
                ambientCapFraction = 0.40f,
                midFluxWeight = 0.25f,
                idleTriggerDelayMs = 2500L
            )
        }
                    prefsRepo.putAppStatePrefFloat("audio_smoothing_attack", 0.85f)
            prefsRepo.putAppStatePrefFloat("audio_smoothing_decay", 0.12f)
            prefsRepo.putAppStatePrefInt("transmission_delay_ms", 16)
            prefsRepo.putAppStatePrefFloat("noise_gate_threshold", 5.0f)
            prefsRepo.putAppStatePrefFloat("beat_threshold_multiplier", 1.3f)
            prefsRepo.putAppStatePrefInt("beat_cooldown_ms", 250)
            prefsRepo.putAppStatePrefFloat("bass_gain", 1.0f)
            prefsRepo.putAppStatePrefFloat("mid_gain", 1.0f)
            prefsRepo.putAppStatePrefFloat("high_gain", 1.0f)
            prefsRepo.putAppStatePrefBoolean("is_auto_gain_enabled", true)
            prefsRepo.putAppStatePrefBoolean("is_palette_cycling_enabled", true)
            prefsRepo.putAppStatePrefBoolean("is_logarithmic_scaling_enabled", true)
            prefsRepo.putAppStatePrefInt("bluetooth_delay_ms", 0)
            prefsRepo.putAppStatePrefString("visualizer_preset", "Default")
            prefsRepo.putAppStatePrefFloat("audio_gamma_exponent", 0.45f)
            prefsRepo.putAppStatePrefFloat("audio_flash_strength", 0.3f)
            prefsRepo.putAppStatePrefFloat("visualizer_min_brightness", 0.15f)
            prefsRepo.putAppStatePrefFloat("visualizer_color_speed", 1.0f)
            prefsRepo.putAppStatePrefFloat("beat_flash_decay_ms", 200f)
            prefsRepo.putAppStatePrefFloat("ambient_cap_fraction", 0.40f)
            prefsRepo.putAppStatePrefFloat("mid_flux_weight", 0.25f)
            prefsRepo.putAppStatePrefLong("idle_trigger_delay_ms", 2500L)
    }

    fun resetCalibrationSettings() {
        prefsRepo.clearCalibrationDelayPrefs()
        prefsRepo.clearCctCalibrationPrefs()
        prefsRepo.clearPacingPrefs()
        
        deviceWriteManagers.forEach { (_, manager) ->
            manager.currentPacingMs = 100
        }
        
        _uiState.update {
            it.copy(
                bluetoothDelayMs = 0,
                totalVisualDelayMs = BeatDetector().lookaheadMs.toInt(),
                calibrationDelayOffsetMs = 0,
                devicePacingMs = it.devicePacingMs.mapValues { 100 },
                cctCalibrations = emptyMap()
            )
        }
        
        prefsRepo.putAppStatePrefInt("bluetooth_delay_ms", 0)
        addLog("Reset Calibration Defaults: bluetooth audio delay compensation set to 0, per-device pacing reset to 100ms, and custom CCT/audio calibration profiles cleared.")
    }

    private fun stopAmbianceIfActive(restoreState: Boolean = true) {
        if (com.example.ambiance.AmbianceCaptureState.isActive.value) {
            if (!restoreState) {
                deviceAutomationMode.forEach { (address, mode) ->
                    if (mode == AutomationType.AMBIANCE) {
                        deviceAutomationMode.remove(address)
                    }
                }
            }
            com.example.ambiance.AmbianceCaptureService.stop(getApplication())
        }
    }

    private fun updateControlledDevicesInMap(
        activeFeatureName: String? = null,
        red: Int? = null,
        green: Int? = null,
        blue: Int? = null,
        warmth: Int? = null,
        modeIndex: Int? = null,
        brightness: Int? = null,
        isPowerOn: Boolean? = null
    ) {
        val targetAddresses = getCurrentlyControlledDeviceAddresses()
        if (targetAddresses.isEmpty()) return
        
        _uiState.update { current ->
            val newMap = current.deviceStatesMap.toMutableMap()
            targetAddresses.forEach { address ->
                val existing = newMap[address] ?: ActiveDeviceState(
                    activeFeatureName = current.activeFeatureName,
                    red = current.red,
                    green = current.green,
                    blue = current.blue,
                    warmth = current.warmth,
                    modeIndex = current.modeIndex,
                    brightness = current.brightness,
                    isPowerOn = current.isPowerOn
                )
                newMap[address] = existing.copy(
                    activeFeatureName = activeFeatureName ?: existing.activeFeatureName,
                    red = red ?: existing.red,
                    green = green ?: existing.green,
                    blue = blue ?: existing.blue,
                    warmth = warmth ?: existing.warmth,
                    modeIndex = modeIndex ?: existing.modeIndex,
                    brightness = brightness ?: existing.brightness,
                    isPowerOn = isPowerOn ?: existing.isPowerOn
                )
            }
            current.copy(deviceStatesMap = newMap)
        }
    }

    fun setPower(isOn: Boolean) {
        cancelSceneChain()
        _uiState.update { it.copy(isPowerOn = isOn) }
        prefsRepo.putAppStatePrefBoolean("power_on", isOn)
        if (!isOn) {
            stopMusicSync()
            stopAmbianceIfActive()
        }
        sendCommand(DuoCoProtocol.createPowerCommand(isOn), "Power $isOn")
        updateControlledDevicesInMap(isPowerOn = isOn)
    }

    fun setColor(r: Int, g: Int, b: Int) {
        cancelSceneChain()
        clearExclusionsIfNotApplyingScene()
        _uiState.update { it.copy(red = r, green = g, blue = b, modeIndex = 0, activeFeatureName = "Colour") }
        stopMusicSync(restoreState = false)
        stopAmbianceIfActive(restoreState = false)
                    prefsRepo.putAppStatePrefInt("red", r)
            prefsRepo.putAppStatePrefInt("green", g)
            prefsRepo.putAppStatePrefInt("blue", b)
            prefsRepo.putAppStatePrefInt("mode_index", 0)
            prefsRepo.putAppStatePrefString("active_feature_name", "Colour")
        sendCommand(DuoCoProtocol.createColorCommand(r, g, b), "Color R:$r G:$g B:$b")
        updateControlledDevicesInMap(activeFeatureName = "Colour", red = r, green = g, blue = b, modeIndex = 0)
    }

    fun setBrightness(percent: Int) {
        _uiState.update { it.copy(brightness = percent) }
        prefsRepo.putAppStatePrefInt("brightness", percent)
        sendCommand(DuoCoProtocol.createBrightnessCommand(percent), "Brightness $percent%", cancelRunningScenes = false)
        updateControlledDevicesInMap(brightness = percent)
    }

    fun setMode(modeIndex: Int) {
        stopMusicSync(restoreState = false)
        stopAmbianceIfActive(restoreState = false)
        cancelSceneChain()
        val modeName = customModes.value.find { it.byteValue == modeIndex }?.name ?: "Mode"
        _uiState.update { it.copy(modeIndex = modeIndex, activeFeatureName = modeName) }
        prefsRepo.putAppStatePrefString("active_feature_name", modeName)
        prefsRepo.putAppStatePrefInt("mode_index", modeIndex)
        sendCommand(DuoCoProtocol.createModeCommand(modeIndex), "Mode $modeIndex")
        updateControlledDevicesInMap(activeFeatureName = modeName, modeIndex = modeIndex)
    }

    fun setModeSpeed(speed: Int) {
        cancelSceneChain()
        _uiState.update { it.copy(modeSpeed = speed) }
        prefsRepo.putAppStatePrefInt("mode_speed", speed)
        sendCommand(DuoCoProtocol.createModeSpeedCommand(speed), "Mode Speed $speed%")
    }

    fun setWarmth(percent: Int) {
        cancelSceneChain()
        clearExclusionsIfNotApplyingScene()
        val coercedPercent = percent.coerceIn(0, 100)
        _uiState.update { it.copy(warmth = coercedPercent, modeIndex = 0, activeFeatureName = "CCT") }

        stopMusicSync(restoreState = false)
        stopAmbianceIfActive(restoreState = false)
        // Map UI slider (0-100) non-linearly to mired/Kelvin to compensate for diminishing human visual returns closer to cool (high Kelvin).
        // 0% -> 500 mired -> 2000K
        // 100% -> 100 mired -> 10000K
        prefsRepo.putAppStatePrefString("active_feature_name", "CCT")
        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(coercedPercent)

        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
        val finalRed = rgb[0]
        val finalGreen = rgb[1]
        val finalBlue = rgb[2]

        _uiState.update { it.copy(red = finalRed, green = finalGreen, blue = finalBlue) }
                    prefsRepo.putAppStatePrefInt("warmth", coercedPercent)
            prefsRepo.putAppStatePrefInt("mode_index", 0)
            prefsRepo.putAppStatePrefString("active_feature_name", "Colour")
            prefsRepo.putAppStatePrefInt("red", finalRed)
            prefsRepo.putAppStatePrefInt("green", finalGreen)
            prefsRepo.putAppStatePrefInt("blue", finalBlue)

        // Apply CCT Correction Profile if available for the connected device
        val address = _uiState.value.connectedDeviceAddress
        val profile = address?.let { _uiState.value.cctCalibrations[it] }

        val (sendR, sendG, sendB) = if (profile != null) {
            val cr = (finalRed * profile.scaleR + profile.offsetR).toInt().coerceIn(0, 255)
            val cg = (finalGreen * profile.scaleG + profile.offsetG).toInt().coerceIn(0, 255)
            val cb = (finalBlue * profile.scaleB + profile.offsetB).toInt().coerceIn(0, 255)
            Triple(cr, cg, cb)
        } else {
            Triple(finalRed, finalGreen, finalBlue)
        }

        sendCommand(DuoCoProtocol.createColorCommand(sendR, sendG, sendB), "Warmth R:$sendR G:$sendG B:$sendB (Original R:$finalRed G:$finalGreen B:$finalBlue)")
        updateControlledDevicesInMap(activeFeatureName = "CCT", red = finalRed, green = finalGreen, blue = finalBlue, warmth = coercedPercent, modeIndex = 0)
    }


    // --- SCENES LOGIC ---
    fun captureCurrentDeviceState(groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean = false, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false): DeviceSceneState {
        val s = _uiState.value
        val isPowerOffMode = groupA == "Power Off"
        val isAnyMode = groupA != null && groupA != "None" && !isPowerOffMode

        return DeviceSceneState(
            groupASelection = groupA,
            colorR = if (groupA == "Colour") s.red else null,
            colorG = if (groupA == "Colour") s.green else null,
            colorB = if (groupA == "Colour") s.blue else null,
            cctWarmth = if (groupA == "CCT") s.warmth else null,
            modeIndex = if (groupA == "HardwareMode") s.modeIndex else null,
            modeSpeed = if (groupA == "HardwareMode" && includeModeSpeed) s.modeSpeed else null,
            audioPreset = if (groupA == "Audio") s.visualizerPreset else null,
            audioAttack = if (includeAudioSettings || groupA == "Audio") s.audioSmoothingAttack else null,
            audioDecay = if (includeAudioSettings || groupA == "Audio") s.audioSmoothingDecay else null,
            audioFlash = if (includeAudioSettings || groupA == "Audio") s.audioFlashStrength else null,
            musicMode = if (groupA == "Audio") s.musicMode else null,
            ambianceIsOn = if (groupA == "Ambiance") com.example.ambiance.AmbianceCaptureState.isActive.value else null,
            ambiancePreset = if (includeAmbianceSettings || groupA == "Ambiance") s.ambiancePreset else null,
            ambianceResponseSpeed = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceResponseSpeed else null,
            ambianceSmoothnessMs = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSmoothnessMs else null,
            ambianceSaturationBoost = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSaturationBoost else null,
            ambianceBrightnessCompensation = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceBrightnessCompensation else null,
            ambianceUpdateRateCapFps = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceUpdateRateCapFps else null,
            ambianceSceneCutSensitivity = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceSceneCutSensitivity else null,
            ambianceNoiseDeadband = if (includeAmbianceSettings || groupA == "Ambiance") s.ambianceNoiseDeadband else null,
            noiseGateThreshold = if (includeAudioSettings) s.noiseGateThreshold else null,
            bassGain = if (includeAudioSettings) s.bassGain else null,
            midGain = if (includeAudioSettings) s.midGain else null,
            highGain = if (includeAudioSettings) s.highGain else null,
            isAutoGainEnabled = if (includeAudioSettings) s.isAutoGainEnabled else null,
            isPaletteCyclingEnabled = if (includeAudioSettings) s.isPaletteCyclingEnabled else null,
            isLogarithmicScalingEnabled = if (includeAudioSettings) s.isLogarithmicScalingEnabled else null,
            audioGammaExponent = if (includeAudioSettings) s.audioGammaExponent else null,
            visualizerMinBrightness = if (includeAudioSettings) s.visualizerMinBrightness else null,
            visualizerColorSpeed = if (includeAudioSettings) s.visualizerColorSpeed else null,
            bluetoothDelayMs = if (includeCalibrationSettings) s.bluetoothDelayMs else null,
            brightness = if (includeBrightness) s.brightness else null,
            isPowerOn = if (isPowerOffMode) false else if (isAnyMode) true else null
        )
    }

    fun saveScene(name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false, chainedSceneId: String? = null, chainedSceneDelaySeconds: Int? = null, chainedSceneReverseId: String? = null): String {
        val stateSnapshot = captureCurrentDeviceState(groupA, includeBrightness, includeModeSpeed, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings)
        val newId = UUID.randomUUID().toString()
        val newScene = AppScene(
            id = newId,
            name = name,
            targetScope = targetScope,
            selectedDeviceMacs = selectedDeviceMacs,
            state = stateSnapshot,
            chainedSceneId = chainedSceneId,
            chainedSceneReverseId = chainedSceneReverseId,
            chainedSceneDelaySeconds = chainedSceneDelaySeconds
        )
        
        val current = _scenes.value.toMutableList()
        current.add(newScene)
        _scenes.value = current
        prefsRepo.saveScenes(current)
        return newId
    }

    fun updateScene(sceneId: String, name: String, groupA: String?, includeBrightness: Boolean, includeModeSpeed: Boolean, targetScope: String, selectedDeviceMacs: List<String>?, includeAmbianceSettings: Boolean = false, includeCalibrationSettings: Boolean = false, includeAudioSettings: Boolean = false, chainedSceneId: String? = null, chainedSceneDelaySeconds: Int? = null, chainedSceneReverseId: String? = null) {
        val stateSnapshot = captureCurrentDeviceState(groupA, includeBrightness, includeModeSpeed, includeAmbianceSettings, includeCalibrationSettings, includeAudioSettings)
        
        val current = _scenes.value.toMutableList()
        val index = current.indexOfFirst { it.id == sceneId }
        if (index != -1) {
            val updatedScene = current[index].copy(
                name = name,
                targetScope = targetScope,
                selectedDeviceMacs = selectedDeviceMacs,
                state = stateSnapshot,
                chainedSceneId = chainedSceneId,
                chainedSceneReverseId = chainedSceneReverseId,
                chainedSceneDelaySeconds = chainedSceneDelaySeconds
            )
            current[index] = updatedScene
            _scenes.value = current
            prefsRepo.saveScenes(current)
        }
    }

    fun deleteScene(sceneId: String) {
        val current = _scenes.value.filter { it.id != sceneId }
        _scenes.value = current
        prefsRepo.saveScenes(current)
    }

    fun renameScene(sceneId: String, newName: String) {
        val current = _scenes.value.map { if (it.id == sceneId) it.copy(name = newName) else it }
        _scenes.value = current
        prefsRepo.saveScenes(current)
    }

    fun saveAiSceneSequence(params: ProceduralSceneParams, sceneName: String, explanation: String): AppScene? {
        if (params.palette.isEmpty()) return null
        
        val sceneId = UUID.randomUUID().toString()
        
        val state = DeviceSceneState(
            groupASelection = "Colour",
            colorR = params.palette.first().first,
            colorG = params.palette.first().second,
            colorB = params.palette.first().third,
            isPowerOn = true,
            animatedSequence = params
        )
        
        val scene = AppScene(
            id = sceneId,
            name = sceneName,
            targetScope = "ALL_DEVICES", // Default for AI scenes
            state = state
        )
        
        val current = _scenes.value.toMutableList()
        current.add(scene)
        _scenes.value = current
        prefsRepo.saveScenes(current)
        
        return scene
    }

    fun updateAiSceneSequence(sceneId: String, params: ProceduralSceneParams, sceneName: String) {
        if (params.palette.isEmpty()) return
        val current = _scenes.value.toMutableList()
        val index = current.indexOfFirst { it.id == sceneId }
        if (index != -1) {
            val oldScene = current[index]
            val updatedState = oldScene.state.copy(
                animatedSequence = params,
                colorR = params.palette.first().first,
                colorG = params.palette.first().second,
                colorB = params.palette.first().third
            )
            val updatedScene = oldScene.copy(
                name = sceneName,
                state = updatedState
            )
            current[index] = updatedScene
            _scenes.value = current
            prefsRepo.saveScenes(current)
        }
    }

    fun applyScene(scene: AppScene, isReversing: Boolean = false) {
        cancelSceneChain()
        isApplyingScene = true
        activeExcludedMacs.clear()

        val targetMacs = if (scene.targetScope == "SELECT_DEVICES" && scene.selectedDeviceMacs != null) {
            scene.selectedDeviceMacs
        } else {
            getCurrentlyControlledDeviceAddresses()
        }

        if (scene.state.groupASelection == "Audio" || scene.state.groupASelection == "Ambiance") {
            val allConnected = _uiState.value.deviceConnectionStates.filter { it.value == BleConnectionState.CONNECTED }.keys
            activeExcludedMacs.addAll(allConnected.filter { !targetMacs.contains(it) })
            applyDeviceState(scene.state, null)
        } else {
            applyDeviceState(scene.state, targetMacs)
            
            // Fix: Update relevant global UI state fields when applying HardwareMode/Colour/CCT scenes
            _uiState.update { current ->
                var updated = current
                when (scene.state.groupASelection) {
                    "Colour" -> {
                        if (scene.state.colorR != null && scene.state.colorG != null && scene.state.colorB != null) {
                            updated = updated.copy(
                                activeFeatureName = "Colour",
                                red = scene.state.colorR,
                                green = scene.state.colorG,
                                blue = scene.state.colorB
                            )
                        }
                    }
                    "CCT" -> {
                        if (scene.state.cctWarmth != null) {
                            val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(scene.state.cctWarmth)
                            val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                            updated = updated.copy(
                                activeFeatureName = "CCT",
                                warmth = scene.state.cctWarmth,
                                red = rgb[0],
                                green = rgb[1],
                                blue = rgb[2]
                            )
                        }
                    }
                    "HardwareMode" -> {
                        if (scene.state.modeIndex != null) {
                            val modeName = customModes.value.find { it.byteValue == scene.state.modeIndex }?.name ?: "Mode"
                            updated = updated.copy(
                                activeFeatureName = modeName,
                                modeIndex = scene.state.modeIndex
                            )
                        }
                        if (scene.state.modeSpeed != null) {
                            updated = updated.copy(modeSpeed = scene.state.modeSpeed)
                        }
                    }
                }
                if (scene.state.brightness != null) {
                    updated = updated.copy(brightness = scene.state.brightness)
                }
                if (scene.state.isPowerOn != null) {
                    updated = updated.copy(isPowerOn = scene.state.isPowerOn)
                }
                
                                    prefsRepo.putAppStatePrefString("active_feature_name", updated.activeFeatureName)
                    prefsRepo.putAppStatePrefInt("red", updated.red)
                    prefsRepo.putAppStatePrefInt("green", updated.green)
                    prefsRepo.putAppStatePrefInt("blue", updated.blue)
                    prefsRepo.putAppStatePrefInt("mode_index", updated.modeIndex)
                    prefsRepo.putAppStatePrefInt("mode_speed", updated.modeSpeed)
                    prefsRepo.putAppStatePrefInt("warmth", updated.warmth)
                    prefsRepo.putAppStatePrefInt("brightness", updated.brightness)

                updated
            }
        }

        var nextIsReversing = isReversing
        val nextSceneId = if (isReversing && scene.chainedSceneReverseId != null) {
            scene.chainedSceneReverseId
        } else if (!isReversing && scene.chainedSceneId != null) {
            scene.chainedSceneId
        } else if (!isReversing && scene.chainedSceneReverseId != null) {
            nextIsReversing = true
            scene.chainedSceneReverseId
        } else if (isReversing && scene.chainedSceneId != null) {
            nextIsReversing = false
            scene.chainedSceneId
        } else {
            null
        }

        if (nextSceneId != null) {
            val targetScene = _scenes.value.find { it.id == nextSceneId }
            if (targetScene != null) {
                sceneChainJob = viewModelScope.launch {
                    val delaySeconds = scene.chainedSceneDelaySeconds ?: 0
                    if (delaySeconds <= 0) {
                        delay(50L)
                    } else {
                        delay(delaySeconds.toLong() * 1000L)
                    }
                    applyScene(targetScene, nextIsReversing)
                }
            }
        }
        isApplyingScene = false
    }

    private fun updateDeviceStateInMap(macAddress: String, state: DeviceSceneState) {
        _uiState.update { current ->
            val existing = current.deviceStatesMap[macAddress] ?: ActiveDeviceState(
                activeFeatureName = current.activeFeatureName,
                red = current.red,
                green = current.green,
                blue = current.blue,
                warmth = current.warmth,
                modeIndex = current.modeIndex,
                brightness = current.brightness,
                isPowerOn = current.isPowerOn
            )
            
            var updated = existing
            
            if (state.brightness != null) {
                updated = updated.copy(brightness = state.brightness)
            }
            if (state.isPowerOn != null) {
                updated = updated.copy(isPowerOn = state.isPowerOn)
            }
            
            when (state.groupASelection) {
                "Colour" -> {
                    if (state.colorR != null && state.colorG != null && state.colorB != null) {
                        updated = updated.copy(
                            activeFeatureName = "Colour",
                            red = state.colorR,
                            green = state.colorG,
                            blue = state.colorB
                        )
                    }
                }
                "CCT" -> {
                    if (state.cctWarmth != null) {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(state.cctWarmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        updated = updated.copy(
                            activeFeatureName = "CCT",
                            warmth = state.cctWarmth,
                            red = rgb[0],
                            green = rgb[1],
                            blue = rgb[2]
                        )
                    }
                }
                "HardwareMode" -> {
                    if (state.modeIndex != null) {
                        val modeName = customModes.value.find { it.byteValue == state.modeIndex }?.name ?: "Mode"
                        updated = updated.copy(
                            activeFeatureName = modeName,
                            modeIndex = state.modeIndex
                        )
                    }
                }
                "Audio" -> {
                    if (state.audioPreset != null) {
                        updated = updated.copy(
                            activeFeatureName = "Audio - ${state.audioPreset}"
                        )
                    }
                }
                "Ambiance" -> {
                    if (state.ambianceIsOn == true) {
                        updated = updated.copy(
                            activeFeatureName = "Ambiance - ${state.ambiancePreset ?: "Balanced"}"
                        )
                    } else if (state.ambianceIsOn == false) {
                        updated = updated.copy(
                            activeFeatureName = "Colour"
                        )
                    }
                }
            }
            
            val newMap = current.deviceStatesMap.toMutableMap()
            newMap[macAddress] = updated
            current.copy(deviceStatesMap = newMap)
        }
    }

    private fun applyDeviceState(state: DeviceSceneState, targetMacsList: List<String>?) {
        val macs = targetMacsList ?: getCurrentlyControlledDeviceAddresses()

        if (targetMacsList != null) {
            macs.forEach { mac -> updateDeviceStateInMap(mac, state) }
        }

        macs.forEach { mac ->
            sceneRunners[mac]?.release()
            sceneRunners.remove(mac)
        }
        
        if (state.animatedSequence != null) {
            val runner = SceneAnimationRunner(
                macAddresses = macs,
                sequence = state.animatedSequence,
                sendCommand = { m, cmd -> sendCommandToDeviceDirect(m, cmd) },
                saveState = { m, p, r, g, b, w -> 
                    viewModelScope.launch {
                        val currentB = deviceStateStore.getState(m)?.brightness ?: 100
                        deviceStateStore.saveState(m, p, r, g, b, w, currentB)
                    }
                }
            )
            macs.forEach { mac ->
                sceneRunners[mac] = runner
            }
            runner.start()
        }

        // Apply Power (Group B - also includes power)
        state.isPowerOn?.let { isOn ->
            if (targetMacsList == null) setPower(isOn)
            else macs.forEach { sendCommandToDeviceDirect(it, DuoCoProtocol.createPowerCommand(isOn)) }
        }

        // Group B
        state.brightness?.let { br -> 
            if (targetMacsList == null) setBrightness(br) 
            else macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createBrightnessCommand(br)) }
        }

        // Group A
        if (state.animatedSequence != null) {
            return // Skip applying static colour/mode if an animated sequence is running
        }

        when (state.groupASelection) {
            "Colour" -> {
                if (state.colorR != null && state.colorG != null && state.colorB != null) {
                    if (targetMacsList == null) setColor(state.colorR, state.colorG, state.colorB)
                    else macs.forEach { sendCommandToDeviceDirect(it, DuoCoProtocol.createColorCommand(state.colorR, state.colorG, state.colorB)) }
                }
            }
            "CCT" -> {
                state.cctWarmth?.let { warmth ->
                    if (targetMacsList == null) setWarmth(warmth)
                    // Wait, warmth command for direct is complex (needs applying calibration logic directly? No, setWarmth applies globally)
                    // Let's just create color command for warmth
                    else {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(warmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        val mappedRed = rgb[0]
                        val mappedGreen = rgb[1]
                        val mappedBlue = rgb[2]
                        macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createColorCommand(mappedRed, mappedGreen, mappedBlue)) }
                    }
                }
            }
            "HardwareMode" -> {
                state.modeIndex?.let { mi ->
                    if (targetMacsList == null) setMode(mi)
                    else macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createModeCommand(mi)) }
                }
                state.modeSpeed?.let { ms ->
                    if (targetMacsList == null) setModeSpeed(ms)
                    else macs.forEach { mac -> sendCommandToDeviceDirect(mac, DuoCoProtocol.createModeSpeedCommand(ms)) }
                }
            }
            "Audio" -> {
                if (targetMacsList == null) {
                    state.musicMode?.let { startMusicSync(it) }
                    state.audioPreset?.let { setVisualizerPreset(it) }
                }
            }
            "Ambiance" -> {
                if (targetMacsList == null) {
                    // Start or stop ambiance based on state.ambianceIsOn
                    if (state.ambianceIsOn == true && !com.example.ambiance.AmbianceCaptureState.isActive.value) {
                        // Ambiance starting requires intent, we can't fully trigger it from here without intent
                        // But we can apply the preset
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(getApplication(), "Ambiance settings applied — tap Ambiance to start capture", android.widget.Toast.LENGTH_LONG).show()
                        }
                        applyAmbiancePreset(
                            state.ambiancePreset ?: "Balanced",
                            state.ambianceResponseSpeed ?: 0.5f,
                            state.ambianceSmoothnessMs ?: 150,
                            state.ambianceSaturationBoost ?: 1.4f,
                            state.ambianceBrightnessCompensation ?: 1.0f,
                            state.ambianceSceneCutSensitivity ?: 110.0f,
                            state.ambianceNoiseDeadband ?: 0.10f
                        )
                        // Restoring the Scene's saved FPS value
                        val fps = state.ambianceUpdateRateCapFps ?: 20
                        _uiState.update { it.copy(ambianceUpdateRateCapFps = fps) }
                        prefsRepo.putAmbiancePrefInt("update_rate_cap_fps", fps)
                    } else if (state.ambianceIsOn == false && com.example.ambiance.AmbianceCaptureState.isActive.value) {
                        com.example.ambiance.AmbianceCaptureService.stop(getApplication())
                    }
                }
            }
        }
    
        // Independent App-level Settings
        // Ambiance Settings
        state.ambianceResponseSpeed?.let { setAmbianceResponseSpeed(it) }
        state.ambianceSmoothnessMs?.let { setAmbianceSmoothnessMs(it) }
        state.ambianceSaturationBoost?.let { setAmbianceSaturationBoost(it) }
        state.ambianceBrightnessCompensation?.let { setAmbianceBrightnessCompensation(it) }
        state.ambianceUpdateRateCapFps?.let { setAmbianceUpdateRateCapFps(it) }
        state.ambianceSceneCutSensitivity?.let { setAmbianceSceneCutSensitivity(it) }

        // Audio Settings
        state.audioAttack?.let { setAudioSmoothingAttack(it) }
        state.audioDecay?.let { setAudioSmoothingDecay(it) }
        state.audioFlash?.let { setAudioFlashStrength(it) }
        state.noiseGateThreshold?.let { setNoiseGateThreshold(it) }
        state.bassGain?.let { setBassGain(it) }
        state.midGain?.let { setMidGain(it) }
        state.highGain?.let { setHighGain(it) }
        state.isAutoGainEnabled?.let { setAutoGainEnabled(it) }
        state.isPaletteCyclingEnabled?.let { setPaletteCyclingEnabled(it) }
        state.isLogarithmicScalingEnabled?.let { setLogarithmicScalingEnabled(it) }
        state.audioGammaExponent?.let { setAudioGammaExponent(it) }
        state.visualizerMinBrightness?.let { setVisualizerMinBrightness(it) }
        state.visualizerColorSpeed?.let { setVisualizerColorSpeed(it) }

        // Calibration Settings
        state.bluetoothDelayMs?.let { setBluetoothDelayMs(it) }
    }
    // --- ROOM DATABASE OPERATIONS ---

    fun savePreset(name: String) {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            val preset = RgbPreset(
                name = name,
                red = state.red,
                green = state.green,
                blue = state.blue,
                brightness = state.brightness,
                modeIndex = state.modeIndex
            )
            repository.insertPreset(preset)
            addLog("Saved Preset: '$name'")
        }
    }

    fun deletePreset(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePresetById(id)
            addLog("Deleted Preset ID: $id")
        }
    }

    fun saveColorCalibration(calibration: ColorCalibration) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertColorCalibration(calibration)
            addLog("Saved Color Calibration Profile for ${calibration.macAddress}")
        }
    }

    fun deleteColorCalibration(macAddress: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteColorCalibration(macAddress)
            addLog("Deleted Color Calibration Profile for $macAddress")
        }
    }

    fun fit3x3Matrix(
        targets: List<IntArray>,
        measured: List<IntArray>
    ): FloatArray {
        return com.example.core.calibration.CalibrationMatrixSolver.fit3x3Matrix(targets, measured)
    }

    fun applyPreset(preset: RgbPreset) {
        _uiState.update { 
            it.copy(
                red = preset.red,
                green = preset.green,
                blue = preset.blue,
                brightness = preset.brightness,
                modeIndex = preset.modeIndex
            ) 
        }
                    prefsRepo.putAppStatePrefInt("red", preset.red)
            prefsRepo.putAppStatePrefInt("green", preset.green)
            prefsRepo.putAppStatePrefInt("blue", preset.blue)
            prefsRepo.putAppStatePrefInt("brightness", preset.brightness)
            prefsRepo.putAppStatePrefInt("mode_index", preset.modeIndex)
        addLog("Applied Preset: '${preset.name}'")
        
        // Send commands
        syncPhysicalBulb()
    }

    fun saveDeviceAlias(address: String, customName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveDeviceAlias(address, customName)
            addLog("Saved Alias '$customName' for address: $address")
            
            // Also update SavedDevice custom name if it exists in Device Center
            val existingSaved = savedDevices.value.find { it.macAddress == address }
            if (existingSaved != null) {
                repository.insertSavedDevice(existingSaved.copy(customName = customName))
            }
            
            // If we are currently connected to this address, update the connected device name
            if (_uiState.value.connectedDeviceAddress == address) {
                _uiState.update { it.copy(connectedDeviceName = customName) }
            }
            
            // Refresh scanned device aliases
            _uiState.update { state ->
                val list = state.scannedDevices.map { 
                    if (it.address == address) it.copy(alias = customName) else it 
                }
                state.copy(scannedDevices = list)
            }
        }
    }

    fun deleteDeviceAlias(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteDeviceAlias(address)
            addLog("Deleted Alias for address: $address")
            
            // If connected, revert to scan name or unknown
            if (_uiState.value.connectedDeviceAddress == address) {
                val origName = _uiState.value.scannedDevices.find { it.address == address }?.name ?: "Unknown Device"
                _uiState.update { it.copy(connectedDeviceName = origName) }
            }
            
            // Revert scanned list aliases
            _uiState.update { state ->
                val list = state.scannedDevices.map { 
                    if (it.address == address) it.copy(alias = null) else it 
                }
                state.copy(scannedDevices = list)
            }
        }
    }

    fun toggleAutoConnect(address: String, name: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = savedDevices.value.find { it.macAddress == address }
            if (existing != null) {
                repository.updateAutoConnect(address, enabled)
                addLog("Updated Auto-Connect for ${existing.customName} to $enabled")
            } else {
                val device = SavedDevice(
                    macAddress = address,
                    customName = name,
                    isAutoConnectEnabled = enabled,
                    isActiveControlEnabled = true
                )
                repository.insertSavedDevice(device)
                addLog("Saved device $name with Auto-Connect = $enabled")
            }
        }
    }

    fun toggleActiveControl(address: String, name: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = savedDevices.value.find { it.macAddress == address }
            if (existing != null) {
                repository.updateActiveControl(address, enabled)
                addLog("Updated Active Control for ${existing.customName} to $enabled")
            } else {
                val device = SavedDevice(
                    macAddress = address,
                    customName = name,
                    isAutoConnectEnabled = true,
                    isActiveControlEnabled = enabled
                )
                repository.insertSavedDevice(device)
                addLog("Saved device $name with Active Control = $enabled")
            }

            // Handle mid-automation additions/removals
            if (enabled) {
                val s = _uiState.value
                val musicActive = s.musicMode != null
                val ambianceActive = com.example.ambiance.AmbianceCaptureState.isActive.value
                val currentFeatureName = if (musicActive) {
                    s.activeFeatureName
                } else if (ambianceActive) {
                    "Ambiance - ${s.ambiancePreset ?: "Balanced"}"
                } else {
                    s.activeFeatureName
                }

                var devState = s.deviceStatesMap[address]
                if (devState == null || devState.activeFeatureName != currentFeatureName) {
                    val baseState = devState ?: ActiveDeviceState(
                        red = s.red,
                        green = s.green,
                        blue = s.blue,
                        warmth = s.warmth,
                        modeIndex = s.modeIndex,
                        brightness = s.brightness,
                        isPowerOn = s.isPowerOn
                    )
                    val newState = baseState.copy(activeFeatureName = currentFeatureName)
                    _uiState.update { current ->
                        val newMap = current.deviceStatesMap.toMutableMap()
                        newMap[address] = newState
                        current.copy(deviceStatesMap = newMap)
                    }
                    devState = newState
                }
                
                // Now send the stored state to the physical device
                val powerCmd = DuoCoProtocol.createPowerCommand(devState.isPowerOn)
                val brightnessCmd = DuoCoProtocol.createBrightnessCommand(devState.brightness)
                sendCommandToDeviceDirect(address, powerCmd)
                sendCommandToDeviceDirect(address, brightnessCmd)
                
                when {
                    devState.activeFeatureName == "Colour" -> {
                        val colorCmd = DuoCoProtocol.createColorCommand(devState.red, devState.green, devState.blue)
                        sendCommandToDeviceDirect(address, colorCmd)
                    }
                    devState.activeFeatureName == "CCT" -> {
                        val kelvin = com.example.ui.components.ColorUtils.warmthToKelvin(devState.warmth)
                        val rgb = com.example.ui.components.ColorUtils.convertKelvinToRgb(kelvin)
                        val colorCmd = DuoCoProtocol.createColorCommand(rgb[0], rgb[1], rgb[2])
                        sendCommandToDeviceDirect(address, colorCmd)
                    }
                    devState.activeFeatureName.startsWith("Audio") || devState.activeFeatureName.startsWith("Ambiance") -> {
                        // Automated states are handled by their respective controllers
                    }
                    else -> {
                        // Mode
                        val modeCmd = DuoCoProtocol.createModeCommand(devState.modeIndex)
                        sendCommandToDeviceDirect(address, modeCmd)
                    }
                }

                if (musicActive) {
                    saveDeviceState(address, AutomationType.AUDIO)
                } else if (ambianceActive) {
                    saveDeviceState(address, AutomationType.AMBIANCE)
                }
            } else {
                _uiState.update { current ->
                    if (!current.deviceStatesMap.containsKey(address)) {
                        val newMap = current.deviceStatesMap.toMutableMap()
                        newMap[address] = ActiveDeviceState(
                            activeFeatureName = current.activeFeatureName,
                            red = current.red,
                            green = current.green,
                            blue = current.blue,
                            warmth = current.warmth,
                            modeIndex = current.modeIndex,
                            brightness = current.brightness,
                            isPowerOn = current.isPowerOn
                        )
                        current.copy(deviceStatesMap = newMap)
                    } else {
                        current
                    }
                }

                val activeMode = deviceAutomationMode[address]
                if (activeMode != null) {
                    restoreDeviceState(address, activeMode)
                }
            }
        }
    }

    fun deleteSavedDevice(address: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSavedDevice(address)
            addLog("Removed device from Saved Center: $address")
            disconnectDevice(address)
        }
    }

    fun updateCustomMode(customMode: CustomMode) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertCustomMode(customMode)
            addLog("Updated Mode #${customMode.byteValue}: '${customMode.name}'")
        }
    }

    fun renameCategory(oldName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameCategory(oldName, newName)
            addLog("Renamed category '$oldName' to '$newName'")
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logMessages = emptyList()) }
    }

    // --- MUSIC SYNC & AUDIO CAPTURE ACTIONS ---
    private var audioRecord: AudioRecord? = null
    private var visualizer: Visualizer? = null
    
    // Thread-safe state for transmission
    @Volatile private var latestR = 0
    @Volatile private var latestG = 0
    @Volatile private var latestB = 0

    private var audioThread: Thread? = null
    private var transmissionThread: Thread? = null

    fun startMusicSync(mode: String) {
        val targetAddresses = getCurrentlyControlledDeviceAddresses()
        targetAddresses.forEach { address ->
            saveDeviceState(address, AutomationType.AUDIO)
            sceneRunners[address]?.release()
            sceneRunners.remove(address)
        }
        cancelSceneChain()
                val featureName = if (mode == "phone_mic" || mode == "on_device") {
            "Audio Visualiser (${getVisualizerPresetName(_uiState.value.visualizerPreset)})"
        } else {
            "LED Visualiser (${getLedPresetName(mode)})"
        }
        _uiState.update { current ->
            val newMap = current.deviceStatesMap.toMutableMap()
            targetAddresses.forEach { address ->
                val existing = newMap[address] ?: ActiveDeviceState()
                newMap[address] = existing.copy(activeFeatureName = featureName)
            }
            current.copy(musicMode = mode, activeFeatureName = featureName, deviceStatesMap = newMap)
        }
        prefsRepo.putAppStatePrefString("active_feature_name", featureName)
        
        stopMusicSyncInternal(keepServiceRunning = (mode == "phone_mic" || mode == "on_device"))

        if (mode == "phone_mic" || mode == "on_device") {
            startAudioRecording(mode)
        } else {
            val presetIndex = when (mode) {
                "energetic_1" -> 0
                "energetic_2" -> 1
                "rhythm_1" -> 2
                "rhythm_2" -> 3
                "spectrum_1" -> 4
                "spectrum_2" -> 5
                "rolling_1" -> 6
                "rolling_2" -> 7
                else -> -1
            }
            if (presetIndex != -1) {
                val toggleCmd = DuoCoProtocol.createPhoneMicToggleCommand(true)
                sendCommand(toggleCmd, "Phone Mic Toggle ON")
                
                val styleCmd = DuoCoProtocol.createMicVisualizerStyleCommand(presetIndex)
                sendCommand(styleCmd, "Mic Visualizer Style Preset $presetIndex")
                
                val sensitivityCmd = DuoCoProtocol.createMusicSensitivityCommand(_uiState.value.musicSensitivity)
                sendCommand(sensitivityCmd, "Phone Mic sensitivity ${_uiState.value.musicSensitivity}")
            }
        }
    }

    fun stopMusicSync(restoreState: Boolean = true) {
        clearExclusionsIfNotApplyingScene()
        
        val previousMode = _uiState.value.musicMode
        _uiState.update { it.copy(musicMode = null) }
        
        if (previousMode != null) {
            val toggleCmd = DuoCoProtocol.createPhoneMicToggleCommand(false)
            sendCommand(toggleCmd, "Phone Mic Toggle OFF")
        }
        
        stopMusicSyncInternal(keepServiceRunning = false)

        deviceAutomationMode.forEach { (address, mode) ->
            if (mode == AutomationType.AUDIO) {
                if (restoreState) {
                    restoreDeviceState(address, AutomationType.AUDIO)
                } else {
                    deviceAutomationMode.remove(address)
                }
            }
        }
    }

    private fun stopMusicSyncInternal(keepServiceRunning: Boolean = false) {
        _uiState.update { it.copy(isAudioSyncRunning = false) }
        
        audioThread?.interrupt()
        audioThread = null
        
        transmissionThread?.interrupt()
        transmissionThread = null
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {}
        try {
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {}
        visualizer = null
        
        if (!keepServiceRunning) {
            try {
                AudioCaptureService.stop(getApplication())
            } catch (e: Exception) {}
        }
        
        
    }

    fun setMusicSensitivity(value: Int) {
        _uiState.update { it.copy(musicSensitivity = value) }
        val cmd = DuoCoProtocol.createMusicSensitivityCommand(value)
        sendCommand(cmd, "Mic Sensitivity $value")
    }

    private fun startAudioRecording(mode: String) {
        startAudioEngine(mode)
    }

    private fun startAudioEngine(mode: String) {
        _uiState.update { it.copy(isAudioSyncRunning = true) }
        
        // 1. Audio Capture and Processing Thread
        if (mode == "on_device") {
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    addLog("Record Audio Permission missing. Running simulation.")
                    runAudioSimulationEngine()
                    return
                }

                val vis = Visualizer(0)
                com.example.DiagnosticLogger.log("AudioCapture", "Active Engine: ON_DEVICE initialized")
                vis.captureSize = Visualizer.getCaptureSizeRange()[1]
                
                // DSP State Variables
                var smoothedBass = 0.0f
                var smoothedMid = 0.0f
                var smoothedHigh = 0.0f
                var continuousHueOffset = 0.0f
                var whiteHotFlashOffset = 1.0f
                var beatHueOffset = 0.0f
                var maxObservedBass = 20.0f
                var maxObservedMid = 20.0f
                
                val bassHistory = FloatArray(86)
                var bassHistoryIdx = 0
                var bassHistorySum = 0.0f
                var bassHistoryCount = 0
                var lastBeatTime = 0L
                val beatDetector = BeatDetector()
                var beatPulsePeak = 0.0f
                var lastBeatFlashTime = 0L
                var currentPaletteIndex = 0
                val palettes = arrayOf(0f, 60f, 120f, 180f, 240f, 300f)

                val noiseHistory = FloatArray(86)
                var noiseHistoryIdx = 0
                var noiseHistoryCount = 0

                val uiAmplitudes8 = FloatArray(8)
                var maxUiObserved = 10.0f
                val maxPerBandObserved = FloatArray(8) { 10.0f }
                var silenceStartTime = 0L
                
                var smoothedHue = 0.0f

                // Sustained energy section-change variables
                val energyWindowSize = 250 // ~6 seconds
                val energyHistory = FloatArray(energyWindowSize)
                var energyHistoryIdx = 0
                var energyHistorySum = 0.0f
                var energyHistorySqSum = 0.0f
                var energyHistoryCount = 0

                val shortWindowSize = 43 // ~1 second
                val shortHistory = FloatArray(shortWindowSize)
                var shortHistoryIdx = 0
                var shortHistorySum = 0.0f
                var shortHistoryCount = 0

                var lastFftTime = 0L

                vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                        if (fft == null || !_uiState.value.isAudioSyncRunning) return
                        
                        val nowElapsed = android.os.SystemClock.elapsedRealtime()
                        if (lastFftTime != 0L) {
                            val interval = nowElapsed - lastFftTime
                            com.example.DiagnosticLogger.log(
                                "AudioCapture",
                                "FFT capture interval: ${interval}ms, mode=on_device"
                            )
                        }
                        lastFftTime = nowElapsed
                        
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
                                realBins[k] = 0.0f
                                imagBins[k] = 0.0f
                            }
                        }

                        val rateHz = if (samplingRate > 0) samplingRate / 1000.0f else 44100.0f
                        val state = _uiState.value
                        
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

                        val now = System.currentTimeMillis()
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
                            now = now
                        )
                        val isBeat = result.isBeat && totalEnergy >= state.noiseGateThreshold
                        if (isBeat) {
                            if (state.isPaletteCyclingEnabled) {
                                currentPaletteIndex = (currentPaletteIndex + 1) % palettes.size
                            }
                            beatPulsePeak = 0.6f + 0.4f * result.strength
                            lastBeatFlashTime = now
                            
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

                        val baseSat = if (state.isAutoGainEnabled) {
                            (0.5f + 0.5f * (smoothedMid / maxObservedMid)).coerceIn(0.5f, 1.0f)
                        } else {
                            (smoothedMid / 100.0f).coerceIn(0.0f, 1.0f)
                        }
                        val sat = (baseSat * whiteHotFlashOffset).coerceIn(0.0f, 1.0f)
                        
                        val sensitivityMultiplier = (state.musicSensitivity / 50.0f)
                        
                        var valBase: Float
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
                        if (smoothedBass >= state.noiseGateThreshold) {
                            bc = Math.pow(bc.toDouble(), 1.5).toFloat()
                        } else {
                            bc = 0.0f
                        }
                        valBase = maxOf(bc, midContribution * 0.3f)

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

                        val (r, g, b) = hsvToRgb(smoothedHue, sat, value)
                        latestR = r
                        latestG = g
                        latestB = b

                        val cmd = DuoCoProtocol.createMusicColorCommand(r, g, b)
                        queueCommand(cmd)

                        val isBelow = totalEnergy < state.noiseGateThreshold
                        val isIdle: Boolean
                        if (isBelow) {
                            if (silenceStartTime == 0L) {
                                silenceStartTime = now
                            }
                            isIdle = (now - silenceStartTime) > state.idleTriggerDelayMs
                        } else {
                            silenceStartTime = 0L
                            isIdle = false
                        }

                        val normalizedAmplitudesList = if (isIdle) {
                            val timeSec = now / 1000.0
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

                            maxUiObserved = maxUiObserved * 0.98f
                            var currentGlobalMax = 0.0f
                            for (band in 0 until 8) {
                                maxPerBandObserved[band] = maxPerBandObserved[band] * 0.98f
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

                        _uiState.update { 
                            it.copy(
                                musicAmplitudes = normalizedAmplitudesList,
                                isVisualizerIdle = isIdle,
                                visualizerHue = if (isIdle) it.visualizerHue else smoothedHue
                            ) 
                        }
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)

                vis.enabled = true
                visualizer = vis
                addLog("System Visualizer initialized successfully for on-device sync (direct FFT).")
                return
            } catch (e: Exception) {
                addLog("Failed to initialize Visualizer: ${e.message}. Running simulation.")
                runAudioSimulationEngine()
                return
            }
        }

        audioThread = Thread {
            val hasPermission = ContextCompat.checkSelfPermission(
                getApplication(),
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                addLog("Record Audio Permission missing or denied. Running simulation.")
                runAudioSimulationEngine()
                return@Thread
            }

            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                addLog("AudioRecord buffer size error, starting simulation.")
                runAudioSimulationEngine()
                return@Thread
            }

            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    maxOf(minBufferSize, 1024 * 2)
                )

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    addLog("Failed to initialize AudioRecord. Starting simulation.")
                    runAudioSimulationEngine()
                    return@Thread
                }

                audioRecord = record
                record.startRecording()
                addLog("AudioRecord recording started at 44.1 kHz (buffer: 1024).")
                com.example.DiagnosticLogger.log("AudioCapture", "Active Engine: PHONE_MIC started successfully")

                val buffer = ShortArray(1024)
                val real = FloatArray(1024)
                val imag = FloatArray(1024)

                // Precompute Hamming Window
                val hammingWindow = FloatArray(1024) { i ->
                    0.54f - 0.46f * Math.cos(2.0 * Math.PI * i / 1023.0).toFloat()
                }

                // DSP State Variables
                var smoothedBass = 0.0f
                var smoothedMid = 0.0f
                var smoothedHigh = 0.0f
                var continuousHueOffset = 0.0f
                var whiteHotFlashOffset = 1.0f
                var beatHueOffset = 0.0f
                var maxObservedBass = 10.0f
                var maxObservedMid = 10.0f
                
                // 2-second rolling bass history window (~86 frames at 23ms per frame)
                val bassHistory = FloatArray(86)
                var bassHistoryIdx = 0
                var bassHistorySum = 0.0f
                var bassHistoryCount = 0
                var lastBeatTime = 0L
                val beatDetector = BeatDetector()
                var beatPulsePeak = 0.0f
                var lastBeatFlashTime = 0L
                var currentPaletteIndex = 0
                val palettes = arrayOf(0f, 60f, 120f, 180f, 240f, 300f)

                var smoothedHue = 0.0f

                val noiseHistory = FloatArray(86)
                var noiseHistoryIdx = 0
                var noiseHistoryCount = 0

                // UI visualizer spectrum mapping
                val uiAmplitudes8 = FloatArray(8)
                var maxUiObserved = 10.0f
                val maxPerBandObserved = FloatArray(8) { 10.0f }
                var silenceStartTime = 0L

                // Sustained energy section-change variables
                val energyWindowSize = 250 // ~6 seconds
                val energyHistory = FloatArray(energyWindowSize)
                var energyHistoryIdx = 0
                var energyHistorySum = 0.0f
                var energyHistorySqSum = 0.0f
                var energyHistoryCount = 0

                val shortWindowSize = 43 // ~1 second
                val shortHistory = FloatArray(shortWindowSize)
                var shortHistoryIdx = 0
                var shortHistorySum = 0.0f
                var shortHistoryCount = 0

                var lastReadTime = 0L

                while (!Thread.currentThread().isInterrupted && audioRecord != null && _uiState.value.isAudioSyncRunning) {
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    if (lastReadTime != 0L) {
                        val interval = nowElapsed - lastReadTime
                        com.example.DiagnosticLogger.log(
                            "AudioCapture",
                            "MIC read callback interval: ${interval}ms, mode=phone_mic"
                        )
                    }
                    lastReadTime = nowElapsed

                    val readResult = record.read(buffer, 0, 1024)
                    if (readResult <= 0) {
                        if (readResult == AudioRecord.ERROR_INVALID_OPERATION || readResult == AudioRecord.ERROR_BAD_VALUE) {
                            break
                        }
                        continue
                    }

                    // Copy raw PCM data to float array and apply Hamming Window
                    for (i in 0 until 1024) {
                        val sample = if (i < readResult) buffer[i].toFloat() else 0.0f
                        real[i] = sample * hammingWindow[i]
                        imag[i] = 0.0f
                    }

                    // Compute FFT
                    Fft.fft(real, imag)

                    // Compute spectral magnitudes
                    val magnitude = FloatArray(512)
                    for (k in 0 until 512) {
                        magnitude[k] = Math.sqrt((real[k] * real[k] + imag[k] * imag[k]).toDouble()).toFloat()
                    }

                    // Calculate frame average magnitude (excluding DC, up to minOf(349, 512))
                    val searchLimit = 349
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
                    for (k in 0 until 512) {
                        if (magnitude[k] < threshold) {
                            magnitude[k] = 0.0f
                            real[k] = 0.0f
                            imag[k] = 0.0f
                        }
                    }

                    val state = _uiState.value
                    // Group frequency bins (each bin has width 44100 / 1024 ≈ 43.07 Hz)
                    // Bass Band: 20Hz - 150Hz (bins 1 to 3)
                    var bassEnergy = 0.0f
                    for (k in 1..3) bassEnergy += magnitude[k]
                    val bassVal = (bassEnergy / 3.0f) * state.bassGain

                    // Mid Band: 150Hz - 2000Hz (bins 4 to 46)
                    var midEnergy = 0.0f
                    for (k in 4..46) midEnergy += magnitude[k]
                    val midVal = (midEnergy / 43.0f) * state.midGain

                    // High Band: 2000Hz - 15kHz (bins 47 to 348)
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

                    // Apply Dynamic Exponential Moving Average (EMA) Temporal Smoothing
                    val bassAlpha = if (bassVal > smoothedBass) state.audioSmoothingAttack else state.audioSmoothingDecay
                    smoothedBass = bassAlpha * bassVal + (1f - bassAlpha) * smoothedBass

                    val midAlpha = if (midVal > smoothedMid) state.audioSmoothingAttack else state.audioSmoothingDecay
                    smoothedMid = midAlpha * midVal + (1f - midAlpha) * smoothedMid

                    val highAlpha = if (highVal > smoothedHigh) state.audioSmoothingAttack else state.audioSmoothingDecay
                    smoothedHigh = highAlpha * highVal + (1f - highAlpha) * smoothedHigh

                    // Track rolling 2-second average of bass energy
                    val oldBass = bassHistory[bassHistoryIdx]
                    bassHistory[bassHistoryIdx] = bassVal
                    bassHistorySum = bassHistorySum - oldBass + bassVal
                    bassHistoryIdx = (bassHistoryIdx + 1) % 86
                    if (bassHistoryCount < 86) bassHistoryCount++
                    val avgBass = if (bassHistoryCount > 0) bassHistorySum / bassHistoryCount else 0.0f

                    val now = System.currentTimeMillis()
                    val result = beatDetector.process(
                        magnitude = magnitude,
                        realBins = real,
                        imagBins = imag,
                        bassRange = 1..8,
                        midRange = 9..46,
                        midWeight = state.midFluxWeight,
                        thresholdMultiplier = state.beatThresholdMultiplier,
                        minCooldownMs = 180,
                        maxCooldownMs = state.beatCooldownMs,
                        now = now
                    )
                    val isBeat = result.isBeat && totalEnergy >= state.noiseGateThreshold
                    if (isBeat) {
                        if (state.isPaletteCyclingEnabled) {
                            currentPaletteIndex = (currentPaletteIndex + 1) % palettes.size
                        }
                        beatPulsePeak = 0.6f + 0.4f * result.strength
                        lastBeatFlashTime = now
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

                    // Shift Hue based on active baseline color palette from beat registration
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

                    // Normalize Saturation (Mid Band) and Value (Bass Band) using rolling max auto-gain
                    if (state.isAutoGainEnabled) {
                        // Aggressive dual-rate auto-gain using instant bassVal instead of smoothed to maintain snappiness
                        val decayFactorBass = if (bassVal < avgBass) 0.97f else 0.992f
                        maxObservedBass = maxOf(maxObservedBass * decayFactorBass, bassVal, 10.0f)
                        
                        val decayFactorMid = if (smoothedMid < maxObservedMid * 0.5f) 0.97f else 0.992f
                        maxObservedMid = maxOf(maxObservedMid * decayFactorMid, smoothedMid, 10.0f)
                    } else {
                        maxObservedBass = 100.0f
                        maxObservedMid = 100.0f
                    }

                    val baseSat = if (state.isAutoGainEnabled) {
                        (0.4f + 0.6f * (smoothedMid / maxObservedMid)).coerceIn(0.4f, 1.0f)
                    } else {
                        (smoothedMid / 100.0f).coerceIn(0.0f, 1.0f)
                    }
                    val sat = (baseSat * whiteHotFlashOffset).coerceIn(0.0f, 1.0f)
                    
                    val sensitivityMultiplier = (state.musicSensitivity / 50.0f)
                    
                    var valBase: Float
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
                    if (smoothedBass >= state.noiseGateThreshold) {
                        bc = Math.pow(bc.toDouble(), 1.5).toFloat()
                    } else {
                        bc = 0.0f
                    }
                    valBase = maxOf(bc, midContribution * 0.3f)

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

                    // Convert HSV to standard RGB values
                    val (r, g, b) = hsvToRgb(smoothedHue, sat, value)
                    latestR = r
                    latestG = g
                    latestB = b

                    val cmd = DuoCoProtocol.createMusicColorCommand(r, g, b)
                    queueCommand(cmd)

                    val isBelow = totalEnergy < state.noiseGateThreshold
                    val isIdle: Boolean
                    if (isBelow) {
                        if (silenceStartTime == 0L) {
                            silenceStartTime = now
                        }
                        isIdle = (now - silenceStartTime) > state.idleTriggerDelayMs
                    } else {
                        silenceStartTime = 0L
                        isIdle = false
                    }

                    val normalizedAmplitudesList = if (isIdle) {
                        val timeSec = now / 1000.0
                        val pulseVal = 0.15f + 0.10f * Math.sin(2.0 * Math.PI * timeSec / 4.0).toFloat()
                        List(8) { pulseVal.coerceIn(0.05f, 1.0f) }
                    } else if (isBelow) {
                        List(8) { 0.05f }
                    } else {
                        val edges = intArrayOf(1, 2, 5, 10, 23, 49, 108, 234, 512)
                        for (band in 0 until 8) {
                            val startBin = edges[band]
                            val endBin = minOf(edges[band + 1], 511)
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

                        maxUiObserved = maxUiObserved * 0.98f
                        var currentGlobalMax = 0.0f
                        for (band in 0 until 8) {
                            maxPerBandObserved[band] = maxPerBandObserved[band] * 0.98f
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

                    _uiState.update {
                        it.copy(
                            musicAmplitudes = normalizedAmplitudesList,
                            isVisualizerIdle = isIdle,
                            visualizerHue = if (isIdle) it.visualizerHue else smoothedHue
                        )
                    }
                }
            } catch (e: Exception) {
                addLog("Audio capture thread error: ${e.message}")
                runAudioSimulationEngine()
            } finally {
                try {
                    record?.stop()
                } catch (e: Exception) {}
                try {
                    record?.release()
                } catch (e: Exception) {}
            }
        }.apply {
            name = "AudioCaptureThread"
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    private fun runAudioSimulationEngine() {
        addLog("Starting DSP audio simulation engine...")
        com.example.DiagnosticLogger.log("AudioCapture", "Active Engine: SIMULATION started successfully")
        val random = java.util.Random()
        
        var smoothedBass = 0.0f
        var smoothedMid = 0.0f
        var smoothedHigh = 0.0f
        var maxObservedBass = 10.0f
        var maxObservedMid = 10.0f
        
        val bassHistory = FloatArray(86)
        var bassHistoryIdx = 0
        var bassHistorySum = 0.0f
        var bassHistoryCount = 0
        var lastBeatTime = 0L
        var beatPulsePeak = 0.0f
        var lastBeatFlashTime = 0L
        var currentPaletteIndex = 0
        val palettes = arrayOf(0f, 60f, 120f, 180f, 240f, 300f)

        var smoothedHue = 0.0f

        val uiAmplitudes8 = FloatArray(8)
        var silenceStartTime = 0L

        // Sustained energy section-change variables
        val energyWindowSize = 250 // ~6 seconds
        val energyHistory = FloatArray(energyWindowSize)
        var energyHistoryIdx = 0
        var energyHistorySum = 0.0f
        var energyHistorySqSum = 0.0f
        var energyHistoryCount = 0

        val shortWindowSize = 43 // ~1 second
        val shortHistory = FloatArray(shortWindowSize)
        var shortHistoryIdx = 0
        var shortHistorySum = 0.0f
        var shortHistoryCount = 0

        val intervalMs = 23L

        var lastSimTime = 0L

        while (!Thread.currentThread().isInterrupted && _uiState.value.isAudioSyncRunning) {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (lastSimTime != 0L) {
                val interval = nowElapsed - lastSimTime
                com.example.DiagnosticLogger.log(
                    "AudioCapture",
                    "Simulation tick interval: ${interval}ms, mode=simulation"
                )
            }
            lastSimTime = nowElapsed

            val state = _uiState.value
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

            val (r, g, b) = hsvToRgb(smoothedHue, sat, value)
            latestR = r
            latestG = g
            latestB = b

            val cmd = DuoCoProtocol.createMusicColorCommand(r, g, b)
            queueCommand(cmd)

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

            _uiState.update {
                it.copy(
                    musicAmplitudes = normalizedAmplitudesList,
                    isVisualizerIdle = isIdle,
                    visualizerHue = if (isIdle) it.visualizerHue else smoothedHue
                )
            }

            val elapsed = System.currentTimeMillis() - startTime
            val sleepTime = intervalMs - elapsed
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    break
                }
            } else {
                Thread.yield()
            }
        }
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
        return com.example.core.color.ColorConverter.hsvToRgb(h, s, v)
    }

    // In-place Radix-2 Cooley-Tukey Fast Fourier Transform
    private object Fft {
        fun fft(real: FloatArray, imag: FloatArray) {
            val n = real.size
            if (n == 0) return
            
            // Bit-reversal permutation
            var j = 0
            for (i in 0 until n) {
                if (i < j) {
                    val tempR = real[i]
                    real[i] = real[j]
                    real[j] = tempR
                    
                    val tempI = imag[i]
                    imag[i] = imag[j]
                    imag[j] = tempI
                }
                var m = n shr 1
                while (m >= 1 && j >= m) {
                    j -= m
                    m = m shr 1
                }
                j += m
            }
            
            // Cooley-Tukey decimation-in-time
            var len = 2
            while (len <= n) {
                val ang = -2.0 * Math.PI / len
                val wlenR = Math.cos(ang).toFloat()
                val wlenI = Math.sin(ang).toFloat()
                for (i in 0 until n step len) {
                    var wR = 1.0f
                    var wI = 0.0f
                    val halfLen = len shr 1
                    for (k in 0 until halfLen) {
                        val uR = real[i + k]
                        val uI = imag[i + k]
                        
                        val tR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                        val tI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                        
                        real[i + k] = uR + tR
                        imag[i + k] = uI + tI
                        
                        real[i + k + halfLen] = uR - tR
                        imag[i + k + halfLen] = uI - tI
                        
                        val nextWR = wR * wlenR - wI * wlenI
                        wI = wR * wlenI + wI * wlenR
                        wR = nextWR
                    }
                }
                len = len shl 1
            }
        }
    }

    override fun onCleared() {
        if (instance === this) {
            instance = null
        }
        super.onCleared()
        stopMusicSyncInternal()
        stopCalibrationMode()
        try {
            getApplication().unregisterReceiver(routingReceiver)
        } catch (e: Exception) {}
        try {
            val audioManager = getApplication().getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {}
    }
}

class MetronomePlayer {
    private val sampleRate = 44100
    private var clickData: ShortArray? = null

    init {
        val durationMs = 50
        val numSamples = (sampleRate * durationMs) / 1000
        clickData = ShortArray(numSamples) { i ->
            val t = i.toDouble() / sampleRate
            val frequency = 1200.0 // crisp tick frequency
            val envelope = (numSamples - i).toDouble() / numSamples
            val value = Math.sin(2.0 * Math.PI * frequency * t) * envelope * Short.MAX_VALUE
            value.toInt().toShort()
        }
    }

    fun playClick() {
        try {
            val data = clickData ?: return
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                data.size * 2,
                AudioTrack.MODE_STATIC
            )
            track.write(data, 0, data.size)
            track.play()
            Thread {
                try {
                    Thread.sleep(120)
                    track.stop()
                    track.release()
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


}
