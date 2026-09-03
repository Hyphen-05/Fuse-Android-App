#!/usr/bin/env bash
#
# The whole capture session, unattended.
#
# Joe's part is camera positioning and nothing else: prop the driver facing the strip, prop the
# camera phone facing the strip, plug both in, close the curtains, run this. It starts and stops
# the recording, runs every sequence, pulls the files between phases and checks each one arrived.
#
# Plan: docs/capture-plan-2026-09-03.md
#
#   bash tools/capture/run-session.sh [--camera <serial>] [--driver <serial>] [--skip <phase,...>]
#                                     [--with-mode-capture]
#
# It stops for exactly one thing: framing. The first phase asks whether the camera is actually
# pointed at the strip, and if it is not, the session stops there having cost a minute rather than
# two hours. Nothing else needs a person.
#
# --camera may be omitted; the session then runs without video, which every phase but capture_all
# tolerates because the driver measures its own light. capture_all is skipped in that case rather
# than run blind.
set -uo pipefail

ADB="${ADB:-C:/Users/attgm/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG=com.github.hyphen05.fuse
ACTION=com.example.debug.ACTION_CONTROL
DRIVER="${DRIVER:-65271FDDV001AB}"
CAMERA="${CAMERA:-}"
# Mode Capture is parked (Joe, 2026-09-03) and opted into rather than out of.
SKIP="mode_capture"
OUT="captures/session-$(date +%Y%m%d-%H%M%S)"

while [ $# -gt 0 ]; do
  case "$1" in
    --camera) CAMERA="$2"; shift 2 ;;
    --driver) DRIVER="$2"; shift 2 ;;
    --skip)   SKIP="$SKIP,$2"; shift 2 ;;
    --with-mode-capture) SKIP=$(echo "$SKIP" | sed 's/mode_capture//'); shift ;;
    --out)    OUT="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
LOG="$OUT/session.log"
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }
skipped() { case ",$SKIP," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

cmd() { # cmd <serial> <command> [extras...]
  local serial="$1"; shift
  "$ADB" -s "$serial" shell "am broadcast -a $ACTION -p $PKG --es cmd $*" >>"$LOG" 2>&1
}

# The app logs every outcome under the AdbControl tag, which is the only way a script can tell a
# sequence that started from one that silently did not. Read it back rather than assuming.
tail_log() {
  "$ADB" -s "$1" shell "logcat -d -s AdbControl -t 40" 2>/dev/null | tail -12 | tee -a "$LOG"
}

pull() { # pull <serial> <label>
  local serial="$1" label="$2"
  mkdir -p "$OUT/$label"
  "$ADB" -s "$serial" pull "/sdcard/Android/data/$PKG/files/" "$OUT/$label/" >>"$LOG" 2>&1
  local n
  n=$(find "$OUT/$label" -type f | wc -l)
  say "pulled $n file(s) for $label"
  # Delete on the phone only after they are safely here, so a failed pull cannot lose a run.
  if [ "$n" -gt 0 ]; then
    "$ADB" -s "$serial" shell "rm -f /sdcard/Android/data/$PKG/files/*.csv" >>"$LOG" 2>&1
  fi
}

run_phase() { # run_phase <label> <seconds> <extras...>
  local label="$1" secs="$2"; shift 2
  if skipped "$label"; then say "SKIP $label"; return 0; fi
  say "PHASE $label (~$((secs / 60))m ${secs}s) $*"
  cmd "$DRIVER" run_calibration --es sequence "$label" --ez attention false "$@"
  sleep 6
  local recent
  recent=$(tail_log "$DRIVER")
  case "$recent" in
    # The liveness guard: the strip acks every write and emits nothing. Device-side state, and no
    # BLE command clears it — it needs a power cycle at the mains.
    *ABORTED*) say "!! $label aborted: the strip acks but is dark. Power-cycle it at the wall."; return 1 ;;
    # Active Control off, not merely disconnected: getCurrentlyControlledDeviceAddresses filters on
    # control, so a device shown as connected in the UI still counts as absent here.
    *"no connected devices"*) say "!! $label: no devices under Active Control. Turn it on in the app."; return 1 ;;
  esac
  sleep "$secs"
  cmd "$DRIVER" stop_calibration
  sleep 3
  pull "$DRIVER" "$label"
}

