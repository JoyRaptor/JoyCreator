# Native GPU probes on the Note 9, exact APK 0adfde398 (Claude Lead, 2026-10-27)

Host: `tasks/joybrush/ui-scripts/run_native_probe.py` (new). Per run: exclusive jb-phone.lock, awake/unlocked/Home
readiness, SHA-256 manifest of every file under the three saved roots (app joybrush dir, /sdcard/Joy Creator,
/sdcard/Joy Creator (first test): 173 files), `adb install -r` only with --install, a host-written logcat fence,
one bench launch, manifest again, installed-APK hash before/after, receipt. Evidence copies in
`evidence/native-probes-20261027/`.

| Run | Case | Result | Saved files |
|---|---|---|---|
| frame-run1 | nativeFrameDuplicateProbe (ink) | ✅ **passed**: 33 tiles exact RGBA8 incl. halo, independent bases 3, Delete/Undo/redo, GL error 0 | 173 unchanged |
| wc-run1 | recordedWatercolourOrdinaryProbe, brush joybrush.lines.watercolour | not run: that id is not in BrushLibrary.builtIn(); the bench logged "no brush". The checker doc's brush id is wrong for the bench | 173 unchanged |
| wc-run2, wc-run3 | same, brush joybrush.watercolour | ❌ **failed, deterministic**: Add exact on 72 tiles; then `translate_37_minus19 exact RGBA8 mismatch at tile 4294967295` (= Tiles.key(0,-1), the row above the origin); tint actual changed 0 (never reached) | 173 unchanged |

Installed APK sha 4ae7c7c0… before and after every run. Remaining frame gates per the report: low-budget refusal,
injected failure atomicity, archive/save/reopen, watercolour, captured/joint. Next: find the cause of the translated
watercolour mismatch in the negative tile row.
