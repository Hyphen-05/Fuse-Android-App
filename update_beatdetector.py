import sys

with open("app/src/main/java/com/example/RgbControllerViewModel.kt", "r") as f:
    content = f.read()

start_str = """private class BeatDetector(
    private val fluxHistorySize: Int = 64
) {"""

end_str = """        val strength = if (stddev > 0.001f) (((flux - mean) / stddev).coerceIn(0f, 4f) / 4f) else 0f
        return BeatResult(isBeat, strength)
    }
}"""

if start_str not in content:
    print("Start string not found")
    sys.exit(1)

if end_str not in content:
    print("End string not found")
    sys.exit(1)

start_idx = content.find(start_str)
end_idx = content.find(end_str) + len(end_str)

replacement = """private class BeatDetector(
    private val fluxHistorySize: Int = 64
) {
    private val fluxHistory = FloatArray(fluxHistorySize)
    private var fluxIdx = 0
    private var fluxCount = 0
    private var fluxSum = 0f
    private var fluxSqSum = 0f

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

    private var wasAboveThreshold = false

    private val ibiHistory = FloatArray(4) { 500f }
    private var ibiIdx = 0
    private var lastBeatTime = 0L

    data class BeatResult(val isBeat: Boolean, val strength: Float)

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

        // Compute threshold from history BEFORE inserting current sample, so a single
        // loud transient doesn't raise the bar against itself.
        val mean = if (fluxCount > 0) fluxSum / fluxCount else 0f
        val variance = maxOf(0f, (if (fluxCount > 0) fluxSqSum / fluxCount else 0f) - mean * mean)
        val stddev = Math.sqrt(variance.toDouble()).toFloat()
        val threshold = mean + thresholdMultiplier * stddev

        val old = fluxHistory[fluxIdx]
        fluxHistory[fluxIdx] = flux
        fluxSum = fluxSum - old + flux
        fluxSqSum = fluxSqSum - old * old + flux * flux
        fluxIdx = (fluxIdx + 1) % fluxHistorySize
        if (fluxCount < fluxHistorySize) fluxCount++

        val avgIbi = ibiHistory.average().toFloat()
        val dynamicCooldown = (avgIbi * 0.55f).coerceIn(minCooldownMs.toFloat(), maxCooldownMs.toFloat())
        val cooldownOk = (now - lastBeatTime) > dynamicCooldown

        // Rising-edge crossing: fire the instant flux exceeds the adaptive threshold,
        // instead of waiting for a subsequent frame to show decline. Avoids missed
        // beats when the true flux peak isn't captured at low FFT sample rates.
        var isBeat = false
        val isAboveThreshold = flux > threshold && threshold > 0.01f
        if (isAboveThreshold && !wasAboveThreshold && cooldownOk) {
            isBeat = true
            if (lastBeatTime != 0L) {
                val ibi = (now - lastBeatTime).coerceIn(80L, 2000L)
                ibiHistory[ibiIdx] = ibi.toFloat()
                ibiIdx = (ibiIdx + 1) % ibiHistory.size
            }
            lastBeatTime = now
        }
        wasAboveThreshold = isAboveThreshold

        val strength = if (stddev > 0.001f) (((flux - mean) / stddev).coerceIn(0f, 4f) / 4f) else 0f
        return BeatResult(isBeat, strength)
    }
}"""

new_content = content[:start_idx] + replacement + content[end_idx:]

with open("app/src/main/java/com/example/RgbControllerViewModel.kt", "w") as f:
    f.write(new_content)

print("Replaced BeatDetector successfully")
