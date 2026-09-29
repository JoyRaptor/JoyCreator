#!/usr/bin/env bash
# brushlab_push.sh — JB-1.21, the PC half of the phone brush lab.
#
# Watch a brush folder on the PC and push it to the phone whenever it changes, so a `brush.json`
# edited here is on the device about a second later and the next stroke is already the new brush.
# The phone side is `BrushHotReload` in joybrush/androidkit (lab/BrushHotReload.kt), which reads
# `<external files>/joybrush/lab/<brush>/brush.json` twice a second and hands a changed, usable
# file straight to the drawing view.
#
# Usage:
#   bash tools/brushlab_push.sh <path-to-brush-folder>     watch it, push on every change
#
# The folder is one brush: a `brush.json` in the shape joybrush/brushes/<name>/ has, e.g.
#   bash tools/brushlab_push.sh joybrush/brushes/ink
# which lands as <app files>/joybrush/lab/ink/brush.json. Ctrl-C stops it.
#
# WHICH PHONE: tools/phone.sh's rules, not this file's. That script is the one place that knows how
# to tell the sandbox phone from the one holding JoyRaptor's real projects, so its serial-picking
# is used as-is rather than re-implemented here — a second copy of that rule is a second chance to
# push at the wrong phone, and the Note 20 must never be picked by accident. Export PHONE=<serial>
# to pin one deliberately.
#
# NO EXTRA TOOLS: the watch is a `stat` twice a second, the same `stat` phone.sh already relies on,
# so there is nothing to install and nothing that can go stale.
set -u
cd "$(dirname "$0")/.." || exit 1

# ─────────────────────────────────────────────────────────── what to watch

FOLDER="${1:-}"
[ -n "$FOLDER" ] || { echo "usage: bash tools/brushlab_push.sh <path-to-brush-folder>"; exit 1; }
[ -d "$FOLDER" ] || { echo "not a folder: $FOLDER"; exit 1; }
# Absolute, because the loop runs for as long as the owner is editing and nothing promises the
# working directory is still the one the command was typed in.
FOLDER="$(cd "$FOLDER" && pwd)" || exit 1
BRUSH="$FOLDER/brush.json"
if [ ! -f "$BRUSH" ]; then
  echo "$FOLDER has no brush.json, and the lab only loads <brush>/brush.json."
  echo "Point this at one brush folder — joybrush/brushes/ink — not at joybrush/brushes/."
  exit 1
fi

# ─────────────────────────────────────────────────────────── which phone

# Sourced, not copied. phone.sh sets up $ADB, defines pick_serial/dev, and exits non-zero when adb
# is missing — which in a sourced script takes this one down too, which is the right way round. Its
# usage text goes to stdout when it runs with no command, so it is let out here; the functions and
# the serials are the only things being borrowed. `set --` first, so that usage branch is the one
# that runs and no device command is fired off as a side effect of loading the file.
set --
# shellcheck disable=SC1091
. tools/phone.sh >/dev/null 2>&1 || { echo "could not load tools/phone.sh — is adb installed?"; exit 1; }

# phone.sh owns the application id: PKG is com.fadcam.beta, the DEBUG build, which is the only build
# with the lab door in it at all. JOYBRUSH_PKG overrides it for a variant.
APP_ID="${JOYBRUSH_PKG:-${PKG:-com.fadcam.beta}}"
LAB="/sdcard/Android/data/$APP_ID/files/joybrush/lab"

# The phone half polls twice a second, so a push has to land inside that window to feel instant.
POLL_S="0.5"

if [ -z "$(pick_serial)" ]; then
  echo "no phone. Run: bash tools/phone.sh doctor"
  exit 1
fi

# `dev` is phone.sh's own "-s <serial>", empty when there is no phone. Word-splitting it is
# deliberate and is how every command in phone.sh reaches the device.

# ─────────────────────────────────────────────────────────── what changed

# The brush.json, NOT the folder around it. A folder's own mtime moves when something is created
# inside it or deleted, and not at all when a file inside it is rewritten in place — which is the
# one edit this tool exists for. And `stat -c %Y` is whole seconds, so an mtime on its own can miss
# a save landing in the same second as the last one; the byte count is in the fingerprint for that
# reason, and a save that changes a value without changing the length still moves the seconds.
fingerprint() { stat -c '%Y %s' "$BRUSH" 2>/dev/null || echo "gone"; }

# ─────────────────────────────────────────────────────────── pushing

# One line per push, whichever way it went. `adb push` is chatty on stdout, so its own output is
# dropped: this loop is watched while editing, and a screenful of adb per save is unusable.
push_now() {
  local why="$1"
  local when bytes
  when=$(date '+%H:%M:%S')
  bytes=$(wc -c < "$BRUSH" | tr -d ' ')
  # adb push will not make the path; it will only put a folder inside somewhere that exists.
  if ! "$ADB" $(dev) shell mkdir -p "$LAB" >/dev/null 2>&1; then
    echo "$when brushlab: FAILED to make $LAB on the phone"
    return 1
  fi
  if "$ADB" $(dev) push "$FOLDER" "$LAB/" >/dev/null 2>&1; then
    echo "$when brushlab: pushed $(basename "$FOLDER") to $APP_ID ($why, $bytes bytes)"
    return 0
  fi
  echo "$when brushlab: FAILED to push $(basename "$FOLDER") ($why) — is the phone still there?"
  return 1
}

# ─────────────────────────────────────────────────────────── the loop

trap 'echo; echo "brushlab: stopped"; exit 0' INT TERM

LAST="$(fingerprint)"
push_now "start" || exit 1

echo "brushlab: watching $BRUSH — edit and save, and the phone reloads it. Ctrl-C to stop."
while : ; do
  sleep "$POLL_S"
  NOW="$(fingerprint)"
  if [ "$NOW" != "$LAST" ]; then
    LAST="$NOW"
    push_now "changed"
  fi
done
