# Audio → LED Mapping Layer Proposal (2026-07-21)

Design proposal for the signal-to-visual mapping in the music visualizer. **Scope:** the mapping
layer only — how `BeatDetector`/band-energy outputs drive hue/saturation/value. The DSP itself
(beat detection, strength/confidence math, band splits, noise gating) is tuned and out of scope.
No code was changed for this document; everything below was read from current source on `main`
plus the uncommitted working tree (items 16/17 tuning already applied).

---

## 1. What the mapping layer actually does today (verified against source)

All of this lives in `AudioDspProcessor.process()`
(`app/src/main/java/com/example/core/audio/AudioDspProcessor.kt`, lines ~214–358). The output is a
single HSV color per frame, converted to RGB and sent as one `createMusicColorCommand` — so the
designable dimensions are exactly **hue, saturation, value** and their motion over time. There is
no independent brightness channel to the hardware.

### Value (brightness + flash)
- Ambient base: `bassContribution^1.5` (gated on noise floor) blended with `midContribution * 0.3`,
  × sensitivity, ^`audioGammaExponent`, × `ambientCapFraction`.
- Beat flash: `beatPulsePeak = 0.6 + 0.4 * strength * confidence(coerced)`, decayed by a quadratic
  envelope `(1 − t²)` over `beatFlashDecayMs`, × `audioFlashStrength`, **added** to ambient.
- `Beat Only` preset: envelope only, no ambient (`ambientCapFraction = 0`).
- Floor: `visualizerMinBrightness`.

### Saturation
- `baseSat` from smoothed mid energy vs. its auto-gain ceiling (backend-branched coefficients —
  preserved divergence per CLAUDE.md, not touched here).
- × `whiteHotFlashOffset`: on beats in the three "white flash" presets (`Strobe Blast`,
  `Beat Only`, `Laser Sharp`) saturation snaps to **0** (full white) and recovers at
  +0.05/frame (binary depth, frame-rate-dependent recovery: ~0.47 s on phone mic, ~1 s on
  Visualizer).

### Hue — five sources, not three (correcting the stale model)
1. **`continuousHueOffset`** — an unbounded integrator: drifts `+totalEnergy * 0.01` **per frame**
   and *also* jumps **+30°** on every beat (+15° in white-flash presets). So the "continuous"
   drift is itself beat-coupled, and its rate is frame-rate-dependent (~2× faster on the 43 Hz
   AudioRecord backend than the ~20 Hz Visualizer backend).
2. **Spectral tilt** — `+midRatio*60 − highRatio*60` (±60°, hard-coded).
3. **`beatHueOffset`** — a **180° impulse** on every beat (non-white-flash presets), decaying
   ×0.85/frame. Because the EMA smoother *snaps* to target on the beat frame and the impulse then
   decays, each beat produces a 180° lurch followed by a ~0.5–1 s rainbow smear back — not a snap
   to a new color.
4. **Sustain shift** — a binary +120° while `isSustainedSection` (1 s short-window mean over a
   threshold AND 6 s variance > 5). Binary: it flickers on/off when energy hovers at the
   threshold, and the 120° applies/vanishes instantly (softened only by the EMA).
5. **Palette offset** — `{0, 60, 120, 180, 240, 300}`, advancing one step **per beat** when
   `isPaletteCyclingEnabled` (every preset except `Beat Only`). Added on top of the drift, so the
   quantization it implies is smeared away by source #1 underneath it.

Final hue is circular-EMA smoothed (`alpha = 0.10 * visualizerColorSpeed`), with a **hard snap on
every beat frame** regardless of preset.

### The real presets (from `visualizerConfigFor()`, `AudioSettingsReducer.kt:51-60`)

