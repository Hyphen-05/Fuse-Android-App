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

### What it implies, and how far to trust the implication

If three bytes is the threshold at base 5, then a **one-byte** change down there is comfortably
invisible — so *dark-scene steppiness cannot be one-byte quantisation*. Whatever he is seeing when
he calls a dark scene steppy must be a jump of three bytes or more.

That points away from quantisation and toward whatever in the Ambiance path emits multi-byte jumps:
`AmbianceOutputRules.floorRamped` and the interpolator's ease. **It is an inference from his
thresholds, not something he reported**, so it is a lead to check against the wall, not a
conclusion — but it is the first evidence-backed lead this problem has had.

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

1. **More fade trials.** Four is too few for the block whose answer decides the most. It was sized
   when the sitting was 195 trials long and needed trimming everywhere; at 76 there is room.
2. **A base level above 25.** All three thresholds came out near three bytes with no sign of
   turning proportional, and 25 is the top of the range tested. Whether that flatness continues
   changes how a correction should be shaped.
3. **Repeat it.** One sitting, one evening, one state of dark adaptation. The zero false-positive
   rate says this one is clean, not that it is repeatable.
