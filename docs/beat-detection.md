# Beat detection: what is measured, and what has already been tried

Read this before changing anything that decides *when the strip flashes*. Everything here is
measured, and most of it contradicts something that sounds obvious.

## How to measure it

Two harnesses, and they answer different questions.

**`BeatAccuracyTest` / `BeatCorpus`** — ten generated tracks whose beat times are exact by
construction, each one a specific way real music defeats naive detection (beats with no onset on
them, onsets that are not beats, swing, drifting tempo, metrical ambiguity, a big mid-track level
change). Use it to ask *why* something fails: every property of the material is known and
controllable. Do not use it to ask how often something fails — a synthetic kick is a cleaner
transient than any real one, and ten hand-written tracks are ten guesses about what music is like.

**`GtzanBeatAccuracyTest` / `PulseTrackerTest`** — 100 real recordings with human beat annotations.
This is the number that counts. Both skip themselves when the corpus is absent.

Setting the corpus up (neither part is committed — the audio is a copyrighted research corpus and
this repo is public):

- **Audio**: the GTZAN genre collection, extracted so `<root>/Data/genres_original/<genre>/<genre>.00000.wav`
  exists. 1000 clips, 30s each, ten genres.
- **Annotations**: <https://github.com/TempoBeatDownbeat/gtzan_tempo_beat>, extracted so
  `<root>/gtzan_tempo_beat-main/beats/gtzan_<genre>_00000.beats` exists. Format is
  `<seconds>\t<position-in-bar>`, so downbeats are available and nothing uses them yet.
- Point `GTZAN_ROOT` at `<root>`, or leave it at the default `C:/Users/attgm/gtzan`.

**The audio is 22.05kHz.** `OfflineAudio.readWav` resamples to 44.1kHz; read raw, every tempo
reports at double and every measurement is nonsense. That resampling is the only reason the corpus
works at all.

The metric is the MIREX F-measure at ±70ms, with greedy one-to-one matching so a preset that strobes
continuously cannot score perfect recall by accident. `BeatAccuracy.bestVariant` scores against the
half, double and offbeat grids too, which is the only thing that separates an octave error from
never having found the pulse — they need opposite fixes.

## The shipped baseline

On 100 annotated clips, the shipped path scores **F=53%, precision 44%, recall 69%**. The synthetic
corpus says 60%, which is about the gap you would expect between a generated kick and a real one.

**The failure is not missed beats.** On **80 of 100 clips the flashes fit the double grid better than
the true one** — the strip flashes at roughly twice the beat rate.

The cause is structural and measurable: **the predictive scheduler in `AudioDspProcessor` fires 0% of
all flashes at the default settings.** It re-arms to the next beat on the very frame the current one
comes due (`nextPredictedBeatMs` is by definition the first grid beat *ahead* of now), so its firing
window never opens — 0% at a 0ms `flashTimingOffsetMs`, 8-41% at any non-zero one. Every flash comes
from the fast causal trigger, which fires on any onset clearing an adaptive threshold and has no
notion of where the beat is.

The tempo estimate underneath is *fine*: 127.7 against a true 128, 150.0 against 150, confident 100%
of the time on eight of ten synthetic tracks. The information was there and nothing used it.

## Rejected, with numbers — do not retry blind

| change | result |
|---|---|
| Fixing the scheduler's ordering so it fires before re-arming | **60% → 55%.** It works, and then both paths flash, and precision falls further. |
| Tempo-scaled refractory on the causal trigger | **53% → 50%.** Precision 44→47%, recall 69→56%. A refractory started by an offbeat swallows the beat after it. |
| Offbeat veto (beat clock as referee, causal trigger still fires) | **53% → 51%.** Best of the suppression candidates and still not a win. |
| `BeatClock` driving the flashing | **53% → 50%.** Decisive where the lock is genuine (synthetic four-on-the-floor 65→92%), badly wrong where it is not. |
| Comb template summing the ACF at multiples of the candidate | **Worsened octave errors.** The true period is itself a multiple of the half-period impostor, so the comb rewards both. Replaced by an asymmetric test that only ever walks the period *upward*. |
| Adaptive thresholding of the onset curve before the ACF | Slightly worse here, and off. Standard practice elsewhere. |
| Peak-picking the DBN's activation to make it spikier | **Collapsed it** — recall 10%. |
| DBN refractory / reading the most probable state | Argmax hops between near-equal tempi and emits 1.6 beats per real beat; a refractory to fix that cost recall 69→42%. Read the circular mean of the phase distribution instead. |

