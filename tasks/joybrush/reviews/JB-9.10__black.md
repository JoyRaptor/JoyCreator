# JB-9.10 flat black continuation

Only the already specified AMOLED black catalogue background is added: #000000, no picture/default surface, lightByDefault=false. It needs no new asset, licence, texture generation or candidate selection. The Paper sheet can now offer off-white/physical pulp and flat black. The broader launch library is still unselected and JB-9.10 is not marked complete.

```powershell
.\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest --tests '*ShippedCatalogueTest'
```

Own shipped-catalogue XML: 5 tests, 0 failures/errors/skipped, one suite; newest2026-10-01 23:53:04 EDT. Final run17s. This is a targeted catalogue/asset check, not a claimed full core run. Every Gradle run holds the shared atomic lock, releases it in finally and uses --no-daemon. No APK rebuild is claimed for this asset-only change; no phone installation.

Mutation: replace black #000000 with #010101 in the shipped catalogue; actual-RGBA test failed1/1 (expected0, actual1); restored. Command: same no-daemon/JVM flags, `-p joybrush :core:jvmTest --tests '*ShippedCatalogueTest.shippedAmoledBlackRendersExactOpaqueBlackWithoutRelief' --rerun-tasks`. The final class filter and restored asset are declared jvmTest inputs, so the final check runs afresh without forcing recompilation of unchanged sources.

Landed as87e82b8b after rebase on origin/joy-creator and push HEAD:joy-creator. Main --ff-only refused because the branch has diverged; left it untouched.
