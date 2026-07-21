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
    val isIdle: Boolean,
    // --- Fields below added for the delivery-path work (visualizer-review-2026-07-21.md P2/P4) ---
    // Final HSV components behind r/g/b, exposed so per-device role transforms (P4) and the
    // wire's peak-hold priority (P2) can recompute a variant color without re-deriving hue/sat/value
    // from the RGB bytes.
    val sat: Float = 0f,
    val value: Float = 0f,
    // value with the beat-flash contribution zeroed out — i.e. what this frame would have been on
    // pure ambient/ambient-floor alone. Used by AlternatingFlash (P4) for the device that isn't the
    // flash target this beat, and available generally as "value minus the transient spike."
    val ambientValue: Float = 0f,
    // True only on the exact frame BeatDetector fired (not just a high `value`) — the write path's
    // peak-priority bypass (P2) and AlternatingFlash's per-beat device toggle (P4) both need to know
    // "this frame IS the beat," not just "this frame is loud."
    val isBeat: Boolean = false,
    // 0..1 band levels for BandSplit (P4): one device following bass, the other mid/high.
    val bassLevel: Float = 0f,
    val midHighLevel: Float = 0f
)

/**
 * Pure, hardware-free port of the DSP pipeline that used to live inline inside
 * `RgbControllerViewModel.startAudioEngine`'s Visualizer callback (~lines 3553-3879) and
 * AudioRecord read loop (~lines 4022-4331). No Android hardware APIs, no `_uiState`/`_telemetry`
 * references — those writes now happen in the ViewModel orchestrator using this class's output.
 *
 * Holds per-run mutable DSP state (smoothing accumulators, rolling histories, the BeatDetector
 * instance, the anchor+breath hue model's anchor/sustain state, auto-gain trackers, idle timer,
 * UI-amplitude normalization trackers)
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
    private var whiteHotFlashOffset = 1.0f
    private var maxObservedBass = if (backend == AudioBackend.VISUALIZER) 20.0f else 10.0f
    private var maxObservedMid = if (backend == AudioBackend.VISUALIZER) 20.0f else 10.0f

    // --- Anchor+breath hue model (mapping-proposal-audio-to-led-2026-07-21.md §4/§6, stage 2) ---
    // hueAnchor: the discrete color base. Moves only on a qualifying beat/timer event (see
    // process()'s anchor-move block below); otherwise constant except for slow continuous drift,
    // which is folded directly into the anchor per the proposal ("drift rotates the anchor's
    // reference frame... anchor += drift·dt") rather than into the bounded breath term.
    private var hueAnchor = 0.0f
    private var beatsSinceAnchorMove = 0
    private var lastAnchorMoveMs = 0L
    // True only on the frame an anchor move actually fires — this, not every raw beat, is what
    // triggers the hue EMA's hard snap (structural problem #3 in the proposal: every preset used
    // to hard-snap on every beat, including Ambient Chill, whose whole identity is "no transients").
    private var anchorMovedThisFrame = false

    // --- Sustain-response hysteresis+ramp (replaces the old binary +120° toggle, problem #5) ---
    // 0L is the "not currently accumulating" sentinel, same convention as silenceStartTime below.
    private var enterCandidateSinceMs = 0L
    private var exitCandidateSinceMs = 0L
    private var sustainActive = false
    private var sustainStateChangedAtMs = 0L

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

    // Time-basing (mapping-proposal-audio-to-led-2026-07-21.md §6, stage 1): dt in ms since the
    // previous frame, derived from successive `nowMs` values rather than assumed as one tick.
    // Used to make whiteHotFlashOffset's recovery rate backend-agnostic — previously a fixed
    // +0.05/frame recovered in ~20 frames, which meant ~465ms on the ~43Hz AudioRecord backend
    // vs. ~1000ms on the ~20Hz Visualizer backend for the exact same "recovery" (see CLAUDE.md's
    // "Deferred / known-not-urgent" backend-timing note, and the proposal's structural problem
    // #2).
    private var lastFrameNowMs = 0L

    /**
     * Runs one frame through the DSP pipeline. Returns null if the frame should be skipped
     * outright — mirrors the Visualizer backend's original `if (numBins < 349) return` guard,
     * which discarded the frame with no state update and no downstream publish at all.
     */
    fun process(
        frame: AudioCaptureFrame,
        settings: AudioSettingsState,
        nowMs: Long,
        // The slowest currentPacingMs among the devices this frame will be broadcast to, or 0 if
        // unknown/no connected devices yet. Used only to floor the effective flash-decay window
        // (visualizer-review-2026-07-21.md P2) — a percussive preset's decay envelope must span at
        // least a couple of wire writes, or the whole flash can land in the gap between two paced
        // writes and never be visible at all. 0 means "don't floor," not "floor to zero."
        effectivePacingMs: Int = 0
    ): AudioDspResult? {
        val numBins = frame.numBins
        if (numBins < 349) return null

        // 0 on the first frame of a run (matches the original per-frame-constant behavior for
        // that one frame — there is no prior frame to measure a real interval against).
        val dtMs = if (lastFrameNowMs == 0L) 0L else (nowMs - lastFrameNowMs).coerceAtLeast(0L)
        lastFrameNowMs = nowMs

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

        // Sustain-response hysteresis+ramp (proposal §4/§5, problem #5): the raw enter/exit
        // conditions are the original formula (enter unchanged; exit is a new, separate 0.8x
        // threshold — the original had no distinct exit condition at all), but the debounced
        // sustainActive flag only flips after the condition has held for a minimum duration, and
        // sustainIntensity ramps 0->1 / 1->0 over state.sustainRampMs rather than snapping — this
        // replaces the old instantaneous binary +120° hue toggle, which flickered near threshold
        // and applied identically to every preset.
        val sustainThreshold = maxOf(35.0f, state.noiseGateThreshold * 5.0f)
        val rawSustainEnter = (shortMean > sustainThreshold) && (rollingVariance > 5.0f)
        val rawSustainExit = shortMean < sustainThreshold * 0.8f

        enterCandidateSinceMs = if (rawSustainEnter) {
            if (enterCandidateSinceMs == 0L) nowMs else enterCandidateSinceMs
        } else {
            0L
        }
        exitCandidateSinceMs = if (rawSustainExit) {
            if (exitCandidateSinceMs == 0L) nowMs else exitCandidateSinceMs
        } else {
            0L
        }
        if (!sustainActive && rawSustainEnter && enterCandidateSinceMs != 0L && nowMs - enterCandidateSinceMs >= 400L) {
            sustainActive = true
            sustainStateChangedAtMs = nowMs
        } else if (sustainActive && rawSustainExit && exitCandidateSinceMs != 0L && nowMs - exitCandidateSinceMs >= 800L) {
            sustainActive = false
            sustainStateChangedAtMs = nowMs
        }
        val sustainRampProgress = if (state.sustainRampMs <= 0f) {
            1f
        } else {
            ((nowMs - sustainStateChangedAtMs).toFloat() / state.sustainRampMs).coerceIn(0f, 1f)
        }
        val sustainIntensity = if (sustainActive) sustainRampProgress else (1f - sustainRampProgress)

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
        val activeTotal = smoothedBass + smoothedMid + smoothedHigh
        val midRatio = if (activeTotal > 0) smoothedMid / activeTotal else 0.0f
        val highRatio = if (activeTotal > 0) smoothedHigh / activeTotal else 0.0f
        val bassRatio = if (activeTotal > 0) smoothedBass / activeTotal else 0.0f

        anchorMovedThisFrame = false
        if (isBeat) {
            // Fold confidence into the visual pulse alongside strength: a beat's strength alone
            // says "how big was the spike," but confidence (peak salience + BPM-lock + phase-grid
            // agreement) says "how sure are we this is really a beat." Multiplying the strength
            // contribution by confidence means a strong-but-uncertain beat (e.g. pre-tempo-lock,
            // or off the expected grid) still registers but visibly softer than an equally strong,
            // high-confidence one — rather than every detected beat flashing with identical
            // intensity purely off strength.
            beatPulsePeak = state.flashFloor + state.flashRange * result.strength * result.confidence.coerceIn(0f, 1f)
            lastBeatFlashTime = nowMs

            val useWhiteFlash = state.visualizerPreset == "Strobe Blast" || state.visualizerPreset == "Beat Only" || state.visualizerPreset == "Laser Sharp"
            if (useWhiteFlash) {
                // Analog white-flash depth (proposal §4): desaturation depth tracks how hard the
                // beat actually hit (strength·confidence) instead of every beat snapping to the
                // same binary full-white — a weak beat gives a pale wash, a locked-in downbeat
                // gives the full white pop.
                val depth = (result.strength * result.confidence.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                whiteHotFlashOffset = (1f - depth).coerceIn(0f, 1f)
            }

            // Anchor moves are confidence-gated (proposal §4): a low-confidence beat still flashes
            // (above) but doesn't recolor — flash is cheap and reversible, recoloring is the
            // perceptually expensive event.
            if (result.confidence.coerceIn(0f, 1f) >= state.hueJumpConfidenceGate) {
                beatsSinceAnchorMove++
                if (state.anchorBeatsPerAdvance > 0 && beatsSinceAnchorMove >= state.anchorBeatsPerAdvance) {
                    hueAnchor = (hueAnchor + state.hueAnchorJumpDeg) % 360f
                    beatsSinceAnchorMove = 0
                    lastAnchorMoveMs = nowMs
                    anchorMovedThisFrame = true
                }
            }
        }

        // Timer fallback for presets that want color variety even without confident beats
        // (Ambient Chill's documented "every 16 beats, or a 30s timer if no BPM lock"). Only
        // engages once the detector has gone a while without locking a tempo — once it has, the
        // beat-count path above is doing the real work and this would just double up.
        if (state.anchorTimerMs > 0L && result.bpmConfidence < 0.3f &&
            nowMs - lastAnchorMoveMs >= state.anchorTimerMs
        ) {
            hueAnchor = (hueAnchor + state.hueAnchorJumpDeg) % 360f
            beatsSinceAnchorMove = 0
            lastAnchorMoveMs = nowMs
            anchorMovedThisFrame = true
        }

        // whiteHotFlashOffset recovery (time-based since stage 1) now uses a per-preset recovery
        // time instead of one global constant, so Strobe Blast/Laser Sharp/Beat Only can each pick
        // how fast their white pop fades back to color.
        whiteHotFlashOffset = Math.min(1.0f, whiteHotFlashOffset + (dtMs.toFloat() / state.whiteFlashRecoveryMs))

        // Continuous drift folds directly into the anchor rather than a separate accumulator, so
        // it can never run away independently of the discrete color state — the old
        // continuousHueOffset mixed exactly this kind of unbounded per-frame drift with per-beat
        // jumps in one variable (structural problem #1 in the proposal). Tempo-locked when the
        // detector has a confident BPM, crossfaded with the free-running deg/sec rate by
        // bpmConfidence otherwise (proposal §4, "tempo-locked color motion").
        val dtSec = dtMs.toFloat() / 1000f
        val bpmConf = result.bpmConfidence.coerceIn(0f, 1f)
        val tempoLockedDriftDegPerSec = (result.bpm.toFloat() / 60f) * state.hueDegreesPerBeat
        val driftDegPerSec = state.hueDriftDegPerSec * (1f - bpmConf) + tempoLockedDriftDegPerSec * bpmConf
        hueAnchor = (hueAnchor + driftDegPerSec * dtSec + 360f) % 360f

        // Breath: a bounded tilt around the anchor, never an accumulator — it can't walk away.
        // Default source is the existing spectral-tilt logic ((midRatio - highRatio), which is
        // already bounded to [-1, 1]), scaled to the preset's configured range. Bass Thump keys
        // this to bassRatio instead (see AudioSettingsState.breathUsesBassRatio) so its color
        // leans with the low end its identity is built on; the bassRatio->offset mapping (centered
        // on ~0.33, the baseline for three roughly-equal bands) is a judgment call, not specified
        // numerically in the proposal doc.
        val breathRange = state.hueBreathRangeDeg
        val breath = if (state.breathUsesBassRatio) {
            (((bassRatio - (1f / 3f)) / (2f / 3f)) * breathRange).coerceIn(-breathRange, breathRange)
        } else {
            ((midRatio - highRatio) * breathRange).coerceIn(-breathRange, breathRange)
        }

        var hue = (hueAnchor + breath + 360f) % 360f

        // Sustain response (proposal §4/§5, problem #5): one of four per-preset behaviors, applied
        // with the hysteresis-debounced, ramped sustainIntensity computed above — replacing the
        // old single binary +120° hue toggle that applied identically to every preset.
        when (state.sustainResponse) {
            "HUE_SHIFT" -> hue = (hue + 120f * sustainIntensity + 360f) % 360f
            // SAT_BOOST and BRIGHTNESS_SWELL are applied later, once sat/value exist.
        }

        // Smoothly interpolate hue only when not experiencing an anchor move using circular
        // smoothing. Snapping only on an actual anchor move (not every raw beat, as before) is
        // what lets a preset like Ambient Chill breathe continuously without ever hard-cutting.
        val targetRad = Math.toRadians(hue.toDouble())
        val targetX = Math.cos(targetRad).toFloat()
        val targetY = Math.sin(targetRad).toFloat()

        val smoothedRad = Math.toRadians(smoothedHue.toDouble())
        val curSmoothedX = Math.cos(smoothedRad).toFloat()
        val curSmoothedY = Math.sin(smoothedRad).toFloat()

        val nextX: Float
        val nextY: Float
        if (anchorMovedThisFrame) {
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
        var sat = (baseSat * whiteHotFlashOffset).coerceIn(0.0f, 1.0f)
        if (state.sustainResponse == "SAT_BOOST") {
            // No magnitude is specified in the proposal doc for this response (only HUE_SHIFT's
            // 120° and BRIGHTNESS_SWELL's +0.10 are given numerically) — 0.3f is a judgment call
            // chosen to be clearly visible without blowing saturation to a flat 1.0 on every
            // sustained section.
            sat = (sat + 0.3f * sustainIntensity).coerceIn(0.0f, 1.0f)
        }

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

        // Floor the effective decay window to the wire (visualizer-review-2026-07-21.md P2): a
        // preset's `beatFlashDecayMs` is tuned assuming every frame reaches the device, but at
        // real pacing (default 50ms, auto-tune may land higher) a short decay like Laser Sharp's
        // 70ms can fall entirely between two paced writes, so the flash is a coin-flip whether it
        // ever appears on the wire at all. 2.5x is chosen to guarantee at least ~2 writes land
        // inside the envelope regardless of phase alignment between the beat and the pacing timer.
        val effectiveBeatFlashDecayMs = if (effectivePacingMs > 0) {
            maxOf(state.beatFlashDecayMs, effectivePacingMs * 2.5f)
        } else {
            state.beatFlashDecayMs
        }

        var value: Float
        var ambientValue: Float
        if (state.visualizerPreset == "Beat Only") {
            val elapsedMs = nowMs - lastBeatFlashTime
            val t = elapsedMs.toFloat() / effectiveBeatFlashDecayMs
            val beatEnvelope = maxOf(1f - t * t, 0f) * beatPulsePeak
            value = maxOf(state.visualizerMinBrightness, (beatEnvelope * maxOf(0.5f, state.audioFlashStrength)).coerceIn(0f, 1f))
            // Beat Only has no ambient term at all — its floor is the closest thing to "no flash."
            ambientValue = state.visualizerMinBrightness
        } else {
            var baseValVal = (valBase * sensitivityMultiplier).coerceIn(0.0f, 1.0f)
            baseValVal = Math.pow(baseValVal.toDouble(), state.audioGammaExponent.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
            // Anticipatory dimming (mapping-proposal-audio-to-led-2026-07-21.md §4, stage 4 —
            // opt-in, purely additive: a new *consumer* of BeatDetector.nextPredictedBeatMs, zero
            // change to detection/tuning math). Pre-dips the ambient contribution up to 10% over
            // the ~80ms leading into a predicted beat, so the subsequent flash reads as a bigger
            // jump for free. Ramps back to 0 immediately once the window passes; no effect at all
            // without a tempo lock (nextPredictedBeatMs null), outside the 80ms window, or on
            // presets with audioFlashStrength == 0 (Ambient Chill/Smooth Flow both set their
            // config's `flash` field to 0.00f, which zeroes `beatFlash` at its use site below
            // regardless of flashFloor/flashRange — those two default to 0.6f/0.4f, nonzero, for
            // both presets, since neither overrides them. audioFlashStrength, not flashRange, is
            // the actual "does this preset flash at all" signal; gating on flashRange here was
            // wrong and never actually skipped the pre-dip for these two presets.
            // Retargeted to the predicted *flash* time, not the predicted audible onset
            // (visualizer-review-2026-07-21.md P2/§1c): nextPredictedBeatMs extrapolates the DP
            // grid, which lives on the capture/onset timeline, but the flash for that beat doesn't
            // actually start until BeatDetector's centered detector fires at onset + lookaheadMs.
            // Without the offset the dip fired, released, and then ~180ms of plain ambient played
            // before the flash — the dip-then-pop contrast never happened. This is the interim fix
            // the proposal names explicitly; the real fix is predictive flash scheduling (P1),
            // out of scope this session.
            val msUntilPredictedBeat = result.nextPredictedBeatMs?.let { (it + beatDetector.lookaheadMs) - nowMs }
            val preDip = if (state.audioFlashStrength > 0f && msUntilPredictedBeat != null && msUntilPredictedBeat in 0..80L) {
                0.10f * (1f - msUntilPredictedBeat / 80f)
            } else {
                0f
            }
            val ambientLevel = baseValVal * state.ambientCapFraction * (1f - preDip)
            val elapsedMs = nowMs - lastBeatFlashTime
            val t = elapsedMs.toFloat() / effectiveBeatFlashDecayMs
            val beatEnvelope = maxOf(1f - t * t, 0f) * beatPulsePeak
            val beatFlash = beatEnvelope * state.audioFlashStrength
            value = maxOf(state.visualizerMinBrightness, (ambientLevel + beatFlash).coerceIn(0f, 1f))
            ambientValue = maxOf(state.visualizerMinBrightness, ambientLevel.coerceIn(0f, 1f))
        }
        if (state.sustainResponse == "BRIGHTNESS_SWELL") {
            // +0.10 is the one sustain-response magnitude the proposal doc gives explicitly
            // (Ambient Chill's row in §5's per-preset table).
            value = (value + 0.10f * sustainIntensity).coerceIn(0.0f, 1.0f)
            ambientValue = (ambientValue + 0.10f * sustainIntensity).coerceIn(0.0f, 1.0f)
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
            isIdle = isIdle,
            sat = sat,
            value = value,
            ambientValue = ambientValue,
            isBeat = isBeat,
            bassLevel = bc,
            midHighLevel = midContribution
        )
    }
}
