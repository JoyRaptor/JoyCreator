# JB-9.06 verification

Cached opaque paper pass in both screen paths; CPU export twin; document surface/bite/scale binding; textured open/snapshot support; explicit tint state; missing asset warnings and flat fallback. Paper never modifies stored layer tiles. Helpers: PaperBackground, DocumentPaperGrain, PaperResources.

From `C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.06`, under the atomic shared build lock, released in finally:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.06/paper_test_heap.gradle -p joybrush :core:jvmTest :androidkit:test --rerun-tasks
```

Own core XML: 1433 tests, 0 failures/errors, 96 suites, newest 2026-10-01 18:36:09 EDT. Own Android-kit XML: 224 tests, 0 failures/errors, 20 suites, newest 2026-10-01 18:38:17 EDT. The worktree-only init script sets test heap 1536m/maxParallelForks1 for the existing large-image benchmark; test assertions remain intact. Initial Android compilation failed on a cross-module smart cast; fixed by a local surface value, then both full suites passed.

Mutation: replace PaperState's explicit `tintSet = p.tint != null` with false; `PaperRasterTest.constantLookTintUsesItsMeanIncludingExplicitBaseColourTint` failed 1/1. Restored before final full suites. Mutation command is the same Gradle memory/no-daemon flags without the init file, `-p joybrush :core:jvmTest --tests '*PaperRasterTest.constantLookTintUsesItsMeanIncludingExplicitBaseColourTint' --rerun-tasks`.

```powershell
$env:NODE_PATH='C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'; node joybrush/tools/shader_check.js
```

Production background compile/link passed; 256-square CPU parity max difference 1 byte/channel. Smooth 64-square render at (10000000,-10000000) max CPU difference 0, adjacent difference 1 byte. Exact black and Show0 base colour passed. Zoom64 detail changes the render; display-only detail is excluded from export. Existing dab/tuft/smudge, exact zero slopes and no-repeat checks passed; GL error0, no-repeat correlation0.029826 vs control1.

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDefaultDebug
```

APK build passed (6m16s): app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk (also armeabi-v7a and universal). Never installed. Phone judgement/UI remain JB-9.07/the Lead.

Known contract consequence recorded in the spec: transparent-stack-then-paper export can differ from screen blend semantics for non-NORMAL layers and ORA readers. Following the explicit export contract; blend metadata is preserved. Main fast-forward previously refused because tasks/todo.md has local changes; left alone as instructed.
