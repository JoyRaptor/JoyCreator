# STU-7 handoff for Claude

## Candidate and evidence

- Product candidate `codex/stu7-finish-20261125` at `ea236f311f2bfcab25ed27b2b53ccaa28dff2782`. Production code matches `57a9e272be831bf16f55754ed3bf8a8b3de8fe66`. The launch gate remains off in production. The full branch inventory is in [BRANCHES.md](BRANCHES.md).
- Decoded luma change and collapsed flow now mark hard cuts. The cached flag rejects cross-cut flow, resets preview frame history, and fences export exposure. The incoming frame stays sharp if the donor is unknown or crosses the cut. The flash exception uses retained texture and consistent exposure change.
- The prior Note 9 build reproduced the boxing defect at exactly 1,200,000 µs. Changing only the preceding shot changed the raw High frame by up to 128/255 while sharp stayed bit-exact. On the candidate, the desktop shader replay is bit-exact. Nine whip-pan frames, nine flash frames and twelve low-light hand frames retained the same blur in the desktop shader replay. The device repeat is open. [C6 receipts](C6_SNAPDRAGON_EXPORT_REPORT.md)
- Focused flow, cache, frame-ring and GL tests passed. Deliberately wrong previous timestamps and cut-pair upload attempts failed the new checks. [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/46034215505) and the [pinned Android build](https://github.com/JoyRaptor/JoyCreator/actions/runs/46034234049) passed.

## First action

Capture `boxing-original` and `boxing-changed` on the signed `dfef8d1f7014cf6dcac8b0dd7165077976c12f8c` fixture. Compare raw High and sharp at 1,160,000, 1,200,000 and 1,240,000 µs. Require the two raw High frames at 1,200,000 µs to match bit-for-bit. Then compare candidate and baseline controls and allow at most one lost blur frame in each clip.

- Private inputs, capture manifests and comparison scripts are under `D:/Temp/stu7-owner-media-intake-20261127/scene-cut-controls/`. The signed candidate APK is `D:/Temp/stu7-cut-emulator-20261127/connector-cut-apk/app-default-arm64-v8a-debug.apk`. The baseline APK is `C:/Temp/stu7-c6-native-input-proof-20261127/build/cloud/joycreator-defaultDebug-f4fc6ad3c-chunks/app-default-arm64-v8a-debug.apk`. Keep the media and raw captures local.
- `capture.ps1` is for the physical Note 9. Use the device runbook and claim the lock only after the current lane releases it. Verify the planned recovery APK against the currently installed APK before replacing it. Lock owner at last check was `website-lane`. This continuation made no changes to the phone. Use an unused output directory for each capture.
- `capture-emulator.ps1` targets only the disposable `stu7_cut_proof` AVD at `emulator-5580`. Its isolated SDK and starter are under `D:/Temp/stu7-cut-emulator-20261127/`. A local API 35 system image download was started. `codex/stu7-c6-cut-proof-20261127` at `6ddc42458a8e5d01e89d071243e24bc6b3116813` is the only temporary proof branch. Actions on this branch use synthetic footage exclusively. In the latest [synthetic run](https://github.com/JoyRaptor/JoyCreator/actions/runs/46039726742), guarded emulator root got past staging. Android then stopped before rendering with `Failed to instantiate extractor`. If using the emulator, check that the app can read the staged fixture and inspect the decoder before retrying. No app-service raw result was produced.

From PowerShell where `$proof` points to the private controls directory and `$apk` points to the signed candidate, use the device-appropriate capture script. On the disposable AVD:

```powershell
& "$proof/capture-emulator.ps1" -Apk $apk -Manifest "$proof/cut-proof.json" -Only @('boxing-original','boxing-changed') -Output "$proof/android-boxing-candidate"
python -B "$proof/compare.py" "$proof/cut-proof.json" "$proof/android-boxing-candidate"
```

For the controls, run the same capture script on `controls.json` with the baseline APK and a separate candidate output. Pass the baseline directory using `compare.py ... --baseline <directory>`. Actual Android readback covers the export path. Keep desktop, emulator and physical performance receipts labeled separately.

## Remaining release work

C4 and gated Help and Joybot landed. C5 physical preview/export parity, C6 wider Android parity and device profiling, and C7 blind review and owner approvals remain open. The owner dropped the Exynos Note 9 requirement. Resume the original [job card](../jobs/STU-7_motion_blur.md) after recording the boxing and control comparisons. Claude owns the FxPanel card and Long trail work under the design in spec §K. Codex did not edit the claimed UI paths.
