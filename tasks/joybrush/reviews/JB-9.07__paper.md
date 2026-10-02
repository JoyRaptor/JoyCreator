# JB-9.07 preview backend handoff

PaperPreviews is the pure helper listed in the sheet spec. It renders crops through an injected PaperRaster-compatible renderer and caches all resolved settings and dimensions, with an LRU cap of 24 crops and maximum side128. Returned RGBA bytes cannot modify cached data. Background circles use each look's default surface, and independent surface choices use neutral #D8D8D8 with upper-left relief light. Android can construct `PaperPreviews { p, rect -> PaperResources.load(p).render(rect) }` on one background worker; UI owns circle clipping, publication and discarding obsolete completions.

This is backend support, not a completed Paper sheet: LayerColumnView, PaperSheetView and JoyBrushActivity remain the Lead's lane. No app/UI hot files changed. The persisted transparency state required by the None choice is undefined in v4; the specific question is under JB-9.07 Questions. Include-in-export is independent of screen paper and must remain so.

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.07/paper_test_heap.gradle -p joybrush :core:jvmTest --rerun-tasks
```

Own full core XML: 1444 tests, 0 failures/errors/skipped, 98 suites; newest2026-10-01 23:46:36 EDT. The four PaperPreviews tests passed. Full run3m1s. The local init file sets Test heap1536m/maxParallelForks1 for existing large image tests. Every Gradle run uses the atomic shared lock and releases it in finally; no daemon, main-folder Gradle or phone installation.

Mutation: key all previews with light=false, making Light-on and Light-off collide. `PaperPreviewsTest.visibleControlsInvalidateTheLiveCrop` failed1/1 against real PaperRaster pixels. Original source restored before the full run. Command: the same no-daemon/JVM flags without the init file, `-p joybrush :core:jvmTest --tests '*PaperPreviewsTest.visibleControlsInvalidateTheLiveCrop' --rerun-tasks`.

No APK rebuild is needed for this unused pure helper; the verified paper engine APK remains at C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.08/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk. Lead integration will build its own app.

Landing/main update: pending.
