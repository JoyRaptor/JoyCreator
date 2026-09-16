#!/usr/bin/env bash
# javacheck.sh — does this Java file still PARSE? Answers in seconds, without Gradle.
#
# WHY THIS EXISTS: the build watcher gets turned off regularly (it kept dropping JoyRaptor's
# wireless-debugging connection), and running Gradle by hand is off-limits in this repo. That
# leaves source edits with no feedback at all until the next real build — which is how a stray
# brace sits in the tree for an hour.
#
# javac alone cannot RESOLVE an Android file: every android.* and project type is off the
# classpath, so it reports hundreds of "cannot find symbol". Those are noise. But javac parses
# BEFORE it resolves, so anything the parser objects to — a missing brace, a stray paren, an
# unterminated string — still shows up, and that is the class of mistake worth catching early.
#
#   bash tools/javacheck.sh app/src/main/java/.../Foo.java [more.java ...]
#
# Exit 0 = parses. Exit 1 = a real syntax error, printed. It does NOT prove the file compiles.
set -u
cd "$(dirname "$0")/.." || exit 1

[ $# -ge 1 ] || { echo "usage: bash tools/javacheck.sh <File.java> [...]"; exit 2; }

JAVAC=""
for c in "/c/Program Files/Android/Android Studio/jbr/bin/javac.exe" \
         "${LOCALAPPDATA:-$HOME/AppData/Local}/Programs/Android Studio/jbr/bin/javac.exe" \
         "$(command -v javac 2>/dev/null)"; do
  [ -n "$c" ] && [ -x "$c" ] && { JAVAC="$c"; break; }
done
[ -n "$JAVAC" ] || { echo "No javac found (looked in Android Studio's jbr and on PATH)."; exit 2; }

out="$(mktemp)"; trap 'rm -f "$out"' EXIT
tmpd="$(mktemp -d)"; trap 'rm -f "$out"; rm -rf "$tmpd"' EXIT
"$JAVAC" -Xmaxerrs 9999 -nowarn -d "$tmpd" "$@" >"$out" 2>&1

# Everything the resolver complains about is expected here. Anything else is ours.
syntax="$(grep -E "error:" "$out" \
  | grep -vE "cannot find symbol|does not exist|does not override or implement|cannot access|bad class file")"

if [ -n "$syntax" ]; then
  echo "SYNTAX: FAILED"
  echo "$syntax" | head -40
  exit 1
fi
echo "SYNTAX: clean  ($# file(s) parsed; symbol errors ignored on purpose)"
exit 0
