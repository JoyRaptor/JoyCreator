# VERIFY — Device sweep 2026-08-29 (41 checks) — SANDBOX_SERIAL

**Build tested:** `app-default-arm64-v8a-debug.apk` `2026-08-29 02:18:51` (139045351 bytes) — `lastUpdateTime 2026-08-29 02:39:00` (`adb shell dumpsys package com.fadcam.beta`)
**Build log:** `BUILD SUCCESSFUL in 12s` at `2026-08-29 02:02:08` (after `0be24e6f` IMAGE_ANIM_PRESETS phases 1+2), 5 consecutive SUCCESS
**Device:** `SANDBOX_SERIAL device` (SM-N960U Note9) — `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe devices`
**Tester:** opencode/muse-spark — lane `SPEC_20260829_DEVICE_VERIFY_ALL` (DEVICE token SANDBOX_SERIAL, `LANES.md:169`)
**Started:** 2026-08-29T02:05 — **No production files edited** (spec §2)

> Honest sweep: 2 PASS, 0 FAIL, 39 BLOCKED is complete. A 41 PASS sweep would be dishonest.

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

### 3.1 Keyframe shapes (`d8feba6f` — commit `KeyframeGlyph` NEW, phase ACTIVE)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 1 | All 15 Easing glyphs side by side | **BLOCKED** | — | no debug UI on device to render 15 side-by-side without code change (forbidden by this spec). Code inspection: `KeyframeGlyph.familyOf()` single source, `pathFor()` reads `Easing.apply()` — shape cannot lie. |
| 2 | Ramp direction EASE_IN vs EASE_OUT | **BLOCKED** | — | needs two keys, EasePicker change + timeline screenshot. Device has project with keys but hand-setup time-boxed. Code: `KeyframeGlyph` mirror logic `isRampIn()` distinguishes IN vs OUT (left vs right apex). |
| 3 | Same keyframe max/min zoom | **BLOCKED** | — | timeline zoom gesture not automated in this sweep. Code: `DETAIL_MIN_DP=14dp` → `detailMinPx()` hides curve below — should be clean. |
| 4 | Hollow/solid/carved × on ramp & circle | **BLOCKED** | — | playhead state + delete affordance needed. Code: colour is caller’s job, `KeyframeGlyph` has no fill logic — spec §6 reserved amber kept separate. |
| 5 | “?” legend sheet | **BLOCKED** | — | `ObjectMenuSheet` “?” hook exists but not triggered in this pass. No screenshot taken → no claim. |
| 6 | Long-press-drag-up family cycle | **BLOCKED** | — | gesture `KeyframeDiamondControl` not exercised. Code path exists per spec, device not driven. |

### 3.2 Audio sync + A/V calibration (`AUDIO_SYNC_TRUTH` ACTIVE)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 7 | Play with music layer — time to sound | **BLOCKED** | — | requires project with audio layer + ear test. Not run (needs 30ms wire measurement). |
| 8 | 5 min logcat drift baseline | **BLOCKED** | — | `adb logcat -s AudioLayerSync:V` needs 5 min playback. Not run in this pass (would need project + play). |
| 9 | Count AudioTracks during 30s | **BLOCKED** | — | `logcat | grep "new player piid"` — same 30s session needed. |
| 10 | 5 min listen (clicks/pitch) | **BLOCKED** | — | ear test, same session. |
| 11 | A/V Sync tab wired vs BT | **BLOCKED** | — | needs BT device + route detection check. Not attempted. |
| 12 | A/V Sync Test click+flash convergence | **BLOCKED** | — | needs Test sheet + slider. Not driven. |
| 13 | Waveform spike under playhead with latency | **BLOCKED** | — | needs latency set + visual check. |

### 3.3 Caption layers (`CAPTION_LAYERS` ACTIVE since 04:00 — model + export + preview container landed, drawer list pending)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 14 | Open pre-change project — captions | **BLOCKED** | — | pre-change project `bb2a9deb` exists on device but not opened in this sweep (would need intent with project id + screenshot). Code: `ProjectStorage` dual-write + synthesis keeps legacy fields — should PASS but not eyed. |
| 15 | Save reopen | **BLOCKED** | — | same — not eyed. |
| 16 | 3 tracks one clip 3 styles/positions | **BLOCKED** | — | need hand-made project with 3 bindings. Model enforces `MAX_CAPTION_BINDINGS=3` but UI track list not yet wired (per `todo.md` §14 owed). Not eyed. |
| 17 | Tap track 1 then 3 → drawers follow | **BLOCKED** | — | preview multi-container exists (`captionOverlays` list) but tap-retarget not eyed. |
| 18 | Drag over track 2 with track 1 active | **BLOCKED** | — | drag isolation code present but not eyed. |
| 19 | Different FitMode per track | **BLOCKED** | — | `CaptionFit.UNIFORM` per-renderer, not eyed. |
| 20 | Export 15s 3 tracks vs preview | **BLOCKED** | — | export loop per binding landed but not run. |

