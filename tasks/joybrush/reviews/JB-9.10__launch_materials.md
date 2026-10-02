# JB-9.10 launch material library — implementation and measured limits

All requested launch categories are selectable:17 physical surfaces,21 backgrounds,11,177,618 PNG bytes (under25MiB). Includes three canvases, three pulp grades, full/subtle crumples from one faceted periodic sheet, rice cool/cream with independent opacity/relief, sugarcane, fine chalkboard grit with black/green dust looks, construction, blueprint, parchment, crossed papyrus strips, cement, fabric, silk and image-free AMOLED black. Structural albedo and default surface share dimensions, texel pitch, hex size, rotation and hash; pigment/opacity/chunks remain separate where appropriate. Heights/slopes affect real dry/directional/wet coverage, not just a lit decoration. Original numerical geometry/pigment; no new AI generation or external media. SOURCES.md and build_launch_library.py record seeds/setup.

Exact row-worktree command, under the atomic build lock:
```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest --tests '*ShippedCatalogueTest' --tests '*PaperTestSetTest' --tests '*SurfaceAssetTest' --tests '*LaunchMaterialsTest'
```
Own XML17 tests,0 failures/errors/skipped across4 suites; newest2026-10-02 07:41:39 EDT. LaunchMaterials8/0, PaperTestSet3/0, ShippedCatalogue5/0, SurfaceAsset1/0. Targeted checks, not the entire core suite. All17 packed files rebuild byte-for-byte in Kotlin. Wrap-neighbour variation is bounded against ordinary neighbours; measured means correct; actual document-coordinate hex sampling pins visible weave/height correlation; directional and wet responses are non-inert. Flattened sheet retains the same geometry with smaller physical amplitude and shared slopeRange0.099.

Combined asset mutations made all7 guard-test methods red (7/8 LaunchMaterials,7/17 total): missing blueprint option; incorrect rice mean; erased sugarcane slopes; cement height wrap join (repacked validly); replacing subtle crumple with full geometry; shifting linen albedo7 texels. They catch catalogue availability, means/geometry, byte twin, physical response, seam, amplitude and visual/physical alignment. Original bytes/catalogue restored before the17/0 run above. No mutation backups remain.

Actual Kotlin renderer comparison sheet: tools/paper/out/launch-contact.png, material scale0.25/1/4. Inspected at original resolution; facets replace old pebbles, papyrus has pressed strip tops. These are scale controls, not a claim about screen zoom filtering.

Production WebGL shader evidence:
```powershell
C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe joybrush/tools/paper/material_gpu_fixtures.py
$env:NODE_PATH='C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe joybrush/tools/paper/material_gpu_check.js
```
21 real packaged cases: decoded/uploaded RGBA byte error0, GL error0; non-minifying/bilinear CPU-GPU maximum1 byte. Normal mip filtering exposes an unresolved CPU minification mismatch: fine linen17 bytes, silk12, chalkboards2–3; remaining cases≤1. Do not claim screen/export parity complete. JB-9.06c will correct CPU filtering; mip error is reported separately rather than hidden by a looser threshold. Actual production shader zoom0.25/1/4 sheet: out/material-gpu-contact.png.

Library code/data can integrate with the Lead's pending version7 UI; visual phone/owner acceptance and full experience are still pending. Export blend parity follows the approved JB-9.06b; imports/drawing integration remains with the Lead. No APK rebuilt or phone installed in this library row. Main ff divergence is left untouched.
