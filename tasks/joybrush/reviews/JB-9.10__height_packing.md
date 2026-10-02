# JB-9.10 physical surface packing

Owner priority: crumpled and slightly crumpled-then-flattened paper, then physical surfaces that affect brush deposition. No new image generation; the existing rounded/pebbly crumple candidates are not selected or shipped.

Corrected CLI input: 16-bit integer greyscale heights scale from0..65535 to0..255 instead of PIL convert(L) clipping most of the range. 8-bit authored heights round-trip unchanged. --height-span0.2 compresses physical height about0.5 without min/max normalization. Use the SAME explicit slopeRange on full/subtle variants so range normalization does not cancel their directional amplitude difference. The default slope range now has the SurfaceMaps minimum0.001, giving flat data exact127/127 zero slopes rather than division by zero.

```powershell
& C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe joybrush/tools/paper/test_pack.py --xml joybrush/tools/paper/out/pack-tests.xml
```

Own tool XML (not a core/Gradle run): 6 tests,0 failures/errors/skipped; newest2026-10-02 00:05:12 EDT. Covers16-bit PNG input,8-bit preservation, exact flat slopes, physical flattening amplitude, actual CLI and invalid ranges. Standard-library unittest writes the per-test XML; NumPy/Pillow are the existing bundled dependencies. No packages installed. Fixtures live only in this worktree's ignored tools/paper/out and are deleted after tests.

Mutation: restore convert(L) before reading16-bit values. The16-bit PNG regression failed1/1, with128/255 incorrectly becoming1. Restored before all6 passed. Command adds `--test test_sixteen_bit_heights_scale_instead_of_clipping` and writes out/pack-mutation.xml.

Existing-crumple data probe: old clipped8-bit height std5.599748; correctly scaled std63.404575; subtle/full height ratio0.200043 and slope ratio0.206540 using shared range0.099. Existing linen height ratio0.200037 and slope ratio0.258689 (8-bit quantization). These are packing measurements, not a claim of phone brush judgement. Existing artisan asset packing is unchanged:0 differences among1,048,576 RGBA channels.

No Gradle run, APK rebuild or phone installation for this Python tooling fix. Screen/deposit integration remains the already verified engine; new crumple appearance requires refinement and the owner's judgement after the Lead's Paper sheet integration.

Landed asd6c43dab after rebase on origin/joy-creator and push HEAD:joy-creator. Main --ff-only refused branch divergence; left it untouched.
