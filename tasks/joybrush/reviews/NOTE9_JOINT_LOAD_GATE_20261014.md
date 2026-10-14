# Note 9: joint root and caller-thread load gate — 2026-10-14

## Exact artifact and independent report recount

Artifact source: `8dd645ac363ac83c0da1e7bcfa374eefbcbbe59b`. Install receipt SHA-256 `a9b7809a12150df7dc8140e842141e807d8123b3c9136f48d44f92c6c2878f42` matches `load-admission-apk-receipt.json`. The install receipt records a changed update timestamp and expects the two joint probe cases for Ink and Watercolour.

The four reports comprise all four distinct case/brush/mode pairs. All passed. I independently counted the comparison arrays: 141 eager Ink + 141 eager Watercolour + 137 cold Ink + 137 cold Watercolour = 556, exactly matching every report's `comparisonCount`. All 556 rows and report aggregates have zero differing channels, zero differing pixels, and zero max byte delta. Total inspected volume is 145,752,064 RGBA channels over 36,438,016 pixels. Each run covers 8 actual union keys (under the 16-key limit).

Aggregate device counters: 572 readbacks; 676 GL error checks; 572 framebuffer checks; 744 feedback/ownership checks. Observed GL errors, incomplete framebuffers, and feedback failures are all zero. Per run, the fixture recorded 5 root checks, 4 history checks, 4 native API checks, 4 native `readTile` checks, 4 untouched native sibling checks, 48 raw source preservation checks, and 32 physical slab checks. Each run binds, publishes, undoes, redoes, and resets once. Eager opening uses 2 line replays and 12 compositions, then 1 replay/8 compositions for first edit. Cold opening uses zero line replay/composition and 8 uploaded looks, then 2 line replays/8 compositions on first edit.

## Caller-thread load gate in this artifact

The source adds the metadata admission to the `JbCanvasView.load(contents, onDone)` entry: `validatedLoadMetadata(contents)` runs on the calling thread before UI state changes and before `onGl` is queued. It applies the existing full-content refusal checks and `CanvasSnapshot.metadataOf` validation. `checkContents` uses the same gate for preflight. This artifact's receipt reports 199 native tests green and APK assembly PASS (20 seconds). The GPU probe reports exercise the same joint engine paths, but they are not startup/recovery or arbitrary-archive-open tests, and do not themselves demonstrate that the load gate rejected an unsafe file before mutation.

## Preserved artifacts and boundaries

The working receipt reports the Working archive present and unchanged; all three app working-copy archives were hash-checked and unchanged; four reports were captured. No archive/recovery roundtrip was performed. No optional native-media execution, native-base promotion/projection in the live path, live capture, Select, save activation, quality, or performance claim follows from these reports. Native base-support proof classes are pure support tests. The engine is unchanged from source `5b3ebdca`; the APK receipt reuses 636 backend tests from that source and 2,309 core tests (4 skipped) from `1f185f334`. APK assembly reports `gpuExecuted: false`; device reports are the separate GPU evidence.

Independent compact recount: `NOTE9_JOINT_LOAD_GATE_20261014.json`.
