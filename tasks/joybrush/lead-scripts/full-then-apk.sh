#!/bin/bash
S=${LEAD_SCRIPTS:-$(dirname "$0")}
W=/c/Temp/jb-integ-fix
bash $S/lead-build.sh $W 1200 $W/verify-engine.log -p joybrush :core:jvmTest :androidkit:test --continue
bash $S/lead-build.sh $W 1200 $W/verify-app.log :joybrush-android:testDebugUnitTest --continue
cd $W
for d in joybrush/core/build/test-results/jvmTest joybrush/androidkit/build/test-results/test joybrush-android/build/test-results/testDebugUnitTest; do printf "%s: " $d; cat $d/*.xml 2>/dev/null | grep -o '<testsuite [^>]*' | sed -E 's/.*tests="([0-9]+)".*skipped="([0-9]+)".*failures="([0-9]+)".*errors="([0-9]+)".*/\1 \3 \4 \2/' | awk '{t+=$1;f+=$2;e+=$3;s+=$4} END {print t" tests "f" fail "e" err "s" skip"}'; done
FAILS=$(grep -l "<failure" joybrush/core/build/test-results/jvmTest/*.xml joybrush/androidkit/build/test-results/test/*.xml joybrush-android/build/test-results/testDebugUnitTest/*.xml 2>/dev/null)
if [ -n "$FAILS" ] || ! grep -q "BUILD SUCCESSFUL" $W/verify-engine.log || ! grep -q "BUILD SUCCESSFUL" $W/verify-app.log; then echo "TESTS NOT GREEN — no install"; echo "$FAILS" | head; exit 1; fi
bash $S/apk-install.sh
