# Combined live-cursor phone proof proposal — 2026-10-14

Source: `38cd9cbace23dac8c4663365bb3a49f5f176250e`. APK SHA-256: `5833CFF301447051F053A4BED3910B26DFAAE06B6143BE96E9E535F5AD499B76`. The APK receipt records `PASS1m41`; the phone receipt confirms installation was requested and the package update time changed from `2026-10-14 10:10:14` to `2026-10-14 10:41:28`.

Four of four expected reports passed and cleaned up: `recordedCaptureProbe` for Ink and Watercolour, and `recordedSavedProbe` for Watercolour and Ink. All reports identify Qualcomm Adreno (TM) 630, OpenGL ES 3.2 V@415.0.

## Independently aggregated report evidence

- Captured-stroke parity: 2 cases, each 2 actual, 2 derived, and 2 union tiles. Each report has four exact RGBA8 comparisons (captured live versus replay and unchanged-original checks over two keys); all channels match.
- Cold saved/bake diagnostic: 2 cases, each with 4 source tiles and 28 exact RGBA8 comparisons; all channels match. Cold opening was accepted with exactly 0 source-preparation and 0 composition calls. The first edit performed exactly 1 old-line preparation. Each case completed 2 archive roundtrips and 20 exact CPU payload comparisons.
- Combined totals: 64 exact comparisons / 0 differing channels; 148 GL checks / 0 errors; 100 framebuffer checks / 0 incomplete; 116 external ownership/feedback checks / 0 failures; 4 archive roundtrips / 40 exact CPU payload comparisons; cleanup complete in all 4 reports. That is 4 captured actual tiles across the parity reports and 8 source tiles across the saved/bake reports.

The Working receipt records the pre-install archive baselines unchanged after installation and probe: Working was present and unchanged, and all 3 named drawing archives were unchanged. The runner took a SHA inventory of the named `.joybrush` files before install, checked that inventory again after install before dispatch, and compared it again after the probe. The receipt retains the count and equality result, not the individual archive names/hashes.

## Source validation and serialized execution

Retained XML receipts record core: 2,299 tests / 0 failures / 0 errors / 4 skipped; backend: 531 / 0 / 0 / 0 skipped; native: 193 / 0 / 0 / 0 skipped. APK build log: `C:/Temp/jb-lead-ops/live-joint-cursor-apk.log`; test receipts: `live-joint-cursor-core-xml.json`, `live-joint-cursor-backend-xml.json`, and `live-joint-cursor-native-xml.json`.

The phone runner uses the shared exclusive lock files `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock` and `.../jb-phone.lock`. It acquires them via `FileMode.CreateNew` and removes only locks bearing its owner token in `finally`; the lead build runner uses the same Gradle lock with owner-checked cleanup. This serializes physical-phone and Gradle work with the other lead jobs.

## Scope

These are synthetic MotionEvent/captured-replay and throwaway-engine cold saved-look/bake/undo/redo diagnostics. They do not activate records in real document layer/cel maps or establish user-facing recording, Select, save/recovery, or physical quality acceptance. Reports explicitly say `workingOrRecoveryInstalled:false` and `internalEngineChecksCounted:false`. Preserve those activation gates.

Raw reports: `C:/Temp/jb-lead-ops/recorded-phone-combined-38cd9cbac-reports.json`. Install and Working receipts, APK receipt, and independent XML test counts are retained under `C:/Temp/jb-lead-ops`.
