#!/usr/bin/env bash
#
# Parrot Karaoke — local, no-car UI loop.
#
# Runs the REAL app (the debug APK from this repo) inside a headless Android
# emulator against the REAL Spotify-lyrics relay. The relay URL + Basic-Auth are
# baked into the debug build from `app/local-dev.properties` (gitignored), so
# the app needs no extra config.
#
# You never need the car to: build → boot → change a lyric state → screenshot.
#
#   * Pixels   -> AVD `ui28`  (API 28, 800x480 @240, has `screencap`) [default]
#   * Behavior -> AVD `car10` (API 10 car runtime; old API has NO screencap —
#                 use it for install/launch/logcat/no-crash, not pixels)
#
# Commands
#   setup                     create/verify the pixel AVD (idempotent)
#   build                     build the debug APK
#   run                       boot headless, install + launch, wait for boot
#   shot [NAME]               capture a PNG (default -> $OUT)
#   dismiss                   tap OK of the 'older Android' dialog (API 2x); after 'run'/'launch'
#   state                     print the relay /status JSON (auth out of output)
#   control <resume|pause|next|prev>
#   log                       tail the app's HTTP debug log from the emulator
#   all                       build + run + shot  (the quick loop)
#   stop                      kill the emulator on this port
#   help                      this text
#
# Options / env overrides (all optional):
#   --avd NAME     pixel AVD name (default ui28)         [ AVD_NAME ]
#   --port N       emulator port (default 5556)          [ EMU_PORT ]
#   --name NAME    screenshot basename                   [ NAME ]
#   ANDROID_HOME   SDK root (default /home/rafaj/tools/android-sdk)
#   JAVA_HOME      JDK 17 (default /home/rafaj/tools/jdk17)
#
# Data source is the real relay (no mock). To SEE the karaoke band the relay
# must have an active track (Spotify playing): `control resume|next`, or start
# playback on your phone, then `shot`. `state` always shows what the relay says.
#
set -euo pipefail

die()  { printf '✗ %s\n' "$*" >&2; exit 1; }
say()  { printf '→ %s\n' "$*"; }
first_existing() { local p; for p in "$@"; do [ -e "$p" ] && { printf '%s' "$p"; return 0; }; done; return 1; }
usage() { awk 'NR>1{ if ($0 ~ /^# *set -euo pipefail/) exit; sub(/^# ?/,""); print }' "$0"; }

# ---- environment -----------------------------------------------------------
if [ -z "${JAVA_HOME:-}" ]; then
  JH="$(first_existing /home/rafaj/tools/jdk17 /home/rafaj/tools/jdk-17.0.20.1+1 \
       /usr/lib/jvm/java-17-openjdk-amd64 2>/dev/null)" || die "Set JAVA_HOME to a JDK 17."
  export JAVA_HOME="$JH"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  AH="$(first_existing /home/rafaj/tools/android-sdk /opt/android-sdk /usr/local/android-sdk 2>/dev/null)" \
    || die "Set ANDROID_HOME to your Android SDK."
else AH="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"; fi
export ANDROID_HOME="$AH"
export ADB="${ADB:-$AH/platform-tools/adb}"
export EMULATOR="${EMULATOR:-$AH/emulator/emulator}"
[ -x "$ADB" ] || die "adb not found at $ADB (check ANDROID_HOME / ADB)."

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PKG="${PKG:-me.farnasx.parrotkaraoke}"
ACTIVITY="$PKG/.MainActivity"
OUT="${OUT:-/tmp/opencode}"
PROPS="$ROOT/app/local-dev.properties"
AVD_DIR="$HOME/.android/avd"

# ---- relay helpers (app/local-dev.properties; gitignored, dev-only) --------
kv() { local ek; [ -f "$PROPS" ] || return 0
  ek="$(printf '%s' "$1" | sed 's/[.]/\\./g')"
  sed -n -E "s/^[[:space:]]*${ek}=//p" "$PROPS" | tail -n1
}
relay_info() { # sets RELAY_URL / RELAY_USER / RELAY_PASS / RELAY_BASE
  [ -f "$PROPS" ] || die "app/local-dev.properties not found (needed for state/control). It holds the gitignored relay URL + auth."
  RELAY_URL="$(kv relay.url)"; RELAY_USER="$(kv relay.user)"; RELAY_PASS="$(kv relay.pass)"
  RELAY_BASE="${RELAY_URL%/status}"
  [ -n "${RELAY_URL:-}" ] || die "relay.url missing in local-dev.properties."
}