**Suppression cannot fix the double-rate problem.** Every suppression candidate traded recall for
precision at break-even or worse, because each suppressed flash costs about as many true beats as
false ones. That result is what pointed at the front end rather than the policy.

> **Superseded 2026-08-25 — read the section below before trusting the paragraph above.** The
> conclusion held for every candidate *tested*, and every candidate tested suppressed the causal
> trigger only. A cap applied to all four mechanisms at once behaves differently, and the reason the
> earlier result looked decisive is that F-measure cannot see the thing being fixed.

## Flash density, and why F-measure hid this (2026-08-25)

Joe, on hardware: the visualisers are "way too flashy ... if you have 10 flashes or stuff that looks
like flashes per beat then it all becomes a mess not a satisfying visualiser". That is a complaint
about **rate**, and F-measure cannot express it — F weighs a missed beat exactly as heavily as a
spurious flash, so halving the flashing scores as a loss even when it is the entire goal.
`flashes/beat` measures it directly. `GtzanBeatAccuracyTest.flash density per beat, by configuration`
reports it.

| config | flashes/beat | precision | recall |
|---|---|---|---|
| shipped | 1.68 | 44% | 69% |
| pulse | 1.42 | 52% | 66% |
| refractory | 1.26 | 47% | 56% |
| veto | 1.50 | 45% | 63% |
| pulse + refractory | 1.21 | 54% | 60% |
| clock | 1.55 | 44% | 62% |
| **cap** | **0.98** | 54% | 50% |
| **cap + pulse** | **0.99** | **56%** | 52% |
| cap + pulse + refractory | 0.99 | 56% | 52% |

**The cap is `GLOBAL_FLASH_BEAT_FRACTION`, enforced inside `triggerFlash`** so no mechanism can skip
it and none added later can either. Until this change that method gated on *amplitude alone* — a
flash was refused only for being dimmer than the one still decaying — so nothing in the DSP bounded
how often the strip could flash. That is the actual bug behind the double-rate finding, and it was
never a detection problem.

**It costs nothing on F.** Shipped is F=53.7%, cap+pulse F=53.9%. Density falls 41% and precision
rises 12 points for no measurable F change, which is why the earlier "break-even or worse" result
should not be read as ruling this out — those candidates suppressed one mechanism out of four while
the others carried on flashing.

**The refractory is now redundant, and is deleted.** `cap + pulse` and `cap + pulse + refractory`
are identical: a 0.9-beat cap subsumes a 0.55-beat one. `beatRefractoryEnabled`,
`REFRACTORY_BEAT_FRACTION` and the candidate rows that used them were removed on 2026-08-26 — the
rows in the tables above are the record, and are the reason not to rebuild it. The causal trigger is
back to the flat `FAST_TRIGGER_COOLDOWN_MS` it always shipped with, so nothing about the default
path changed.

**What the cap does not fix is phase.** Precision 56% means about half the remaining flashes are
still not on a true beat — they are simply no longer doubled up. If one-per-beat still reads wrong on
hardware, phase is the next problem, and it is the harder one. Rate was cheap; phase is not.


## Comfort and coupling are the same number (2026-08-26)

Joe judged Live Wire on hardware and rejected it — "way too flashy or jumpy... very uncomfortable on
the eyes" — then, asked about the rest, said **none of the presets are comfortable or satisfying**.
That second sentence is the important one: it removes the assumption that Smooth Flow and Ambient
Chill mark a comfortable end of the scale. There is no known-good reference anywhere in the app.

Two metrics were added to `trackingOf` because `movement` is a mean and the complaint is not about
the mean: `peakSlew` (95th percentile of |dBrightness|/s) and `contrast` (p95 - p5 of brightness). A
third, `bestLift`, sweeps the +-80ms beat window back over lags 0-200ms, because slowing the attack
delays the peak and plain `lift` would mark a calm tuning down for *lagging* rather than for failing
to follow the music.

### The result: three knobs, one line

Sweeping `fastAttackTauMs` x `bodyShare` x `punchGain` (27 combinations, 34 clips) does not describe a
frontier to pick a point on. It describes a **line**:

    excess coupling (bestLift - 1) ~= k * peakSlew,   pearson r = 0.87 overall

and within a fixed `bodyShare` the constant is near enough exact — `bodyShare` 0.25 holds k between
0.067 and 0.085 while `peakSlew` varies **8-fold** across the other two knobs.

So `fastAttackTauMs` and `punchGain` are not independent levers. They are the same lever twice: both
slide the tuning along one line, trading coupling for jarring at a fixed rate. `bodyShare` is the only
knob that changes the exchange rate at all, and it moves it the **wrong way** — raising it from 0.25
to 0.65 drops k from ~0.075 to ~0.040, i.e. less felt connection per unit of discomfort.

