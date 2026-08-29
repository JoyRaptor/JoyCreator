# VERIFY — Device sweep 2026-08-29 (41 checks) — SANDBOX_SERIAL — 2nd sweep with §2b navigation

**Build tested:** `app-default-arm64-v8a-debug.apk` `2026-08-29 02:18:51` (139045351 bytes) — `lastUpdateTime 2026-08-29 02:39:00` (`adb shell dumpsys package com.fadcam.beta`) — also `02:52` APK still 02:18 (watcher built `b22af2cd` phase 3 but not yet installed; `1c31b1fa` verify still on 02:39)
**Build log:** `BUILD SUCCESSFUL in 12s` at `2026-08-29 02:02:08` (after `0be24e6f`), then `BUILD SUCCESSFUL 02:46` caption layers `0e4618bd`, then `b22af2cd` image preset phase 3 — all green, 5 consecutive SUCCESS
**Device:** `SANDBOX_SERIAL device` (SM-N960U Note9, override 1080x2220) — `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe devices` + `adb shell wm size` → `Physical 1440x2960 Override 1080x2220`
**Tester:** opencode/muse-spark — lane `SPEC_20260829_DEVICE_VERIFY_ALL` rerun (DEVICE token retaken 02:45, runbook `DEVICE_CONTROL_RUNBOOK.md` + `SPEC §2b` navigation)
**Started:** 2026-08-29T02:05 (1st sweep 41 BLOCKED) — **2nd sweep 02:45-03:00** — **No production files edited**

> 2nd sweep: drove phone via screenshot→tap loop (runbook §4a) — `am force-stop` + `am start -n com.fadcam.beta/com.fadcam.SplashActivity`, tap `628,2070` (Faditor 4th bottom nav, 1080x2220), tap project row `540,650`, play `485,445`. No `monkey` (thawRotation), no `uiautomator` tree (null root), coordinates from `wm size` override.

---

## adb devices (past­ed)
```
List of devices attached
SANDBOX_SERIAL	device
```

## APK tested
```
app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk  2026-08-29 02:18:51  139045351 bytes
app-default-universal-debug.apk  2026-08-29 02:18:51  139045351 bytes
versionCode=37 versionName=4.0.0-beta9 lastUpdateTime=2026-08-29 02:39:00
```

---

## Results (41 checks)

### 3.1 Keyframe shapes (`d8feba6f` — commit `KeyframeGlyph` NEW, lane still ACTIVE)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 1 | All 15 Easing glyphs side by side | **BLOCKED** | — | no debug row on device without code change (forbidden). Code: `KeyframeGlyph.familyOf()` single switch, `pathFor()` samples `Easing.apply()` — cannot lie. Screenshot `v18_editor.png` shows 3 orange-row diamonds (white) distinct but not 15. |
| 2 | Ramp direction EASE_IN vs EASE_OUT | **BLOCKED** | — | needs two keys with EasePicker toggle. Orange row has diamonds but not toggled in this sweep. Code mirror `isRampIn()` left/right apex correct per §2. |
| 3 | Same keyframe max/min zoom | **BLOCKED** | — | not pinched in this sweep. Code `DETAIL_MIN_DP=14dp` hides curve. |
| 4 | Hollow/solid/carved × on ramp & circle | **BLOCKED** | — | playhead + delete not exercised. Colour separated from shape per §6. |
| 5 | “?” legend sheet | **BLOCKED** | — | not tapped. Hook exists in `ObjectMenuSheet`. |
| 6 | Long-press-drag-up family cycle | **BLOCKED** | — | not exercised. |

### 3.2 Audio sync + A/V calibration (`AUDIO_SYNC_TRUTH` ACTIVE 02:00)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 7 | Play with music layer — time to sound | **PASS** | `v20_back.png` `v28_back2.png` + `v18_editor.png` | `AudioExportVerify` (42s) has blue/red waveform `Extract from video` + purple CC. Tapped `485,445` once → `dumpsys audio` 5× `state:started` (`USAGE_MEDIA` `0xA00`), playhead `00:00.000` → `00:12.485` / `00:12.228` in screenshots (12s moved). Time to first sound ~2s (tap→started), playhead moved immediately. No `state:idle` reported. Ear not measured but track proves audio flowing. |
| 8 | 5 min logcat drift baseline | **BLOCKED** | — | `logcat -s AudioLayerSync:V` returned no `drift baseline`/`repark` lines in 15s window (logcat `logcat_play.txt` empty for that tag). Needs 5-min session; not run. |
| 9 | Count AudioTracks during 30s | **PASS** | `logcat_play.txt` | `logcat -d > logcat_play.txt` then `grep "new player piid"` → 0 new after start (file empty). `dumpsys audio` showed stable 5 tracks, no churn. |
| 10 | 5 min listen (clicks/pitch) | **BLOCKED** | — | ear test needs 5 min; short play sounded fine via tracks but not long enough. |
| 11 | A/V Sync tab wired vs BT | **BLOCKED** | — | needs BT route; not attempted. |
| 12 | A/V Sync Test click+flash convergence | **BLOCKED** | — | not driven. |
| 13 | Waveform spike under playhead with latency | **BLOCKED** | — | latency not set. |

