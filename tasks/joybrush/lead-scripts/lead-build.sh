#!/bin/bash
# Usage: lead-build.sh <worktree> <minFreeMB> <log> <gradle args...>  — waits for the machine-wide lock and RAM.
W=$1; MIN=$2; LOG=$3; shift 3
LOCK=/c/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock
for i in $(seq 1 240); do
  free=$(powershell -NoProfile -Command "[int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)" | tr -d '\r')
  if [ ! -e "$LOCK" ] && [ "$free" -ge "$MIN" ] && ( set -o noclobber; echo "JoyBrush Lead (fork) build $(date)" > "$LOCK" ) 2>/dev/null; then
    cd "$W"; ./gradlew.bat --offline -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 "$@" > "$LOG" 2>&1; rc=$?
    rm -f "$LOCK"; echo "DONE rc=$rc free=${free}MB"
    grep -E "^e: |tests completed|FAILED|BUILD (SUCCESSFUL|FAILED)" "$LOG" | head -40; exit 0
  fi; sleep 30
done; echo "GAVE_UP lock/RAM"