| preset | lift | bestLift | lag | movement/s | peakSlew | contrast | mean |
|---|---|---|---|---|---|---|---|
| Punchy | 1.307 | 1.372 | 38ms | 2.96 | 14.56 | 0.510 | 0.372 |
| Live Wire | 1.278 | 1.424 | 59ms | 1.81 | 6.89 | 0.465 | 0.362 |
| Smooth Flow | 1.001 | 1.039 | 96ms | 0.49 | 1.87 | 0.213 | 0.455 |
| Ambient Chill | 0.997 | 1.014 | 102ms | 0.55 | 1.58 | 0.380 | 0.570 |

Lag compensation does *not* rescue the calm tunings: `bestLift` lifts 45/0.45/0.30 from 1.037 to only
1.094, against 1.424 for shipped Live Wire. Smoothing genuinely destroys the coupling; it does not
merely delay it. Both metrics agree, which is the point of having both.

### What this means

**Stop tuning brightness.** Every preset in the app modulates one scalar and only that scalar, and
this line is the whole space those presets live in. Punchy and Live Wire sit at the jarring end;
Smooth Flow and Ambient Chill sit at the dead end; Joe has now rejected both ends and everything
between them is on the same line by construction. Another sweep buys another point on it.

**The strip is single-colour.** `DuoCoProtocol` has one colour command for the whole strip, no
per-LED addressing. So a visualiser has exactly two dimensions over time: brightness and hue. Hue is
currently near-frozen on every preset (Live Wire drifts 3 deg/s with an +-8 deg breath), which means
the entire judged history of this feature has explored one of the two available axes.

**Colour is the untried axis, and the eye's flicker sensitivity is a luminance phenomenon** — chroma
motion at the same subjective "amount of movement" is markedly less fatiguing. A mapping that holds
brightness fairly steady and puts the music into hue and saturation is the one quadrant that has
never been built, and it is the only one that can satisfy both halves of Joe's complaint at once.

**projectM is not a coupling to copy.** He rates it "pretty much perfect", and `ContinuousDrive` was
written from its audio model — but what makes it satisfying is *spatial*, 2D shapes in motion, and
that is exactly the part a one-colour strip cannot show. Copying its audio front end copies the part
that was not doing the work. The open question is whether anything of it survives being averaged to a
single colour, and the cheap way to settle that is to run projectM on the Pixel and point Fuse's
existing **ambiance screen capture** at it: the strip then shows projectM averaged down to one colour,
with no new code at all. If that feels good there is finally a ground truth to characterise and
reproduce; if it feels dead, this hardware's ceiling has been found and the honest move is to say so.
Do not read "sluggish" as "dead" in that test — ambiance applies its own EMA and deadband first.

## What is in the tree now

`PulseTracker` — SuperFlux onset strength (log magnitudes differenced against a frequency-maximum-
filtered earlier frame, which is what stops vibrato and portamento reading as onsets), autocorrelation
with a log-normal tempo prior, comb phase search. Causal throughout. **F=57% standalone, 56% through
the flash pipeline** — the first candidate to beat shipped on every axis.

Per genre it is very uneven, and the unevenness matters more than the average: hiphop 78%, reggae 82%,
metal 69%, disco 68%, country 68%, against jazz 29% and classical 36%. It helps on music with a pulse
and mildly hurts on music without one.

`BeatDbn` — a dynamic Bayesian network over joint tempo-and-phase states, ~2000 states, forward
algorithm, causal. **F=53%, loses to the autocorrelation front end it was meant to replace.** Kept
because it is exactly the decoder a *learned* activation would need, and it is already built and
measured.

## The finding that should drive the next attempt

**Confidence is nearly worthless; steadiness is strongly predictive.**

`PulseTracker.confidence` (how peaked the tempo evidence is) sorts the corpus barely at all: 53% mean
F in its lowest bucket against 65% in its highest. `PulseTracker.stability` (how little the *raw*
estimate moves between looks) sorts it hard:

| steadiness | clips | mean F |
|---|---|---|
| 0.0 – 0.2 | 10 | 38% |
| 0.2 – 0.4 | 19 | 45% |
| 0.4 – 0.6 | 19 | 45% |
| 0.6 – 0.8 | 26 | 56% |
| 0.8 – 0.9 | 26 | **84%** |

Above 0.7 steadiness: 44% of clips at F=75%. Above 0.8: 26% of clips at F=84%. So the tracker drives
the flashing only while it is steady and hands straight back when it is not, which is what the wiring
does (`PULSE_STEADY_ENOUGH`).

