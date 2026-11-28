#!/usr/bin/env bash
# SaveLedgerTest: a character's saved place belongs to that character; two saves of a new one make one file. Pure Java.
# Usage:  bash tools/jvm-harness/run-avatar-ledger.sh
set -u
cd "$(dirname "$0")/../.." || exit 1

OUT=tools/jvm-harness/out-avatar-ledger
rm -rf "$OUT"; mkdir -p "$OUT"

javac -nowarn -encoding UTF-8 -d "$OUT" \
  app/src/main/java/cc/joycreator/avatar/file/SaveLedger.java \
  tools/jvm-harness/SaveLedgerTest.java || exit 1
java -cp "$OUT" SaveLedgerTest
