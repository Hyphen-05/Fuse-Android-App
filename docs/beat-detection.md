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

## Why 90% is not reachable this way

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
