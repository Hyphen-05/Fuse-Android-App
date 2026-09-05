# The first Perception Lab sitting — 2026-09-05

Joe, his own strips, his own room, brightness where he normally runs it. 76 trials, about eight
minutes. Raw file: `tools/perception/results/perception_1788616857094.json`; re-read it with
`python tools/perception/analyse.py tools/perception/results/perception_*.json`.

This is the first number in this project that came from measuring the *viewer* rather than the
strip.

## The sitting is trustworthy

**20 catch trials, 20 "can't tell". A false-positive rate of zero.** On a catch trial both
intervals are byte-for-byte identical and there is nothing to find; anything but "can't tell" is
somebody picking a side. He picked none. Nothing below is discounted by guessing, which is the one
thing that could have made the whole sitting worthless.

Median response 1.8s, so he was answering deliberately rather than clicking through.

## His floor: byte 5

First visible **byte 5**, clearly lit **byte 5** — the two marks landed on the same rung, so the
strip goes from nothing to properly-on inside one step of the ladder at his brightness. Bytes 1-4
emit nothing he can see.

That retires the question the hardcoded levels got wrong: the original design's base levels of 4
and 8 straddled the edge of visibility, and its dither and fade blocks both ran entirely at byte 4
— below it.

## Step visibility: about three bytes, flat across the range

| base byte | smallest change he spotted | as a share of the base |
|---|---|---|
| 5 | 3.5 bytes | 70% |
| 10 | 2.5 bytes | 25% |
| 25 | 3.5 bytes | 14% |

**Roughly three commanded bytes, and near-constant in absolute terms rather than proportional.**
That is the number the whole exercise was after.

### This is probably not a perceptual threshold at all

**Read the table again: the threshold is flat.** A real detection threshold follows roughly Weber's
law and scales with the level — at bases 5, 10 and 25 that predicts something like 1, 2 and 5
bytes. A constant ~3 bytes is not what an eye does. It is what a **grid** does.

Joe said so unprompted after the sitting, and his phrasing is the diagnostic one:

> *"i either could very clearly tell something changed or nothing at all, no like just about."*

A perceptual threshold has a "just about" region by definition — that is what a threshold *is*. He
reports none.

**The hypothesis: the firmware quantises the emitted level, and his brightness setting sets how
coarsely.** If it multiplies colour byte by brightness and keeps an integer, then at his **22%** the
emitted level only changes every `1 / 0.22 ≈ 4.5` commanded bytes. A step of `d` bytes crosses a
boundary with probability about `d × 0.22`; a 2-down/1-up staircase converges where he is right
about 71% of the time, so it should settle at `d ≈ 3.2`. **Measured mean: 3.17.**

It also predicts the binary phenomenology exactly: either the step crosses a boundary — a whole
increment, which near the floor is a large jump — or it changes nothing whatsoever.

So the reading in the first version of this document, that steppiness *cannot* be quantisation, was
**wrong and is retracted**. On this hypothesis it is quantisation — not of the byte we command, but
of the level the firmware emits, coarsened about 4.5x by the brightness setting. At 22% roughly
**56 of 256 levels survive**.

The fit uses one free parameter (his brightness) and was made after the fact, so the arithmetic
agreement is suggestive rather than conclusive. **The flatness is the stronger evidence**, because
it is a qualitative prediction that does not depend on the fit at all.

### The experiment that settles it

Not another staircase — staircases are what disguised this as a threshold in the first place. A
**direct quantisation probe**:

- Hold a base level, step the commanded byte up **one at a time**, and after each ask only "did
  anything change?".
- Record which increments produced a change. **The spacing between the yes answers is the grid**,
  read straight off with no model in between.
- Then run the identical probe at **100% firmware brightness**, which the app can set itself with
  `DuoCoProtocol.createBrightnessCommand`. If the spacing collapses toward one byte there, the
  integer-multiply hypothesis is confirmed and its arithmetic is known.

**Restore his brightness afterwards** — it is his setting, not the probe's.

If it confirms, the consequence is a real product change: **deep dimming should run the colour bytes
low at a high firmware brightness, rather than running the brightness slider low**, which is the
opposite of how the app is used today. That is a change to how his lights behave and needs his
agreement on the wall, not a derivation.

## Dithering: invisible, and not preferred

**15 of 16 dither trials answered "can't tell"**, at every write interval from 20ms to 100ms. He
cannot pick the dithered interval out from a steady one. So dithering does **not** read as flicker
on this hardware at any rate tested — the risk the block existed to check is not there.

But the fade block, which asks the question that matters:

**Three decisive answers, all three picked the plain fade as smoother.** (A fourth was "can't
tell".)

Those two results are compatible: a dithered hold is indistinguishable from a steady one, while a
dithered *fade* still lost. So the honest reading is **no evidence dithering helps, and weak
evidence it hurts.** Three answers is not enough to conclude against it, and the fade block is the
one to lengthen in the next sitting — but it is certainly not a green light to ship dithering, and
`PerceptionTrialsTest`'s argument that dithering pays over slow dark content now has a measurement
pointing the other way.

## What the next sitting should change

0. **Run the quantisation probe first** (design above). Until it is known whether the ~3-byte
   threshold is his eye or the firmware's grid, every other number here is of uncertain meaning —
   including the dithering result, since dithering between two commanded bytes that map to the
   *same* emitted level would do nothing at all, which would explain the fade block's outcome
   without any perceptual claim.
1. **More fade trials.** Four is too few for the block whose answer decides the most. It was sized
   when the sitting was 195 trials long and needed trimming everywhere; at 76 there is room.
2. **A base level above 25.** All three thresholds came out near three bytes with no sign of
   turning proportional, and 25 is the top of the range tested. Whether that flatness continues
   changes how a correction should be shaped.
3. **Repeat it.** One sitting, one evening, one state of dark adaptation. The zero false-positive
   rate says this one is clean, not that it is repeatable.