# ---- steps -----------------------------------------------------------------
wait_boot() { local i bc
  say "Waiting for the device to report boot_completed…"
  "$ADB" wait-for-device -s "$SERIAL" 2>/dev/null || true
  for i in $(seq 1 180); do
    bc="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d ':\r')"
    [ "$bc" = "1" ] && return 0
    sleep 1
  done
  [ -f "$OUT/emulator-$AVD.log" ] && tail -n 15 "$OUT/emulator-$AVD.log" >&2 2>/dev/null
  die "Boot did not complete within 180s."
}

cmd_setup() {
  local cfg="$AVD_DIR/$AVD.avd/config.ini"
  if [ -e "$cfg" ]; then say "AVD '$AVD' already exists ($cfg)."; return 0; fi
  local amt="$AH/cmdline-tools/latest/bin/avdmanager"
  [ -x "$amt" ] || die "avdmanager not found at $amt (need Android cmdline-tools)."
  say "Creating AVD '$AVD' (image: android-28/default/x86)…"
  "$amt" create avd -n "$AVD" -k "system-images;android-28;default;x86" --force >/dev/null 2>&1
  [ -e "$cfg" ] || die "avdmanager could not create '$AVD'."
  set_cfg() { local k="$1" v="$2"
    if grep -Eq "^[[:space:]]*${k}=" "$cfg"; then sed -i -E "s|^[[:space:]]*${k}=.*|${k}=${v}|" "$cfg";
    else printf '%s=%s\n' "$k" "$v" >>"$cfg"; fi
  }
  set_cfg hw.lcd.width 800;     set_cfg hw.lcd.height 480
  set_cfg hw.lcd.density 240;   set_cfg hw.initialOrientation landscape
  set_cfg hw.ramSize 2048
  say "Ready: AVD '$AVD' at 800x480 @240 landscape."
}

cmd_build() {
  say "Building debug APK (gradle)…"
  ( cd "$ROOT" && ./gradlew :app:assembleDebug --console=plain -q )
  [ -f "$APK" ] || die "APK not produced at $APK"
  say "APK: $APK"
}

cmd_run() {
  local state emu_pid
  state="$("$ADB" get-state -s "$SERIAL" 2>/dev/null || true)"
  if [ "$state" != "device" ]; then
    mkdir -p "$OUT"
    say "Booting AVD '$AVD' (headless) on port $PORT…"
    "$EMULATOR" -avd "$AVD" -no-window -no-audio \
      -gpu swiftshader_indirect -no-boot-anim -no-snapshot -no-snapshot-save \
      -port "$PORT" >"$OUT/emulator-$AVD.log" 2>&1 &
    emu_pid=$!; echo "$emu_pid" >"$OUT/emulator-$AVD.pid"; EMU_PID="$emu_pid"
  else say "AVD on port $PORT already running; reusing."; fi
  wait_boot
  "$ADB" -s "$SERIAL" root >/dev/null 2>&1 && "$ADB" wait-for-device -s "$SERIAL" 2>/dev/null || true
  if [ ! -f "$APK" ]; then say "APK missing — building first…"; cmd_build; fi
  say "Installing APK…"
  "$ADB" -s "$SERIAL" install -r -d -t "$APK" 2>/dev/null || "$ADB" -s "$SERIAL" install -r "$APK"
  say "Launching $ACTIVITY …"
  "$ADB" -s "$SERIAL" shell am start -n "$ACTIVITY" >/dev/null
  sleep 2
  cmd_dismiss
  say "Ready.  Screenshot:  $(basename "$0") shot --name state   |   Relay:  $(basename "$0") state"
}

