#!/usr/bin/env bash
# PipVolumeTest - STU-F6: the Volume controls show and change what the export does for a selected picture-in-picture.
set -u
cd "$(dirname "$0")/../.." || exit 1
OUT=tools/jvm-harness/out-pipvolume
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 -d "$OUT" app/src/main/java/com/fadcam/ui/faditor/model/PipVolume.java tools/jvm-harness/PipVolumeTest.java || exit 1
[ -f "$OUT/PipVolumeTest.class" ] || { echo "no PipVolumeTest class - the compile did not run"; exit 1; }
java -cp "$OUT" PipVolumeTest
