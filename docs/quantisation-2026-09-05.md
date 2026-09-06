# The strip quantises, and the firmware brightness sets how coarsely

Quantisation probe, run by Joe on his own strips on 2026-09-05, at the brightness he actually uses.
Raw data: `tools/perception/results/probe_1788630240972.json`. Re-read it with
`python tools/perception/analyse.py tools/perception/results/probe_*.json`.

**Joe raised this hypothesis himself** after the 2026-09-05 sitting, from what the trials *felt*
like: *"i either could very clearly tell something changed or nothing at all, no like just about.
is it possible the leds phsycially only change every 2.5 bytes or something like that"*. He was
right, and the number is 4.

## The result

**Confirmed, and exactly.**

| segment | brightness | one-byte steps | seen as changed | spacing |
|---|---|---|---|---|
| low (bytes 5-29) | 25% (his own) | 24 | 6 | **4, 4, 4, 4, 4** |
| high (bytes 48-68) | 25% (his own) | 20 | 5 | **4, 4, 4, 4** |
| low again (bytes 5-21) | 100% | 16 | **16** | **1** every time |

Controls clean: **0 of 6** no-change trials called changed, **0 of 8** obvious changes missed. Both
failure modes the design guards against are absent, so both readings above stand.

The changes land on bytes 8, 12, 16, 20, 24, 28 and 48, 52, 56, 60, 64 — every multiple of four in
the range walked, and nothing else. There is no scatter at all.

## The model, and it is not a fit

    emitted level = ceil(commanded byte × brightness percent / 100)

**All 74 trials agree with it, including every anchor and every catch.** Not 73. This is not a
curve fitted to a cloud of answers; it is a rule that predicts each individual answer, and it was
checked that way (`QuantisationProbeTest` has the synthetic-viewer version of the same check).

Two consequences of the arithmetic:

- **At 25% the strip has 64 distinct output levels, not 256.** Bytes 1-4 all emit level 1, bytes
  5-8 all emit level 2, and so on to byte 255 emitting level 64.
- **A brightness that does not divide 100 gives *uneven* rungs.** At 25% the spacing is a clean 4
  everywhere. At 22% it alternates 4 and 5, at 30% it alternates 3 and 4. Irregular steps read
  worse than regular ones, so the brightness setting changes not just how coarse the ladder is but
  how *even* it is.

Note the file records **25%**, not the 22% CLAUDE.md had been carrying. Whichever it was before, the
setting is part of the result and is written into every probe file for that reason.

## What this explains

**The 2026-09-05 sitting's ~3-byte "threshold" was this grid, not Joe's eye.** A 2-down/1-up
staircase against a 4-byte grid settles near 3 commanded bytes with no perceptual threshold
involved at all, which is why the figure came out *flat* across bases 5, 10 and 25 where a real
threshold would have scaled with level. That number is now retired as a measurement of him.

**The dither block measured nothing, as suspected.** It dithered between the floor byte and the one
above it — bytes 5 and 6 at 25%, which are `ceil(1.25) = 2` and `ceil(1.5) = 2`. The same emitted
level. A dithered "alternation" that emits one steady level is indistinguishable from a steady
level because it *is* one, so 15 of 16 "can't tell" answers were correct and told us nothing about
dithering.

**And it is the shape of the steppiness.** Every smoothing thing the app does works in commanded
bytes: `AmbianceOutputRules.floorRamped` fading the floor in, `AmbianceOutputInterpolator`'s
half-life ease, plain rounding down a fade. At 25% three of every four writes those produce change
nothing at all and the fourth moves a whole rung. Ramping the floor from byte 3 to byte 14 emits
levels 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4 — **four distinct levels out of twelve steps**, which is a
lurch dressed up as a ramp.

## What it does NOT support — a prediction to retract

The handoff and CLAUDE.md both carried this consequence: *deep dimming should run colour bytes low
at high firmware brightness, rather than running the brightness slider low.* **That does not follow
from the model, and it buys nothing.**

At 25% the reachable emitted levels are 1 to 64. At 100% they are 1 to 255. **The dark end is the
same set either way** — to keep the same appearance at 100% the app must send a quarter of the
byte, which lands on the same emitted level it was already reaching. Byte 20 at 25% and byte 5 at
100% are both emitted level 5, and a fade between two fixed light levels crosses exactly the same
rungs at either setting.

What high brightness actually buys is **headroom**: a maximum of level 255 instead of level 64.
It does not add a single rung below a given light level, because there are none to add — the
hardware has 255 emitted levels in total and the dark ones are levels 1, 2, 3 whatever the slider
says.

**Anyone reading the old wording should stop there.** It was a plausible-sounding step that the
measurement does not license, and it would have been a visible change to Joe's lights made on a
derivation. His standing rule applies: ask the wall, not the arithmetic.

## What does follow, and is worth building

Untested proposals, listed so the next session does not have to re-derive them — **none of these
have been near the wall.**

1. **Make the app brightness-aware.** It currently assumes a 256-rung ladder it does not have. If
   the emitted level is what matters, the app can compute in emitted levels and convert once at the
   edge, instead of smoothing in a space where three quarters of its output is discarded.
2. **Dithering has to straddle a boundary to exist.** Alternating bytes 20 and 21 at 25% alternates
   emitted levels 5 and 6 and really does average between them; alternating 21 and 22 does nothing.
   The pairs are `4k` and `4k+1`, derivable from the brightness. **Dithering has still never been
   tested on this hardware** — the one attempt tested a pair that could not work.
3. **A brightness that divides 100 gives even rungs.** 25%, 20% and 50% do; 22% and 30% do not.
   Whether an even ladder looks better than an uneven one at the same coarseness is a question for
   Joe's eyes, and it is cheap to ask.

## Caveats

- **Two brightness settings, not a sweep.** 25% and 100% both fit `100/B`, which is two points on a
  line. The rule is exact where it was measured; a third setting would make it a law.
- **One sitting, one evening, one observer.** The controls say this sitting is clean, not that it
  repeats. It is, however, a much stronger kind of result than the thresholds it replaces: it
  predicts individual trials rather than an average, and 74 for 74 is not something a lucky run
  produces.
- **This says nothing about how emitted level maps to light.** That is `StripResponse`'s LUT, which
  was measured at brightness 100% and is unaffected — the multiply happens before it, not after.
  What has changed is that the app now knows *which* entries of that LUT it can actually reach.