say "=== session start; driver=$DRIVER camera=${CAMERA:-none} out=$OUT"
"$ADB" devices -l | tee -a "$LOG"

# --- preflight --------------------------------------------------------------------------------
# Everything that makes a session void while looking fine, checked in five seconds. Each of these
# has cost a run before: an app in the background stops advancing its own sequence, a receiver with
# no ViewModel registered swallows every command silently, and a strip that is connected but not
# under Active Control is skipped by getCurrentlyControlledDeviceAddresses without complaint.
preflight_failed=0
note_fail() { say "!! $*"; preflight_failed=1; }

"$ADB" -s "$DRIVER" get-state >/dev/null 2>&1 || note_fail "driver $DRIVER does not answer adb"
"$ADB" -s "$DRIVER" shell "pm list packages $PKG" 2>/dev/null | grep -q "$PKG" ||
  note_fail "Fuse is not installed on the driver — ./gradlew installDebug"

# The app must be in the foreground for the whole run. Backgrounding takes it out of TOP, Android
# then refuses startForegroundService, and delay() inside a sequence stops advancing: no CSV, no
# error, and a recording of a strip that stopped moving.
"$ADB" -s "$DRIVER" shell "am start -n $PKG/com.example.MainActivity" >>"$LOG" 2>&1
sleep 3
"$ADB" -s "$DRIVER" shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>/dev/null |
  grep -q "$PKG" || note_fail "Fuse is not the foreground app on the driver"

# A registered listener means the ViewModel is alive and adb commands will land somewhere.
cmd "$DRIVER" status
sleep 2
if ! "$ADB" -s "$DRIVER" shell "logcat -d -s AdbControl -t 10" 2>/dev/null | grep -q "vmListenerRegistered=true"; then
  note_fail "no ViewModel listener registered — the app is running but not ready"
fi

if [ -n "$CAMERA" ]; then
  "$ADB" -s "$CAMERA" get-state >/dev/null 2>&1 ||
    note_fail "camera phone $CAMERA does not answer adb (USB debugging authorised to this laptop?)"
  "$ADB" -s "$CAMERA" shell "pm list packages $PKG" 2>/dev/null | grep -q "$PKG" ||
    note_fail "Fuse is not installed on the camera phone — CalibrationRecorder lives in it"
fi

if [ "$preflight_failed" -ne 0 ]; then
  say "preflight failed; nothing has been run. Fix the above and start again."
  exit 1
fi
say "preflight OK"

if [ -n "$CAMERA" ]; then
  say "starting recording on $CAMERA"
  cmd "$CAMERA" start_recording --es name session
  sleep 4
  tail_log "$CAMERA"
else
  say "no camera phone given: running without video, and skipping capture_all"
  SKIP="$SKIP,capture_all"
fi

# --- framing gate -----------------------------------------------------------------------------
# The one question a script cannot answer for itself. Twenty seconds, before anything long starts:
# a phone that has been nudged, or is aimed at half the strand, produces a session that completes,
# exports, and measures a wall. Everything after this assumes the framing is good.
if ! skipped framing_check; then
  say "PHASE framing_check (20s) — the only thing that can stop the session"
  cmd "$DRIVER" run_calibration --es sequence framing_check --ez attention false
  sleep 26
  cmd "$DRIVER" stop_calibration
  sleep 3
  pull "$DRIVER" framing_check
  FRAMING_CSV=$(find "$OUT/framing_check" -name "fuse_latency_framing_check_*.csv" | head -1)
  if [ -z "$FRAMING_CSV" ]; then
    say "!! framing check produced no photometer CSV — is the app foregrounded and the camera permitted?"
    exit 1
  fi
  if ! python tools/calibration/analyse_framing.py "$FRAMING_CSV" | tee -a "$LOG"; then
    say "!! STOPPING: the camera needs moving. Nothing long has run, so this costs a minute."
    [ -n "$CAMERA" ] && cmd "$CAMERA" stop_recording
    exit 1
  fi
