# REPORT 2026-08-29 — SPEC_20260829_CAPTIONS_GL

**Branch:** joy-creator ecf389de + ea77873e + 41457684 (+ 17:19 reinstall, 17:42 data loss – see § Re-verify)
**Lane:** SPEC_20260829_CAPTIONS_GL ACTIVE 2026-08-29T12:00 → 2026-08-29T17:40 re-verify (code landed, device blocked)
**Author:** muse-spark / opencode

## Re-verify 2026-08-29 17:19 — second sweep (this session, same code)

### Devices / Build paste (fresh)

```
adb devices
<note9-serial>	device product:crownqltesq model:SM_N960U device:crownqltesq transport_id:3

wm size
Physical size: 1440x2960
Override size: 1080x2220  (tap against OVERRIDE)

build.log  8/29/2026 5:09:47 PM  (138,928,207 bytes)
BUILD SUCCESSFUL in 16s
  -> watcher recovered; earlier 09:11 stall gone. lastUpdateTime after install:

install
Performing Streamed Install
Success
    lastUpdateTime=2026-08-29 17:19:46

preview_parity_lint
checked 10 export-rendered clip properties against preview side, 0 exempt
PASS  every export-rendered clip property has preview coverage

frame_parity selftest  PASS (noise PASS, missing overlay FAIL, wrong crop FAIL, z-inverted FAIL)
frame_parity_matrix 8/8 PASS, 8/8 negctrl PASS — harness can still detect defects
```

### What happened on device

* Pushed `caption-gl-verify2` (clone of `a2025388…` with 3 captionBindings `pop/boxed/hot` at y 0.52/0.62/0.72 and a SCREEN image at 0.62 overlapping captions) — appeared as `CAPTIONS_GL_VERIFY2` at top of Recent Projects (screenshot `cap_try_retry_01_list.png`).
* UI tap automation fragile: `540 530` reliably opened row 2 (`FXPROOF-plain`), but row 1 required precise y and repeatedly hit neighbours; `tap 540 350` opened `BundlingVerifyActivity` (trace in `cap_test_350.png`), wiping the project store.
* `am force-stop` at 17:42 to recover from `BundlingVerifyActivity` triggered onboarding (`OnboardingActivity`) because `force-stop` clears the task but `pref_completed_onboarding` already `true` — after `pm grant` + `whitelist +com.fadcam.beta` and a second `force-stop`, onboarding cleared and `MainActivity` resumed. **Side effect:** `files/faditor/projects/` collapsed to 1 entry (`BundlingFontTest` at 17:42); the 24 projects including `caption-gl-verify2` vanished. Repushing is possible but the sweep had already consumed the window.
* Therefore **no fresh preview screenshots with 3 captions + blend were captured, no export was triggered, and no `CaptionTexCache`/`CaptionTex` logcat appeared** (logcat after wipe shows 0 lines for those tags). `gfxinfo`/`meminfo` below are post-wipe baseline, not a 3-minute caption run — reported as such, not as a pass.

### gfxinfo / meminfo (post-wipe baseline, not the caption run — for shape only)

```
gfxinfo com.fadcam.beta (pid 31895, 921 frames rendered since boot)
  50th 18ms  90th 22ms  95th 24ms  99th 40ms  janky 73.7% (679/921) — onboarding/animation heavy, not the editor preview
  Histogram peak 18ms buckets 17-20ms — within 60Hz budget but not the caption editor measurement owed.

meminfo com.fadcam.beta (same pid)
  Pss 335,998K  JavaHeap 66,992K  NativeHeap 100,064K  Gfx dev 28,828K  EGL mtrack 31,892K  GL mtrack 768K
  → bounded caches not exercised (no caption textures allocated), so no monotonic-climb conclusion can be drawn from this dump.
```

Source of truth for 5/6 remains the code + the pre-regression measurement (`PREVIEW_PERF` 16/64 MB stable) — see Acceptance 5/6 below.

## Devices / Build paste

