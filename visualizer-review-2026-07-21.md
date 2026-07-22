# Visualizer Satisfaction Review — Fresh-Eyes Pass (2026-07-21)

Scope: the whole visualizer system — BeatDetector (DSP), AudioDspProcessor (mapping), the 8
presets, and the delivery path to hardware. Prompted by the post-merge impression that the
mapping-layer rewrite (CLAUDE.md items 19–22) made some presets better but the overall experience
is not dramatically more satisfying. Everything below was read from current source on
`mapping-stage1-time-basing-flash-params`/`main`, with file:line references; nothing is relayed
from prior docs without re-checking.

**Headline: the mapping layer is no longer the bottleneck. The delivery/timing layer is.**
The anchor+breath rewrite fixed real structural problems, but the color math's output is then
(a) delivered ~230–360 ms after the audible beat, and (b) subsampled by the BLE write pacing in a
way that randomly clips or drops the flash peaks the mapping layer so carefully computes. No
amount of further hue/preset tuning can compensate for either. That is a sufficient explanation
for "better, but not dramatically more satisfying."

---

## 1. Diagnosis: why it doesn't feel locked-in

### 1a. Every flash is structurally late — by roughly half a beat at dance tempos

The chain, each stage verified in source:

| Stage | Latency | Where |
|---|---|---|
| Capture + FFT buffer | ~23 ms (phone mic) / ~30–50 ms (Visualizer) | `AudioRecordCaptureSource` 1024@44.1k; Visualizer ~20 Hz callback |
| **BeatDetector centered-window lookahead** | **180 ms, by design** | `BeatDetector.kt:11` (`lookaheadMs=180`), `:420` (`evalTimestamp = now - lookaheadMs`) |
| Write-queue pacing quantization | 0–50 ms jitter (default pacing) | `DeviceWriteManager.tryWrite()`, pacing default 50 ms (`RgbControllerViewModel.kt:813`) |
| BLE stack + firmware render | ~30–100 ms (unmeasured) | — |

Total: **~230–360 ms after the physical onset**. At 120–130 BPM (period 460–500 ms) the flash
lands at 50–75% of the beat period — i.e. nearer the *off*-beat than the beat. Humans reliably
perceive audio-visual asynchrony above ~100 ms; at ~half a period the flash doesn't read as
"on the beat" at all, it reads as "vaguely music-reactive." This is the single biggest gap
between the current system and a satisfying one.

The tap-to-sync calibration can't fix this: `totalVisualDelayMs` (item 17) is delay-*only* — it
can push the flash a full period later to land on beat N+1 at one specific tempo, but it cannot
pull it earlier, and it goes stale the moment the tempo changes.

Note the irony: the 180 ms lookahead exists to make detection *robust* (centered median/MAD
evaluation, `BeatDetector.kt:433–439` comments) — and the robustness is genuinely good per the
on-device diagnostic data. But robustness and immediacy were fused into one path, so the flash —
which needs immediacy and tolerates false positives cheaply — pays the full robustness latency
that only the hue/anchor decisions (expensive, sticky, confidence-gated) actually need.

### 1b. The write pacing subsamples the flash envelope — peaks are lost at random

- The DSP produces a color per frame at ~20–43 Hz; `publishAudioDspResult` queues **every** frame
  (`RgbControllerViewModel.kt:2227–2244`).
- `DeviceWriteManager.updateCommand` (`DeviceWriteManager.kt:52–89`) dedupes by command type:
  queued music-color commands are **replaced by the latest**, and `tryWrite` only writes every
  `currentPacingMs` (default 50 ms, auto-tune may set higher).
- So the wire sees the envelope sampled at ≤20 fps, **latest-wins, not peak-wins**. Whether a
  beat's peak frame ever gets written depends on the phase alignment between the beat and the
  pacing timer.

Now compare against the flash envelopes: Laser Sharp `beatFlashDecayMs = 70`, Strobe Blast /
Beat Only `= 90`, Punchy `= 140`. At 50 ms pacing that is **1–3 wire frames per flash**, and at
a slower auto-tuned pacing (100 ms appears as the default elsewhere: `RgbControllerViewModel.kt:1198`)
Laser Sharp's entire envelope can fall *between* two writes. Consequences:

1. The quadratic decay shape, the item-16 confidence-scaled peak, and the stage-1
   `flashFloor`/`flashRange` work are all computed at a resolution the wire cannot express.
   Perceived flash intensity varies randomly beat-to-beat even when the computed peak is
   identical — which reads as sloppiness/inconsistency, the opposite of "punchy."
2. The three most percussive presets (exactly the ones whose identity is the flash) are hurt the
   most. This is likely a big part of why the rewrite didn't feel transformative: its gains are
   concentrated in the channel the transport degrades most.

### 1c. The stage-4 pre-dip is aimed at the wrong timestamp (structurally, not a bug in the gate)

