# Parity fixtures — 8 stacking combinations

Each JSON is a minimal project template for the matrix. Import into the app via file import or push:

  adb push export/fixtures/parity/*.json /sdcard/parity/
  # then in app: Open project → Import → pick file

For synthetic (no device) verification, the matrix harness renders these stacks with PIL
and compares preview vs export at COMPARE_WIDTH=480, mean<=3.0 p95<=12.0 (codec noise only).

| Fixture | Stack |
|---------|-------|
| 01_mask_x_blend | mask (rounded rect, feather) + MULTIPLY on same overlay |
| 02_mask_on_NORMAL | mask + NORMAL blend (mask-only, must cut without GL) |
| 03_fx_plus_blend | FxStack invert + MULTIPLY |
| 04_adjustment_plus_mask | AdjustmentLayer invert masked to region |
| 05_track_matte_still | luma matte from still peer (4323e8db fallback path) |
| 06_crop_plus_blend | effectiveCropFractions + MULTIPLY |
| 07_two_images_blending | two image overlays both blending (MULTIPLY + SCREEN) |
| 08_z_mixed_gl_canvas | z: video GL bottom, text Canvas middle, image GL top |

Timestamp for comparison: 1500 ms, paused.

Run: `bash tools/run-frame-parity-matrix.sh`  (synthetic)  or with serial for live.
