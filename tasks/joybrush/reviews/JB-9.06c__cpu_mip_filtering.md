# JB-9.06c — CPU mip filtering

CPU paper now uses a lazy RGBA8 mip pyramid and trilinear reads at 1 / texel pitch, matching screen LINEAR_MIPMAP_LINEAR. Existing magnification and lattice hashes/rotations are unchanged; all packed surface channels are averaged before slope decoding. Odd rectangular inputs retain their full image area. Input texture bytes must remain immutable after construction. No schema/shader/hot app edits; no APK or phone installation.

Worktree: C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06c. Atomic jb-gradle.lock held for every Gradle call and released in finally. Command:

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest --tests '*PaperMipTest' --tests '*PaperRasterTest' --tests '*HexTileTest' --tests '*LaunchMaterialsTest'
```

Own XML: 32 tests, 0 failures/errors/skips, 4 suites; newest LaunchMaterialsTest XML 2026-10-02T12:36:36.4789227-04:00. Tests cover whole-image minification, fractional LOD, magnification, wrapping, odd rectangular input, invalid footprint and the raster look/surface hook. An initial sampler call compilation typo was corrected before the passing run.

Actual production WebGL shader vs fresh Kotlin material fixtures: 21/21 pass, max channel error 1 byte (previous linen17/silk12); raw packed upload error0, GL error0. Commands: bundled Python joybrush/tools/paper/material_gpu_fixtures.py; bundled Node joybrush/tools/paper/material_gpu_check.js with NODE_PATH pointing at bundled node_modules. The checker now gates actual mip filtering at max3 rather than forced level0.

Mutation: forcing the GPU checker to GL.LINEAR makes linen17/silk12 and exits1; restoring LINEAR_MIPMAP_LINEAR returns all21 to <=1 and exits0. A CPU-hook-removal mutation was prepared but its Gradle run was refused by another valid build lock; source was restored immediately. It was NOT run and is not claimed. No CPU changes followed the successful XML run. OpenCode may run that additional mutation under the lock if desired.

General blend-order export parity remains JB-9.06b. Lead-owned version7 UI and phone acceptance remain separate. See PAPER_OPENCODE_HANDOFF.md for precise next work and gates. The owner requested wrap-up to conserve weekly usage.