`nextPredictedBeatMs` extrapolates the DP grid, whose timestamps live on the *capture* timeline —
i.e. the predicted time of the **audible onset**. The pre-dip (`AudioDspProcessor.kt:484–490`)
ramps into that time and releases. But the flash for that same beat doesn't start until detection
fires at onset + 180 ms (`lookaheadMs`). So in the command stream the sequence is: dip → recover →
~180 ms of normal ambient → flash. The dip-then-pop contrast the proposal intended never actually
happens; at best the dip is invisible, at worst it reads as a stumble before the beat. (Separate
issue from the item-22 `audioFlashStrength` gate fix, which was about *which presets* dip.)

### 1d. Secondary observations (real, but smaller)

- **Saturation is a nearly dead channel outside white-flash presets.** `baseSat`
  (`AudioDspProcessor.kt:419–431`) is a slow mid-energy follower floored at 0.4/0.5 with auto-gain
  pulling it toward 1.0 — in practice it hovers high and nearly static. One of three output
  dimensions is idling.
- **No representation of song structure.** Every signal lives on a 20 ms–6 s timescale
  (`energyWindowSize` ≈ 6 s is the longest). Verse/chorus/build/drop — the events listeners
  actually anticipate — produce no macro response. The sustain mechanism (1 s mean + variance) is
  the closest thing and it's a local measure. Auto-gain actively *fights* structure: it
  renormalizes loudness within ~seconds, so a chorus ends up about as bright as a verse.
- **All devices mirror.** `broadcastCommandDirect` (`RgbControllerViewModel.kt:1791–1808`) sends
  identical bytes to every controlled device. With ≥2 devices (the firework-splash experiment is
  two-device) there is a whole spatial dimension available at zero hardware cost, currently unused.
- **Auto-gain decay constants are per-frame** (`0.97f`/`0.992f`/`0.98f`,
  `AudioDspProcessor.kt:406–416, 539–546`) — same backend-Hz dependence class that stage 1 fixed
  for white-flash recovery; still unfixed here (adjacent to the deferred DSP-window issue but
  mapping-layer-owned).
- **Hardware ceiling, stated honestly:** the app-driven channel is one RGB color, whole-strip,
  ≤20 fps. Per-LED spatial motion is only available via the onboard firmware music modes
  (`energetic_*`/`rhythm_*` etc.), which take over entirely when active. Within the app-driven
  path, the designable space is exactly {hue, sat, value} × time × device — which is why timing
  precision (1a/1b) and the device dimension are where the remaining headroom is.

### What is *not* the problem

- **BeatDetector quality.** The diagnostic captures showed real confidence spread and sane BPM
  locking; the item-16/17 fixes stand. Detection is good — its *latency allocation* is the issue.
- **The anchor+breath model.** Structurally sound, right abstraction, right per-preset knobs.
  Further hue-model tuning is deep in diminishing returns until delivery is fixed.
- **Preset count/variety.** Eight presets with genuinely different personalities is fine.
- **The deferred DSP sample-count windows.** Real but second-order versus the above.

---

## 2. Plan, ranked by expected perceptual impact

### Priority 0 — Measure before building (cheap, do first)

The latency ladder in §1a has two unmeasured rungs (BLE stack + firmware render). Before
investing in the fixes below, put numbers on the whole chain:

- Log, per beat: detection timestamp, the enqueue timestamp of the corresponding peak frame, and
  `writeCharacteristic`-initiated timestamp (all three already have `DiagnosticLogger` hooks or
  trivial additions; `deviceAchievedFps` telemetry already exists for the wire rate).
- One manual observation: film phone + LEDs at 240 fps slo-mo alongside a metronome track to get
  ground-truth beat→light latency. One song, two backends, ~10 minutes of effort.
- Also capture what pacing auto-tune actually settled on for Joe's devices — if they're running
  at 100 ms+, a re-tune (or firmware-tolerance test at 25–30 ms) is a free multiplier on
  everything else.

### Priority 1 — Make flashes land on the beat (the big one)

Split the flash trigger from the robust detector. Two complementary mechanisms, crossfaded by
`bpmConfidence` (both consume signals that already exist):

1. **Predictive scheduling when tempo-locked.** `nextPredictedBeatMs` already exists (stage 4).
   When `bpmConfidence` is high, *schedule* the flash to render at
   `nextPredictedBeatMs − transportLatencyMs` (transport latency from the P0 measurement, or a
   new tap-calibration that can now genuinely cancel latency instead of only adding delay).
   Detection then plays confirmation/correction: a predicted flash that no detected beat confirms
   within ~150 ms accelerates its decay; strength/confidence from the (late) detection can shape
   the *next* scheduled flash. This is the mechanism that makes commercial visualizers feel
   locked-in, and it's the payoff the DP grid + BPM machinery has been building toward while
   feeding nothing but a 10% dip.
