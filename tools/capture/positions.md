# Positions — worked out against the actual fixtures

The plan's positions were written before anyone had looked at the hardware, and they assume a linear
strip aimed at a plain wall. That is not what is on the wall. Written 2026-09-01 from a photo taken
on the Pixel 11; every distance here is **estimated from that photo** and worth a tape measure before
the rig is taped down.

## What is actually there

**Device A — the starburst.** A red hub with roughly fourteen short arms radiating from it, each
about six to eight LEDs, mounted flat on the wall facing into the room. One arm continues as a
**long vertical strand** running about 0.9m down the wall to the connector and the mains adapter.
Full span across the arms is roughly 1m; the photo cuts off its left edge, so that is a floor.

**Device B — the two bars.** Two slim vertical bars flanking the window, **one device between them**
(Joe, 2026-09-01). They are the only fixtures here that wash a wall rather than face the room —
there is a clear glow spilling onto the blind and the reveal around each one.

## What that changes

**P1's premise is gone for the starburst.** There is no lit wall patch to film: the arms point at the
camera. Filming emitters directly rather than a diffuse patch makes **clipping** the dominant risk,
not an edge case — a saturated pixel has no intensity structure left, and at these angles the LEDs
will clip long before the wall would have.

So P1 splits in two:

- **P1a — the bars, for anything photometric.** They wash the blind and reveal, which is the diffuse
  patch the plan was written around. `brightness_ramp`, `colour_primaries`, `cct_sweep`,
  `brightness_x_colour` belong here.
- **P1b — the starburst, filmed head-on**, for everything that classifies rather than measures
  absolute level: `spacing_staircase`, `control_bursts`, `transition_probe`.

**Mode Capture cannot sample the starburst.** `ModeCaptureCameraSource` samples along a straight line
between two endpoints set on the preview and reads nothing else — everything off that line is
invisible to it. A line across the starburst crosses the hub and two opposite arms: a dozen LEDs out
of roughly a hundred, with bare wall between them dominating the samples.

**And straightness is not the real requirement — wiring order is.** The protocol has no per-LED
addressing; the modes are animated by the strip's own firmware, which addresses LEDs by their index
along the wired chain. A line that follows *consecutive* LEDs reads the chain in order and shows a
chase travelling the way the firmware meant it. A line across the arms samples indices from all over
the chain in an order that has nothing to do with the wiring, so a smooth animation comes back
scrambled — worse than a short sample, because it looks like data.

**Confirm it on the day rather than assuming it.** Run a mode with an obvious chase and watch the
strand: if the movement travels smoothly along it, the strand is consecutive and the sample is
sound. If it jumps about, it is not, and the bar becomes the only usable line.

The fix is to give it one:

- **Sample along the long vertical strand.** It is part of device A, it is straight, it has an even
  pitch, and it is long enough for a spatial mode to be visible along it. This is the best target in
  the room for the line sampler.
- **Or sample along one bar**, for device B. Clean, but it is half a device — the mode's animation
  spans both bars, and one line can only cover one.

**Film wider than you sample.** The Pixel 9 should frame the *whole* starburst for device A, and
*both* bars for device B, even though the sampler only sees a line. Mode Capture's own sampling has
never been verified; if its endpoint mapping or its density is wrong the on-device JSON is wrong, and
the video is the only thing that still holds the truth. Framing both bars also answers P5 — do the
two stay in step or drift — in the same footage, for free.

## Both phones need to see the LEDs at once

This is easy to get wrong. During Mode Capture the Pixel 11 is not only the driver: it is the
*sensor*. Its camera has to be on the strand or the bar with the endpoints set, while the Pixel 9
films the whole fixture from beside it. Two cameras, one subject, neither in the other's frame.

For every other sequence the Pixel 11 only needs to be in the foreground and untouched — except P4,
where its screen faces the Pixel 9.

## Distances and lenses

Assuming the Pixel 9's main camera at 1x (about 73 degrees across the frame):

| Position | Subject | Lens | Distance |
|---|---|---|---|
| P1a photometric | one bar plus the wall it washes | 1x | ~1.0m |
| P1b / P6 | whole starburst, ~1.2m of framing | 1x | **~0.8m**, square to the wall |
| P6 line | the vertical strand, ~1.1m of framing | 1x, portrait | ~0.8m |
| P2 macro | 3-5 LEDs, ~150mm of framing | **5x tele** | **~0.5m** |
| P3 PWM | the vertical strand | 1x | ~0.8m, swept horizontally |
| P4 latency | Pixel 11's screen and a fixture together | 1x, stock 240fps | as close as gets both sharp |

**Stay on 1x.** The ultrawide and the 5x have their own vignetting, distortion and colour response,
so a run that switches lenses is not comparable with itself. The one deliberate exception is P2,
where the main lens cannot focus close enough to fill a frame with five LEDs — reach with the
telephoto from 0.5m rather than shoving the ultrawide in at 10cm, which buys barrel distortion
exactly where per-LED geometry is the measurement.

**P3 sweeps horizontally**, because the strand is vertical. The rule is across the strip, never along
it: on its own axis, LED pitch and PWM chopping land on the same axis and cannot be told apart.

## Vignetting — the white card now has two jobs

The starburst fills the frame corner to corner, so arms near the edges read dimmer than arms near the
hub *because of the lens*, not the LEDs. On `colour_primaries` and `brightness_x_colour` that is a
systematic error sitting right on top of the quantity being measured.

The plan already says to film a white or grey card at each position. **Fill the frame with it**, at
the exact settings the run will use — that frame is a flat-field, and it is what makes the correction
possible afterwards. Failing that, keep whatever is actually being measured in the middle third.

## The room, from the photo

- **Walls are white and close.** Inter-reflection lifts the black floor, which is precisely what
  `dark_ramp` is trying to measure. Expect the bottom of the range to read high; a dark cloth on the
  facing wall, or an angle with less wall in frame, is worth the two minutes.
- **The blind.** Roller blinds leak down both edges. Check it after dark with the lights off before
  trusting any blackout run — "low" is not the requirement, "low and constant" is.
- **Kill the other emitters.** The fan on the shelf has a blue indicator LED, the monitor is in
  frame bottom-right, and a phone was face-up on the desk with its screen on. All three are in shot.
- **The curtain pole is specular** and will carry a reflection of whatever the strip is doing. Keep
  it out of frame or expect a second, dimmer copy of the signal in the video.
- **Mains is bottom-left**, which is where the strand ends. Route the phone chargers so nobody has to
  step over a cable in a dark room during a 35-minute unattended run.