fi

# --- light phases: photometer open, no video needed -------------------------------------------
run_phase chase_probe        60 || true
run_phase full_ramp_x3      700 || true
run_phase cct_probe         180 || true

# --- cool-down --------------------------------------------------------------------------------
# Not padding. Thirteen minutes of bound camera puts real heat into the phone, a hot phone
# throttles, and throttling lands directly on the write ceiling the next phase exists to find.
say "cool-down 5m (camera released, strip black)"
"$ADB" -s "$DRIVER" shell dumpsys thermalservice 2>/dev/null | grep -i "Temperature{" | head -4 | tee -a "$LOG"
sleep 300
"$ADB" -s "$DRIVER" shell dumpsys thermalservice 2>/dev/null | grep -i "Temperature{" | head -4 | tee -a "$LOG"

# --- rate phases: photometer shut -------------------------------------------------------------
# The same ladder at four pacing values. Bypassed says what the hardware can do; 50 says what the
# app's current default costs, which is the number the 2026-09-02 analysis mistook for the strip's.
for pacing in "" 0 25 50; do
  if [ -z "$pacing" ]; then
    run_phase rate_ceiling 200 || true
  else
    say "PHASE rate_ceiling at pacing=${pacing}ms"
    cmd "$DRIVER" run_calibration --es sequence rate_ceiling --ez attention false --ei pacing "$pacing"
    sleep 200
    cmd "$DRIVER" stop_calibration
    sleep 3
    pull "$DRIVER" "rate_ceiling_pacing_$pacing"
  fi
done

run_phase capture_all       620 || true
run_phase sustained_load    960 --ei minutes 15 || true

# --- Mode Capture -----------------------------------------------------------------------------
# PARKED — Joe's call, 2026-09-03. It is built and wired (run_mode_capture over adb, endpoints
# passed rather than tapped), but it is 35 of the session's 80 minutes and it answers a different
# question from everything else here: what the built-in modes are called, not what the hardware
# does. It is off by default and needs --with-mode-capture to run. When it comes back, endpoints
# are better taken from the chase_probe grid pulled above — that says exactly where in frame the
# strip is on this phone in this position — than from the default vertical line.
if ! skipped mode_capture; then
  say "PHASE mode_capture (~35m)"
  cmd "$DRIVER" run_mode_capture
  sleep 60
  tail_log "$DRIVER"
  # It runs ~200 modes at 10s each and exports itself. Poll rather than sleeping blind, so a run
  # that failed to start is caught in the first minute instead of the fortieth.
  for _ in $(seq 1 80); do
    sleep 30
    if "$ADB" -s "$DRIVER" shell "am broadcast -a $ACTION -p $PKG --es cmd status" >/dev/null 2>&1; then
      state=$("$ADB" -s "$DRIVER" shell "logcat -d -s AdbControl -t 5" 2>/dev/null | grep -o "modeCaptureAuto=[^ ]*" | tail -1)
      say "  mode capture: $state"
      case "$state" in *done:*|*blocked:*) break ;; esac
    fi
  done
  pull "$DRIVER" mode_capture
  "$ADB" -s "$DRIVER" pull "/sdcard/Android/data/$PKG/files/mode_capture" "$OUT/mode_capture/" >>"$LOG" 2>&1
fi

if [ -n "$CAMERA" ]; then
  say "stopping recording on $CAMERA"
  cmd "$CAMERA" stop_recording
  # Finalisation is asynchronous: the mp4's moov atom lands a moment after the stop returns, and a
  # pull that beats it produces a file that looks fine and will not decode.
  sleep 10
  pull "$CAMERA" video
fi

say "=== session done. Files in $OUT"
find "$OUT" -type f -printf '%10s  %p\n' 2>/dev/null | sort -k2 | tee -a "$LOG"
say "Now: python tools/calibration/analyse_wire.py && ... (see tools/calibration/derived/README.md)"