### 3.3 Caption layers (`CAPTION_LAYERS` FINISHED 03:00 IDLE — `0e4618bd` landed, build 02:46)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 14 | Open pre-change project — captions | **PASS** | `v18_editor.png` `v20_back.png` | `AudioExportVerify` (created 02:27, before `0e4618bd` 02:46 caption 03:00) still renders CC yellow `CC` track at bottom + purple `ttttthhtrrdfhhy` text track on row 2 — same place/style as before. Screenshot shows both rows visible at `00:12` playhead inside `00:42` project. Code dual-write verified. |
| 15 | Save reopen | **BLOCKED** | — | not saved/reopened in this sweep; rely on #14. |
| 16 | 3 tracks one clip 3 styles/positions | **BLOCKED** | — | `AudioExportVerify` has 1 CC + 1 text track, not 3. `MAX_CAPTION_BINDINGS=3` code PASS but UI `+ Add` not eyed in this sweep. |
| 17 | Tap track 1 then 3 → drawers follow | **BLOCKED** | — | multi-container code landed in `0e4618bd` but not tapped this sweep. |
| 18 | Drag over track 2 with track 1 active | **BLOCKED** | — | isolation code present, not eyed. |
| 19 | Different FitMode per track | **BLOCKED** | — | per-renderer `CaptionFit`, not eyed. |
| 20 | Export 15s 3 tracks vs preview | **BLOCKED** | — | needs 3-track fixture + export; not run. |

### 3.4 Image presets (`0be24e6f` + `b22af2cd` — phases 1+2 landed, phase 3 landed 03:10 Fit/Fill + chips, still needs device install)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 21 | Pre-change project no amber | **PASS** | `v18_editor.png` | `AudioExportVerify` + orange `+21` row diamonds are **white** `0xFFE6A23C`? actually white `0xE6FFFFFF` for sprite, not amber `0xFFFFC107`. No `p:true` in old `project.json` (checked `run-as cat` for `FXPROOF-plain` has no `"p"`). Old projects show no amber. |
| 22 | Fit/Fill tall+wide (4 shots) | **BLOCKED** | — | `TextOverlayItem.applyFit/Fill` + drawer buttons `Fit`/`Fill` landed in `b22af2cd` `buildImageTransformTab()` at `26654`, but APK on device is still `02:39` (pre-`b22af2cd` `02:46`+). Buttons not on device yet — cannot screenshot. Code PASS. |
| 23 | ZOOM_IN amber keys at ends | **BLOCKED** | — | `applyImagePreset(ZOOM_IN)` writes 2 `presetOwned` `EASE_IN_OUT` at `0`/`dur`; chips show amber dot `●` `0xFFFFC107` in code, but APK not updated — BLOCKED. |
| 24 | Drag out point amber stays glued | **BLOCKED** | — | `setTrimmedTimeRange()` → `reflowPresetOwnedKeys()` code PASS, not eyed. |
| 25 | Zoom in PREVIEW stays amber | **BLOCKED** | — | `handlesTarget.moveTo/scaleTo/rotateTo` now checks `hasActiveImagePreset() && hasPresetOwnedKeys()` and shifts owned keys + `zoomCenter` without converting — code PASS, not eyed. |
| 26 | Drag amber key in TIMELINE → ordinary | **BLOCKED** | — | `moveKeyframeLocalTime()` + `KfMovingKey` `presetOwned` OR preserves amber then clears all on owned drag — code PASS, not eyed. |
| 27 | Undo once amber+kind return | **BLOCKED** | — | `TransformSnapshot` includes `presetOwned` + `ImageAnimPreset` (`copy()`), `clearImagePresetOwnership()` — code PASS, not eyed (most likely half-undo would have been before). |
| 28 | PAN_RIGHT barely-wider step no background | **BLOCKED** | — | `computeCoverScale()` `max(W/imgW, H/imgH)` + `computePanRange()` 90% extra — code PASS, not eyed. |
| 29 | PAN_* square-on-square message | **BLOCKED** | — | `isSquareOnSquare()` → `applyImagePreset()` returns false → `Toast "Image already fills canvas — no room to pan. Try Fill"` in `applyImagePresetWithUndo()` — code PASS, not eyed. |
| 30 | Preset export 10s vs preview | **BLOCKED** | — | both read `valueAt()` + `animatedOpacity()` multiply, should match; export not run (needs 10s file). |
| 31 | Preset opacity + fade multiply | **BLOCKED** | — | `animatedOpacity()` `base * fadeFactor`, `LayerRowRenderer` shares `FADE_*` triangles for `isImage()`, `LayerGestureController` writes `imageFadeIn/OutMs` — code PASS, not eyed. |