cmd_dismiss() {
  # Best-effort dismiss of the "built for an older Android" dialog (API 2x only;
  # it does NOT appear on the car image / API 10). Tap the visible OK + KEYCODE_ENTER.
  "$ADB" -s "$SERIAL" shell input keyevent 66 >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell input tap 521 344 >/dev/null 2>&1 || true
  sleep 0.5
  "$ADB" -s "$SERIAL" shell input tap 414 344 >/dev/null 2>&1 || true
  sleep 0.5
}

cmd_shot() {
  local serial_state ts out
  serial_state="$("$ADB" get-state -s "$SERIAL" 2>/dev/null || true)"
  case "$serial_state" in
    device) : ;; *) die "No device on port $PORT — run:'$(basename "$0") all' first." ;;
  esac
  ts="$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$OUT"
  out="$OUT/${NAME:-shot-$AVD}-$ts.png"
  "$ADB" -s "$SERIAL" exec-out screencap -p >"$out"
  if [ ! -s "$out" ] || ! head -c5 "$out" | grep -aq "PNG"; then
    rm -f "$out"
    die "screencap isn't on this image (API 10). Use the pixel AVD (ui28); car10 is behavior/logcat only."
  fi
  say "Saved: $out"
}

cmd_state() {
  relay_info
  say "GET $RELAY_URL"
  curl -sS --max-time 25 -u "$RELAY_USER:$RELAY_PASS" "$RELAY_URL" 2>/dev/null \
    | { python3 -m json.tool 2>/dev/null || cat; } \
    || say "(relay not reachable from host — the app may still reach it from the emulator.)"
}

cmd_control() {
  [ -n "$ACTION" ] || die "usage: control <resume|pause|next|prev>"
  relay_info
  case "$ACTION" in resume|pause|next|prev) : ;; *) die "action must be resume|pause|next|prev"; ;; esac
  say "POST $RELAY_BASE/control?action=$ACTION"
  curl -sS --max-time 30 -X POST -u "$RELAY_USER:$RELAY_PASS" "$RELAY_BASE/control?action=$ACTION" 2>/dev/null
  printf '\n'
}

cmd_log() {
  say "Following /sdcard/parrot-karaoke-debug.log — Ctrl-C to stop."
  "$ADB" -s "$SERIAL" shell "tail -n 40 -f /sdcard/parrot-karaoke-debug.log" 2>/dev/null \
    || "$ADB" -s "$SERIAL" logcat -v time | grep -Ei "parrot|RelayClient|Http|Diagnostics"
}

cmd_stop() {
  case "$("$ADB" get-state -s "$SERIAL" 2>/dev/null || true)" in
    device) say "Stopping emulator on port $PORT…"; "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true ;;
    *) say "Emulator on port $PORT is not running." ;;
  esac
}

cmd_all() { cmd_build; cmd_run; sleep 3; cmd_shot; }

# ---- arg parsing -----------------------------------------------------------
CMD="" NAME="" ACTION="" AVDOPT="" PORTOPT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --avd)  shift; AVDOPT="${1:-}" ;;
    --port) shift; PORTOPT="${1:-}" ;;
    --name) shift; NAME="${1:-}" ;;
    -h|--help|help) usage; exit 0 ;;
    setup|build|run|shot|state|log|stop|all|control|dismiss) CMD="$1"; shift ;;
    resume|pause|next|prev) ACTION="$1"; CMD="control"; shift ;;
    *) NAME="$1"; shift ;;
  esac
done
[ -n "$AVDOPT" ]  && AVD="$AVDOPT"
[ -n "$PORTOPT" ] && PORT="$PORTOPT"
AVD="${AVD:-${AVD_NAME:-ui28}}"
PORT="${PORT:-${EMU_PORT:-5556}}"
SERIAL="emulator-$PORT"
CMD="${CMD:-help}"

case "$CMD" in
  setup)   cmd_setup ;;
  build)   cmd_build ;;
  run)     cmd_run ;;
  shot)    cmd_shot ;;
  state)   cmd_state ;;
  control) cmd_control ;;
  log)     cmd_log ;;
  stop)    cmd_stop ;;
  all)     cmd_all ;;
  dismiss) cmd_dismiss ;;
  help)    usage ;;
  *)       die "Unknown command: $CMD  (try 'help')" ;;
esac