| Preset id | attack | decay | flash | gamma | noiseGate | bass/mid/high gain | palette | beatMult | minBright | colorSpeed | flashDecayMs | ambientCap | midFluxWt |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `Default` ("Balanced") | 0.85 | 0.12 | 0.3 | 0.45 | 5.0 | 1.0/1.0/1.0 | ✓ | 1.6 | 0.15 | 1.0 | 200 | 0.40 | 0.25 |
| `Punchy` | 0.95 | 0.35 | 0.6 | 0.35 | 8.0 | 1.2/1.0/1.0 | ✓ | 1.3 | 0.15 | 1.5 | 140 | 0.40 | 0.20 |
| `Smooth Flow` | 0.25 | 0.08 | 0.0 | 0.45 | 4.0 | 1.0/1.1/1.0 | ✓ | 1.8 | 0.18 | 1.2 | 300 | 0.45 | 0.30 |
| `Strobe Blast` | 1.0 | 0.85 | 1.0 | 0.2 | 10.0 | 1.0/1.0/1.5 | ✓ | 1.2 | 0.10 | 2.0 | 90 | 0.25 | 0.15 |
| `Ambient Chill` | 0.10 | 0.02 | 0.0 | 0.65 | 3.0 | 1.0/0.7/0.4 | ✓ | 2.5 | 0.35 | 0.15 | 600 | 0.75 | 0.20 |
| `Bass Thump` | 0.90 | 0.20 | 0.5 | 0.4 | 6.0 | 2.0/0.6/0.4 | ✓ | 1.4 | 0.15 | 1.0 | 170 | 0.45 | 0.05 |
| `Laser Sharp` | 1.0 | 0.95 | 0.8 | 0.3 | 12.0 | 1.0/1.0/1.0 | ✓ | 1.1 | 0.10 | 3.0 | 70 | 0.20 | 0.10 |
| `Beat Only` | 1.0 | 0.9 | 1.0 | 0.3 | 10.0 | 1.0/1.0/1.0 | ✗ | 1.2 | 0.05 | 2.0 | 90 | 0.0 | 0.15 |

Note what's *missing* from this table: **no preset can vary any hue behavior except smoothing
speed (`colorSpeed`) and palette on/off.** The 30°/15°/180°/±60°/120° constants, the drift rate,
and the beat-snap are identical across all eight presets. Presets differentiate brightness
dynamics well and hue dynamics almost not at all.

---

## 2. Signal inventory: what exists vs. what's used

| Signal | Source | Used for | Wasted potential |
|---|---|---|---|
| `isBeat` | BeatDetector | flash trigger, hue jump, palette step, EMA snap | fine — arguably over-used (drives 4 things identically in all presets) |
| `strength` (0–1, soft-saturating) | BeatDetector | flash peak only | could scale hue-jump size, white-flash depth |
| `confidence` (0–1; salience + BPM-lock + phase-grid) | BeatDetector | flash peak only | **should gate hue jumps** — currently a conf=0.3 maybe-beat yanks hue exactly as hard as a conf=1.0 locked beat |
| `bpm`, `bpmConfidence` | BeatDetector | **nothing** | tempo-locked color motion; bar-level (multi-beat) structure; free-run vs. grid crossfade |
| `smoothedBass/Mid/High`, ratios | band energies | value base, sat base, hue tilt | tilt is hard-coded ±60° everywhere |
| `totalEnergy` | band sum | hue drift rate, noise/idle gates | drift is per-frame (backend-dependent) and unbounded |
| `isSustainedSection` | 1 s mean + 6 s variance | binary 120° hue shift | no hysteresis, no ramp, same response in every preset |
| rolling variance | 6 s window | sustain gate only | a usable "how dynamic is this music" macro signal |

The on-device diagnostic data (items 13/15/16) showed confidence genuinely spreads 0.31–1.0 across
true beats — it now shapes flash, but it is exactly as informative about whether a *hue* change
should happen, and it isn't consulted there.

---

## 3. Structural problems worth fixing (independent of taste)

1. **Discrete and continuous hue are entangled.** Beat jumps feed the same unbounded integrator
   that the energy drift feeds, and a decaying 180° impulse rides on top. No parameter setting can
   express "hold a steady color, snap to a new one on the beat" — the closest you get is smeared
   by drift and the impulse tail. This is the root of the open question, and it's why tuning the
   existing constants can't answer it.
2. **Mapping-layer time constants are per-frame, not per-second.** Hue drift, the 0.85 impulse
   decay, and the 0.05 white-flash recovery all run ~2.15× faster on `phone_mic` (43 Hz) than
   `on_device` (~20 Hz). This is distinct from the *deferred DSP* sample-count issue documented in
   CLAUDE.md (flux/energy window sizes — not touched here): these three constants are pure
   mapping-layer and can be time-based without going near the DSP windows. `process()` already
   receives `nowMs`; a per-frame `dt` is available for free.
3. **Every beat hard-snaps the hue EMA in every preset**, including `Ambient Chill` — the one
   preset whose entire identity is "no transients."
