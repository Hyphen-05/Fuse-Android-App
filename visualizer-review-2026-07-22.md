# Audio Visualizer Feature Review — 2026-07-22

Scope: the full audio visualizer pipeline as it exists on `main` today — capture
(`AudioRecordCaptureSource`, `VisualizerCaptureSource`), detection (`BeatDetector`), mapping
(`AudioDspProcessor`), delivery (`RgbControllerViewModel` publish path, `DeviceWriteManager`),
and the per-device role transforms. Everything below was verified by reading current source in
this session; nothing is relayed from CLAUDE.md or prior docs without re-checking. **Verification
level: source-read only — none of these findings have been confirmed or disproven on a device.**

This builds on `visualizer-review-2026-07-21.md` (P0–P5). P0/P2/P4 and P1 have since landed;
this review focuses on (a) new bugs introduced or exposed by those merges, (b) pre-existing
issues still open, and (c) what to do next for maximum end-user impact.

---

## A. Bugs (ranked by user-visible impact)

### A1. The P2 pacing bypass never fires for predictive flashes — the main flash path lost its wire priority

`broadcastAudioResultDirect` (`RgbControllerViewModel.kt:1851`) sets
`bypassPacing = result.isBeat`. But since P1, the flash brightness peak no longer occurs on the
`isBeat` frame — it occurs on the frame the *scheduler* fires (`triggerFlash` in
`AudioDspProcessor.process()`), which is ~180 ms + `flashTimingOffsetMs` *before* detection's
`isBeat` frame. So on a tempo-locked song (the normal case), the frame carrying the actual flash
peak goes through the ordinary pacing wait, and the bypass instead fires ~280 ms later on a frame
whose envelope has already mostly decayed. P2's entire purpose — "the flash-peak frame skips the
pacing gap" — is defeated for exactly the flashes P1 made primary. The peak-hold `priority =
result.value` still helps (the peak survives in-queue), but the timing benefit is gone.

**Fix:** add a `flashFiredThisFrame: Boolean` to `AudioDspResult`, set it in `triggerFlash()`
(both mechanisms), and key `bypassPacing` (and the P0 `"AudioSync"` beat-peak log in
`publishAudioDspResult`, which has the same stale keying) on that instead of `isBeat`.

### A2. AlternatingFlash advances its round-robin ~180+ ms after the visible flash

Same root cause as A1: `alternatingFlashBeatCounter` increments on `result.isBeat`
(`RgbControllerViewModel.kt:1813`), but the flash envelope in `result.value` starts on the
scheduled frame. Sequence on a locked song: scheduled flash fires → device A (current target)
renders the rising/peak envelope → ~180–280 ms later detection confirms, the counter increments →
mid-decay, the flash target switches to device B, which suddenly renders the *tail* of an envelope
it never showed the peak of. The intended clean call-and-response becomes a smeared handoff on
every beat. **Fix:** increment the counter on the same `flashFiredThisFrame` signal as A1.

### A3. Mid-confidence songs get systematically weak flashes — the guaranteed 0.6 floor is gone

Both trigger mechanisms scale the *entire* peak, floor included, by their trust weight:

- Mechanism 1: `peak = (flashFloor + flashRange·s·c) · predictiveWeight` (`AudioDspProcessor.kt:438`)
- Mechanism 2: `peak = (flashFloor + flashRange·0.5) · fastWeight` where `fastWeight = 1 − predictiveWeight` (`:489`)

At `bpmConfidence ≈ 0.5` — common for real music with loose drumming, and the entire first ~5–10 s
of every song — each mechanism fires at roughly half strength, so the strongest flash on screen is
~0.4–0.5 where the item-16 design guaranteed every detected beat ≥ 0.6. The old "every beat gets a
minimum visible pulse" invariant silently no longer holds anywhere except at confidence extremes.
This directly produces "flashes feel inconsistent/timid" on ordinary material.

**Fix:** scale only the variable portion by trust, keep the floor whole once *either* mechanism
decides to fire at all: `peak = flashFloor + (flashRange·s·c)·weight`. The `triggerFlash`
max-envelope rule already prevents double-firing from stacking.

### A4. On white-flash presets, the desaturation pop is desynchronized from the brightness pop

`whiteHotFlashOffset` (the flash-to-white mechanism for Strobe Blast / Beat Only / Laser Sharp) is
still set inside the `isBeat` block (`AudioDspProcessor.kt:383-391`) — i.e. on detection, ~180+ ms
after the predictive scheduler already fired the brightness flash. The user sees: brightness pop
(colored) → ~200 ms later → white wash while brightness is already decaying. The two components of
what should read as one "white flash" now happen at visibly different times. **Fix:** move the
white-flash depth write into the scheduled-flash path, using `carriedFlashStrength/Confidence`
(the same values the peak already uses); let detection correct depth for the *next* flash, same
pattern as intensity.

### A5. Sustain-response intensity jumps discontinuously on a mid-ramp state flip

`sustainIntensity = if (sustainActive) rampProgress else (1 − rampProgress)` with `rampProgress`
computed from `sustainStateChangedAtMs` (`AudioDspProcessor.kt:317-322`). If the sustain state
flips while a ramp is in progress — e.g. deactivation at 40% ramp-up — the timer resets and the
formula flips branches: intensity jumps instantaneously from 0.4 to 1.0, then ramps down. On
Ambient Chill that's a visible brightness *step up* at the exact moment the music got quieter.
**Fix:** ramp from the current intensity value toward the new target (store `sustainIntensity` as
a field and move it toward 0/1 by `dtMs / sustainRampMs` per frame) instead of deriving it from
the state-change timestamp.

### A6. `VisualizerCaptureSource`'s shared-session branch can silently deliver a dead pipeline

When another client already has `Visualizer(0)` enabled, the new `alreadyEnabledElsewhere` branch
(`VisualizerCaptureSource.kt:192-199`) correctly skips `captureSize=`, inheriting whatever the
other client configured. But if that inherited capture size gives `numBins < 349`, every frame is
dropped at the callback's own guard (`:282`) — while the watchdog's `receivedRealSignal` check
still passes (it looks at raw bytes, before the size guard). Result: capture "running", real
signal flowing, zero frames ever reach the DSP, no watchdog, no fallback — permanently dark
visualizer that reports success. **Fix:** in the `alreadyEnabledElsewhere` branch, check the
inherited `vis.captureSize` up front; if `< 698`, treat as a failed attach (retry/fall back)
rather than proceeding.

### A7. Non-Mirror device roles render black under the simulation fallback

`DemoAudioDspSimulator` constructs `AudioDspResult` with the defaulted P4 fields (`sat = 0f`,
`value = 0f` — CLAUDE.md item 23 records this as an accepted gap). But the gap is worse than "no
role transform": `broadcastAudioResultDirect` computes HueOffset/AlternatingFlash/BandSplit colors
via `hsvToRgb(…, result.sat, result.value)` → `(0,0,0)`. So the moment real capture fails and the
demo engine takes over, any device with a non-Mirror role goes completely dark while Mirror
devices keep animating — which will read as a broken lamp, not a degraded mode. **Fix (cheap):**
in `broadcastAudioResultDirect`, fall back to Mirror behavior when `result.value == 0f && result.sat == 0f`
(or add an `hasHsvComponents` flag), or populate the fields in the simulator.

### A8. Known open: the DP beat grid runs ~210–220 ms early (item 24 — still unfixed)

Reconfirmed status, not a new finding: `nextPredictedBeatMs` extrapolates `gridBeats`, and field
captures measured the grid consistently ~210–220 ms ahead of true onsets. With
`flashTimingOffsetMs` defaulting to 100, predictive flashes currently land ~300 ms early
*by design intent minus bias* — and the slider can only make it earlier, never later. The
`"BeatGridPhase"` diagnostic log is already in place; the root cause (suspects: `transitionPenalty
= 100f` letting the DP wander inside its ±50% search window; onset smearing on real music) is
unidentified. **This is the single most important open item** — every P1 gain is offset by it.

---

## B. Issues (real, lower severity)

- **B1. A scheduled flash can fire up to `flashTimingOffsetMs` late.** The fire condition
  `nowMs >= scheduledFlashAtMs && predictiveWeight > 0.15` has no upper lateness bound; if
  confidence crosses 0.15 after the target time, the flash fires immediately at that moment
  (bounded only by the grid advancing). Add a staleness cutoff (e.g. skip if
  `nowMs − scheduledFlashAtMs > 80`).
- **B2. Frame-order under `totalVisualDelayMs` is not guaranteed.** `queueAudioResult` launches
  one delayed coroutine per frame (~43/sec on phone_mic); dispatcher scheduling jitter can reorder
  two frames' broadcasts. Rare, shows as a 1-frame color flicker. A single delayed channel/queue
  would make ordering structural.
- **B3. Two user-facing timing knobs now fight each other.** `bluetoothDelayMs` (push later,
  tap-calibrated) and `flashTimingOffsetMs` (pull earlier, 0–300 ms) both exist, interact with the
  A8 bias, and are calibrated by different flows. After A8 is fixed, these should collapse into
  one measured end-to-end latency value (see C1).
- **B4. Backend feel divergence from sample-count windows (long-deferred, now more visible).**
  `energyWindowSize = 250` is ~6 s on phone_mic but ~12.5 s on on_device; `shortWindowSize = 43`
  is ~1 s vs ~2 s; `noiseHistory`/`bassHistory` (86) likewise. Sustain enter/exit and auto-gain
  therefore respond ~2× slower on on_device. Also auto-gain's per-frame decay constants
  (0.97/0.992/0.98) have the same Hz dependence. With `dtMs` now available in `process()`, the
  conversion is mechanical. Not urgent individually, but it means every tuning judgment made on
  one backend is ~2× off on the other — it taxes all future tuning work.
- **B5. Per-frame allocation/sort churn in the hot path.** `BeatDetector.process()` does ~4
  `filter` + list `map` + sorted-median passes per frame, and `AudioDspProcessor` sorts an
  86-element copy per frame for the noise floor. Fine on modern hardware, but this runs 43×/sec on
  a `MAX_PRIORITY−1` thread; worth a cheap pass (reusable buffers, partial selection) only if
  profiling ever shows pressure — noted so nobody adds more per-frame allocation casually.
- **B6. `AudioRecord` shutdown latency (documented, still real).** `stop()` can't interrupt a
  blocking `read()`; worst case one extra ~23 ms buffer. Harmless today; would matter if backend
  switching ever becomes rapid.

---

## C. How to make the end result more satisfying — recommended order

The 2026-07-21 review's core diagnosis stands: **timing precision is the product.** The mapping
layer is good; what separates "vaguely music-reactive" from "locked-in" is whether the flash lands
on the beat, every beat, at full computed strength. Everything below is ordered by expected
perceptual payoff per unit of work.

1. **Fix A8 (grid early-bias) first — it gates everything.** Run one capture with the existing
   `BeatGridPhase` log against both a metronome and 2–3 real songs. If `signedDeviationMs` is
   consistently negative by ~200 ms on real music but near zero on the metronome, the cause is
   onset smearing / DP flexing — try raising `transitionPenalty` (100 → 300–500) and re-capture;
   if the metronome also shows it, the bug is in grid construction/extrapolation bookkeeping.
   Until this is fixed, no amount of slider tuning can land flashes on the beat.

2. **The A1/A2/A4 cluster (re-key everything to the flash-fire frame).** One small, coherent
   change — `flashFiredThisFrame` on `AudioDspResult`, consumed by `bypassPacing`, the
   AlternatingFlash counter, the P0 log, and the white-flash depth write. This restores P2's
   intent, fixes the two-device handoff, and reunifies the white flash. All plumbing, no new
   tuning judgment, unit-testable in `AudioDspProcessorTest`.

3. **A3 (stop scaling the flash floor by trust).** One-line formula change per mechanism plus a
   test. This is likely the biggest pure "feel" win after timing: consistent minimum flash
   strength across the whole confidence range is what makes a visualizer feel *confident*.

4. **One end-to-end latency calibration to replace the knob pair (B3).** After #1: a guided
   flow that plays the metronome through the flash pipeline itself (predictive path, real BLE
   writes) and lets the user tap when the *light* blinks vs the click — measuring the one number
   that matters (audio-onset → photon) and storing it as the single offset the scheduler consumes.
   The 240 fps slo-mo ground-truth check from the old P0 plan is still worth doing once, to
   validate the calibration flow itself.

5. **P3 macro-dynamics (still the biggest unbuilt satisfaction feature).** Unchanged from the
   prior review and still not started: a 30–60 s loudness-percentile tracker outside the auto-gain
   loop so choruses visibly bloom over verses, plus a rare-fire drop detector (white blast +
   guaranteed anchor jump + ~2 s boosted flashRange, ≥30 s refractory). The existing 6 s
   energy/variance windows provide most ingredients. This is what makes the moments people
   actually remember.

6. **A5, A6, A7 (polish/robustness batch).** Sustain ramp continuity, the shared-session
   captureSize guard, and the demo-role black fix. Each is small and independent; none needs
   hardware to implement, though A6 wants an on-device check.

7. **P5 saturation (last, as before).** Key `sat` to spectral crest or `breath` magnitude so the
   third output dimension moves. Deliberately after the timing work — a third moving dimension
   amplifies whatever timing quality exists.

8. **B4 time-basing (background hygiene, fold into any future DSP touch).** Convert the
   sample-count windows to `dtMs`-based durations so both backends share one feel and one tuning.

**Standing caveat:** items 1–4 all touch the live flash/write path — each slice should gate on the
usual on-device smoke test (both backends, at least one percussive and one ambient preset, and a
two-device AlternatingFlash check for #2), per the project's standing convention.

---

## D. What is explicitly fine (don't spend effort here)

- **BeatDetector detection quality** — the item-16/17/24 fixes (soft-saturating strength,
  madReference noise anchoring, 40 bpm floor, stale-lock release, causal-path energy gate) read as
  coherent and correct in source; nothing further until A8's data says otherwise.
- **The anchor+breath hue model** — structurally sound; further preset-table tuning stays capped
  until timing lands.
- **Capture-layer resilience** — the exception-retry + real-signal watchdog + shared-session
  handling in `VisualizerCaptureSource` is now genuinely robust (modulo A6).
- **Preset count/variety, and keeping the 11 stage-2 fields preset-only** (no new per-knob UI).
