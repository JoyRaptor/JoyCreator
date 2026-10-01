# JB-9.08 verification

Directional dry deposits and wet pooling use the literal CPU/GLSL effective-height contract. Dab travel is document-space, unit length, and independent of sample batching, scattering and pen lean. Stationary tuft deposits clear travel even when the previous spaced footprint is behind the pen. Dab instances grow from 24 to 32 bytes; tuft remains 64 and smudge remains 40. Smudge never deposits paper grain. Paper response flows through the existing stroke records, so no view hot edit was needed.

Document bite multiplies brush influence exactly once; depth and tooth are not scaled by bite. Previously ungrained brushes with positive influence use the specified universal depth/edge. Tip-texture behavior is preserved. The three response controls are now live.

Final own XML: core 1440 tests, 0 failures/errors/skipped, 97 suites, newest 2026-10-01 19:05:31 EDT; Android-kit 225 tests, 0 failures/errors/skipped, 20 suites, newest 2026-10-01 19:07:44 EDT. Full run passed in 5m27s.

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.08/paper_test_heap.gradle -p joybrush :core:jvmTest :androidkit:test --rerun-tasks
```

The local init file sets Test maxHeapSize to 1536m and maxParallelForks to 1 for the existing large image benchmark. All Gradle runs take the atomic shared build lock and release it in finally. Worktree settings copied; no Gradle in main and no phone installation.

Mutation: negate the slope/travel dot product. The east-face ridge test failed 1/1 (expected 0.85, actual 0.15), then the original source was restored. Command: same Gradle memory/no-daemon flags without the init file, `-p joybrush :core:jvmTest --tests '*PaperDepositTest.rightToLeftLoadsTheEastFaceOfANorthSouthRidgeFirst' --rerun-tasks`.

The initial full suite caught the stationary tuft bug (1440 tests, 1 failure); the fix resets the travel tracker before a stationary footprint and its hairs, then tracks that position for subsequent moving deposits. The regression requires actual dwell footprints, not an empty result.

```powershell
$env:NODE_PATH='C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'; node joybrush/tools/shader_check.js
```

GPU: production dab/tuft compile and link; zero-influence R16F coverage bit-exact with paper off, including tip grain and soft edges. Opposite dry directions changed 30882 pixels, correlation with the expected slope sign 0.99126488. Existing pencil, tuft, smudge and no-repeat checks pass; GL error 0. Paper background CPU parity remains max 1 byte/channel, large-origin parity 0 with adjacent difference 1. Black/show-zero exact and zoom detail checks pass.

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDefaultDebug
```

APK passed in 5m37s: C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.08/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk (also armeabi-v7a and universal). Never installed.

Landing/main fast-forward: pending. Phone judgement remains with the owner/Lead; the Paper swatch/sheet is JB-9.07. The JB-9.06 non-NORMAL export blend contract question remains recorded there.
