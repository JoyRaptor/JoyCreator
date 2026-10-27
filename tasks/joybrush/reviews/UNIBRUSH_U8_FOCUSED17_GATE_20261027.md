# U8 source deliverable closed; focused17 gate prepared — 2026-10-27

**SOURCE ONLY. Tests17 UNRUN. No queue/phone grant, execution, runtime integration or activation.**
Source checkpoint2f119eed2828eb3f00faabeb7ce1d0da6a982229, base f6287b921, branch
codex/unibrush-u8-guards-20261026, isolated C:/Temp/jb-unibrush-u8-guards-20261026.
This follow-up changes documentation only. The final commit is the published handoff checkpoint;
production/test source remains the original2f119eed2. Reconcile row edits only into current docs.

## Exact requested next queue gate — do not run without root admission

Task: :joybrush-android:testDebugUnitTest. Two filters select7 lifetime/notice tests and10
readback/parity tests. Luna independently counted source annotations; that is not a test run.
After the coordinator grants the focused slot and owns its lock/preflight, invoke from the
isolated checkout in PowerShell:

```powershell
.\gradlew.bat --offline --no-daemon --no-watch-fs `
  -Pkotlin.compiler.execution.strategy=in-process --max-workers=1 `
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' `
  :joybrush-android:testDebugUnitTest `
  --tests 'cc.joycreator.joybrush.android.bench.UnibrushProbeLifetimeTest' `
  --tests 'cc.joycreator.joybrush.android.bench.UnibrushParityEvidenceTest'
```

This command is not a self-admitting wrapper. Root must perform fresh RAM/job/watcher checks,
focused minimum2200MiB free RAM, exclusive CreateNew jb-gradle.lock, and existing watcher policy
per current AGENT_START.1536MiB above is the proposed focused heap, not a RAM measurement or grant;
root may choose its current validated heap and must record the actual command. No waiting loop,
process kill, watcher change, phone lock, install or device operation is included here.

## Setup and source dependencies

- SDK/JDK local setup is not configured in this new checkout: root local.properties and
  joybrush/local.properties are absent. Queue owner must supply the existing validated Android
  SDK36/JDK toolchain through local settings/environment; never commit machine paths/secrets.
- Gradle wrapper8.13; Android library compileSdk36/minSdk24, Java/Kotlin target17.
- Root composite includeBuild("joybrush") supplies local core and androidkit; studiokit is also
  an implementation dependency. AGP compiles/resolves module dependencies and test sources even
  when execution is filtered to these two classes. This is not a tiny standalone compiler job.
- Pure JUnit4 test classes, no Robolectric runner or device operations in these17 methods.
  Module test dependencies still include JUnit4.13.2 and Robolectric4.17; offline classpath/plugin/
  wrapper/SDK/transitive availability must be checked. Missing caches are a blocked gate, not
  permission to install or silently enable networking. Existing cached JUnit alone is insufficient.
- Helpers: UnibrushProbeLifetime/UnibrushProbeNoticeLease and UnibrushParityEvidence. The latter
  reuses existing MatrixDiff plus core Tiles.SIZE/key. No historical BenchRunner/LineParityBench,
  NativeLines, U6 View/engine delta or old merge commit is required to compile these new leaves.
- JSON companion records canonical Git blob hashes for the four new leaves plus direct shared
  arithmetic/build inputs. Whole build dependency identity comes from the exact Git checkpoint;
  this list is not a complete transitive build-source hash. If root ports source, record the new
  actual checkpoint and diff instead of calling the old source tested.

## Receipt required before changing UNRUN

Capture actual execution HEAD/tree, clean/dirty source manifest, command, JDK/SDK setup, run ID,
start/end timestamps, exit code, complete log and XML fingerprints. Fresh selected XML files:

- joybrush-android/build/test-results/testDebugUnitTest/TEST-cc.joycreator.joybrush.android.bench.UnibrushProbeLifetimeTest.xml
- joybrush-android/build/test-results/testDebugUnitTest/TEST-cc.joycreator.joybrush.android.bench.UnibrushParityEvidenceTest.xml

Independently aggregate exactly2 suites/17 tests/0 failures/0 errors/0 skips. Preserve failure
logs. If task is UP-TO-DATE/FROM-CACHE, do not label that fresh execution; queue owner can schedule
an explicit --rerun-tasks invocation under fresh admission if needed. Never delete old evidence
to fabricate freshness. No selected report exists from this preparation. A green run proves only
these helpers, not runtime hookup, real pixels, U6 safety, full U8, speed or activation.

## Claude-ready remaining contract

Original lead-uni@a5e98f5c3 and both dirty U8 paths are preserved; hashes match the recovered
inventory. Exact findings: [U6/U8 file/line/input](unibrush-recovery-20261026/FINDINGS_EXACT.md).
U6 cancels provisional pixels before replacement admission succeeds: root owns physical
failure-safety review/fault injection. U8 needs every async boundary bound to actual generation/
retirement, owned notice cleanup, A10 durable preference preservation, baseline/A/M/B physical
readbacks, nonblank/control/one-step assertions and full edit/save/recovery matrices. The helper
alone cancels no launched GL command. U9/U10 remain unmeasured; U11 conditional; shipping OFF.

No owned jobs, claims, device reservations or dirty source paths at handoff. Root/UX retain shared
queue sequencing. Next action is this exact focused17 gate once setup/admission is provided;
then reconcile a narrow runtime/probe contract, never old whole hot files. Current source-level
assignment is closed; validation remains pending. [Recovery/source handoff](UNIBRUSH_U8_GUARDS_SOURCE_20261026.md).