2. **Fast causal trigger when not locked.** A cheap causal onset check — current frame flux vs. a
   trailing (not centered) median/MAD threshold, reusing the flux value BeatDetector already
   computes — fires a provisional flash at ~1-frame latency, at reduced strength (it lacks the
   centered window's certainty; a false-positive small flash is cheap and self-erasing). The
   existing 180 ms centered detector remains the *sole* authority for anchor moves, confidence,
   tempo, and white-flash depth — hue stays exactly as robust as today.

Also in this slice: **re-target the pre-dip** at predicted *render* time rather than onset time.
Under predictive scheduling this falls out automatically (dip and flash share the schedule);
without it, the interim fix is `nextPredictedBeatMs + lookaheadMs`.

Expected effect: flash latency drops from ~230–360 ms to ~transport-only (~50–100 ms), tempo-locked
songs feel synchronized rather than reactive. This is a BeatDetector-surface + orchestrator change
(new consumer of the grid; a scheduling loop in the ViewModel or processor), not a detection-math
change.

### Priority 2 — Make the flash peak survive the wire

Whatever P1 decides about timing, the peak must actually get written:

- **Peak-priority write:** on the frame a flash fires (or a scheduled flash renders), bypass the
  pacing wait for that one command (a beat frame resets the pacing timer rather than waiting on
  it), or give `DeviceWriteManager.updateCommand` a peak-hold rule during an active flash window:
  keep the max-value command, not the latest, until it's been written once.
- **Floor the envelope to the wire:** `beatFlashDecayMs` should never be below ~2.5× the device's
  actual pacing — derive an effective floor from `currentPacingMs` at runtime rather than trusting
  preset constants tuned for a 43 Hz ideal. (Laser Sharp at 70 ms vs. 100 ms pacing is currently a
  coin-flip whether a flash appears at all.)

Small, contained (DeviceWriteManager + one mapping-layer clamp), and it directly converts the
item-16/stage-1 flash-quality work from "computed" to "visible."

### Priority 3 — Song-structure awareness (macro-dynamics)

The moments people remember from a good visualizer are drops and builds. Two additions, both pure
mapping-layer, both feeding on existing state:

- **Long-horizon loudness reference:** a slow (30–60 s) percentile tracker of `totalEnergy`,
  *outside* the auto-gain loop, that scales overall brightness headroom — verses sit lower,
  choruses visibly bloom. This deliberately counteracts auto-gain's flattening (auto-gain keeps
  doing its job *within* sections; this reintroduces contrast *between* sections).
- **Drop detector → one-shot "big moment":** quiet-or-falling long-window energy + a sharp flux
  crest (the existing 6 s variance + short-window mean are most of the ingredients) triggers a
  single spike response: full-strength white blast, guaranteed anchor jump, and ~2 s of boosted
  `flashRange`, with a long refractory period (≥ 30 s) so it stays special. False positives are
  acceptable at this rarity; a missed drop costs nothing.

### Priority 4 — Multi-device spatial roles

Currently N devices = N mirrors. With the per-device write path already in place
(`sendCommandToDeviceDirect`, per-device `DeviceWriteManager`s), the mapping layer can emit
per-role variants of the same frame:

- **Hue-offset roles:** device B renders `anchor + 180°` (or +90°) — complementary color motion,
  the cheapest possible "wow" for two devices.
- **Alternating flash:** consecutive beats flash alternate devices (call-and-response). Halves
  each device's wire load during flashes, which also helps P2.
- **Band split:** one device follows bass value, the other mid/high — the room becomes a spatial
  spectrum display.

A `deviceRole` assignment (per saved device, default "mirror") plus a small per-role transform in
the publish path. Moderate effort, and it's the only item on this list that adds a genuinely new
perceptual *dimension* rather than sharpening an existing one.

### Priority 5 — Wake up the saturation channel

Key sat to something that moves: e.g. spectral crest/flatness (noisy/percussive → desaturated
toward white, tonal/sustained → deep color), or simply couple it to `breath` magnitude. Cheap,
but do it after P1–P3 — a third moving dimension amplifies whatever timing quality exists, bad or
good.

### Explicitly not recommended right now

- Further anchor/breath/preset-table tuning (cap reached until delivery is fixed).
- BeatDetector detection-math changes (data says it works; P1 only *consumes* its outputs
  differently).
- Unifying the backend `baseSat`/window-size divergences (still correctly deferred).
- New presets or per-knob UI surface (the 11 stage-2 fields staying preset-only remains right).

---

## 3. Suggested sequencing

1. **P0 measurement** (hours, no risk) — numbers decide how much of P1's win comes from
   scheduling vs. just pacing.
2. **P2 peak-survival** (small, independent, immediately visible on percussive presets) — worth
   doing first as its own slice since it also improves the P0→P1 measurements' interpretability.
3. **P1 predictive/fast-path flash** (the core slice; BeatDetector-surface + orchestrator).
4. **P3 macro-dynamics** (pure mapping layer, independent of P1/P2).
5. **P4 multi-device roles** (independent; can run in parallel with P3 if desired).
6. **P5 saturation** (last, small).

Each slice gates on the standing on-device smoke test (this is all live visual pipeline), and P1
in particular should be A/B'd against the current build with the same songs used for the item
13/15 diagnostic captures.