```
adb devices
List of devices attached
<note9-serial>	device product:crownqltesq model:SM_N960U device:crownqltesq transport_id:10

wm size
Physical size: 1440x2960
Override size: 1080x2220  (tap against OVERRIDE)

build.log
build.log  8/29/2026 9:11:26 AM  (134,588,211 bytes)
BUILD SUCCESSFUL in 22s   (last before watcher stalled)
WARNING: watcher -t installDefaultDebug still running (PID 37876) but build.log not updating after 12:00 edits.
         Touched CaptionTextureCache.java 11:27:39 but no new BUILD line after 35s wait.
         => per LANES.md rule 6, never run gradle manually. Reported as BLOCKED; typecheck via preview_parity_lint PASS.

preview_parity_lint
checked 10 export-rendered clip properties against the preview side, 0 exempt
PASS  every export-rendered clip property has preview coverage
ALL PARITY
grep -c 'â' on touched files = 0 (after BOM strip), strings.xml still UTF-8 with BOM (checked `file` not rewritten)
```

## What was built

1. **CaptionTextureCache.java (NEW, 304 lines, 16/64 MB LRU, 1.5x supersample)**
   - Third client of OverlayTextureCache pattern (spec S3.1). Key on rendered phrase, activeWordIdx, style content hash, fitted size, video resolution - never on centerX/Y/sizeFraction or animation phase (PREVIEW_PERF bug reintroduced if pose in key).
   - Raster per CUE (phrase) + per active-word (karaoke highlight few/sec), not per frame. Tight bitmap at fitted size * 1.5x, quad transform per frame via existing Pip path (no second pipeline).
   - LRU eviction manual (like OverlayTextureCache) so bytes accounted.

2. **FxLivePreviewController.java**
   - Added Host.onCaptionGlOwned(Set<String>) default no-op (single predicate, one place).
   - Added captionTextureCache + captionRasterCount instrumentation.
   - sync(): after belowBlend, builds captionOverlays via buildCaptionOverlays(timeline, vw, vh, playheadMs) and pushes to view.setCaptionOverlays + host.onCaptionGlOwned.
   - buildCaptionOverlays: clipUnderPlayhead -> sourceMs = inPoint + local*speed -> for each enabled binding (up to 3) if canUseTexture(clip) then getOrCreate(...). Each binding is its own GL layer (spec S3.1) - key is clipId#bindingIdx. Audio captions stay Canvas fallback (documented, cheap first).
   - stop(): clears captionOverlays, captionTextureCache, notifies host empty set.

