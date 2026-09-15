# The power button

`ui/components/PowerButton.kt`, in `MainActivity`'s top bar. Read this before changing it.

## Why it exists

Joe, 2026-09-14: *"the power on/off button and animation and haptics need an overhaul they could be so
much nicer and more satisfying."* The old one was an `IconButton` whose background crossfaded over 300ms
and which gave the same `LongPress` buzz as every other button, on touch-down, both ways.

## First version (2026-09-15, `5a715e9` + `838f7fb`) — Joe's verdict

Built as: touch-down squash with a faint `PRIMITIVE_LOW_TICK`; **on** = circle morphs to a static
`MaterialShapes.Cookie9Sided` with a 40° twist, colour floods from the centre, a ring flies off, haptic
`QUICK_RISE` then `CLICK`; **off** = flood drains, cookie relaxes to a circle, `QUICK_FALL` then `TICK`.

Joe, 2026-09-15, on his Pixel:

- **Haptics: "isnt quite right for a switch, it should be more like a click or something."** The rise
  and fall primitives read as swells, not a switch. He wants a crisp click.
- **Off: "still too boring."**
- **On: "looks wrong."** The static cookie as the resting on-state is the wrong call.
- **"shape morphing is a good idea and looks nice, but maybe use those shapes as the transition, not the
  static states."** So both resting states should be plain (circle, presumably), and the expressive
  shapes should appear *only while switching* — morph through them and settle back.

Not yet changed in code. The static-cookie on-state is what is installed on his Pixel.

## Traps

- **It crashed on the Pixel and passed on the moto.** `Vibrator.vibrate` needs `android.permission.VIBRATE`;
  `View.performHapticFeedback` does not. The moto has no composition primitives, so it only ever ran the
  fallback. `VibratePermissionTest` now checks source against the manifest, and every `vibrate` is wrapped
  so a refusal falls back instead of crashing. **Haptic feel can only be judged on the Pixel.**
- **The moto's `screenrecord` drops most frames** of a sub-second animation; it confirms drawing, not
  motion. It did confirm the morph path is placed correctly (MaterialShapes polygons are normalised to the
  unit square: translate to centre, rotate, scale by size, translate −0.5).
- The tap is raw `pointerInput`, so the semantics block carries `role = Switch` and an explicit `onClick`
  action; keep both or TalkBack cannot flip it.
- First composition must not animate (the `settled` flag), or every launch plays a switch nobody pressed.
