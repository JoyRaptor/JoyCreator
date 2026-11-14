#!/usr/bin/env bash
# TAB-1: window size classes, Library columns and the reading column (pure Java).
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-sizeclass
rm -rf "$OUT"; mkdir -p "$OUT"
if grep -n "^import android\|^import androidx" app/src/main/java/com/fadcam/ui/size/SizeClass.java; then
  echo "FAIL: an Android import leaked into SizeClass"
  exit 1
fi
javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/com/fadcam/ui/size/SizeClass.java \
  tools/jvm-harness/SizeClassTest.java || exit 1
java -cp "$OUT" SizeClassTest