4. **Palette cycling advances every beat.** At 128 BPM the full 6-color cycle repeats every 2.8 s.
   Combined with the underlying drift, the palette contributes churn, not structure.
5. **The sustain response is binary and identical everywhere.** A +120° toggle with no hysteresis
   flickers near threshold, and "sustained section" should mean different things to `Strobe Blast`
   (nothing) and `Ambient Chill` (a slow bloom).

---

## 4. Proposed architecture: anchor + breath

Replace the five-way additive hue pile with an explicit two-component decomposition:

```
hue = anchor + breath

anchor : discrete. A palette-indexed base hue that MOVES ONLY on qualifying events
         (confident beats, bars, or a slow timer). Between events it is constant.
breath : continuous. A BOUNDED offset around the anchor, driven by spectral balance
         and energy. It can never walk away — it's a tilt, not an integrator.
```

- **`anchor`** replaces: `continuousHueOffset`'s beat jumps, `beatHueOffset`'s 180° impulse, and
  the palette offset — all three collapse into "the anchor moved." An anchor move is instant
  (the EMA snap happens *only* when an anchor move actually fires, not on every raw beat).
- **`breath`** replaces: the spectral tilt (±60° hard-code becomes a per-preset range) and the
  energy drift. Two sub-terms:
  - *tilt*: `(midRatio − highRatio) * hueBreathRangeDeg` — the existing musical logic, bounded
    per preset.
  - *drift*: optional slow rotation in **degrees per second** (`dt`-integrated, backend-agnostic),
    optionally tempo-locked (below). Drift rotates the anchor's *reference frame* slowly rather
    than accumulating into a separate variable — equivalently: anchor += drift·dt, breath stays
    bounded.
- **Anchor moves are confidence-gated**: a move fires on `isBeat && confidence ≥ hueJumpConfidenceGate`
  (per preset). Low-confidence beats still flash (value channel, scaled down by the item-16
  formula) but don't recolor — flash is cheap and reversible, recoloring is the perceptually
  expensive event. This finally gives `confidence` a second, natural consumer.