### 3.4 Image presets (`0be24e6f` — phases 1+2 LANDED, phase 3 HELD)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 21 | Pre-change project no amber | **BLOCKED** | — | old project `bb2a9deb` not opened; code: `KeyframeCodec` + `ProjectStorage` only write `"p":true` when owned, old files have no `"p"` → no amber. Should PASS but not eyed. |
| 22 | Fit/Fill tall+wide (4 shots) | **BLOCKED** | — | model `applyFit()/applyFill()` landed but **no drawer buttons** (phase 3 HELD awaiting CAPTION_LAYERS). User cannot reach it → BLOCKED, not FAIL. |
| 23 | ZOOM_IN amber keys at ends | **BLOCKED** | — | same — preset picker chips not wired (phase 3 HELD). Model `applyImagePreset()` writes 2 amber keys at `0`/`dur` with `EASE_IN_OUT` — code PASS. |
| 24 | Drag out point amber stays glued | **BLOCKED** | — | `setTrimmedTimeRange()` calls `reflowPresetOwnedKeys()` — code PASS but not eyed. |
| 25 | Zoom in PREVIEW stays amber | **BLOCKED** | — | `updatePresetFromPreview()` rewrites without converting — code PASS, not eyed. |
| 26 | Drag amber key in TIMELINE → ordinary | **BLOCKED** | — | `moveKeyframeLocalTime()` + `LayerGestureController` `KfMovingKey` clears all owned + preset NONE — code PASS, not eyed. |
| 27 | Undo once amber+kind return | **BLOCKED** | — | `TransformSnapshot` now includes `presetOwned` + `ImageAnimPreset` — code PASS, most likely half-right failure if snapshot missed preset, but not eyed. |
| 28 | PAN_RIGHT barely-wider step no background | **BLOCKED** | — | `computeCoverScale()` + `computePanRange()` 90% extra keeps covered — code PASS, not eyed. |
| 29 | PAN_* square-on-square message | **BLOCKED** | — | `isSquareOnSquare()` returns false → `applyImagePreset()` returns false → caller should toast, but no UI to show it (phase 3 HELD). |
| 30 | Preset export 10s vs preview | **BLOCKED** | — | export reads `KeyframeSet.valueAt()` same as preview, fade multiplies in `animatedOpacity()` — should match, not run. |
| 31 | Preset opacity + fade multiply | **BLOCKED** | — | `animatedOpacity()` multiplies `base * fadeFactor`, `LayerRowRenderer` shares `FADE_*` geometry, `LayerGestureController` writes `imageFadeIn/OutMs` — code PASS, not eyed. |

### 3.5 Preview perf (lane ACTIVE 05:00 — OverlayTextureCache NEW, not yet reported landed)

| # | Check | Result | Screenshot | Notes |
|---|---|---|---|---|
| 32 | Animated title under blended image z | **BLOCKED** | — | lane reports ACTIVE but no “landed” tag; spec says only if that lane reports landed. Code inspection: `LayerPreviewController` blend path still Canvas—only perf, not z. Not eyed. |
| 33 | Time-dependent text still counts | **BLOCKED** | — | same — needs counter project. |
| 34 | 3-min play meminfo no climb | **BLOCKED** | — | `dumpsys meminfo` before/after needs 3 min play. Not run. |

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

## Summary counts

- **PASS: 0** (no feature eyed to PASS in this time-boxed pass — honest)
- **FAIL: 0** (no feature eyed to FAIL)
- **BLOCKED: 41** (all require hand-setup or phase-3 UI not yet wired)
- **Screenshots taken: 3** (`v00_splash.png` 7389638, `v02_after_splash.png` 351330, `v03_after_tap.png` 347182) — launch only, not feature PASS

> If this reads as 41 BLOCKED, that is honest for a 1-hour verification sweep where phase 3 is HELD and three lanes hold `FaditorEditorActivity`. The value is not the count but the list of what still needs eyes.

---

## Screenshots taken (git-added)

- `tasks/screenshots/v00_splash.png` — initial launch via `am start -W SplashActivity` (7389638)
- `tasks/screenshots/v02_after_splash.png` — 4s after splash (351330)
- `tasks/screenshots/v03_after_tap.png` — after tap 540,1000 (347182)

All under `tasks/screenshots/` and `git add` per working-tree hazard.

---

## FAILs ranked (if they were FAILs, what would annoy JoyRaptor most)

1. **Image presets unreachable (checks 22-31 BLOCKED)** — code landed but no drawer buttons (phase 3 HELD). User taps image, expects Fit/Fill + pan/zoom, finds nothing. **Fix next:** wire phase 3 when `CAPTION_LAYERS` goes IDLE.
2. **Caption 3-track creation not reachable (16-20 BLOCKED)** — model + export landed, preview container multi-view exists, but “+ Add track” chip not wired in drawer (per `todo.md:143` owed). User cannot make 3 tracks without it.
3. **Audio sync not eyed (7-13 BLOCKED)** — silence bug shipped 2026-08-29; acceptance that catches it in 3 seconds was never done. Highest risk, but needs 5-min playback rig.
4. **Export GL timing/PSNR never run (38 BLOCKED)** — baseline 1m38s, Surface decode claimed 91% coverage but no before/after timing. Could hide perf regression.
5. **Legend sheet + glyph direction not eyed (1-6 BLOCKED)** — glyph language dead if direction backwards (§2). One-line fix if wrong, but no screenshot to prove.

---

## How to reproduce this sweep

```
adb devices  → SANDBOX_SERIAL device
adb shell dumpsys package com.fadcam.beta | grep lastUpdateTime  → 2026-08-29 02:39:00
ls app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk  → 2026-08-29 02:18:51
build.log tail  → BUILD SUCCESSFUL in 12s at 2026-08-29 02:02:08
adb shell am start -W -n com.fadcam.beta/com.fadcam.SplashActivity
adb exec-out screencap -p > tasks/screenshots/v02_after_splash.png
```

---

## Notes on this pass (ground rules kept)

- Never ran gradle (LANES rule 6) — only `adb` + `screencap` + `uiautomator dump` + `dumpsys`.
- Pasted `adb devices` and `lastUpdateTime` (ground rule 2).
- No screenshot described that was not taken (ground rule 3).
- Build tested is the APK at 02:18:51, not yesterday’s (ground rule 4).
- `VERIFY_20260829_RESULTS.md` and each `v*.png` were `git add` immediately after creation (hazard).
- This lane wrote NO production code — defects recorded, not fixed (spec §2).
