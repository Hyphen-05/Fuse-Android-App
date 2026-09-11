# The connecting indicator and the connect reveal

Read before touching `ConnectionStatusSurface`, the `huntingDevice` / `waitingForPartner` logic in
`MainActivity`, or HomeScreen's tiles row and `connectReveal`. Signed off by Joe on his Pixel on
2026-09-11 ("its great") after four rounds, three of which were wrong diagnoses.

## What it does

- **The indicator stays up until the first strip is usable**, meaning `uiState` says `CONNECTED`,
  not until the radio links.
- **If a second auto-connect strip already has a GATT link** when the first becomes usable, the page
  holds for it: the indicator stays up and Home keeps showing its disconnected state, capped at
  `partnerWaitMs` = 1s. Both tiles then arrive together. The hold is latched per connect
  (`revealedFor`), so a strip that links *after* the reveal cannot pull the page back under the
  indicator. A strip that is only *trying* to connect is never waited for.
- **The indicator's exit** runs on `MotionScheme.expressive()`: `fastEffectsSpec` for alpha (no
  bounce, since a bouncing opacity reads as flicker) and `fastSpatialSpec` for scale.
- **The reveal** is `Modifier.connectReveal(revealAt, index)` in HomeScreen.kt: fade
  (`defaultEffectsSpec`) plus a 24dp rise (`defaultSpatialSpec`), with each piece 50ms behind the
  one above it. Order: tiles 0, power-off hint 1, CCT 2, brightness 3, colour 4, scene rows 5+. It
  plays only for a connect that happens while Home is already on show. A cold launch, a return to
  the tab, and anything first composed more than 1s later all draw at rest.
- **If the second strip is later than the hold**, the first tile lands at full width and springs to
  half as its partner fades in. Both widths come from one `split` Animatable read in the layout
  phase. The arriving tile is measured at its final half width and clipped to its growing slot.

## Traps, each paid for

1. **Two clocks for "connected".** `handleConnectionStateChange` calls `connectionManager.setConnected`
   at GATT link-up. `uiState` turns `CONNECTED` only in `onDuoCoCharacteristicRegistered`, after MTU
   exchange and service discovery. HomeScreen's deck is gated on `uiState`. Anything that decides
   "is there something to show" must read `uiState`; `connectionManager` is still the only source
   for "this disconnect was the user's". Reading the earlier clock produced "animation ends, half a
   second of nothing, UI pops in". The first fix added an *earlier* exit and left the old
   `ConnectionState.Connected -> false` clause, so the gap survived untouched until Joe suggested
   stopping the animation on UI load instead.
2. **Animate drawing, never layout.** `animateItem` with a placement spec sent the scene rows about
   1000px down when the deck appeared, and a spring over that distance looks like a glitch at any
   speed. Everything is in `graphicsLayer`, and layout lands in one frame.
3. **Do not reserve an empty slot for a strip that is still linking.** It was built and Joe rejected
   it: it reads as a hole for up to a second. The hold plus split replaced it.
4. **There is no official Material 3 Expressive "completion" for `LoadingIndicator`.** Checked in
   the AARs for 1.5.0-alpha25 and alpha27: indeterminate and determinate-with-progress, nothing
   else. Joe's rule was to use one only if it officially exists, so do not hand-roll one.
   `MotionScheme` does exist, but the app theme does not set it. The code uses
   `MotionScheme.expressive()` locally on purpose; passing it to `MaterialTheme` would retime every
   M3 component, which is a separate decision.
5. **Debug builds are not a fair test of frame timing.** They have no R8, no baseline profile, and
   `debuggable=true`. Release is installable from a dev machine now: `app/build.gradle.kts` signs it
   with the debug key when `my-upload-key.jks` is absent. That APK is not distributable.

## Known and not addressed

- **The late-arrival split has never been watched on hardware.** Both recorded connects landed
  inside the hold, 341ms and 315ms apart.
- **A 50–80ms blink.** The Disconnected card leaves about two frames before the tiles start fading
  in, while the indicator is still up. A crossfade was offered; Joe has not asked for it.
- **More than two strips:** the `LazyRow` branch has no reveal and no split.
- **A strip disconnecting** snaps the remaining tile to full width.
- **One run on the moto stalled ~170ms mid-reveal; a later run did not.** Unexplained, and likely
  just the moto.

## Verifying on hardware

The moto has Joe's two strips saved with auto-connect, so a cold launch there reproduces a real
connect, but only while his Pixel is not holding them. Force-stop Fuse on the moto afterwards; that
is Joe's standing rule.

`tools/capture/record-launch.sh <outdir> [seconds]` force-stops, records the screen through a cold
launch, pulls the clip and splits it. Then read, in order: `overview.png` (2fps) to find the reveal;
`ffmpeg ... select='gt(scene,0.02)',metadata=print` for its timestamps; a 30fps `tile=6x6` sheet of a
1.2s window; and `showinfo` with `-fps_mode passthrough` for the real capture times. `screenrecord`
writes only frames that change, so a gap there means the app drew nothing new. ffmpeg comes from
`python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"`.