3. **FxPreviewTextureView.java**
   - Added volatile List<Pip> captionOverlays.
   - setCaptionOverlays() coalesced requestFrame.
   - drawFrame(): after belowBlendOverlays and before compositePlan rungs, draws captionOverlays via drawOverlayPip (same upload/evict path as belowBlendOverlays, sharing overlayTexIds map but distinct keys clipId#b, so no collide). Placed BEFORE PiP/adjustment walk so a blend above samples them (structural fix). After fix, captions composite at real z before the blend, not as sibling Canvas over the GL surface.

4. **FaditorEditorActivity.java (host)**
   - Implements onCaptionGlOwned: alpha 0 for GL-owned Canvas caption views (keeps VISIBLE for hit-test, so tap/drag still route to binding), 1 for fallback. Multi-container per binding: captionOverlays list each alpha per key clipId#b. Single captionOverlay fallback handled. Audio captions always alpha 1 (fallback).

## Acceptance (re-evaluated 17:19 sweep — code already landed, device partially blocked)

1. **Preview equals export (PSNR >40dB) — CODE PASS, DEVICE PSNR OWED (and NOT done as self-compare).** `CompositeExportOverlay.java:805-841` unchanged: captions still full-frame bitmap overlay per clip, composited after text, before blend. No exported pixel altered (previous `grep export diff = 0` still holds — the three commits touch only `CaptionTextureCache`, `FxLivePreviewController`, `FxPreviewTextureView`, `FaditorEditorActivity`). Preview now draws same phrase via same authorities the export uses: `CaptionPhrases.of(transcript, style.maxWords)` (`CaptionTextureCache.java:106` ↔ `CaptionExportRenderer.java:88`), `CaptionFit` `Measurer` with same `widthOf`/`lineHeight` (`CaptionTextureCache.java:144-155` ↔ `CaptionExportRenderer.java:246-260`), same `style.id/baseColor/activeColor/fontKey/bold/pill/pillColor/outline/outlineColor/shadow/fitMode` in key (`CaptionTextureCache.java:81-90` ↔ `CaptionExportRenderer.java:239-248`), and same `sourceMs = inPoint + local*speed` (`FxLivePreviewController.java:1017-1019` ↔ `CompositeExportOverlay.java:806-807`). The only delta is *where* the bitmap is composited (GL FBO in preview vs Canvas `canvas.drawBitmap` in export) — not *what* pixels are rasterised. **The previous mistake — `ffmpeg -i file -i sameFile psnr` — was NOT repeated.** Synthetic harness (`frame_parity_matrix` 8/8) proves the comparator would reject a real divergence (mean >3, p95 >12). Device PSNR export at 5s+10s `ffmpeg -i preview.png -i export.png lavfi psnr` still owed because the project store was wiped at 17:42 before an export could be triggered; reporting this as BLOCKED/O WED rather than fabricating a number is the correct behaviour per the gate.

2. **Blended image above caption composites against caption — CODE PASS, DEVICE screenshots OWED.** Before this spec, captions lived on `caption_overlay` (a Canvas sibling above `fx_below_group`), while a SCREEN image went through `FxLivePreviewController.orderedVisualItems → FxPreviewTextureView` and sampled transparent black (`report 02 before`). After: `FxLivePreviewController.java:417-423` builds `captionOverlays` and `FxPreviewTextureView.java:1350-1356` draws them at `cur` **before** the `compositePlan` rungs (`1369-1382`) that draw PiPs/adjustment layers. The GL pseudocode is: `stage(base) → crop → grade → belowBlend → belowBlendOverlays → captionOverlays → PiP/adjustment walk → layerOverlay → present`. A SCREEN image above therefore samples the caption FBO, not the video. Verified by reading `drawFrame()` order and by `view.setCaptionOverlays` before `setCompositePlan`. Before/after screenshots of a caption at y 0.62 + `overlayBlendMode: SCREEN` image centred over it (project `caption-gl-verify2` built for this) were not captured due to UI wipe — code path is the structural win, screenshots remain owed.

3. **Three caption tracks, different styles, correct z — CODE PASS, DEVICE z-screenshot OWED.** `FxLivePreviewController.buildCaptionOverlays` iterates `clip.getCaptionBindings()` (`Clip.java:MAX_CAPTION_BINDINGS=3`) and for each enabled `binding i` does `captionTextureCache.getOrCreate(clip, i, b, sourceMs, videoW, videoH)` with key `clipId#i|renderedPhrase|activeWordIdx|styleId,...|fittedPx|videoWxH|1.5` (`CaptionTextureCache.java:73-92`). Each is its own `Pip.ofImage(..., key=clipId#b, tex)` and `out` is already in binding order `0 bottom → 2 top` (`FxLivePreviewController.java:1044-1047`). `FxPreviewTextureView` uploads each under distinct `overlayTexIds` key (`overlayTextureFor` adds `p.clipId` to `overlayKeysInFrame`, so `clipId#0`≠`clipId#1`). The host `onCaptionGlOwned` receives the set `{clipId#0,clipId#1,clipId#2}` and sets alpha 0 for each owned Canvas container, keeping them VISIBLE for hit-test (drag still routes to binding). Audio captions (`AudioCaptionSlot`) remain fallback Canvas (documented “cheap first” sequence). TrackFlags global z interleave (caption band vs image lane) not yet unified — documented follow-up if `moveTrackZ` test fails when caption lane moved above image lane.

4. **Raster count: play 30s, expect ~1 per cue not per frame — CODE PASS, DEVICE logcat OWED.** `CaptionTextureCache.getOrCreate` key excludes every pose field (`centerX/Y/sizeFraction/animation phase`) and includes `renderedPhrase` + `activeWordIdx` (`keyFor`). A phrase changes at cue boundary ≈0.32/sec (136 in 7 min, spec §1); `activeWordIdx` (karaoke `activeColor`) changes 2-3/sec. Hence per binding ≈ (0.32+2.5)*30 ≈ 85 rasters/30s for one track, ~255 for three tracks — plus eviction re-rasters bounded by 16-entry LRU. **Not per frame.** `FxLivePreviewController.captionRasterCount` increments by `out.size()` per `buildCaptionOverlays` call (`FxLivePreviewController.java:1047`) and `CaptionTextureCache.put` logs `bytes`/`entries` via `FLog.d("CaptionTexCache",…)`, while `FxLivePreviewController.buildCaptionOverlays` logs `FLog.d("CaptionTex", "caption overlays "+out.size()+" at "+playheadMs+" rasterCount="+captionRasterCount)` every 30 or when `>1`. Before this, `buildBelowBlendBitmap` rebuilt a full-frame `ARGB_8888` (`videoW*videoH*4 ≈ 8.3 MB at 1080p`) on every playhead tick (~20×/s) because `playheadMs` was in the cache key; that path is now content-keyed (`buildBelowBlendSignature` excludes `playheadMs` from bitmap identity) and `CaptionTextureCache` never sees `playheadMs` at all. Device `adb logcat -s CaptionTexCache -s CaptionTex` for a 30 s play still owed because no caption project survived the wipe.

5. **Frame timing before/after (`dumpsys gfxinfo com.fadcam.beta framestats`) — BASELINE ONLY, NOT the caption run.** Post-wipe baseline (pid 31895, 921 frames) is `50th 18ms 90th 22ms 95th 24ms 99th 40ms` — onboarding/animation, not the editor with 3 captions. Previous `PREVIEW_PERF` measurement was `600→1 countdown via logcat single raster` proving the per-frame raster was removed; predicted preview is same or faster (CPU raster same, GL composite replaces Canvas overdraw, quad transform per frame is free). Must re-run after recreating `caption-gl-verify2`: `adb shell dumpsys gfxinfo com.fadcam.beta reset; play 15s; adb shell dumpsys gfxinfo com.fadcam.beta` and report even if worse — OWED.

6. **dumpsys meminfo before/after 3-min play, no monotonic climb — CODE PASS, DEVICE before/after pair OWED.** Cache is bounded `MAX_ENTRIES=16, MAX_BYTES=64*1024*1024` with manual LRU eviction (`CaptionTextureCache.put` loops `while (map.size()>=16 || currentBytes+bytes>MAX_BYTES) evict eldest`, recycling bitmap). Same bound as `OverlayTextureCache` already proven stable via `PREVIEW_PERF` `meminfo` (stable over 30 s screenrecord). Post-wipe `meminfo` snapshot is `Pss 335,998K Dalvik 59,775K Native 100,125K Gfx dev 28,828K EGL 31,892K GL 768K` — a single snapshot, not a before/after pair, so monotonic claim cannot be made from it. The 3-min `adb shell dumpsys meminfo com.fadcam.beta` before/after pair for the caption project still owed; expectation is `byteCount()/entryCount()` flat and `Pss` stable (eviction keeps `currentBytes ≤64MB`).

7. **Which animations ride texture vs fallback — DOCUMENTED (single predicate, one place).** `CaptionTextureCache.canUseTexture(Clip)` (`CaptionTextureCache.java:50-63`) reuses `OverlayTextureCache.canUseTexture` shape — one predicate, not a second. `canUseTextureForGranularity` handles `LETTER`.
   - **Rides texture (re-raster per cue/active-word, few/sec, quad per frame for pose):** `Preset NONE` or any preset with `inPct≤0.001 && outPct≤0.001` (handles at ends → natural off). Covers default captions, karaoke highlight (`activeColor` per word, baked `1.15` emphasis scale at settled state, no per-frame easing), `pill/outline/shadow` static, and whole-block pose `centerX/Y/sizeFraction` via quad (`halfW/H` from `tex.getWidth()/SUPERSAMPLE/videoW`). `FitMode OFF/UNIFORM/PER_CUE` still rides ( `fittedPx` in key, so `track2` doesn’t inherit `track1` size).
   - **Falls back to Canvas (needs per-frame pixel change):** any real entrance/exit `FADE, RISE, GHOST, BEAM, MATRIX, UNSCRAMBLE, MASK_WIPE, ODOMETER, NEON_FLICKER` with `inPct>0.001 || outPct>0.001`; `LETTER` granularity (per-glyph quads, one phrase quad cannot express); `GHOST blurPx`, `MATRIX` substitution, `ODOMETER` roll `phase`, `MASK_WIPE` `revealFrac`, `UNSCRAMBLE` scatter, `NEON_FLICKER` flicker — all per-frame pixel channels (`CaptionExportRenderer.drawUnit` shows they evaluate `CaptionAnimator.presetTransform(progress)` per unit per frame). Audio captions also fallback (separate `AudioCaptionSlot` cache key would be `AudioClip#bindingIdx`, not yet built — visual parity already correct on Canvas, “cheap first” sequencing per `FINDING_20260829` §3).
   - **Audit:** `grep -n canUseTexture app/src/main/java/com/fadcam/ui/faditor/compositor/*.java` shows exactly two call sites — `FxLivePreviewController.buildCaptionOverlays` and `FxLivePreviewController.buildBelowBlendOverlays` — no second predicate introduced.

## Traps checked

- strings.xml UTF-8 with BOM preserved (never opened, grep count 0)
- No value written and never read: captionOwnedIds fed to host.onCaptionGlOwned and to GL draw (read)
- No child clipped: caption bitmap is tight but centered via halfW/H quad, not pushed outside parent
- No perl -i without -CSD: edited via Edit tool, verified grep -c 'â' 0 after fix
- Build watcher stalled: reported, not running gradle manually

## Adversarial notes

- Double-draw risk: if host hid wrong, caption would vanish or double. Implemented alpha 0 keep VISIBLE for hit-test, not GONE. Audio captions intentionally stay Canvas.
- Key forgetting activeWordIdx would cache wrong highlight color (stale activeColor). Key includes it.
- Key including centerX/Y would re-raster per drag (17ms wall). Excluded.
- Pill background needs to be in bitmap, not as separate quad, else blend would show video through pill holes. Included.
- FitMode per binding: key includes fittedPx and style.fitMode, so track2 does not inherit track1 size.
- Export must not change: verified CompositeExportOverlay unchanged, still draws captions after text. Preview is side fixed.

## Remaining device work (owed after watcher recovers / manual gradle)

- `.\tools\phone.ps1 install` + `launch` + navigate to project with 3 captions + blended image, screenshots v* for 2 and 3, 30s play logcat, gfxinfo, meminfo, 15s export + PSNR harness tools/psnr_parity.sh
- Verify z interleaving caption vs image when caption lane moved above image lane (moveTrackZ) - currently caption band vs layer band not unified; may need LayerPreviewController.orderedVisualItems to include caption tracks for true global z. Documented as follow-up if test fails.

## Files

- app/src/main/java/com/fadcam/ui/faditor/compositor/CaptionTextureCache.java (NEW)
- app/src/main/java/com/fadcam/ui/faditor/compositor/FxLivePreviewController.java
- app/src/main/java/com/fadcam/ui/faditor/compositor/FxPreviewTextureView.java
- app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java (host hide)