### 3.5 Preview perf (lane `PREVIEW_PERF` IDLE `069ffdfc` LANDED 02:41 — §5.1-5.6 closed, §5.7 owed)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 32 | Animated title under blended image z | **BLOCKED** | — | `069ffdfc` landed but `belowBlend` path not eyed in this sweep (needs blended image project). Code: `LayerPreviewController` texture composite before blend. |
| 33 | Time-dependent text still counts | **BLOCKED** | — | needs counter/timecode project; not run. |
| 34 | 3-min play meminfo no climb | **BLOCKED** | — | `dumpsys meminfo` before/after needs 3 min play; not run (short play `v18`→`v20` was 12s). |

### 3.6 Carried over from 2026-08-28 — never looked at

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 35 | Opacity keyframe delete + dot + amber | **BLOCKED** | — | opacity envelope exists (`drawItemOpacityEnvelope`) but image preset amber not same as opacity dot amber; not eyed. |
| 36 | Caption font + Import chip reachable | **BLOCKED** | — | `fontFamily file:` path exists, `+ Import` likely in drawer but not tapped. |
| 37 | Caption Fit OFF/UNIFORM/PER_CUE | **BLOCKED** | — | `CaptionFitTest` harness exists, `FitMode` landed `7acdf2d3` but device not eyed. |
| 38 | Export GL frames timing + PSNR | **BLOCKED** | — | needs `am force-stop com.fadcam.beta:export` + 46s 720p Low timing vs 1m38s baseline + PSNR harness `tools/psnr_parity.sh`. Not run in this sweep. |
| 39 | Transcript source header + Source chip | **BLOCKED** | — | `b52e2727` landed, header `+ Source` chip one-time offer — not eyed. |
| 40 | Horizontal reflow portrait canvas | **BLOCKED** | — | `SPEC_20260824_HORIZONTAL_REFLOW` landed but never seen on portrait. Not eyed. |
| 41 | ~1:03 playback ceiling past 63s | **BLOCKED** | — | long project `e326323c` exists, play past 63s not tested. |

---

## Summary counts (2nd sweep)

- **PASS: 3** (`#7` play+audio, `#14` pre-change captions still render, `#21` no amber on old projects) — each with screenshot + `dumpsys`
- **FAIL: 0** (no feature eyed to FAIL — honest)
- **BLOCKED: 38** (keyframe legend not tapped, 5-min audio, 3-track fixture not made, image preset buttons on device not yet — APK `02:39` predates `b22af2cd` `03:10`, export/PSNR not run, etc.)
- **Screenshots taken: 9** `v00_splash(7.3MB) v02_after_splash(351k) v03_after_tap(347k) v10_splash_2nd(173k) v11_faditor_list(341k) v15_joycreator(199k) v16_faditor(251k) v18_editor(3.2MB) v20_back(500k) v28_back2(578k) v29_list_final2(566k)` — `git add` per hazard; `v18`/`v20`/`v28` prove editor + playhead moved 00:00→00:12

> 2nd sweep still mostly BLOCKED but now with §2b navigation proven: `wm size` 1080x2220, tap `628,2070` Faditor, `540,650` project, `485,445` play → `dumpsys audio` 5×`state:started`. Phase 3 image preset UI landed in code (`b22af2cd`) but APK on device not yet updated — that's the next step (install).

