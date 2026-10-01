# JB-9.03b verification, 2026-10-01

All runs were in `%TEMP%/jb-9.03b`, with the atomic `jb-gradle.lock` and `--no-daemon`; the lock was released in `finally` on every run.

Core: 1426 tests, 0 failures/errors/skips, newest XML 2026-10-01 15:20:24 -04:00.
Android-kit: 213 tests, 0 failures/errors/skips, newest XML 2026-10-01 15:27:47 -04:00.

Core command:
```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest :androidkit:test --rerun-tasks
```
The existing large-image benchmark exhausted the default test heap on that run; core passed. Android-kit was rerun without changing benchmark code:
```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.03b/audit_test_heap.gradle -p joybrush :androidkit:test --rerun-tasks
```
The worktree-only init script sets `tasks.withType(Test).configureEach { maxHeapSize = '1536m'; maxParallelForks = 1 }` on all projects. Free physical memory was about 3.4 GB before the rerun.

Mutation command uses the core command's JVM settings with `:core:jvmTest --tests '*SurfaceMapsTest.aSineRampInYReadsAsTheCentralDifferenceAndHasNoHorizontalSlope' --rerun-tasks`. Changing only dy's centre weight 10 to 2 failed the intended assertion (1 test, 1 failure). Restored before the full runs.

Shader command:
```powershell
$env:NODE_PATH='C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'; node joybrush/tools/shader_check.js
```
Dab and tuft compile/link, GL error 0, flat slopes exactly [0,0], production dab strip correlation 0.029826 versus plain repeat 1.0. SurfaceAssetTest reproduces all 1,048,576 RGBA bytes, with zero differences. PNG B stayed unchanged during regeneration. No phone installation.
