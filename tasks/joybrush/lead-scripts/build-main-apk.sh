#!/bin/bash
# Keep the current integration APK, build main's APK in the same folder, switch back. Prints both paths.
S=${LEAD_SCRIPTS:-$(dirname "$0")}
W=/c/Temp/jb-integ-fix
OUT=$W/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk
cp $OUT $S/apk-integ.apk && echo "kept $(git -C $W log --oneline -1 | cut -c1-9) -> $S/apk-integ.apk"
cd $W && git checkout -q --detach origin/joy-creator && echo "building main $(git log --oneline -1 | cut -c1-9)"
bash $S/lead-build.sh $W 1200 $W/apk-main.log :app:assembleDefaultDebug
grep -q "BUILD SUCCESSFUL" $W/apk-main.log && cp $OUT $S/apk-main.apk && echo "MAIN_APK $S/apk-main.apk"
git checkout -q lead/integration-1018-fix && echo "back on $(git log --oneline -1 | cut -c1-9)"
