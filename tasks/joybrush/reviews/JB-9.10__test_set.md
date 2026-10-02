# JB-9.10 small physical test set

Ready for the owner's selector/brush trials: fine linen, cotton duck and rough jute (rotation off), factory and handmade pulp, alongside the existing artisan pulp. Each new surface has an image-free neutral background look with its defaultSurface id; choosing a surface separately remains independent of the colour. 512-square packed height/slopes, shared slopeRange0.099, centred physical amplitudes without renormalisation. Five files add3,090,337 bytes. No AI look generation, new crumple selection or app UI changes.

Source setup and input hashes: assets/paper/SOURCES.md. Rebuild:
`C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe joybrush/tools/paper/prepare_test_surfaces.py C:/+Projects/Screenrecorder/FadCam/joybrush/tools/paper/candidates/surfaces`

Exact verification command, from this row's worktree under the atomic build lock:
```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest --tests '*ShippedCatalogueTest' --tests '*PaperTestSetTest' --tests '*SurfaceAssetTest'
```
Own worktree XML9 tests,0 failures/errors/skipped across3 suites; newest2026-10-02 00:30:38 EDT (UTC04:30:38). ShippedCatalogue5/0, PaperTestSet3/0, SurfaceAsset1/0. These are targeted checks, not the whole core suite. All five new files rebuild byte-for-byte in Kotlin; real hex-sampled dry brush coverage and rendered selector pixels differ between materials. Actual PaperRaster contact sheet at paper scales0.25,1,4: joybrush/tools/paper/out/contact.png (ignored local artifact, visually inspected).

Mutation: replace the linen file with a valid packed flat height128, zero slopes127 and height-squared alpha64. The packing check stays green, while eachMaterialProducesDistinctDryBrushCoverageAndLitSelectorPixels fails with 'canvas_linen must affect deposition, not be flat':1/3 in PaperTestSet,1/9 total. Restore original bytes; all9 pass above. This proves the test catches loss of brush tooth, not just a missing or corrupt file.

No APK built or phone installed in this continuation. The Lead owns the circle below layers, selector/picker UI and phone judgement; the catalogue and previews are ready for that wiring. Custom colour backend follows separately under JB-9.07.
