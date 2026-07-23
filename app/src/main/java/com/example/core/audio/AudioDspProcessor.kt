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
    val midHighLevel: Float = 0f,
    // True only on the exact frame a flash (predictive or fast-causal) actually rendered its peak
    // (visualizer-review-2026-07-22.md A1/A2/A4). Since P1, that is NOT the same frame as `isBeat`
    // -- detection confirms/corrects a flash that already fired up to ~180ms+flashTimingOffsetMs
    // earlier. Anything that needs to key off of "the visible flash is happening right now" (the
    // wire's pacing-bypass priority, AlternatingFlash's device round-robin, the P0 diagnostic log)
    // must use this instead of `isBeat`, or it fires ~200-300ms late against a mostly-decayed
    // envelope.
    val flashFiredThisFrame: Boolean = false
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

    // visualizer-review-2026-07-22.md C8/B4: tau constants for the auto-gain/UI-amplitude decay
    // conversion to exp(-dtMs/tau), chosen so the decay reduces to the original per-frame
    // multiplicative constant at phone_mic's reference ~23ms/frame interval (i.e. phone_mic
    // behavior is unchanged; Visualizer's ~50ms/frame cadence now decays the correct amount of
    // real time instead of only half as much). tau = -REF_DT_MS / ln(originalPerFrameFactor).
    private val AUTO_GAIN_FAST_TAU_MS = 755f  // from the original 0.97f/frame
    private val AUTO_GAIN_SLOW_TAU_MS = 2864f // from the original 0.992f/frame
    private val UI_AMPLITUDE_DECAY_TAU_MS = 1138f // from the original 0.98f/frame

    // visualizer-review-2026-07-22.md C5 (P3 macro-dynamics): see the ceiling/floor tracker and
    // drop detector inside process() for the mechanism. 45s sits in the review's suggested
    // 30-60s range.
    private val MACRO_TAU_MS = 45000f
    private var macroLoudnessCeiling = 0f
    private var macroLoudnessFloor = 0f
    private var lastDropFiredAtMs = 0L
    private var dropFlashRangeBoostUntilMs = 0L

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
    // visualizer-review-2026-07-22.md A5: sustainIntensity used to be derived fresh each frame from
    // (nowMs - sustainStateChangedAtMs), which meant a state flip mid-ramp reset the timer AND
    // flipped which branch of the if/else computed intensity -- e.g. deactivating at 40% ramped-in
    // jumped instantly to 1.0 (the "1f - rampProgress" branch's value at rampProgress=0) before
    // decaying, a visible brightness step in the wrong direction. Now a stored field that moves
    // toward its target by dtMs/sustainRampMs each frame, so a flip mid-ramp reverses smoothly from
    // wherever it actually was instead of snapping.
    private var sustainIntensity = 0f

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

    // --- P1 predictive flash scheduling (mapping-proposal-audio-to-led-2026-07-21.md §4,
    // "Predictive flash scheduling") ---
    // Detection (BeatDetector's centered ~180ms-lookahead isBeat) is structurally too late to
    // feel on-beat, so the flash trigger itself no longer waits for it. Once tempo-locked, the
    // flash is scheduled ahead of time off the DP beat grid (nextPredictedBeatMs); detection then
    // only confirms/corrects the already-scheduled flash and seeds the *next* one's intensity.
    // Detection still stays the sole authority for everything else (anchor moves, white-flash
    // depth, confidence/tempo) -- see the isBeat block in process() below, unchanged in shape.

    // The beat-grid instant (BeatResult.nextPredictedBeatMs) the current schedule was derived
    // from. Used to detect "the grid moved on to a new upcoming beat" vs. "still the same one" --
    // re-deriving scheduledFlashAtMs every frame off a stable nextPredictedBeatMs would just be
    // reactive-with-extra-steps. 0L = nothing currently scheduled.
    private var scheduledForBeatMs = 0L
    // The actual render target for the next flash: nextPredictedBeatMs - flashTimingOffsetMs.
    private var scheduledFlashAtMs = 0L
    private var scheduledFlashFired = false
    // Set once either a real detection confirms the fired flash, or its confirmation window
    // lapses unconfirmed (at which point it's been handled/corrected and shouldn't be re-checked
    // every subsequent frame until the next schedule).
    private var scheduledFlashSettled = false
    private var scheduledFlashConfirmDeadlineMs = 0L
    // Strength/confidence carried into the *next* scheduled flash's peak. Detection for beat N
    // necessarily arrives after beat N's schedule has already fired (that's the whole reason for
    // scheduling), so the only thing it can shape is beat N+1's intensity, never beat N's own.
    // Seeded at a reasonable mid-range guess so the very first scheduled flash (before any real
    // detection has landed yet) isn't silently zero.
    private var carriedFlashStrength = 0.7f
    private var carriedFlashConfidence = 0.7f
    // The decay window actually governing the currently-active flash envelope -- normally just
    // the frame's effectiveBeatFlashDecayMs at the moment the flash fired, but shrunk if that
    // flash later goes unconfirmed (a bad prediction should fade fast, not lingering for the
    // preset's full tuned decay). Read by the envelope calculation further down in process().
    private var activeFlashDecayMs = 200f
    // Cooldown between mechanism-2 (fast causal trigger) flashes, so a sustained loud passage
    // above the causal threshold doesn't retrigger every single frame.
    private var lastFastTriggerFlashAtMs = 0L

    private val noiseHistory = FloatArray(86)
    private var noiseHistoryIdx = 0
    private var noiseHistoryCount = 0

    private val uiAmplitudes8 = FloatArray(8)
    private var maxUiObserved = 10.0f
    private val maxPerBandObserved = FloatArray(8) { 10.0f }
    private var silenceStartTime = 0L

    private var smoothedHue = 0.0f

    // visualizer-review-2026-07-22.md C7/P5: EMA-smoothed spectral crest (peak/mean magnitude
    // ratio), consumed by the saturation calculation further down in process(). Starts at 1.0 --
    // "flat spectrum," the crestRatio value for a silent/uniform first frame.
    private var smoothedCrest = 1.0f

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

        // Calculate frame average magnitude (excluding DC, up to minOf(349, numBins)). Also tracks
        // the frame's peak bin magnitude in the same pass -- visualizer-review-2026-07-22.md
        // C7/P5 uses peak/mean ("spectral crest") further down to give saturation a third moving
        // dimension. No extra pass over the spectrum needed; this loop already visits every bin.
        val searchLimit = minOf(349, numBins)
        var magnitudeSum = 0.0f
        var magnitudeCount = 0
        var magnitudePeak = 0.0f
        for (k in 1 until searchLimit) {
            magnitudeSum += magnitude[k]
            magnitudeCount++
            if (magnitude[k] > magnitudePeak) magnitudePeak = magnitude[k]
        }
        val frameAvgMag = if (magnitudeCount > 0) magnitudeSum / magnitudeCount else 0.0f
        // Crest ratio: 1.0 for a flat/noisy spectrum, higher for a peaky/tonal one. Smoothed with
        // a fixed-rate EMA (independent of audioSmoothingAttack/Decay, which are tuned for
        // band-energy dynamics, not spectral shape) so per-frame FFT bin jitter doesn't make
        // saturation flicker.
        val crestRatio = if (frameAvgMag > 0.001f) magnitudePeak / frameAvgMag else 1f
        smoothedCrest = 0.15f * crestRatio + 0.85f * smoothedCrest

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

        // Floor the effective decay window to the wire (visualizer-review-2026-07-21.md P2) --
        // moved up from its original spot further down in this function so the P1 scheduling
        // block below (which needs a decay window at the moment a flash fires) can read it too.
        // See the original comment, preserved at its old call site, for the full P2 rationale.
        val effectiveBeatFlashDecayMs = if (effectivePacingMs > 0) {
            maxOf(state.beatFlashDecayMs, effectivePacingMs * 2.5f)
        } else {
            state.beatFlashDecayMs
        }

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

        // --- P3 macro-dynamics (visualizer-review-2026-07-22.md C5) ---
        // A slow (~45s) loudness ceiling/floor derived from the existing 6s rollingMean -- cheap
        // (no new rolling buffer, no per-frame sort, unlike a literal percentile tracker, which
        // would add exactly the kind of per-frame allocation/sort churn B5 flags) way to tell
        // "chorus" from "verse": ceiling attacks instantly and releases over ~45s, floor is the
        // mirror image (drops instantly, releases upward over ~45s). macroPercentile is where
        // the current rollingMean sits within that recent dynamic range.
        macroLoudnessCeiling = if (rollingMean > macroLoudnessCeiling) {
            rollingMean
        } else {
            val release = kotlin.math.exp(-dtMs.toFloat() / MACRO_TAU_MS)
            macroLoudnessCeiling * release + rollingMean * (1f - release)
        }
        macroLoudnessFloor = if (macroLoudnessFloor == 0f || rollingMean < macroLoudnessFloor) {
            rollingMean
        } else {
            val release = kotlin.math.exp(-dtMs.toFloat() / MACRO_TAU_MS)
            macroLoudnessFloor * release + rollingMean * (1f - release)
        }
        val macroLoudnessRange = maxOf(macroLoudnessCeiling - macroLoudnessFloor, 1f)
        val macroPercentile = ((rollingMean - macroLoudnessFloor) / macroLoudnessRange).coerceIn(0f, 1f)

        // Drop detector: a sudden spike well above the established ~45s ceiling. Gated on a real
        // ceiling actually existing yet (not startup silence) and a 30s refractory so a loud song
        // doesn't retrigger every few seconds. 2.5x is a judgment call, not a measured value --
        // large enough that ordinary chorus-level loudness (already captured by macroPercentile
        // above) doesn't itself qualify as a "drop."
        var dropFiredThisFrame = false
        if (totalEnergy > macroLoudnessCeiling * 2.5f &&
            macroLoudnessCeiling > state.noiseGateThreshold &&
            nowMs - lastDropFiredAtMs > 30000L
        ) {
            lastDropFiredAtMs = nowMs
            dropFlashRangeBoostUntilMs = nowMs + 2000L
            // Uniform white blast, not gated by useWhiteFlash (unlike the regular per-beat white
            // pop below) -- a drop is meant to read as a moment regardless of preset. Reuses the
            // existing whiteHotFlashOffset recovery mechanic (per-preset whiteFlashRecoveryMs)
            // rather than inventing a second decay, so it fades back at whatever rate the current
            // preset is already tuned for.
            whiteHotFlashOffset = 0f
            dropFiredThisFrame = true
            DiagnosticLogger.log(
                "MacroDrop",
                "backend=${backend.name} atMs=$nowMs totalEnergy=$totalEnergy ceiling=$macroLoudnessCeiling floor=$macroLoudnessFloor"
            )
        }

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
        } else if (sustainActive && rawSustainExit && exitCandidateSinceMs != 0L && nowMs - exitCandidateSinceMs >= 800L) {
            sustainActive = false
        }
        val sustainTarget = if (sustainActive) 1f else 0f
        sustainIntensity = if (state.sustainRampMs <= 0f) {
            sustainTarget
        } else {
            val step = dtMs.toFloat() / state.sustainRampMs
            if (sustainTarget > sustainIntensity) {
                (sustainIntensity + step).coerceAtMost(sustainTarget)
            } else {
                (sustainIntensity - step).coerceAtLeast(sustainTarget)
            }
        }

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
            // Diagnostic-only (2026-07-22, on_device signal-zero investigation): confirms whether
            // real signal energy is even reaching the DSP pipeline before BeatDetector runs, since
            // strength/confidence/bpm sitting at exactly 0.0 for an entire on_device session is
            // consistent with either (a) no real audio energy in the reconstructed FFT bins at
            // all, or (b) energy present but never clearing the noise gate / beat threshold. This
            // line distinguishes the two without touching any decision logic.
            DiagnosticLogger.log(
                "SignalLevel",
                "backend=${backend.name} frameAvgMag=$frameAvgMag noiseFloor=$noiseFloor totalEnergy=$totalEnergy " +
                    "bassVal=$bassVal midVal=$midVal highVal=$highVal noiseGateThreshold=${state.noiseGateThreshold}"
            )
        }
        val activeTotal = smoothedBass + smoothedMid + smoothedHigh
        val midRatio = if (activeTotal > 0) smoothedMid / activeTotal else 0.0f
        val highRatio = if (activeTotal > 0) smoothedHigh / activeTotal else 0.0f
        val bassRatio = if (activeTotal > 0) smoothedBass / activeTotal else 0.0f

        // visualizer-review-2026-07-22.md A4: white-flash desaturation used to be set here, keyed
        // on detection (isBeat), which fires up to ~180ms+flashTimingOffsetMs after the predictive
        // scheduler below already rendered the brightness flash -- the white "pop" landed visibly
        // after the color pop it's supposed to be part of. Depth is now written at the same trigger
        // sites (below) that set beatPulsePeak, using the carried strength/confidence values, so
        // both halves of a "white flash" render on the same frame.
        val useWhiteFlash = state.visualizerPreset == "Strobe Blast" || state.visualizerPreset == "Beat Only" || state.visualizerPreset == "Laser Sharp"

        // A drop's anchor jump is guaranteed -- unlike a regular beat's confidence-gated move,
        // there's no "was this really a beat" question here, the energy spike itself is the
        // trigger. Bypasses hueJumpConfidenceGate entirely.
        if (dropFiredThisFrame) {
            hueAnchor = (hueAnchor + state.hueAnchorJumpDeg) % 360f
            beatsSinceAnchorMove = 0
            lastAnchorMoveMs = nowMs
        }
        anchorMovedThisFrame = dropFiredThisFrame
        if (isBeat) {
            // Detection stays the sole authority for anchor moves and confidence -- only the flash
            // *trigger* itself (beatPulsePeak/lastBeatFlashTime/whiteHotFlashOffset) moved to the
            // predictive scheduler below.

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

        // visualizer-review-2026-07-22.md A1/A2/A4: true only on the frame a flash actually
        // rendered its peak (either trigger mechanism below), not on detection's isBeat frame.
        var flashFiredThisFrame = false

        // --- P1 predictive flash scheduling / fast causal trigger orchestration ---
        // Mechanism selection crossfades by bpmConfidence: once tempo-locked, schedule the flash
        // ahead of detection off the DP beat grid (mechanism 1); before/without a lock, fall back
        // to a cheap causal onset check reacting ~1 frame after the actual transient (mechanism
        // 2). This is new orchestration consuming BeatDetector's existing outputs
        // (nextPredictedBeatMs, causalFlux/causalIsCandidate) -- no detection/tuning math changed.
        val predictiveWeight = result.bpmConfidence.coerceIn(0f, 1f)

        // Mechanism 1: re-derive the schedule only when the grid has actually advanced to a new
        // upcoming beat, not every frame (nextPredictedBeatMs is stable between grid rebuilds).
        val predictedBeatMs = result.nextPredictedBeatMs
        if (predictedBeatMs != null && predictedBeatMs != scheduledForBeatMs) {
            scheduledForBeatMs = predictedBeatMs
            scheduledFlashAtMs = predictedBeatMs - state.flashTimingOffsetMs
            scheduledFlashFired = false
            scheduledFlashSettled = false
        } else if (predictedBeatMs == null && scheduledForBeatMs != 0L) {
            // Lost tempo lock entirely -- drop the stale schedule so mechanism 2 takes over
            // cleanly instead of firing a flash for a beat the grid no longer believes in.
            scheduledForBeatMs = 0L
            scheduledFlashAtMs = 0L
            scheduledFlashFired = false
            scheduledFlashSettled = true
        }

        // visualizer-review-2026-07-22.md C5: for ~2s after a macro-dynamics drop fires, both
        // trigger mechanisms get a temporarily widened dynamic range -- part of the drop "moment"
        // alongside the guaranteed anchor jump and white blast above.
        val dropBoostedFlashRange = if (nowMs < dropFlashRangeBoostUntilMs) state.flashRange * 1.5f else state.flashRange

        // visualizer-review-2026-07-22.md B1: bound how late a scheduled flash is allowed to fire.
        // Without this, if predictiveWeight only crosses 0.15 well after scheduledFlashAtMs (grid
        // jitter, a confidence dip that recovers late), the flash fires immediately at that later
        // moment instead of being skipped -- unbounded lateness, not just unbounded earliness.
        val flashIsStale = scheduledFlashAtMs != 0L && (nowMs - scheduledFlashAtMs) > 80L
        if (scheduledFlashAtMs != 0L && !scheduledFlashFired && nowMs >= scheduledFlashAtMs && !flashIsStale && predictiveWeight > 0.15f) {
            // Peak uses the *carried* strength/confidence from the most recent detection, not
            // this frame's `result` -- detection for this exact beat hasn't arrived yet (that's
            // the whole point of scheduling ahead of it). visualizer-review-2026-07-22.md A3: only
            // the variable flashRange portion is scaled by predictiveWeight -- the flashFloor
            // guarantee stays whole once a flash decides to fire at all, so a mid-confidence song
            // (bpmConfidence ~0.5, or the first several seconds of any song before lock) doesn't
            // get a systematically halved flash. Previously the whole sum was scaled, silently
            // breaking the item-16 "every detected beat gets a minimum visible pulse" guarantee.
            val peak = state.flashFloor + (dropBoostedFlashRange * carriedFlashStrength * carriedFlashConfidence) * predictiveWeight
            if (triggerFlash(nowMs, peak, effectiveBeatFlashDecayMs)) {
                scheduledFlashFired = true
                flashFiredThisFrame = true
                scheduledFlashConfirmDeadlineMs = nowMs + 150L
                if (useWhiteFlash) {
                    val depth = (carriedFlashStrength * carriedFlashConfidence).coerceIn(0f, 1f)
                    whiteHotFlashOffset = (1f - depth).coerceIn(0f, 1f)
                }
                DiagnosticLogger.log("FlashSchedule", "fired mechanism=predictive backend=${backend.name} atMs=$nowMs targetMs=$scheduledFlashAtMs peak=$peak weight=$predictiveWeight")
            }
        }

        // Confirmation/correction: a real detected beat arriving while a scheduled flash awaits
        // confirmation validates the prediction and reseeds the *next* scheduled flash's
        // intensity. It deliberately does NOT retrigger or resize the flash already playing --
        // that would just be the old reactive behavior wearing a disguise.
        if (isBeat) {
            if (scheduledFlashFired && !scheduledFlashSettled && nowMs <= scheduledFlashConfirmDeadlineMs) {
                scheduledFlashSettled = true
            }
            carriedFlashStrength = result.strength
            carriedFlashConfidence = result.confidence.coerceIn(0f, 1f)
        }

        // Bad-prediction correction: nothing confirmed the fired flash within its window --
        // accelerate its decay instead of letting it linger at the preset's full tuned rate for a
        // beat that, as far as detection can tell, never actually happened.
        if (scheduledFlashFired && !scheduledFlashSettled && nowMs > scheduledFlashConfirmDeadlineMs) {
            scheduledFlashSettled = true
            val elapsedSinceFired = (nowMs - lastBeatFlashTime).toFloat()
            activeFlashDecayMs = minOf(activeFlashDecayMs, elapsedSinceFired + 60f)
            DiagnosticLogger.log("FlashSchedule", "unconfirmed mechanism=predictive backend=${backend.name} atMs=$nowMs acceleratedDecayMs=$activeFlashDecayMs")
        }

        // Mechanism 2: fast causal trigger, used when not (yet) tempo-locked. A false positive
        // here is cheap -- it just self-erases via the normal decay envelope -- unlike a wrong
        // anchor move, so this stays gated purely on the causal onset check, its own cooldown,
        // and (1 - predictiveWeight); it never looks at isBeat/the centered detector at all.
        //
        // Bug fix (2026-07-22): causalIsCandidate is purely a *relative* statistical check (this
        // frame's flux vs. its own recent median/MAD) -- it has no absolute floor on whether
        // there's any real signal to react to at all. The centered detector's own `isBeat` above
        // is never trusted without also clearing `totalEnergy >= state.noiseGateThreshold`; this
        // path was missing that same gate entirely. That's exactly what let it fire indiscriminately
        // during genuine silence (not just a quiet passage): with nothing to lock onto,
        // predictiveWeight is 0 so fastWeight is 1.0 (fully undamped, the *most* trusting this
        // mechanism ever is), and ordinary statistical noise in near-zero flux readings routinely
        // crosses its own adaptive threshold by chance. Requiring real absolute energy first closes
        // that hole without touching the relative onset math itself.
        if (result.causalIsCandidate && totalEnergy >= state.noiseGateThreshold && nowMs - lastFastTriggerFlashAtMs > 150L) {
            val fastWeight = 1f - predictiveWeight
            if (fastWeight > 0.05f) {
                // Reduced strength, per the plan -- a fixed mid-range peak (not tied to a
                // detection's strength/confidence, since mechanism 2 fires ahead of/instead of
                // the centered detector) scaled down further by how little we trust "not locked."
                // visualizer-review-2026-07-22.md A3: same floor-preservation fix as mechanism 1 --
                // only the variable flashRange·0.5 portion is damped by fastWeight, not the floor.
                val peak = state.flashFloor + (dropBoostedFlashRange * 0.5f) * fastWeight
                if (triggerFlash(nowMs, peak, effectiveBeatFlashDecayMs)) {
                    lastFastTriggerFlashAtMs = nowMs
                    flashFiredThisFrame = true
                    if (useWhiteFlash) {
                        // No detection-confirmed strength/confidence exists yet for this trigger
                        // (mechanism 2 fires ahead of/instead of the centered detector) -- reuse
                        // the same carried values mechanism 1 uses, damped by fastWeight so an
                        // unlocked, low-trust trigger doesn't pop to full white.
                        val depth = (carriedFlashStrength * carriedFlashConfidence * fastWeight).coerceIn(0f, 1f)
                        whiteHotFlashOffset = (1f - depth).coerceIn(0f, 1f)
                    }
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
            // Aggressive dual-rate auto-gain: rapid decay in low-amplitude states to boost quiet
            // sections instantly. visualizer-review-2026-07-22.md C8/B4: the original per-frame
            // multiplicative constants (0.97f/0.992f) were tuned against phone_mic's ~43Hz cadence
            // (~23ms/frame) -- applied unchanged on Visualizer's ~20Hz cadence (~50ms/frame), the
            // SAME nominal factor decays roughly twice as much real time per frame, so auto-gain
            // adapts to loudness changes about half as fast on that backend. Converted to a
            // continuous exponential decay (exp(-dtMs/tau)) using dtMs (already available since
            // stage 1) with tau chosen so this reduces to the original per-frame factor at
            // phone_mic's reference ~23ms interval -- i.e. behavior is unchanged on phone_mic,
            // fixed on Visualizer. dtMs=0 on the first frame yields a decay factor of 1.0 (no
            // decay), matching this class's existing "0 on first frame" convention elsewhere.
            val dtF = dtMs.toFloat()
            val decayFactorBass = if (bassVal < avgBass) {
                kotlin.math.exp(-dtF / AUTO_GAIN_FAST_TAU_MS)
            } else {
                kotlin.math.exp(-dtF / AUTO_GAIN_SLOW_TAU_MS)
            }
            maxObservedBass = maxOf(maxObservedBass * decayFactorBass, bassVal, 10.0f)

            val decayFactorMid = if (smoothedMid < maxObservedMid * 0.5f) {
                kotlin.math.exp(-dtF / AUTO_GAIN_FAST_TAU_MS)
            } else {
                kotlin.math.exp(-dtF / AUTO_GAIN_SLOW_TAU_MS)
            }
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
        // visualizer-review-2026-07-22.md C7/P5: saturation gets a third moving dimension from
        // spectral crest (peak/mean magnitude ratio) instead of being driven only by mid-band
        // energy -- a tonal/peaky passage (high crest) reads more saturated than a noisy/broadband
        // one at the same loudness. Uniform across all presets (not a per-preset knob), same
        // rationale as stage 4's pre-dip: bounded (0-0.15) and additive so it nudges baseSat's
        // existing backend-branched dynamic range rather than fighting it. crestRatio is exactly
        // 1.0 (no boost) for a perfectly flat/uniform spectrum.
        val crestBoost = ((smoothedCrest - 1f).coerceIn(0f, 4f) / 4f) * 0.15f
        var sat = ((baseSat + crestBoost) * whiteHotFlashOffset).coerceIn(0.0f, 1.0f)
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

        // visualizer-review-2026-07-22.md C5: modest brightness boost/cut (0.85x-1.15x) tracking
        // macroPercentile, so a chorus reads visibly brighter than a verse at the same
        // beat-to-beat dynamics. Deliberately does not touch the beat-flash envelope itself
        // (beatEnvelope/beatPulsePeak) -- that already has its own P1/P2/A3/B1 timing/intensity
        // treatment; this only shapes the ongoing ambient/ (for Beat Only) flash-magnitude level.
        // 0.85/0.30 are judgment calls, not measured -- chosen to be a real, felt difference
        // without blowing out the preset's own tuned dynamic range.
        val macroBoost = 0.85f + 0.30f * macroPercentile

        var value: Float
        var ambientValue: Float
        if (state.visualizerPreset == "Beat Only") {
            val elapsedMs = nowMs - lastBeatFlashTime
            val t = elapsedMs.toFloat() / activeFlashDecayMs
            val beatEnvelope = maxOf(1f - t * t, 0f) * beatPulsePeak
            value = maxOf(state.visualizerMinBrightness, (beatEnvelope * maxOf(0.5f, state.audioFlashStrength) * macroBoost).coerceIn(0f, 1f))
            // Beat Only has no ambient term at all — its floor is the closest thing to "no flash."
            ambientValue = state.visualizerMinBrightness
        } else {
            var baseValVal = (valBase * sensitivityMultiplier).coerceIn(0.0f, 1.0f)
            baseValVal = Math.pow(baseValVal.toDouble(), state.audioGammaExponent.toDouble()).toFloat().coerceIn(0.0f, 1.0f)
            // Anticipatory dimming (mapping-proposal-audio-to-led-2026-07-21.md §4, stage 4 —
            // opt-in, purely additive). Pre-dips the ambient contribution up to 10% over the
            // ~80ms leading into the flash's own scheduled render time, so the flash reads as a
            // bigger jump for free. No effect without a tempo lock (nothing scheduled), outside
            // the 80ms window, or on presets with audioFlashStrength == 0 (Ambient Chill/Smooth
            // Flow both zero `beatFlash` at its use site below regardless of flashFloor/
            // flashRange — audioFlashStrength, not flashRange, is the actual "does this preset
            // flash at all" signal).
            // Now targets scheduledFlashAtMs directly (P1) instead of re-deriving a separate ETA
            // from nextPredictedBeatMs + lookaheadMs -- the dip and the flash trigger share
            // exactly the same schedule by construction, so this falls out naturally rather than
            // needing its own logic (per the P1 plan's explicit ask to just confirm that).
            val msUntilPredictedBeat = if (scheduledFlashAtMs != 0L && !scheduledFlashFired) {
                scheduledFlashAtMs - nowMs
            } else {
                null
            }
            val preDip = if (state.audioFlashStrength > 0f && msUntilPredictedBeat != null && msUntilPredictedBeat in 0..80L) {
                0.10f * (1f - msUntilPredictedBeat / 80f)
            } else {
                0f
            }
            val ambientLevel = baseValVal * state.ambientCapFraction * (1f - preDip) * macroBoost
            val elapsedMs = nowMs - lastBeatFlashTime
            val t = elapsedMs.toFloat() / activeFlashDecayMs
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

            // visualizer-review-2026-07-22.md C8/B4: same fixed-per-frame-factor-to-tau conversion
            // as the auto-gain trackers above.
            val uiDecayFactor = kotlin.math.exp(-dtMs.toFloat() / UI_AMPLITUDE_DECAY_TAU_MS)
            maxUiObserved *= uiDecayFactor
            var currentGlobalMax = 0.0f
            for (band in 0 until 8) {
                maxPerBandObserved[band] *= uiDecayFactor
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
            midHighLevel = midContribution,
            flashFiredThisFrame = flashFiredThisFrame
        )
    }

    // Retriggers the flash envelope only if `peak` is at least as strong as whatever's already
    // decaying -- lets mechanism 1 (predictive) and mechanism 2 (fast causal) coexist without one
    // silently clobbering a bigger, still-visible flash the other just started. Returns whether
    // it actually fired, so callers can gate their own bookkeeping (cooldowns, confirm deadlines)
    // on a real trigger rather than a suppressed one.
    private fun triggerFlash(atMs: Long, peak: Float, decayMs: Float): Boolean {
        val elapsed = (atMs - lastBeatFlashTime).toFloat()
        val t = if (activeFlashDecayMs > 0f) elapsed / activeFlashDecayMs else 1f
        val currentEnvelope = kotlin.math.max(1f - t * t, 0f) * beatPulsePeak
        if (peak < currentEnvelope) return false
        lastBeatFlashTime = atMs
        beatPulsePeak = peak.coerceIn(0f, 1f)
        activeFlashDecayMs = decayMs
        return true
    }
}
