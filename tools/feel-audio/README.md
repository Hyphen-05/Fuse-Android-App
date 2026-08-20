# Real music for the feel harness

The harness runs on synthetic audio by default (`OfflineAudio.syntheticTrack`,
`OfflineAudio.structuredTrack`), so it works with nothing installed and every run is identical.
Some questions need a real recording, and this is where that goes.

**The audio files here are never committed** — `.gitignore` excludes `*.wav`. Two reasons, both
sufficient on their own: they are Joe's own copyrighted music and this repo is public, and they are
several megabytes each.

## What real music is and is not good for

Use it for anything about *material*: how much macro-dynamic range a modern master actually has,
whether the show tracks a real arrangement, how a preset feels on the kind of thing someone will
actually play.

Do **not** use it for ground truth about structure. A downloaded song has no annotations, and the
sections you think you hear are not measurable. `structuredTrack` exists for that: its
intro/build/chorus/breakdown/chorus boundaries are known to the second because they were generated,
which is why `MusicalContextTest` asserts against it and not against a recording.

What real music settled, 2026-08-19: this single's loudness range is **~8 dB** across 90s (5th to
95th percentile of a 2s-smoothed envelope). Modern masters are limited hard enough that there is far
less macro-dynamics to follow than the Tier D writeup assumed — the visualiser work has to earn its
contrast from a narrow input, not a wide one.

## Making a file

Any mono 16-bit PCM WAV at 44.1kHz works; `OfflineAudio.readWav` also accepts stereo and averages
it. 90 seconds is plenty and keeps the run quick.

There is no ffmpeg on this machine, but a pip package ships one:

```bash
pip install imageio-ffmpeg
```

```bash
python -c "import imageio_ffmpeg,subprocess; subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-y','-i','SONG.mp3','-t','90','-ac','1','-ar','44100','-sample_fmt','s16','tools/feel-audio/almost-there.wav'])"
```

Name it `almost-there.wav` or change the path in `MusicalDynamicsTest`. Tests that need it call
`assumeTrue(wav.exists())`, so the suite passes without it — they simply skip, and the numbers they
print are the ones worth reading when it is present.

Note the working directory: unit tests run from `app/`, so the path from a test is
`../tools/feel-audio/...`.
