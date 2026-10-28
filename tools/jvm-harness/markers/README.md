# Timeline marker JVM checks

Focused, plain-JVM checks against the current marker production classes. The harness compiles
only `MarkerWorkspace`, `MarkerWorkspaceStore`, `MarkerLoopPolicy`, and `RegionExportPlan`.
Small Android `AtomicFile` and app `FLog` stubs make store persistence behavior testable without
Android or Gradle. The runner uses an `org.json` jar from the Gradle cache when available;
otherwise it downloads pinned `org.json:json:20240303` into its temporary scratch directory.
Compilation output is written to `C:\Temp\timeline-marks-final-check` and removed after a run.

Run from PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File tools/jvm-harness/markers/run.ps1
```

Pass `-JsonJarPath <path>` to use a specific jar. Pass `-KeepArtifacts` to retain the exact
scratch directory for inspection; default runs clean it up.
