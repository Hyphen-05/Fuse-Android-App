# The Perception Lab — how it is built, and why each piece is that way

Read this before changing anything under `core/perception/`, `core/perception/lab/`,
`ui/components/PerceptionLabScreen.kt` or `ui/components/LabBatteryScreen.kt`.

Results and what they concluded live elsewhere: [perception-2026-09-05.md](perception-2026-09-05.md)
(first sitting), [quantisation-2026-09-05.md](quantisation-2026-09-05.md) (the grid rule),
[battery-2026-09-06.md](battery-2026-09-06.md) (blocks 0-3),
[taste-2026-09-06.md](taste-2026-09-06.md) (blocks 4-6).

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
Block 10 (validation) is what would license tuning without him; **until it passes, the honest answer
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
- **Mirrored repeats, on every taste block.** A consistency repeat is the same comparison with the
  intervals swapped, never a fresh randomisation — half of those would land in the original order
  and control for nothing. The swap makes each repeat a three-way diagnostic: same arm is a
  preference, same interval letter is an order effect, neither is inconsistency. **On a taste block
  this is a better instrument than the catch trial**, because a catch cannot tell inattention from a
  habit of breaking ties by position, and Joe has that habit — 6 of 6 decisive catch answers named
  the second interval while real comparisons tracked the arm 6 of 7 times across a swap.
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

## Four mistakes that are now tests, not comments

`LabBlocksTest` (47 tests). Each of these shipped once and cost a sitting or a wrong conclusion:

- **Nothing is commanded below the measured floor.**
- **Every dither pair straddles a grid boundary.** The first attempt compared bytes 5 and 6, which
  at 25% both emit level 2 — it measured nothing. Boundaries are found by searching the emitted-level
  rule, *not* from `LabContext.gridSpacing`, which is a rounded average: at 22% the real boundaries
  alternate 4 and 5 bytes apart and an averaged pair lands inside a level about half the time.
- **Rate trials pin both fade endpoints**, so what differs is update count and not final level.
  `PerceptionTrials.fadePlain` stops wherever it lands; `LabBlocks.fadeAt` does not.
- **A block that builds stimuli at 100% pins `LabTrial.brightnessPercent` on every trial.** The
  runner drives firmware brightness *per trial* from that field, so leaving it null plays a
  100%-context stimulus at the viewer's own setting and divides every emitted level by four. That
  spoiled block 6 on 2026-09-06: its lifted arm was meant to sit above level 64 and arrived at 24.
  The test asserts both directions — a `commandsBrightness` block pins 100, and one that is not must
  pin nothing.

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
| 2 | `rate` | **retired** — as designed it cannot work, see below | n/a |
| 3 | `dither` | whether a boundary-straddling pair reads as an in-between level, and whether it flickers | yes |
| 4 | `smoothing` | preferred settling speed when the picture changes — the 2026-09-04 number | yes |
| 5 | `jumps` | how big a change has to be before a cut beats an ease | yes |
| 6 | `near_black` | where the floor sits, and whether dim scenes should be lifted into the smooth region | yes |
| 7 | `ambiance_fall` | whether removing the smoother's downward bias makes a dark scene steadier | yes |
| 8-10 | — | two visualiser taste blocks, then validation | no |

**Blocks 4-6 were built and run on 2026-09-06.** Results and their caveats:
[taste-2026-09-06.md](taste-2026-09-06.md) — block 4 answered (the shipped 50ms ease is right, and
only in the dark does speed matter), block 5 suggestive, block 6 spoiled by a brightness bug, fixed
and re-run on 2026-09-07 with the answer that **the lift loses**. They are preference measurements and
differ from blocks 0-3 in three ways that are all enforced by `LabBlocksTest`:

- **`truth = PREFERENCE`, never scored.** The first sitting's fade block scored "picked the dithered
  fade" as correct, which reported a taste as an accuracy.
- **Consistency repeats**, placed at the end of the block rather than beside the original — asked
  back to back they would measure memory of the last answer instead of agreement.
- **Transitivity checks.** A over B, B over C, C over A does not mean a bad answer; it means the arms
  are not on one axis, so no single number can summarise them.

