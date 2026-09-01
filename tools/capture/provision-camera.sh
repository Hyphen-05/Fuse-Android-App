#!/usr/bin/env bash
# Installs Open Camera on the camera phone and grants it what it needs, so a capture session does
# not depend on anyone having prepared the phone beforehand.
#
# Joe's call, 2026-09-01: Open Camera, with the stock camera as a fallback for anything Open Camera
# cannot do — slow-mo above all, which is the one thing the P4 latency run wants most.
#
# What this CANNOT do: configure Open Camera. Its settings live in its own SharedPreferences and
# cannot be written from adb without root. ISO, shutter, white balance and focus are set by hand or
# by simulated taps, and a missed tap is the worst failure mode in the whole session because the run
# looks fine and the data is void. See camera-setup.md — every setting is read back before a run.
#
# Usage: provision-camera.sh <serial>
set -euo pipefail

ADB="${ADB:-C:/Users/attgm/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="net.sourceforge.opencamera"
SERIAL="${1:-}"

if [ -z "$SERIAL" ]; then
    echo "usage: $0 <serial>   (the CAMERA phone, not the driver)" >&2
    "$ADB" devices -l >&2
    exit 2
fi

WORK="${TMPDIR:-/tmp}/fuse-capture"
mkdir -p "$WORK"

echo "== Resolving the current Open Camera build from F-Droid"
CODE=$(curl -fsS --max-time 30 "https://f-droid.org/api/v1/packages/$PKG" \
    | sed -n 's/.*"suggestedVersionCode":\([0-9]*\).*/\1/p')
[ -n "$CODE" ] || { echo "could not read suggestedVersionCode from F-Droid" >&2; exit 1; }
APK="$WORK/${PKG}_${CODE}.apk"

if [ ! -s "$APK" ]; then
    echo "== Downloading versionCode $CODE"
    curl -fsSL --max-time 300 -o "$APK" "https://f-droid.org/repo/${PKG}_${CODE}.apk"
fi

# Recorded, not verified against a published hash: the v1 API does not carry one. What this buys is
# that a silently different APK on a later run is visible in the diff rather than invisible.
sha256sum "$APK" | tee "$WORK/${PKG}_${CODE}.sha256"
ls -l "$APK"

echo "== Installing on $SERIAL"
"$ADB" -s "$SERIAL" install -r -g "$APK"

# -g grants install-time what it can; these are the ones that matter and are cheap to re-assert.
# Failures are tolerated: a permission the platform does not define on this API level is not an error.
for p in android.permission.CAMERA android.permission.RECORD_AUDIO \
         android.permission.WRITE_EXTERNAL_STORAGE android.permission.READ_EXTERNAL_STORAGE; do
    "$ADB" -s "$SERIAL" shell "pm grant $PKG $p" 2>/dev/null || echo "   (skipped $p)"
done

echo "== Installed:"
"$ADB" -s "$SERIAL" shell "dumpsys package $PKG | grep -m1 versionName"

echo
echo "Next: settings cannot be set from here. Work through camera-setup.md and read each one back."
