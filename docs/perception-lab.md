# The Perception Lab — how it is built, and why each piece is that way

Read this before changing anything under `core/perception/`, `core/perception/lab/`,
`ui/components/PerceptionLabScreen.kt` or `ui/components/LabBatteryScreen.kt`.

Results and what they concluded live elsewhere: [perception-2026-09-05.md](perception-2026-09-05.md)
(first sitting), [quantisation-2026-09-05.md](quantisation-2026-09-05.md) (the grid rule),
[battery-2026-09-06.md](battery-2026-09-06.md) (blocks 0-3).

## What it is for

The capture programme measured the strip and never measured the viewer, and that gap is what let a
change ship on 2026-09-04 that a simulation scored as an improvement and Joe described as
"everything is steppy now". With the Pixel 9 gone there is no camera rig — which matters less than
it sounds, since a camera integrates light differently from an eye and has no opinion about whether
a fade looks smooth.

Joe's brief on 2026-09-06 set the target: build the tests needed to fill the data gaps and learn his
taste, **so that tuning can stop needing his eyes on the strip for every change**. That means the
goal is not numbers but a **model that predicts his answers**. The quantisation probe is the
standard — a rule that got all 74 individual trials right rather than an average fitted to a cloud.
Block 9 (validation) is what would license tuning without him; **until it passes, the honest answer
to "can you tune this without me looking" is no**.

He approved running stimuli on his real strips on 2026-09-04: *"playing different staircases or
demos to gather data on what i do and dont like is a good idea. you can do it on my real lights"*.

## Load-bearing design decisions

Every one of these was paid for by a failure.

- **A floor calibration runs first, and everything is placed relative to it.** The first version
  hardcoded base levels at bytes 4-100; Joe ran it and reported *"for lots of the tests one or both
  is just leds off"*. Defining stimuli in commanded bytes removed the need to know *how much* light
  comes out; it did not remove the need for some to come out. `FloorFinder` walks a geometric ladder
  up (two marks: first visible, clearly lit) then one byte at a time back down. **Never reintroduce
  a hardcoded base level.**
- **The guard checks a live link, not just the Active Control flag.**
  `getCurrentlyControlledDeviceAddresses()` filters saved devices on `isActiveControlEnabled` and
  says nothing about whether a connection exists, so the lab's "can this run" check once passed with
  both strips disconnected. `perceptionTargetCount()` requires `bleGattTransport.isConnected` too. A
  sitting against a dark strip records answers about light he could not have seen — the exact
  failure the lab exists to replace.
- **A run is `(seed, config, answers)` and nothing else.** State is rebuilt by replaying answers
  from clean, so going back a trial, changing an old answer and resuming after a crash are one
  mechanism. The on-disk file cannot describe a state the code could not reach. Unwinding a
  staircase in place is what this avoids — it works until the step size changes underneath it.
  Battery blocks go further: their trial lists do not adapt at all, so a trial is a pure function of
  its index.
- **The seed is minted once per part-finished block and kept.** Re-minting on resume would
  regenerate a different trial list and silently attach existing answers to trials he never saw.
- **Catch trials, and anchor trials.** Catches carry no difference (the honest answer is the null
  one) and give the guessing rate. Anchors carry an unmissable difference and give the attention
  rate. Both are needed because "saw nothing all run" and "stopped watching" are otherwise the same
  record — and "saw nothing" is a legitimate outcome of most of these blocks.
- **Write cadence is equalised across both intervals.** A dithered interval sends ~100 writes and a
  plain hold sends one; comparing those lets the dithered interval be picked out by its write
  pattern rather than by any flicker. Comparison stimuli are `dither(duty = 0.0)` and
  `step(delta = 0)` — same writes, same moments.
- **Fixed ladders, read off directly, rather than adaptive staircases.** A staircase converges on
  whatever gets answered right 71% of the time, and a hardware grid does that with no eye involved —
  which is how the 2026-09-05 sitting's "3-byte threshold" happened. A fixed ladder preserves the
  raw pattern of yes and no, which a grid cannot disguise.
- **`LabTrial.truth` has three values, not two.** `KNOWN` (controls, brightness orderings —
  scorable), `UNKNOWN` (a one-byte step's visibility *is* the measurement and must never be scored
  as accuracy), `PREFERENCE` (taste, votes only). `init` enforces that `correctOptionId` is non-null
  exactly when `KNOWN`. Collapsing these is how the first sitting's fade block reported a preference
  as an accuracy.
- **Firmware brightness is borrowed, never set.** `setPerceptionBrightness` writes directly and
  touches no pref, so a crash leaves Joe's saved setting intact. Restoration runs from a
  `DisposableEffect` as well as from Close, because tapping a nav-bar tab drops the screen without
  calling `onClose` and would hand his strips back at 100%.
- **Results are one row per trial**, as JSON under `getExternalFilesDir(null)/perception/` (battery
  blocks under `perception/lab/`), so any analysis can be redone without another sitting.

## Three mistakes that are now tests, not comments

`LabBlocksTest` (23 tests). Each of these shipped once and cost a sitting or a wrong conclusion:

- **Nothing is commanded below the measured floor.**
- **Every dither pair straddles a grid boundary.** The first attempt compared bytes 5 and 6, which
  at 25% both emit level 2 — it measured nothing. Boundaries are found by searching the emitted-level
  rule, *not* from `LabContext.gridSpacing`, which is a rounded average: at 22% the real boundaries
  alternate 4 and 5 bytes apart and an averaged pair lands inside a level about half the time.
- **Rate trials pin both fade endpoints**, so what differs is update count and not final level.
  `PerceptionTrials.fadePlain` stops wherever it lands; `LabBlocks.fadeAt` does not.

## Sitting length

**~99 trials, ~11 minutes** for the old full sitting (simulated over 300 sittings, three staircases
at six reversals). The original design was a median of **195** trials, 25-35 minutes, which is what
Joe was asked to sit through the first time. Battery blocks are 3-6 minutes each and separately
runnable, which is the shape that replaced it.

## The battery

Blocks are in `LabBlocks.ALL`, in run order, reachable from **Settings > Experimental > Perception
Lab > Test battery**.

| # | id | gives | built |
|---|---|---|---|
| 0 | `floor_grid` | tonight's floor and grid spacing | yes |
| 1 | `scale` | smallest visible change at anchors 4-128 → a perceptual scale. Runs at 100% brightness | yes |
| 2 | `rate` | *(as designed, cannot work — see below)* | yes |
| 3 | `dither` | whether a boundary-straddling pair reads as an in-between level, and whether it flickers | yes |
| 4-9 | — | ambiance smoothing, jumps, near-black, two visualiser taste blocks, validation | no |

**Blocks 4-9 are listed in `LabBlocks.PLANNED` and deliberately unbuilt.** They are preference
measurements and want their stimuli spaced in *visible steps*, which is block 1's output; guessing
that spacing would repeat the hardcoded-base-level mistake one level up. A preference also needs
consistency repeats and transitivity checks — the first sitting's fade block was four trials and its
"result" should never have been quoted.

**Blocks 7-8 (visualiser taste) were explicitly licensed by Joe on 2026-09-06**, against CLAUDE.md's
standing "do not restart the visualiser work". The licence is for measuring taste, not for
re-opening beat detection.

**Block 2 needs rebuilding as a latency question.** As designed it asks which of two fades is
smoother at different update rates, and [battery-2026-09-06.md](battery-2026-09-06.md) shows that
cannot work: the fade's step count is set by the number of output levels it crosses, which is
identical at every rate. Rate's real payoff is latency, which nothing has asked about.