`LabAnalysis.preferenceReading` reports all three as health numbers *before* any ranking, and
`trustworthy` is false unless consistency ≥ 0.75, violations = 0 and "can't tell" ≤ 50%. That last
gate is block 2's failure written down: 10 "can't tell" out of 15 read naively as "one rate is as
good as another" and actually meant none of them were any good.

Their stimuli are spaced in **visible steps** via `VisibleScale`, which carries block 1's measured
table. Two caveats travel with every number it produces: one observation per cell, and the low
anchors are floored by the instrument (a measured threshold of 1 means "one output level *or
less*"), so below level 32 the scale under-counts visible steps by an unknown amount.

**Block 6 is the live taste question**, and it must be put as a trade rather than a fix. Blocks 0-3
concluded a smooth dark fade is not achievable on this hardware; the only remaining route to one is
to stop making the scene dark. Its lifted arm maps dim content above level ~64 and its cost is
exactly the thing it buys. Joe rejected a superficially similar proposal on 2026-09-04 — but that
one was arithmetic telling him what his LEDs looked like, and this one shows him both and asks. The
whole block runs at 100% firmware brightness because at his 25% the lifted arm is unreachable; the
faithful arm is unaffected, since emitted level 8 is the same light however it was commanded.

**Block 7 replays recorded ambiance output rather than constructing a stimulus** (built
2026-09-09), and is the first block whose two arms are a *rule* difference rather than a parameter
difference. `AmbianceVideoBench` runs the shipped `AmbianceFrameAnalyser` over real film frames and
emits `AmbianceTraces.kt`; the block plays the two traces back to back and asks which was steadier.

Three things about it are load-bearing:

- **Both arms come from the same frames**, so they are the same scene rendered two ways, carry the
  same number of writes at the same moments, and cannot be told apart by cadence. That control had
  to be designed into blocks 3 and 4 and here it is free by construction.
- **The excerpts are the worst four seconds under the shipped rule**, chosen by counting emitted
  level reversals. Picking the stretch where the complaint lives is the point; picking it by eye
  would have been picking the answer.
- **It pins no brightness**, because the traces are commanded bytes computed with no brightness
  assumption and the complaint is about ordinary viewing. It is the mirror of block 6's bug: that
  one needed 100 and pinned nothing.

It also introduced colour to the stimulus vocabulary — `StimulusStep.rgb`, null everywhere else. A
dark scene wobbles in hue as well as in level, and grey would have thrown away half of what is being
judged. The grey `byte` still carries the brightest channel so the floor guard reads something real.

**Blocks 8-10 remain unbuilt.** 8 and 9 need a running visualiser to modulate, which is more wiring
than the steady-level stimuli everything so far has used. 10 scores the model blocks 4-7 produce, so
there is nothing for it to predict until they have run. They shifted up by one when block 7 was
added, which is free only because none of them has ever run — renumbering a block with results would
silently rename its data, which is why block 2 keeps its slot despite being retired.

**Blocks 8-9 (visualiser taste) were explicitly licensed by Joe on 2026-09-06**, against CLAUDE.md's
standing "do not restart the visualiser work". The licence is for measuring taste, not for
re-opening beat detection.

**Block 2 is retired in place, and still needs rebuilding as a latency question.** As designed it
asks which of two fades is smoother at different update rates, and
[battery-2026-09-06.md](battery-2026-09-06.md) shows that cannot work: the fade's step count is set
by the number of output levels it crosses, which is identical at every rate.

It keeps position 2 in `LabBlocks.ALL` — renumbering to close the gap would silently rename block
3's results files — and carries `retiredBecause`, which the menu renders instead of a Run button.
A retired block left runnable is worse than a deleted one, because the answers it collects look like
data.

Rate's real payoff is **latency**, which nothing has asked about. The obstacle is that latency needs
a reference event to be judged against: on a strip alone there is nothing to be late *relative to*.
The obvious source is the phone itself — flash the screen at the moment the write goes out and ask
whether the strip lagged it — which is a new stimulus shape rather than a new ladder, and is why
this was not simply rewritten alongside blocks 4-6.