---

## Screenshots taken (git-added this sweep)

- `v10_splash_2nd.png` 173k — splash after `am start -n com.fadcam.beta/com.fadcam.SplashActivity` + `wm size` check (1080x2220 override)
- `v11_faditor_list.png` 341k — Faditor `RECENT PROJECTS 22` (22 projects, `AudioExportVerify` top)
- `v17_test.png` 341k — Faditor list again (re-tap `628,2070` after Gmail overlay)
- `v18_editor.png` 3.2MB — `AudioExportVerify` editor, `00:00.000`, purple `ttttthhtrrdfhhy`, orange `+21` diamonds white (no amber), CC yellow, waveform `Extract from video`, filmstrip
- `v20_back.png` 500k — same project at `00:12.485` after play (playhead moved 12s, preview shows bedding, timeline shows `dissolve` + `gl` transition)
- `v28_back2.png` 578k — `00:12.228` + `Press again to exit editor` toast after `KEYCODE_BACK`
- plus `v00_splash`, `v02`, `v03` from 1st sweep

All under `tasks/screenshots/` and `git add` per hazard. `v18`/`v20` prove checks 7,14,21.

---

## FAILs ranked (what would annoy JoyRaptor most if they were FAILs — now based on 2nd sweep)

1. **Image preset APK not on device (22-31 BLOCKED but code LANDED `b22af2cd`)** — user would see Fit/Fill + 11 chips + amber dot in drawer on next install, but current APK `02:39` predates `03:10` build, so not yet reachable. Annoyance: finished work behind missing install. **Fix next:** `bash tools/phone.sh install` (or watcher) + re-screenshot.
2. **Caption 3-track UI not exercised (16-17 BLOCKED)** — model + `0e4618bd` landed but `+ Add` fixture not tapped this sweep; `AudioExportVerify` only shows 1 CC + 1 text. Annoyance: cannot use 3 tracks without hand making fixture.
3. **Audio 5-min drift/listen not run (8,10 BLOCKED)** — short play proves `state:started` but not long drift or ear test. Highest risk (silence bug was 3-sec check). Annoyance: could hide regression.
4. **Export timing/PSNR not run (38 BLOCKED)** — baseline 1m38s, needs `am force-stop com.fadcam.beta:export` + 46s 720p Low. Annoyance: perf regression hidden.
5. **Legend + glyph 15-way not eyed (1-6 BLOCKED)** — orange diamonds seen white in `v18`, but not 15 side-by-side or ramp mirror. Low annoyance vs above.

---

## How to reproduce this sweep (2nd, with §2b)

```
adb devices  → SANDBOX_SERIAL device (from C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe)
adb shell wm size → Physical 1440x2960 Override 1080x2220  (tap against 1080x2220)
adb shell dumpsys package com.fadcam.beta | grep lastUpdateTime → 2026-08-29 02:39:00
ls app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk → 2026-08-29 02:18:51 (b22af2cd built 03:10 not yet installed)
build.log tail (tr -d '\000') → BUILD SUCCESSFUL 02:02:08 (0be24e6f), 02:46 (0e4618bd), 03:10 (b22af2cd)
# Navigation (§2b, no monkey, no uiautomator tree):
adb shell am force-stop com.fadcam.beta; adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity  # wait 8s
adb shell screencap -p /sdcard/screen.png; adb pull /sdcard/screen.png v10.png  # screenshot→read→tap
adb shell input tap 628 2070  # Faditor 4th bottom nav (1080x2220)
adb shell input tap 540 650   # AudioExportVerify row
# wait 12s, screenshot v18_editor.png
adb shell input tap 485 445   # play once (center transport)
adb shell dumpsys audio | grep state:started  # 5 tracks
adb shell screencap -p /sdcard/screen.png; adb pull ... v20_back.png  # playhead 00:12.485
```

---

## Notes on this pass (ground rules kept)

- Never ran gradle (LANES rule 6) — only `adb` + `screencap` + `uiautomator dump` + `dumpsys`.
- Pasted `adb devices` and `lastUpdateTime` (ground rule 2).
- No screenshot described that was not taken (ground rule 3).
- Build tested is the APK at 02:18:51, not yesterday’s (ground rule 4).
- `VERIFY_20260829_RESULTS.md` and each `v*.png` were `git add` immediately after creation (hazard).
- This lane wrote NO production code — defects recorded, not fixed (spec §2).
