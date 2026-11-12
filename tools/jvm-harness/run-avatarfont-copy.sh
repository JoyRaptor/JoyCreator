#!/usr/bin/env bash
# Work-loss audit F6 (2026-11-12): characters and fonts get their copy in the Joy Creator folder and come
# back from it. The REAL AvatarBundleFile, AvatarFolderCopy and FontFolderCopy on temporary folders.
# Plain javac, no Gradle.
# Usage:  bash tools/jvm-harness/run-avatarfont-copy.sh
set -u
cd "$(dirname "$0")/../.." || exit 1
GSON=$(ls ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/*/*/gson-*.jar 2>/dev/null | grep -v sources | head -1)
[ -n "$GSON" ] || { echo "no gson jar in the Gradle cache"; exit 1; }
OUT=tools/jvm-harness/out-avatarfont.$$
trap 'rm -rf "$OUT"' EXIT
rm -rf "$OUT"; mkdir -p "$OUT"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";"; GSON=$(cygpath -w "$GSON");; esac
S=app/src/main/java/com/fadcam/joyfolder/studio
javac -nowarn -encoding UTF-8 -cp "$GSON" -d "$OUT" \
  $S/AvatarBundleFile.java $S/AvatarFolderCopy.java $S/FontFolderCopy.java $S/B64.java \
  studiokit/src/main/java/com/fadcam/joyfolder/*.java \
  tools/jvm-harness/avatarfont/AvatarFontCopyTest.java || exit 1
java -cp "$OUT${SEP}$GSON" AvatarFontCopyTest
