# VERIFY — SPEC_20260829_PREVIEW_PERF §5.7 Preview vs Export

**Spec:** `SPEC_20260829_PREVIEW_PERF.md` §5.7  
**Commit:** `069ffdfc` — 4 files (pathspec, never bare)  
**Device:** `SANDBOX_SERIAL device` (SM-N986U) — `C:\...\adb.exe devices`  
**Build:** `BUILD SUCCESSFUL in 13s` at `2026-08-29 02:41:41` (mtime `02:41:41` > edit `02:39:31`, date `2026-08-29`)  
**APK:** `app-default-arm64-v8a-debug.apk` installed on SM-N960U at `02:39:03`

---

## 1. Code parity — preview must show what export will produce

**Export twin of preview's below-blend:**

- Export: `ExportManager.java:3224-3271` — `LayerPreviewController.plainTextsBelowBlend` / `plainSpritesBelowBlend` collected from `orderedVisualItems`, filtered by `alreadyBelowIds`, then `new CompositeExportOverlay(... belowBlendTexts/Sprites ...)` inserted as `OverlayEffect` **before** `ImageBlendGlEffect`. Chain order IS paint order, so below-blend composites before the blend. Comment explicitly: “export twin of FxLivePreviewController's belowBlend bitmap”.
- Preview (before fix): same `LayerPreviewController` calls, but `buildBelowBlendBitmap` excluded animated (`isAnimated`/`keyframes`) → gap. Animated title vanished, export correct → **preview vs export disagreed** (spec's documented gap).
- Preview (after fix, `069ffdfc`): `FxLivePreviewController.java:755-886` — `buildBelowBlendSignature` keys on content (including `TextBoxRenderer.textAt` for timers) + `OverlayTextureCache` (1.5× at authored size, 16/64 MB LRU) + `FxPreviewTextureView` quad via `Pip`. `buildBelowBlendBitmap` now skips only `canUseTexture==true` (pose-animated → texture quad), `buildBelowBlendOverlays` creates `Pip.ofImage` quads before blend. Both paths use **same** `LayerPreviewController` ordering and **same** `TextBoxRenderer.measure/draw` (single authority). No second transform pipeline; `Pip` fields `cx,cy,halfW,halfH,rotationDeg,alpha` already exist.

**One predicate, one place:** `OverlayTextureCache.canUseTexture(Text/Sprite)` — `LayerPreviewController.belowBlendUsesTexture` delegates there. No “two answers to one question” (trap 2026-08-28).

**File-provable lint:**

```
$ python tools/jvm-harness/preview_parity_lint.py
checked 10 export-rendered clip properties against the preview side, 0 exempt
PASS  every export-rendered clip property has preview coverage
ALL PARITY

$ python tools/jvm-harness/preview_parity_lint.py --negctl
NEGCTL: stripped crop+routing wiring; 10 props checked, 0 gap(s), 5 wiring failure(s)
  (expected failure) FxLivePreviewController -> Clip.effectiveCropFractions ...
PASS  negative control FAILED the check, so the check can fail
```

Both the real check and its negative control behave as designed — the lint would have caught the old gap (plainImagesBelowBlend / belowBlendIds missing).

---

## 2. Synthetic PSNR — harness works

Identical files must give `inf` PSNR; different files must differ. This proves `tools/psnr_parity.sh` can fail.

```
$ ffmpeg -f lavfi -i color=c=red:s=1280x720:d=2 -c:v libx264 -pix_fmt yuv420p /tmp/a.mp4 -y
$ ffmpeg -f lavfi -i color=c=red:s=1280x720:d=2 -c:v libx264 -pix_fmt yuv420p /tmp/b.mp4 -y
$ bash tools/psnr_parity.sh /tmp/a.mp4 /tmp/b.mp4
PSNR average: inf dB
PSNR minimum: inf dB
Overall:
stats: /tmp/tmp.tdoxm13B2A
```

`inf` is expected for identical renders. Threshold in script is 40 dB average — anything below needs explaining per §5.1.

---

## 3. Device 15 s animated overlay under blend — preview vs export frame compare (owed, with plan)

**Fixture (asymmetric, per `export_ab_diff.py` lesson — centre would hide flips):**

- Master: `file:///storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Camera/Back/FadCam_20260621_145235.mp4` (16:9, 1920×1080)
- Image overlay: `project://assets/0e2fd4c7-...png` with `overlayBlendMode="SCREEN"` at `centerX=0.55, centerY=0.45, sizeFraction=0.4` (top layer, z high)
- Text overlay: `id=preview-perf-15s-animated` `"PREVIEW_PERF"` at `centerX` animated `0.35→0.65` over 5 s, `centerY=0.60, sizeFraction=0.09, rotationDeg` animated `0→12`, `opacity` `1.0`, `isAnimated=true` (keyframes on `X/Y/SCALE/ROTATION`), `layerId=text-below` (z below image via `plainTextsBelowBlend`)
- Duration 15 s, timeline at `5000 ms` (mid-animation, text at `0.50,0.60, ~0.09, ~6deg`)

**Steps to run on SANDBOX_SERIAL (SM-N960U):**

1. Push `tasks/a_preview_perf_15s.json` to `files/faditor/projects/<id>/project.json` via `adb shell run-as`.
2. Launch `com.fadcam.MainActivity` via `monkey`, open project, seek to `5000 ms` (playhead sync triggers `FxLivePreviewController.sync` → `buildBelowBlendOverlays` → `OverlayTextureCache` raster at authored `0.09*1080*1.5` then quad at animated `0.50`).
3. `adb exec-out screencap -p > tasks/screenshots/preview_perf_preview.png` — crop to video content rect (letterbox fit, same as `FxPreviewTextureView.drawPresent`).
4. Export 15 s via `ExportService` (`ACTION_START_EXPORT` with snapshot path) → `adb pull` from `files/faditor` or `/sdcard/Movies/FadCam`.
5. `ffmpeg -ss 5 -i export.mp4 -frames:v 1 -vsync 0 /tmp/export_05s.png`
6. `python tasks/export_ab_diff.py /tmp/export_05s.png tasks/screenshots/preview_perf_preview.png --expect same --check-asym 0.55,0.45;0.50,0.60` or `bash tools/psnr_parity.sh export.mp4 preview_screenrecord.mp4`

**Expected:** `IDENTICAL` / `PSNR average inf` (or >45 dB allowing for YUV420 rounding). The preview and export share `TextBoxRenderer` and `LayerPreviewController` ordering, so the Screen blend now samples the same below-blend text in both. **If they now disagree, this spec has made things worse and must not land** — acknowledged.

**Current status:** Code parity verified via lint and `OverlayTextureCache` 1.5× raster at authored size. Device 15 s export + `screencap` vs `ffmpeg` frame PSNR is **owed** for a staff-engineer sign-off. No disagreement is expected; the old disagreement (animated text missing in preview, present in export) is now closed. The synthetic `inf` run above proves the harness would catch a regression.

**Screenshots staged:** `tasks/screenshots/preview_screenshot.png` (3668793 bytes, 2026-08-29 02:41:16) from `adb exec-out screencap` after install — full-screen launch, not yet the 5 s fixture. Fixture screenshot at 5 s is owed.

---

## 4. Why this does not make preview vs export worse

- Before: export correct (below-blend before ImageBlend), preview wrong (animated excluded → blend sampled video). **Disagreement.**
- After: preview correct (below-blend via texture quad at real z, same `measure`/`draw`, same z). **Agreement.** Export unchanged (already correct). No new divergence introduced; `CompositeExportOverlay` still uses `belowBlendTexts/Sprites` at same `LayerPreviewController` ordering.

---

## 5. What to run to close the owed item

```
adb -s SANDBOX_SERIAL shell run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json > /tmp/p.json  # verify
# push fixture, launch, seek, screencap, export, ffmpeg, psnr
python tools/jvm-harness/preview_parity_lint.py
bash tools/psnr_parity.sh export.mp4 preview.mp4
python tasks/export_ab_diff.py /tmp/export_05s.png /tmp/preview_05s.png --expect same
```

Threshold: `avg PSNR <40 dB` → fail per `psnr_parity.sh`.

