package com.example.core.audio

// Phase 5, part B: moved verbatim out of RgbControllerViewModel.kt (was `internal class
// BeatDetector` at file scope there since Phase 4, when it was widened from `private` so
// AudioDspProcessor could reuse it). Only the package changed — no logic touched. Lives
// alongside AudioDspProcessor since it's pure computational logic (autocorrelation/phase-flux
// beat detection), not tangled up with any of the ViewModel's other responsibilities.
internal class BeatDetector(
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

    // Slow-release reference for scaledMad — see its use in process() below. Prevents the
    // MAD-based threshold/strength normalization from collapsing to near-zero the moment a
    // track ends, which otherwise lets ambient mic self-noise/room noise look like a normal
    // beat relative to its own (now tiny) recent variability.
    private var madReference = 0f

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

        // Slow-release reference for scaledMad: tracks the loudest recent scaledMad instantly
        // (attack), but decays over a few seconds (release) rather than collapsing the moment a
        // track ends. Without this, scaledMad — and therefore both the candidate threshold below
        // and the strength ratio further down — re-normalizes against whatever tiny variability
        // is present during near-silence, so ambient mic/room noise between tracks ends up
        // looking like a totally ordinary beat relative to itself, even though it's nowhere near
        // the music's actual dynamic range. Flooring at 25% of the reference (not the full
        // reference) still lets the detector adapt down over time for genuinely quiet songs.
        madReference = if (scaledMad > madReference) scaledMad else madReference * 0.995f + scaledMad * 0.005f
        val effectiveMad = maxOf(scaledMad, madReference * 0.25f)

        val threshold = maxOf(medianFlux + thresholdMultiplier * effectiveMad, 0.3f)

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
        // Soft-saturating curve rather than a hard linear ceiling: the old
        // `.coerceIn(0f, 4f) / 4f` pinned every beat with ratio >= 4 to an identical 1.0, which
        // on-device diagnostic data showed happening on ~48% of real detected beats (both
        // backends) — discarding all "how much bigger than 4x" information. This approaches 1
        // asymptotically instead, so strength keeps distinguishing "solid beat" from "huge beat"
        // well beyond the old ceiling.
        val strength = if (effectiveMad > 0.001f) {
            val ratio = ((evalSample.flux - medianFlux) / effectiveMad).coerceAtLeast(0f)
            (1f - kotlin.math.exp(-ratio / 6f)).coerceIn(0f, 1f)
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