## The continuous path — "Live Wire" (2026-08-25)

Joe, comparing against the projectM Android app: "pretty much perfect and genuinely feels like it's
going to the song". That app does not beat-detect for its visuals. MilkDrop and projectM expose
`bass`/`mid`/`treb` as continuously normalised band energies, auto-gained to average ~0.65 whatever
the track's loudness, read every frame; discrete detection is reserved for coarse things like
switching presets. **A continuous mapping cannot flash on the wrong beat, because it never claims a
beat exists.** Everything above is an attempt to win a bet that a continuous mapping never places.

`ContinuousDrive` copies that. No detection, no threshold, no flash trigger — `triggerFlash` refuses
outright on a continuous preset, which stands all four mechanisms down at one choke point.
A transient is *emergent*: two envelopes over the same signal, one quick and one slow, and the gap
between them is the pulse. A kick makes the fast one leap while the slow one lags; a sustained note
moves both together and the gap stays at zero.

Scored on the same 100 clips, with metrics that suit a mapping having no events to score —
`lift` is mean brightness within ±80ms of an annotated beat over mean brightness elsewhere, and
`movement/s` is mean |Δbrightness| per second:

| preset | lift | movement/s | mean |
|---|---|---|---|
| **Live Wire** | **1.278** | **1.81** | 0.362 |
| Punchy | 1.307 | 2.96 | 0.372 |
| Smooth Flow | 1.001 | 0.49 | 0.455 |
| Ambient Chill | 0.997 | 0.55 | 0.570 |

**Live Wire tracks as well as Punchy at 61% of the movement, at the same brightness, with zero
flashes.** Note also that Smooth Flow and Ambient Chill sit at a lift of ~1.0 — they do not track the
beat at all, which no previous measurement had shown.

### Tuning, and the traps in it

`ContinuousDrive.Tuning` is injectable and `GtzanBeatAccuracyTest.sweep continuous tuning` runs the
real pipeline under each candidate. Three findings worth not rediscovering:

- **`bodyShare` at 1.0 gives a lift of 1.035** — nearly flat. With the body claiming the whole range
  the light parks near the top and a transient has nowhere to go. The first attempt did this.
- **`punchGain` peaks near 1.5 and falls above it.** Punch fires on every transient, not on beats, so
  past the optimum extra gain adds off-beat brightness as fast as on-beat: the ratio drops while
  movement keeps climbing. A sweep sampling only 1.5-5.0 reads as "punch does not help", which is an
  artefact of starting on the downslope.
- **`lift` alone cannot pick a winner.** It is a ratio, so a preset sitting nearly dark and pulsing
  dimly scores beautifully and looks feeble. `mean` was added for exactly this — the best-scoring row
  in the second sweep was a `bodyShare` of 0.15 at a mean of 0.327, dimmer than Punchy.

Shipped at `bodyShare 0.25, punchGain 1.0, fastRelease 100ms`. `punchGain` is deliberately below its
measured optimum: 1.5 buys 4% more tracking for 30% more movement, and movement is the complaint.
It is deliberately *above* zero for a reason the metric cannot see — `lift` averages over a ±80ms
window, so it rewards a broad swell exactly as much as a sharp hit and is blind to whether a hit
reads as a hit. If Live Wire feels limp on hardware, `punchGain` is the first knob.

## Why 90% is not reachable this way

**And why it may be the wrong target.** 90% was always about *catching beats* — recall. The cap above
deliberately gives recall away (69% → 52%) to buy a flash rate Joe can stand, and measured better on
his stated complaint while scoring identically on F. Those are opposing goals: a system that catches
every beat flashes on everything that might be one. Decide which is wanted before chasing the number.


90% F-measure on GTZAN is at or above published state of the art. madmom's DBN — the standard
reference — sits at 86-88%, and only recent neural trackers clear 90%, all of them **offline and
non-causal with the whole file in hand**. This app is causal and real-time on a phone.

The DBN experiment is the direct evidence for where the remaining ceiling is: madmom's 86% does not
come from its state space, it comes from an RNN activation that is near zero everywhere and near one
on beats. The DBN's observation model asks "is a beat happening now", and a spectral-flux z-score
cannot answer it — it is just as high on an offbeat hat as on the beat. A DBN over a hand-crafted
onset function being much weaker than over a learned one is the known result in the literature and is
now the measured result here.

**So the ceiling is the onset function, not the decoder.** Reaching ~85-90% means a small learned
activation (a TCN), which means model weights (trained or licensed), TFLite on device, and a battery
question — days of work, with the licensing question likely deciding it before the engineering does.
`BeatDbn` is the decoder that would consume it.