- **Bar-level structure via the unused BPM grid**: `anchorBeatsPerAdvance` — advance the anchor
  every N qualifying beats (N=1 today's behavior, N=4 ≈ per bar, N=0 never/time-based). When
  `bpmConfidence` is high the beat counter is trustworthy; when low, fall back to per-beat.
- **Sustain becomes a per-preset response** with hysteresis (enter: above threshold ≥ 400 ms;
  exit: below 0.8× threshold ≥ 800 ms) and a ramp (`sustainRampMs`), choosing one of:
  `HUE_SHIFT` (ramped ±120°), `SAT_BOOST`, `BRIGHTNESS_SWELL`, `NONE`.
- **White flash depth becomes analog**: desaturation depth = `strength * confidence` instead of
  binary full-white, recovery expressed as `whiteFlashRecoveryMs` (time-based). A weak beat gives
  a pale wash; a locked-in downbeat gives the full white pop.
- **Flash floor/range become per-preset**: `flashFloor + flashRange * strength * confidence`
  replaces the hard-coded `0.6 + 0.4·…`. The item-16 formula is preserved as the default values
  (0.6/0.4); the *formula shape* is untouched — this only lets presets choose how much dynamic
  range the flash has.

### Answer to the open question (breathe vs. snap)

**Both, but on different components — and the amount of each is the preset's main personality
knob.** Breathing should be *bounded and relative* (a tilt around a stable anchor), never an
unbounded integrator; discreteness should be *event-driven and gated* (anchor moves on confident
beats/bars), never a decaying impulse. The current code can't express this because both behaviors
share one accumulator. Concretely:

- Chill/flow presets: large breath range (±40–45°), zero or timer-based anchor moves → color
  visibly swims with the music's spectral balance and never lurches.
- Percussive presets: near-zero breath (±0–10°), anchor moves every confident beat → color is a
  sequence of clean, distinct states, changing exactly when the music does.
- Balanced sits in the middle (±25°, anchor per beat with a moderate gate).

A decaying hue impulse (today's 180° `beatHueOffset`) is the one mechanism I recommend deleting
outright rather than parameterizing: it occupies the perceptual space *between* breathe and snap
(a fast smear), which reads as instability in every preset.

### Tempo-locked color motion (new mechanism, uses the wasted BPM signal)

When `bpmConfidence ≥ 0.5`, express drift as degrees-per-beat instead of degrees-per-second:
`driftDegPerSec = (bpm / 60) * hueDegreesPerBeat`, crossfaded with the free-run rate by
`bpmConfidence`. Slow music → slow color motion; fast music → fast motion; and because the grid
and the anchor moves share a clock, drift and snaps stop fighting. Primarily for `Smooth Flow`
(whose identity is motion without transients); harmless to leave disabled (`hueDegreesPerBeat=0`)
elsewhere.

### Optional, flagged separately — anticipatory dimming (touches the DSP *surface*, not tuning)

`BeatDetector` already maintains a forward beat grid (`gridBeats`) for phase agreement. Exposing
one additive field (`nextPredictedBeatMs`) in `BeatResult` would let the mapping layer pre-dip
ambient value ~10% in the last ~80 ms before a predicted beat, making the flash pop harder for
free. This is a new output field only — zero change to detection/tuning math — but since the
brief says don't touch the DSP, it's listed as opt-in for a later pass, not part of the core
proposal.

---

## 5. Per-preset mapping table (proposed values)

New per-preset parameters (added to `VisualizerConfig` + `AudioSettingsState` + prefs):

| Param | Meaning |
|---|---|
| `flashFloor` / `flashRange` | flash peak = floor + range·strength·confidence |
| `anchorBeatsPerAdvance` | 0 = never (timer/none), 1 = every qualifying beat, N = every N |
| `hueAnchorJumpDeg` | anchor step size per advance |
| `hueJumpConfidenceGate` | min confidence for an anchor move |
| `hueBreathRangeDeg` | bound on spectral-tilt breathing |
| `hueDriftDegPerSec` / `hueDegreesPerBeat` | free-run drift / tempo-locked drift (crossfaded by bpmConfidence) |
| `sustainResponse` + `sustainRampMs` | enum per §4 |
| `whiteFlashRecoveryMs` | replaces +0.05/frame |

Proposed values (existing 16 fields unchanged unless noted):

| Preset | flashFloor/Range | anchor advance | jump° | conf gate | breath° | drift | sustain | white flash |
|---|---|---|---|---|---|---|---|---|
| **Balanced** | 0.6 / 0.4 | every 2 beats | 60 | 0.35 | ±25 | 4°/s free | HUE_SHIFT, 2000 ms | — |
| **Punchy** | 0.5 / 0.5 | every beat | 90 | 0.45 | ±10 | 0 | SAT_BOOST, 800 ms | — |
| **Smooth Flow** | (flash = 0) | never | — | — | ±40 | 1.5°/beat tempo-locked, 6°/s fallback | HUE_SHIFT, 3000 ms | — |
| **Strobe Blast** | 0.7 / 0.3 | every beat | 120 | 0.50 | 0 | 0 | NONE | depth = str·conf, 250 ms |
| **Ambient Chill** | (flash = 0) | every 16 beats (or 30 s timer if no BPM lock) | 60 | 0.50 | ±45 | 1.5°/s free | BRIGHTNESS_SWELL +0.10, 3000 ms | — |
| **Bass Thump** | 0.4 / 0.6 | every beat | 60 | 0.40 | ±15, driven by bassRatio instead of mid−high | 2°/s free | SAT_BOOST, 800 ms | — |
| **Laser Sharp** | 0.7 / 0.3 | every beat | 180 | 0.60 | 0 | 0 | NONE | depth = str·conf, 120 ms |
| **Beat Only** | 0.6 / 0.4 | every beat | 137.5 (golden angle) | 0.40 | 0 | 0 | NONE | depth = str·conf, 250 ms |

Reasoning per preset:

- **Balanced** — the default must demonstrate both behaviors without committing to either:
  moderate breath, anchor per-2-beats (half the churn of today's per-beat palette), a permissive
  gate so it still feels responsive pre-tempo-lock.
- **Punchy** — widest flash dynamic range short of Bass Thump (0.5+0.5): its identity is transient
  contrast, so let strength·confidence actually swing the flash. Big 90° jumps, minimal breath —
  color states, not color motion. Gate 0.45 keeps double-fire jitter from recoloring twice.
- **Smooth Flow** — the tempo-lock showcase. No anchor events at all; all motion is continuous
  and clocked to the music (1.5°/beat ≈ one full wheel per ~4 bars of 4/4 at any tempo). Breath
  ±40° keeps spectral character visible. This is the preset that most directly answers "breathe."
- **Strobe Blast** — pure snap. 120° = two palette steps for maximum adjacent-flash contrast.
  Analog white-flash depth is the biggest single win here: today every strobe is identical white;
  proposed, the strobe brightness/whiteness tracks how hard the beat actually hit.
- **Ambient Chill** — the anti-transient preset: beats may not recolor it at all (16-beat/30 s
  anchor drift gives long-arc variety), sustain blooms brightness instead of shifting hue, and it
  keeps the largest breath range so it still *listens*. Also the preset that most benefits from
  removing the universal beat-snap of the EMA (problem #3).
- **Bass Thump** — flash floor lowered to 0.4 so `strength` (which, with `midFluxWeight = 0.05`,
  is nearly pure bass flux here) owns the room: weak bass hits visibly small, big drops visibly
  huge. Breathing keyed to bassRatio rather than mid−high tilt, so the color leans with the low
  end its identity is built on.
- **Laser Sharp** — hard quantize: 180° alternation (max possible hue distance), highest gate
  (0.60) so only locked-grid beats fire, `colorSpeed` 3.0 (near-instant EMA) preserved. Fastest
  white-flash recovery to match its 70 ms envelope.
- **Beat Only** — hue should be as event-driven as its brightness already is: frozen between
  beats (breath 0, drift 0), golden-angle jumps so consecutive flashes never repeat or cycle
  visibly (137.5° never revisits a color in short order, unlike 60°'s 6-cycle). Palette cycling
  stays off; the golden-angle walk replaces it.

---

## 6. Implementation surface (for a future execution pass — not done here)

- `AudioDspProcessor.process()` hue/sat sections rewritten around anchor+breath; the value/flash
  section only gains `flashFloor`/`flashRange` in place of 0.6/0.4. `BeatDetector` untouched.
- `VisualizerConfig` grows ~8 fields (16 → ~24); `visualizerConfigFor()` table, matching
  `AudioSettingsState` fields, `SetVisualizerPreset`'s state-copy + prefs-effect list, and the
  prefs-load in the ViewModel `init` all extend in lockstep. `AudioSettingsReducerTest`'s preset
  table assertions update mechanically.
- Deletions: `beatHueOffset` (the 180° impulse), the per-beat +30°/+15° drift jumps, the binary
  sustain toggle, the universal beat-snap of the hue EMA (snap becomes conditional on an anchor
  move).
- Time-basing (`dt` from successive `nowMs`) applies to: drift, white-flash recovery, sustain
  ramp. The deferred DSP sample-count constants (flux/energy/noise window sizes) are explicitly
  NOT touched, per CLAUDE.md.
- Natural slices if staged: (1) time-basing + flashFloor/Range (small, low-risk, no new UX);
  (2) anchor+breath rewrite + per-preset table (the core); (3) tempo-lock + bar counting;
  (4) optional `nextPredictedBeatMs` pre-dip (needs the one-field BeatResult addition).
- Testing: anchor/breath decomposition is pure math on `AudioDspProcessor` — the existing
  synthetic-frame test approach in `AudioDspProcessorTest.kt` extends naturally (e.g. "no anchor
  move below gate," "breath bounded by range," "hue constant between beats in Beat Only"). Note
  the existing in-file warnings about `hsvToRgb`'s cubic value-crush and uniform-magnitude frames
  self-gating before writing amplitude assertions.
- Standing rule applies: this touches the live visual pipeline, so it gates on an on-device
  smoke test (all 8 presets, both backends) before promotion.

---

## 7. Out of scope / explicitly not proposed

- Any change to `BeatDetector` internals, thresholds, strength/confidence math (item 16/17 tuning
  stands). The only DSP-adjacent item is the *optional* additive `nextPredictedBeatMs` field, §4.
- The backend-branched `baseSat` coefficients and initial auto-gain seeds — preserved divergences
  per CLAUDE.md.
- The deferred sample-count DSP window constants.
- The 8 onboard BLE LED presets (`energetic_1` … `rolling_2`) — firmware-side, no mapping layer
  involved.
- Palette *contents* (the 6 fixed hues) — worth a separate conversation (user-selectable palettes
  would slot cleanly into the anchor model), but orthogonal to the signal-routing design.
