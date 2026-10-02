# JB-9.07 custom background colour preview backend

The owner's custom Colour choice is ready for UI integration. PaperPreviews.customColour(current, "#RRGGBB") returns the document Paper with the chosen colour and clears lookId/tint, preserving surface, scale, show, bite, light and includeInExport. PaperPreviews.colour(current, colour, catalogue, size) renders the same resolved state for the picker/selector preview. Live swatch after applying it uses crop(PaperState.resolve(chosen, catalogue), size). The Lead still owns the picker, selector, circle below layers, undo/save and phone check; this continuation does not complete the UI row.

Exact command from this worktree, under the atomic lock:
```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.07/paper_test_heap.gradle -p joybrush :core:jvmTest --tests '*PaperPreviewsTest' --tests '*PaperStateTest'
```
The local init script only sets jvmTest maxHeapSize1536m and maxParallelForks1 for the inherited benchmark configuration. Own XML21 tests,0 failures/errors/skipped across2 suites, newest2026-10-02 00:34:06 EDT (UTC04:34:06). PaperPreviews6/0, PaperState15/0; targeted checks, not the whole core suite. Pins exact opaque chosen pixels when Light is off, actual relief crop when on, retained brush/export settings, colour cache invalidation and malformed colour refusal.

Mutation: retaining current.tint instead of clearing it turns customColourClearsTheOldLookAndTintButKeepsPhysicalAndExportSettings red:1/6 preview tests,1/21 total. Restore original source; all21 pass above. No hot app files changed, no APK built or phone installed.

JB-9.10 data handoff landed46dcc00a/d31f9684: three canvases, factory and handmade pulp plus existing artisan, matching neutral background choices and actual-renderer contact sheet. Main fast-forward refused divergence at51ad3802; left the main checkout untouched.
