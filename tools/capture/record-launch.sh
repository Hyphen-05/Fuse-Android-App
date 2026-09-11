#!/usr/bin/env bash
# Kept from the 2026-09-11 connect-reveal work; see docs/connect-reveal.md for how to read the output.
# The caller claims the moto lock, and must force-stop Fuse and release the lock afterwards, ideally
# with a trap on EXIT: the moto has Joe's real strips saved with auto-connect.
# Cold-launch Fuse on the moto while recording the screen, then split the clip into frames. The
# moto's saved strips auto-connect on launch, so the connect (and the reveal) plays on Home by itself
# -- but only when Joe's Pixel is not holding the strips; otherwise nothing connects.
set -u
ADB=/c/Users/attgm/AppData/Local/Android/Sdk/platform-tools/adb.exe
SER=ZY22H38QWD
PKG=com.github.hyphen05.fuse
OUT=${1:?output dir}
LIMIT=${2:-40}
FFMPEG=$(python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())")

mkdir -p "$OUT/frames"
"$ADB" -s $SER shell "input keyevent KEYCODE_WAKEUP"
"$ADB" -s $SER shell "am force-stop $PKG"
"$ADB" -s $SER shell "rm -f '/sdcard/fuse_reveal.mp4'"
"$ADB" -s $SER logcat -c

# Record in the background, launch once it is running, then wait for the time limit.
"$ADB" -s $SER shell "screenrecord --bit-rate 8000000 --time-limit $LIMIT '/sdcard/fuse_reveal.mp4'" &
REC=$!
"$ADB" -s $SER shell "sleep 1"
"$ADB" -s $SER shell "am start -n $PKG/com.example.MainActivity"
wait $REC

# MSYS_NO_PATHCONV=1 stops Git Bash rewriting the remote path, but it also stops it converting the
# local one, and adb.exe cannot write to a /c/Users/... path. So hand it a Windows-form local path.
MSYS_NO_PATHCONV=1 "$ADB" -s $SER pull "/sdcard/fuse_reveal.mp4" "$(cygpath -m "$OUT")/reveal.mp4"
"$ADB" -s $SER shell "logcat -d" | grep -E "Connected \(Simulated\)|locating disconnected|Found write" | tail -5

# 10fps overview sheet to locate the connect, plus every frame at 30fps for close reading.
"$FFMPEG" -loglevel error -y -i "$OUT/reveal.mp4" -vf "fps=2,scale=180:-1,tile=10x8" -frames:v 1 "$OUT/overview.png"
"$FFMPEG" -loglevel error -y -i "$OUT/reveal.mp4" -vf "fps=30,scale=360:-1" "$OUT/frames/%04d.png"
ls "$OUT/frames" | wc -l
